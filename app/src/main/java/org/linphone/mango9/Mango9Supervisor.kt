// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resumeWithException

internal class Mango9SupervisorFailure(val code: String, val status: Int = 0) : Exception(
    when (code) {
    "ai_consent_required" -> "Review AI privacy and allow sharing before using Supervisor chat or voice."
    "scope_changed" -> "Your account changed. Reopen Supervisor in your current account."
    "supervisor_owner_required", "forbidden" -> "Supervisor is available to the account owner."
    "wallet_insufficient" -> "Your available AI credit is too low. Check your Mango9 wallet."
    "call_active" -> "End your phone call before starting Supervisor voice."
    "voice_permission" -> "Allow microphone access in Android Settings to use Supervisor voice."
    "voice_interrupted" -> "Voice was interrupted by an audio change. Tap the microphone to reconnect."
    "voice_audio_format" -> "This audio route is unavailable. Check your microphone or headset and try again."
    "storage_unavailable" -> "Couldn’t securely save this request. Your message has not been sent."
    "history_pending" -> "The last request has not been confirmed. Refresh saved history before sending another request."
    else -> if (code.startsWith("voice_")) {
        "Voice disconnected. Tap the microphone to reconnect."
    } else if (status == 403) {
        "Supervisor is not enabled for this account."
    } else if (status == 429) {
        "Supervisor is busy. Wait a moment, then try again."
    } else {
        "Couldn’t reach Supervisor. Check your connection and try again."
    }
}
)

internal object Mango9SupervisorConsent {
    const val VERSION = "2026-10-30.1" // Bump when recipients or purposes change.
    const val CHAT = "Mango9 sends your messages, conversation history and relevant account/CRM records to OpenRouter and the configured AI provider: DeepInfra (DeepSeek) or OpenAI. They process this information to answer your requests."
    const val VOICE = "Voice also sends microphone audio, transcripts and conversation context to Google (Gemini) for spoken replies."

    fun key(session: Mango9Session) = MessageDigest.getInstance("SHA-256").digest(
        listOf(session.crmApiBaseUrl.trimEnd('/'), session.userId, session.sipIdentity.orEmpty()).joinToString("|").toByteArray()
    ).joinToString("") { "%02x".format(it) }

    fun valid(receipt: String?, now: Long = System.currentTimeMillis()): Boolean = runCatching {
        val row = JSONObject(receipt ?: return false)
        row.getString("version") == VERSION && row.getLong("acceptedAt") in 1..now
    }.getOrDefault(false)

    fun receipt(now: Long = System.currentTimeMillis()) = JSONObject().put("version", VERSION).put("acceptedAt", now).toString()

    fun required(path: String, body: JSONObject?) = path == "chat" || path == "voice/ticket" ||
        (path.startsWith("actions/") && path.endsWith("/decision") && body?.optString("decision") == "approve")
}

