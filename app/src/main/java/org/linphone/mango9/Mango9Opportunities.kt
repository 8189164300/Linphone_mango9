// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

internal fun JSONArray.opportunityObjects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

internal class Mango9OpportunityFailure(val status: Int, message: String) : Exception(message)

internal data class Mango9OpportunityStage(val id: String, val name: String, val classification: String, val probability: Int, val default: Boolean, val retired: Boolean)

internal class Mango9OpportunityMetadata(val raw: JSONObject) {
    val userId = raw.getInt("userId")
    val manager = raw.optBoolean("manager")
    val tenantId = raw.optInt("tenantId", userId)
    val permissions = raw.getJSONObject("permissions")
    val stages = raw.getJSONArray("statusSpecs").opportunityObjects().map {
        Mango9OpportunityStage(it.getString("id"), it.getString("name"), it.getString("classification"), it.getInt("probability"), it.optBoolean("default"), it.optBoolean("retired"))
    }
    val activeStages get() = stages.filterNot { it.retired }
    val owners = raw.getJSONArray("users").opportunityObjects().map { it.getInt("id") to it.getString("name") }
    val canCreate get() = permissions.optBoolean("create")
    val canAccess get() = permissions.optBoolean("access")
}

/** Original request bodies survive interruptions; a retry never creates a second Opportunity. */
@Suppress("DEPRECATION")
internal class Mango9OpportunityCommands(context: Context) {
    private val preferences by lazy {
        EncryptedSharedPreferences.create(
            context.applicationContext, "mango9_crm_commands",
            MasterKey.Builder(context.applicationContext, "mango9_crm_commands_key").setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun key(lease: Mango9SessionLease, id: Int, operation: String) = "${Mango9MessagingApi.account(lease.session)}:$id:$operation"

    fun load(lease: Mango9SessionLease, id: Int, operation: String): JSONObject? = preferences.getString(key(lease, id, operation), null)?.let(::JSONObject)

    fun save(lease: Mango9SessionLease, id: Int, operation: String, body: JSONObject?) {
        val edit = preferences.edit(); val key = key(lease, id, operation)
        if (body == null) edit.remove(key) else edit.putString(key, body.toString())
        check(edit.commit()) { "The pending action could not be saved. Nothing was sent." }
    }

    companion object {
        fun definite(error: Exception) = error is Mango9OpportunityFailure && error.status in setOf(400, 403, 404, 409, 413, 415, 422, 428)
    }
}

internal class Mango9Opportunities(context: Context) {
    private val sessions = Mango9SessionStore(context)
    private val authorized = Mango9AuthorizedRequests(sessions, Mango9ApiClient(context)::refresh)
    private val transport = Mango9CalendarHttpTransport()

    suspend fun request(lease: Mango9SessionLease, path: List<String> = emptyList(), query: Map<String, String> = emptyMap(), body: JSONObject? = null, contact: Boolean = false, mobilePath: String? = null): JSONObject = withContext(Dispatchers.IO) {
        authorized.execute(lease) { session -> decode(transport.execute(wire(session, path, query, body, contact, mobilePath))) }
    }

    companion object {
        fun creationId(receipt: JSONObject): Int {
            val id = receipt.optInt("id")
            require(id > 0 && receipt.optString("action") == "create" && (receipt.opt("saved") == true || receipt.opt("saved") == false && receipt.opt("duplicate") == true && receipt.opt("unchanged") == true)) {
                "The Opportunity save was not confirmed."
            }
            return id
        }

        fun duplicateMatches(row: JSONObject, command: JSONObject): Boolean {
            if (row.optInt("contact_id") != command.optInt("contactId")) return false
            val values = command.optJSONObject("values") ?: return false
            return values.keys().asSequence().all { key ->
                val value = values.opt(key); val stored = row.opt(key)
                when {
                    value == JSONObject.NULL -> stored == null || stored == JSONObject.NULL
                    key in setOf("value", "actual_value", "probability") -> runCatching {
                        value.toString().toBigDecimal().compareTo(stored.toString().toBigDecimal()) == 0
                    }.getOrDefault(false)
                    key == "next_due_at" -> runCatching {
                        OffsetDateTime.parse(value.toString()).toInstant() == OffsetDateTime.parse(stored.toString().replace(" ", "T")).toInstant()
                    }.getOrDefault(false)
                    else -> value.toString() == stored.toString()
                }
            }
        }

        fun wire(session: Mango9Session, path: List<String>, query: Map<String, String>, body: JSONObject?, contact: Boolean, mobilePath: String? = null): Request {
            require(path.all { it.isNotBlank() && it !in setOf(".", "..") && '/' !in it })
            val base = Mango9Configuration.verifiedHttpsUrl(session.crmApiBaseUrl)?.toString()?.toHttpUrl() ?: throw Mango9ApiException.InvalidResponse
            require(base.query == null && base.fragment == null && base.username.isEmpty() && base.password.isEmpty())
            val prefix = base.encodedPath.trimEnd('/'); require(prefix.endsWith("/api/v2"))
            val builder = base.newBuilder().encodedPath(if (contact) "$prefix/mobile/contacts" else prefix.removeSuffix("/v2") + "/opportunities")
            if (mobilePath != null) {
                require(body == null && mobilePath.matches(Regex("^(contacts/[1-9][0-9]*/activity(/item)?|documents/(quotes|invoices)/[1-9][0-9]*)$")))
                builder.encodedPath("$prefix/mobile/$mobilePath")
            }
            path.forEach(builder::addPathSegment); query.forEach { (k, v) -> builder.addQueryParameter(k, v) }
            return Request.Builder().url(builder.build()).header("Authorization", "Bearer ${session.accessToken}")
                .header("Accept", "application/json").header("Cache-Control", "no-cache")
                .method(if (body == null) "GET" else "POST", body?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        }

        fun decode(response: Mango9CalendarResponse): JSONObject {
            if (response.status == 401) throw Mango9ApiException.Unauthorized
            val raw = runCatching { JSONObject(response.body) }.getOrNull()
            if (response.status !in 200..299 || raw?.optBoolean("success") != true) {
                throw Mango9OpportunityFailure(
                    response.status,
                raw?.optString("message")?.takeIf { it.isNotBlank() } ?: "The action could not be confirmed. Please retry."
                )
            }
            return raw.optJSONObject("data") ?: throw Mango9OpportunityFailure(0, "The response could not be confirmed. Please retry.")
        }
    }
}

/** Strict mobile validation mirrors the server; money is sent as decimal text, never a float. */
internal data class Mango9OpportunityDraft(
    var title: String = "", var stage: String = "", var value: String = "", var currency: String = "USD", var closeDate: String = "",
    var ownerId: Int = 0, var probability: Int = 0, var manualProbability: Boolean = false, var source: String = "", var description: String = "",
    var nextAction: String = "", var nextDue: String = "", var actualValue: String = "", var actualClose: String = LocalDate.now().toString(), var lossReason: String = "",
) {
    fun values(metadata: Mango9OpportunityMetadata, existingStage: String? = null): JSONObject {
        val selected = metadata.stages.firstOrNull { it.id == stage && (!it.retired || it.id == existingStage) }
        require(title.trim().isNotEmpty() && title.trim().length <= 250 && selected != null && currency in currencies && probability in 0..100) {
            "Enter a title and choose a valid stage, currency and probability."
        }

        fun money(raw: String): Any {
            val v = raw.trim(); require(v.isEmpty() || v.matches(Regex("(0|[1-9][0-9]{0,11})(\\.[0-9]{1,2})?"))) {
                "Enter an amount with up to two decimal places."
            }; return v.ifEmpty { null } ?: JSONObject.NULL
        }

        fun date(raw: String): Any {
            if (raw.isBlank())return JSONObject.NULL; require(
                runCatching {
                LocalDate.parse(raw).toString() == raw
            }.getOrDefault(false)
            ) {"Choose a valid date."}; return raw
        }
        require(source.trim().length <= 150 && description.trim().length <= 5000 && nextAction.trim().length <= 500 && lossReason.trim().length <= 500) {
            "Shorten the details to fit the field limits."
        }
        require(
            nextDue.isBlank() || (
                nextAction.isNotBlank() && runCatching {
            OffsetDateTime.parse(nextDue)
        }.isSuccess
            )
        ) {"Choose a follow-up date and enter its next action."}
        val closed = selected.classification != "open"
        if (closed) {
            require(
                actualClose.isNotBlank() && runCatching {
            !LocalDate.parse(actualClose).isAfter(LocalDate.now())
        }.getOrDefault(false)
            ) {"Choose an actual close date that is not in the future."}
        }
        if (selected.classification == "lost") require(lossReason.isNotBlank()) {"Enter a loss reason."}
        return JSONObject().put("title", title.trim()).put("stage", stage).put("value", money(value)).put("currency", currency)
            .put("probability", if (manualProbability)probability else selected.probability).put("probability_mode", if (manualProbability)"manual" else "stage")
            .put("close_date", date(closeDate)).put("source", source.trim()).put("description", description.trim())
            .put("next_action", nextAction.trim()).put("next_due_at", nextDue.ifBlank { null } ?: JSONObject.NULL)
            .put("actual_value", if (closed)money(actualValue) else JSONObject.NULL).put("actual_close_date", if (closed)date(actualClose) else JSONObject.NULL)
            .put("loss_reason", if (selected.classification == "lost")lossReason.trim() else "")
    }

    fun command(metadata: Mango9OpportunityMetadata, contactId: Int) = JSONObject().put("requestId", UUID.randomUUID().toString()).put("contactId", contactId).put("ownerId", ownerId).put("values", values(metadata))

    companion object {val currencies = listOf("USD", "CAD", "EUR", "GBP", "AUD", "NZD", "AMD", "AED", "INR", "JPY", "CHF", "MXN")}
}
