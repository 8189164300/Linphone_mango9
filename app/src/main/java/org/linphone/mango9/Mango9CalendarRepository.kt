/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import android.content.Context
import java.io.IOException
import java.time.Duration
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

internal data class Mango9CalendarResponse(val status: Int, val body: String)

internal fun interface Mango9CalendarTransport {
    suspend fun execute(request: Request): Mango9CalendarResponse
}

/** No automatic retry or redirect of a write/bearer token, including uncertain network failures. */
internal class Mango9CalendarHttpTransport : Mango9CalendarTransport {
    override suspend fun execute(request: Request): Mango9CalendarResponse = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request.newBuilder().tag(Attempt::class.java, Attempt()).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resumeWith(
                    runCatching {
                    response.use {
                        val body = it.body
                        // request(n) is bounded even for a chunked response with no Content-Length.
                        if (body.contentLength() > MAX_BYTES || body.source().request(MAX_BYTES + 1)) {
                            throw Mango9CalendarFailure("invalid_response")
                        }
                        Mango9CalendarResponse(it.code, body.string())
                    }
                }
                )
            }
        })
    }

    private companion object {
        class Attempt { val sent = AtomicBoolean(false) }

        const val MAX_BYTES = 2L * 1024 * 1024
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                // OkHttp can follow a 503 Retry-After: 0 even with connection retries
                // disabled. A calendar write must never be replayed implicitly.
                val attempt = chain.request().tag(Attempt::class.java)
                if (attempt?.sent?.compareAndSet(false, true) != true) throw IOException("Calendar request cannot be replayed")
                chain.proceed(chain.request())
            }.build()
    }
}