@Suppress("DEPRECATION")
internal class Mango9SupervisorStore(context: Context) {
    private val app = context.applicationContext
    private val prefs: SharedPreferences by lazy {
        val key = MasterKey.Builder(app, "mango9_supervisor_key").setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            app, "mango9_supervisor_private", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun accepted(session: Mango9Session) = runCatching {
        Mango9SupervisorConsent.valid(prefs.getString("consent." + Mango9SupervisorConsent.key(session), null))
    }.getOrDefault(false)

    fun consent(session: Mango9Session, allow: Boolean) = put("consent.", session, if (allow) Mango9SupervisorConsent.receipt() else null)

    fun pending(session: Mango9Session) = prefs.getString("pending." + Mango9SupervisorConsent.key(session), null)?.let {
        runCatching { JSONObject(it) }.getOrNull()
    }

    fun pending(session: Mango9Session, command: JSONObject?) = put("pending.", session, command?.toString())

    fun conversation(session: Mango9Session): String? = prefs.getString("conversation." + Mango9SupervisorConsent.key(session), null)

    fun conversation(session: Mango9Session, id: String) = put("conversation.", session, id)

    private fun displayKey(session: Mango9Session) = "display." + Mango9SupervisorConsent.key(session) + "." +
        MessageDigest.getInstance("SHA-256").digest((session.loginEpoch ?: session.refreshToken).toByteArray()).joinToString("") {
            "%02x".format(it)
        }

    fun savedChats(session: Mango9Session): List<JSONObject> = runCatching {
        Mango9SupervisorPresentation.objects(org.json.JSONArray(prefs.getString(displayKey(session), "[]")))
    }.getOrDefault(emptyList())

    fun savedChats(session: Mango9Session, chats: List<JSONObject>) {
        val bounded = chats.take(10).toMutableList()
        while (bounded.isNotEmpty() && org.json.JSONArray(bounded).toString().toByteArray().size > 3 * 1024 * 1024) bounded.removeAt(bounded.lastIndex)
        if (!prefs.edit().putString(displayKey(session), org.json.JSONArray(bounded).toString()).commit()) throw Mango9SupervisorFailure("storage_unavailable")
    }

    fun clearDisplay(session: Mango9Session) {
        val prefix = "display." + Mango9SupervisorConsent.key(session) + "."
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach { edit.remove(it) }
        if (!edit.commit()) throw Mango9SupervisorFailure("storage_unavailable")
    }

    private fun put(prefix: String, session: Mango9Session, value: String?) {
        val key = prefix + Mango9SupervisorConsent.key(session)
        val edit = prefs.edit(); if (value == null) edit.remove(key) else edit.putString(key, value)
        if (!edit.commit()) throw Mango9SupervisorFailure("storage_unavailable")
    }
}

internal object Mango9SupervisorWire {
    private val actionPath = Regex("actions/[0-9a-fA-F-]{36}(/decision)?")

    fun validText(value: String) = value.isNotBlank() && value.toByteArray(Charsets.UTF_8).size <= 8000

    fun command(conversation: String, text: String) = JSONObject().put("conversationId", conversation)
        .put("requestId", UUID.randomUUID().toString()).put(
            "message",
            JSONObject().put("id", UUID.randomUUID().toString())
            .put("role", "user").put("parts", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        )

    fun owner(row: JSONObject, session: Mango9Session) = row.opt("enabled") == true && row.opt("ownerOnly") == true &&
        row.optString("supervisorAuth") == "mobile_owner" && row.optString("userId") == session.userId && row.optString("tenantId") == session.userId

    fun request(session: Mango9Session, path: String, body: JSONObject? = null): Request {
        require(path in setOf("identity", "supervisor", "history", "chat", "voice/ticket", "actions", "activity") || actionPath.matches(path))
        val base = Mango9Configuration.verifiedHttpsUrl(session.crmApiBaseUrl)?.toString()?.toHttpUrl() ?: throw Mango9SupervisorFailure("invalid_configuration")
        require(base.query == null && base.fragment == null && base.username.isEmpty() && base.password.isEmpty())
        val url = base.newBuilder().apply { if (base.pathSegments.lastOrNull() == "") removePathSegment(base.pathSize - 1) }
            .addPathSegment("assistant").apply { path.split('/').forEach(::addPathSegment) }.build()
        return Request.Builder().url(url).header("Authorization", "Bearer ${session.accessToken}").header("Accept", "application/json")
            .header("Cache-Control", "no-cache").apply {
                if (body != null) post(body.toString().toRequestBody("application/json".toMediaType()))
            }.build()
    }

    fun voiceUrl(session: Mango9Session, path: String): String {
        require(path == "/app/command-center/voice-stream")
        return request(session, "identity").url.newBuilder().encodedPath(path).query(null).fragment(null).build().toString().replaceFirst("https:", "wss:")
    }

    fun messageText(row: JSONObject): String = row.optJSONArray("parts")?.let { parts ->
        (0 until parts.length()).mapNotNull {
            parts.optJSONObject(it)?.takeIf { p -> p.optString("type") == "text" }?.optString("text")
        }.joinToString("\n")
    } ?: row.optString("text")

    fun webUrl(raw: String, origin: String): String? = runCatching {
        if (raw.startsWith("//") || raw.contains("..") || raw.contains('\\')) return null
        val base = origin.toHttpUrl(); val url = if (raw.startsWith("/app/")) base.resolve(raw) else raw.toHttpUrl()
        url?.takeIf {
            it.username.isEmpty() && it.password.isEmpty() &&
                it.pathSegments.flatMap { segment -> segment.split('/') }.none { segment -> segment.equals("api", true) }
        }?.toString()
    }.getOrNull()

    fun fullLinks(text: String, origin: String): String = Regex("\\[([^]\\n]+)]\\(([^)\\s]+)\\)").replace(text) {
        val link = webUrl(it.groupValues[2], origin)
        if (link != null) "${it.groupValues[1]}\n$link" else it.groupValues[1]
    }
}

internal class Mango9SupervisorHttp : Mango9CalendarTransport {
    private class Attempt { val sent = AtomicBoolean() }

    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(130, TimeUnit.SECONDS).callTimeout(135, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            if (chain.request().tag(Attempt::class.java)?.sent?.compareAndSet(false, true) != true) throw IOException("Request cannot be replayed")
            chain.proceed(chain.request())
        }.build()

    override suspend fun execute(request: Request): Mango9CalendarResponse = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request.newBuilder().tag(Attempt::class.java, Attempt()).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                    val body = it.body
                    if (body.contentLength() > 2097152 || body.source().request(2097153)) throw Mango9SupervisorFailure("invalid_response")
                    Mango9CalendarResponse(it.code, body.string())
                }
                }
                if (continuation.isActive) continuation.resumeWith(result)
            }
        })
    }
}

internal class Mango9SupervisorApi internal constructor(
    private val sessions: Mango9SessionAccess, private val refresh: suspend (Mango9Session) -> Mango9Session,
    private val allowed: (Mango9Session) -> Boolean, private val transport: Mango9CalendarTransport,
) {
    constructor(context: Context, store: Mango9SupervisorStore) : this(Mango9SessionStore(context), Mango9ApiClient(context)::refresh, store::accepted, Mango9SupervisorHttp())

    private val authorized = Mango9AuthorizedRequests(sessions, refresh)

    fun active(lease: Mango9SessionLease) = sessions.currentSession(lease) != null

    fun consent(lease: Mango9SessionLease) = allowed(lease.session)

    suspend fun request(lease: Mango9SessionLease, path: String, body: JSONObject? = null) = resolved(lease, path, body).first

    private suspend fun resolved(lease: Mango9SessionLease, path: String, body: JSONObject? = null): Pair<JSONObject, Mango9Session> = withContext(Dispatchers.IO) {
        val needs = Mango9SupervisorConsent.required(path, body)
        if (needs && !allowed(lease.session)) throw Mango9SupervisorFailure("ai_consent_required")
        authorized.execute(lease) { session ->
            if (needs && !allowed(session)) throw Mango9SupervisorFailure("ai_consent_required")
            val reply = transport.execute(Mango9SupervisorWire.request(session, path, body))
            if (reply.status == 401) throw Mango9ApiException.InvalidCredentials
            val json = runCatching { JSONObject(reply.body) }.getOrNull()
            if (reply.status !in 200..299 || json?.opt("success") != true) throw Mango9SupervisorFailure(json?.optJSONObject("error")?.optString("code") ?: "invalid_response", reply.status)
            (json.optJSONObject("data") ?: throw Mango9SupervisorFailure("invalid_response")) to session
        }
    }

    suspend fun authorize(lease: Mango9SessionLease): JSONObject {
        if (!Mango9SupervisorWire.owner(request(lease, "identity"), lease.session)) throw Mango9SupervisorFailure("supervisor_owner_required")
        return request(lease, "supervisor").also {
            if (it.opt("enabled") != true) throw Mango9SupervisorFailure("supervisor_disabled", 403)
        }
    }

    suspend fun ticket(lease: Mango9SessionLease, conversation: String): Pair<JSONObject, Mango9Session> {
        if (!consent(lease)) throw Mango9SupervisorFailure("ai_consent_required")
        val current = sessions.currentSession(lease) ?: throw Mango9SupervisorFailure("scope_changed")
        Mango9TokenRefresh.renew(
            sessions, Mango9LoginLease(lease.session, lease.loginGeneration), current,
            current = {
                sessions.currentSession(lease)
            }, replace = { before, after -> sessions.replaceTokens(lease, before, after) }, refresh = refresh
        )
        return resolved(lease, "voice/ticket", JSONObject().put("conversationId", conversation).put("requestId", UUID.randomUUID().toString()).put("locale", java.util.Locale.getDefault().toLanguageTag()))
    }
}