internal object Mango9CalendarWire {
    fun request(
        session: Mango9Session, path: String, method: String = "GET", query: Map<String, String> = emptyMap(),
                body: JSONObject? = null, revision: String? = null, crmScope: Boolean = false, tasksScope: Boolean = false
    ): Request {
        val verified = Mango9Configuration.verifiedHttpsUrl(session.crmApiBaseUrl) ?: throw Mango9CalendarFailure("invalid_configuration")
        val base = verified.toString().toHttpUrl()
        require(
            base.query == null && (
                (tasksScope && path.isEmpty()) || path.split('/').all {
            it.isNotEmpty() && it != "." && it != ".."
        }
            )
        )
        val url = base.newBuilder().apply {
            // Avoid a double slash when the provisioned base includes a trailing slash.
            if (base.pathSegments.lastOrNull() == "") removePathSegment(base.pathSize - 1)
            addPathSegment("mobile"); addPathSegment(if (tasksScope) "tasks" else if (crmScope) "crm" else "calendar")
            if (path.isNotEmpty()) path.split('/').forEach(::addPathSegment)
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        return Request.Builder().url(url).header("Authorization", "Bearer ${session.accessToken}")
            .header("Accept", "application/json").header("Cache-Control", "no-cache").apply {
                revision?.let {
                    require(it.isNotBlank() && it.length <= 512 && it.all { c -> c in '!'..'~' && c != '"' && c != '\\' })
                    header("If-Match", "\"$it\"")
                }
            }.method(method, body?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
    }

    fun decode(response: Mango9CalendarResponse, path: String): JSONObject {
        if (response.status == 401) throw Mango9ApiException.InvalidCredentials
        val envelope = try { JSONObject(response.body) } catch (_: Exception) { null }
        if (response.status !in 200..299) {
            val code = if (response.status == 404 && path == "metadata") {
                "calendar_endpoint_unavailable"
            } else {
                envelope?.optJSONObject("error")?.optString("code")?.takeIf { it.isNotBlank() } ?: "request_failed"
            }
            throw Mango9CalendarFailure(code, response.status)
        }
        if (envelope?.optBoolean("success") != true) throw Mango9CalendarFailure("invalid_response", response.status)
        return envelope.optJSONObject("data") ?: throw Mango9CalendarFailure("invalid_response", response.status)
    }
}

/** One captured login/selection for the whole read or edit, including every page and token refresh. */
class Mango9CalendarRepository internal constructor(
    private val sessions: Mango9SessionAccess,
    refresh: suspend (Mango9Session) -> Mango9Session,
    private val transport: Mango9CalendarTransport,
) {
    constructor(context: Context) : this(Mango9SessionStore(context), Mango9ApiClient(context)::refresh, Mango9CalendarHttpTransport())

    private val requests = Mango9AuthorizedRequests(sessions, refresh)

    fun capture(): Mango9SessionLease? = sessions.captureSession()

    fun isCurrent(lease: Mango9SessionLease): Boolean = sessions.currentSession(lease) != null

    suspend fun metadata(lease: Mango9SessionLease): Mango9CalendarMetadata =
        send(lease, "metadata", parse = Mango9CalendarJson::metadata)

    suspend fun appointment(lease: Mango9SessionLease, id: Int): Mango9Appointment =
        send(lease, "events/$id", parse = Mango9CalendarJson::appointment).also {
            if (it.id != id) throw Mango9CalendarFailure("invalid_response")
        }

    suspend fun save(
        lease: Mango9SessionLease, draft: Mango9AppointmentDraft, metadata: Mango9CalendarMetadata,
                     event: Mango9Appointment?, timezone: String
    ): Mango9Appointment {
        if (!isCurrent(lease)) throw Mango9ApiException.Unauthorized
        if (event == null && !metadata.capabilities.create) throw Mango9CalendarFailure("permission_denied", 403)
        if (event == null) ZoneId.of(timezone)
        val owner = event == null || event.ownerId.toString() == lease.session.userId
        require(draft.validationMessage(event, owner) == null)
        if (event == null && draft.assignee != null && !metadata.capabilities.assign) throw Mango9CalendarFailure("permission_denied", 403)
        val body = draft.payload(event, owner, timezone)
        if (body.length() == 0) return event ?: throw Mango9CalendarFailure("invalid_response")
        val saved = send(
            lease, event?.let { "events/${it.id}" } ?: "events", if (event == null) "POST" else "PATCH",
            body = body, revision = event?.revision, parse = Mango9CalendarJson::appointment
        )
            .also { if (event != null && it.id != event.id) throw Mango9CalendarFailure("invalid_response") }
            .also { if (isCurrent(lease)) Mango9CalendarChanges.saved() }
        if (event == null || (!body.has("start_at") && !body.has("end_at"))) return saved
        // A successful PATCH can be an older server silently ignoring date fields.
        // Read back once; never replay a write with an uncertain outcome.
        val persisted = try {
            appointment(lease, saved.id)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException || error is Mango9ApiException.Unauthorized) throw error
            throw Mango9CalendarFailure("event_save_unconfirmed")
        }
        if (!datesMatch(body, persisted)) throw Mango9CalendarFailure("event_save_unconfirmed")
        return persisted
    }

    internal fun datesMatch(body: JSONObject, event: Mango9Appointment): Boolean =
        listOf("start_at" to event.startAt, "end_at" to event.endAt).all { (key, actual) ->
            !body.has(key) || runCatching { java.time.Instant.parse(body.getString(key)) == actual }.getOrDefault(false)
        }

    suspend fun changeStatus(lease: Mango9SessionLease, event: Mango9Appointment, statusId: Int?): Mango9Appointment {
        if (!event.permissions.canEdit) throw Mango9CalendarFailure("permission_denied", 403)
        return send(
            lease, "events/${event.id}", "PATCH", body = JSONObject().put("status_id", statusId ?: JSONObject.NULL),
            revision = event.revision, parse = Mango9CalendarJson::appointment
        )
            .also { if (it.id != event.id) throw Mango9CalendarFailure("invalid_response") }
            .also { if (isCurrent(lease)) Mango9CalendarChanges.saved() }
    }

    suspend fun delete(lease: Mango9SessionLease, event: Mango9Appointment) {
        if (!event.permissions.canDelete) throw Mango9CalendarFailure("permission_denied", 403)
        send(lease, "events/${event.id}", "DELETE", revision = event.revision) { it }
        if (isCurrent(lease)) Mango9CalendarChanges.saved()
    }

    suspend fun businessHours(lease: Mango9SessionLease): Mango9BusinessHours =
        send(lease, "business-hours", parse = Mango9CalendarJson::businessHours)

    suspend fun saveBusinessHours(lease: Mango9SessionLease, draft: Mango9BusinessHours, original: Mango9BusinessHours): Mango9BusinessHours =
        send(lease, "business-hours", "PATCH", body = draft.changes(original), revision = original.revision, parse = Mango9CalendarJson::businessHours)
            .also { if (isCurrent(lease)) Mango9CalendarChanges.saved() }

    suspend fun preferences(lease: Mango9SessionLease): Mango9CrmPreferences =
        send(lease, "settings", crmScope = true, parse = Mango9CalendarJson::preferences)

    suspend fun savePreferences(lease: Mango9SessionLease, draft: Mango9CrmPreferences): Mango9CrmPreferences {
        require(draft.isValid)
        return send(lease, "settings", "PATCH", body = draft.body(), revision = draft.revision, crmScope = true, parse = Mango9CalendarJson::preferences)
            .also { if (isCurrent(lease)) Mango9CalendarChanges.saved() }
    }

    suspend fun reminderFeed(lease: Mango9SessionLease): Mango9ReminderFeed =
        send(lease, "reminders", parse = Mango9CalendarJson::reminderFeed)

    suspend fun reminder(lease: Mango9SessionLease, event: Mango9Appointment): Mango9PersonalReminder =
        send(lease, "events/${event.id}/reminder", parse = Mango9CalendarJson::personalReminder)

    suspend fun updateReminder(
        lease: Mango9SessionLease, event: Mango9Appointment, state: Mango9PersonalReminder,
                               action: Mango9ReminderAction
    ): Mango9PersonalReminder {
        require(!event.isRecurring)
        return send(
            lease, "events/${event.id}/reminder", "PATCH", body = action.body(state), revision = event.revision,
            parse = Mango9CalendarJson::personalReminder
        )
            .also { if (isCurrent(lease)) Mango9CalendarChanges.saved() }
    }

    suspend fun pushBack(lease: Mango9SessionLease, event: Mango9Appointment, minutes: Int): Mango9Appointment {
        if (!event.permissions.canEdit) throw Mango9CalendarFailure("permission_denied", 403)
        return send(
            lease, "events/${event.id}", "PATCH", body = Mango9AppointmentActions.pushBackBody(event, minutes),
            revision = event.revision, parse = Mango9CalendarJson::appointment
        )
            .also { if (it.id != event.id) throw Mango9CalendarFailure("invalid_response") }
            .also { if (isCurrent(lease)) Mango9CalendarChanges.saved() }
    }

    suspend fun masters(lease: Mango9SessionLease, range: Mango9CalendarRange, contactId: Int? = null): List<Mango9Appointment> =
        collect(range, contactId) { query -> send(lease, "events", query = query, parse = Mango9CalendarJson::page) }

    suspend fun displayEvents(lease: Mango9SessionLease, range: Mango9CalendarRange, contactId: Int? = null): List<Mango9Appointment> {
        val windows = windows(range)
        val records = linkedMapOf<Int, Mango9Appointment>()
        for (window in windows) {
            for (event in masters(lease, window, contactId)) {
                val previous = records[event.id]
                if (previous != null && previous.revision != event.revision) throw Mango9CalendarFailure("snapshot_changed", 409)
                records[event.id] = event
                if (records.size > 10_000) throw Mango9CalendarFailure("too_many_events")
            }
        }
        val result = withContext(Dispatchers.Default) {
            val context = coroutineContext
            Mango9CalendarOccurrences.expand(records.values.toList(), range) { context.ensureActive() }
        }
        if (!isCurrent(lease)) throw Mango9ApiException.Unauthorized
        return result
    }

    internal suspend fun <T> send(
        lease: Mango9SessionLease, path: String, method: String = "GET",
                                  query: Map<String, String> = emptyMap(), body: JSONObject? = null,
                                  revision: String? = null, crmScope: Boolean = false, tasksScope: Boolean = false, parse: (JSONObject) -> T
    ): T =
        requests.execute(lease) { session ->
            val request = Mango9CalendarWire.request(session, path, method, query, body, revision, crmScope, tasksScope)
            val response = transport.execute(request)
            withContext(Dispatchers.Default) { parse(Mango9CalendarWire.decode(response, path)) }
        }

    companion object {
        fun windows(range: Mango9CalendarRange): List<Mango9CalendarRange> {
            if (range.end <= range.start || range.duration > Duration.ofDays(366)) throw Mango9CalendarFailure("invalid_range", 422)
            val result = ArrayList<Mango9CalendarRange>()
            var cursor = range.start
            while (cursor < range.end) {
                val next = minOf(cursor.plus(Duration.ofDays(90)), range.end)
                result += Mango9CalendarRange(cursor, next); cursor = next
            }
            return result
        }

        /** Return only a complete, consistent snapshot; never silently publish partial pages. */
        internal suspend fun collect(
            range: Mango9CalendarRange, contactId: Int?,
                                     fetch: suspend (Map<String, String>) -> Mango9AppointmentPage
        ): List<Mango9Appointment> {
            if (range.end <= range.start || range.duration > Duration.ofDays(93)) throw Mango9CalendarFailure("invalid_range", 422)
            repeat(2) { attempt ->
                val result = ArrayList<Mango9Appointment>()
                var snapshot: String? = null
                try {
                    for (page in 1..100) {
                        coroutineContext.ensureActive()
                        val query =
                            linkedMapOf("start_at" to range.start.toString(), "end_at" to range.end.toString(), "page" to page.toString(), "limit" to "100")
                        contactId?.let { query["contact_id"] = it.toString() }; snapshot?.let { query["snapshot"] = it }
                        val values = fetch(query)
                        if (values.page != page || values.events.size > 100 || result.size + values.events.size > 10_000 || values.snapshot.isBlank()) {
                            throw Mango9CalendarFailure("invalid_pagination")
                        }
                        if (snapshot != null && snapshot != values.snapshot) throw Mango9CalendarFailure("snapshot_changed", 409)
                        snapshot = values.snapshot
                        result += values.events
                        if (!values.hasMore) return result
                    }
                    throw Mango9CalendarFailure("too_many_events")
                } catch (error: Mango9CalendarFailure) {
                    if (attempt != 0 || error.code != "snapshot_changed") throw error
                }
            }
            throw Mango9CalendarFailure("snapshot_changed", 409)
        }
    }
}
