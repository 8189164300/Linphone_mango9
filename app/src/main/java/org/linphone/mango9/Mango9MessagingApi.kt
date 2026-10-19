/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import android.content.Context
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

enum class Mango9MessageChannel { team, sms, social }

internal class Mango9MessagingFailure(val status: Int, val code: String) : Exception(
    when {
    code == "posting_session_required" -> "Social Chat is not authorized for this account’s supervisor access. Team Chat and SMS are still available."
    code.contains("version_changed") -> "This message changed on another device. Refresh and try again."
    code == "pending" -> "The previous action is unconfirmed. Check its saved receipt before trying again."
    code == "invalid_response" -> "The server response could not be confirmed. Refresh before trying again."
    else -> "This action is unavailable. Refresh and try again."
}
)

/** CRM and chat bearers never cross origins. No cookies, redirects or implicit write retries. */
internal class Mango9MessagingApi internal constructor(
    private val sessions: Mango9SessionAccess,
    refresh: suspend (Mango9Session) -> Mango9Session,
    private val bootstrap: suspend (Mango9Session) -> Mango9ChatBootstrap,
    private val transport: Mango9CalendarTransport,
) {
    constructor(context: Context) : this(
        Mango9SessionStore(context), Mango9ApiClient(context)::refresh,
        Mango9ApiClient(context)::chatBootstrap, Mango9CalendarHttpTransport()
    )

    private val authorized = Mango9AuthorizedRequests(sessions, refresh)

    suspend fun request(
        lease: Mango9SessionLease, channel: Mango9MessageChannel, path: List<String>,
        query: Map<String, String> = emptyMap(), body: JSONObject? = null,
        method: String = if (body == null) "GET" else "POST", envelope: Boolean = true,
    ): JSONObject = withContext(Dispatchers.IO) {
        authorized.execute(lease) { session ->
            val token = if (channel == Mango9MessageChannel.social) session.accessToken else bootstrap(session).token
            if (sessions.currentSession(lease) == null) throw Mango9ApiException.Unauthorized
            decode(transport.execute(wire(session, token, channel, path, query, body, method)), envelope)
        }
    }

    companion object {
        fun account(session: Mango9Session): String {
            val fields =
                listOf(session.crmApiBaseUrl, session.crmBaseUrl, session.smsChatApi, session.crmId, session.userId, session.parentClientId, session.sipIdentity.orEmpty())
            val scope = fields.joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" }
            return MessageDigest.getInstance("SHA-256").digest(scope.toByteArray()).joinToString("") { "%02x".format(it) }
        }

        fun wire(
            session: Mango9Session, token: String, channel: Mango9MessageChannel, path: List<String>,
                 query: Map<String, String>, body: JSONObject?, method: String
        ): Request {
            require(path.isNotEmpty() && path.all { it.isNotBlank() && it !in setOf(".", "..") && '/' !in it })
            val base = Mango9Configuration.verifiedHttpsUrl(if (channel == Mango9MessageChannel.social) session.crmBaseUrl else session.smsChatApi)
                ?.toString()?.toHttpUrl() ?: throw Mango9MessagingFailure(0, "invalid_configuration")
            require(base.query == null)
            val builder = if (channel == Mango9MessageChannel.social) {
                base.newBuilder().encodedPath("/app/ad-manager/api/social-posting")
            } else {
                base.newBuilder().apply {
                if (base.pathSegments.lastOrNull() == "") removePathSegment(base.pathSize - 1)
            }
            }
            path.forEach(builder::addPathSegment)
            query.forEach { (k, v) -> builder.addQueryParameter(k, v) }
            return Request.Builder().url(builder.build()).header("Authorization", "Bearer $token")
                .header("Accept", "application/json").header("Cache-Control", "no-cache")
                .method(method, body?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        }

        fun decode(response: Mango9CalendarResponse, envelope: Boolean = true): JSONObject {
            if (response.status == 401) throw Mango9ApiException.InvalidCredentials
            val json = runCatching { JSONObject(response.body) }.getOrNull()
            if (response.status !in 200..299) {
                throw Mango9MessagingFailure(
                    response.status,
                json?.optJSONObject("error")?.optString("code") ?: (json?.opt("error") as? String) ?: "request_rejected"
                )
            }
            if (!envelope && json != null) return json
            if (json?.opt("success") != true) throw Mango9MessagingFailure(0, "invalid_response")
            return json.optJSONObject("data") ?: throw Mango9MessagingFailure(0, "invalid_response")
        }
    }
}

data class Mango9ReactionCapabilities(
    val supported: Boolean, val scope: String, val recipientVisible: Boolean,
    val textFallback: Boolean, val providerReactions: Boolean?, val canAdd: Boolean, val canRemove: Boolean,
) {
    fun permits(channel: Mango9MessageChannel, removing: Boolean = false): Boolean = supported && !textFallback && when (channel) {
        Mango9MessageChannel.team -> scope == "conversation"
        Mango9MessageChannel.sms -> scope == "workspace" && !recipientVisible && providerReactions == false
        Mango9MessageChannel.social -> scope == "provider" && recipientVisible && if (removing) canRemove else canAdd
    }

    companion object {
        fun parse(json: JSONObject?) = json?.let {
            Mango9ReactionCapabilities(
                it.opt("supported") == true, it.optString("scope"), it.opt("recipientVisible") == true,
                it.opt("textFallback") == true, it.opt("providerReactions") as? Boolean, it.opt("canAdd") == true, it.opt("canRemove") == true
            )
        }
    }
}

data class Mango9StaffReactions(val values: Map<String, List<Int>> = emptyMap(), val version: Int = 0) {
    companion object {
        fun parse(json: JSONObject): Mango9StaffReactions {
            val map = json.optJSONObject("reactions") ?: JSONObject()
            val values = map.keys().asSequence().mapNotNull { emoji ->
                val ids = map.optJSONArray(emoji) ?: return@mapNotNull null
                val raw = (0 until ids.length()).map { ids.get(it) }
                // Provider self/participant objects and string IDs are NOT staff maps.
                if (raw.any { it !is Int && it !is Long }) return@mapNotNull null
                val valid = raw.map { (it as Number).toLong() }.filter { it in 1..Int.MAX_VALUE }.map { it.toInt() }.distinct().sorted()
                if (emoji.isBlank() || valid.isEmpty()) null else emoji to valid
            }.toMap()
            return Mango9StaffReactions(values, json.optInt("reactionVersion", json.optInt("version", 0)).coerceAtLeast(0))
        }
    }
}

data class Mango9SocialReactions(val self: String? = null, val participant: String? = null) {
    companion object {
        fun parse(json: JSONObject?): Mango9SocialReactions {
            fun active(key: String, fromMe: Boolean): String? = json?.optJSONObject(key)?.takeIf {
                it.optString("action") == "added" && it.opt("fromMe") == fromMe
            }?.optString("emoji")?.takeIf { it.isNotBlank() }
            return Mango9SocialReactions(active("self", true), active("participant", false))
        }
    }
}

/** Removing an existing provider reaction may remain allowed after adding is disabled. */
internal object Mango9ReactionChoices {
    private val common = listOf("❤️", "👍", "👎", "😂", "‼️", "❓", "👍🏽", "😮", "😢", "🎉", "👨‍👩‍👧‍👦", "🇺🇸")

    fun visible(canAdd: Boolean, canRemove: Boolean, active: List<String>, existing: List<String> = emptyList()): List<String> =
        (
            (if (canRemove) active else emptyList()) +
            (if (canAdd) common + existing else emptyList())
        ).distinct()
}

/** Durable intent only; unknown provider results survive navigation and process death. */
internal class Mango9MessageCommands internal constructor(
    private val preferences: android.content.SharedPreferences,
    private val api: Mango9MessagingApi,
) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences("mango9_message_commands", Context.MODE_PRIVATE), Mango9MessagingApi(context))

    fun hasPending(lease: Mango9SessionLease, channel: Mango9MessageChannel, conversation: String, message: String): Boolean =
        preferences.contains("${Mango9MessagingApi.account(lease.session)}:$channel:$conversation:$message")

    suspend fun react(
        lease: Mango9SessionLease, channel: Mango9MessageChannel, conversation: String, message: String,
                      version: Int, emoji: String, remove: Boolean, checkSaved: Boolean = false
    ): JSONObject = mutex.withLock {
        val key = "${Mango9MessagingApi.account(lease.session)}:$channel:$conversation:$message"
        val old = preferences.getString(key, null)
        if (checkSaved && old == null) throw Mango9MessagingFailure(0, "no_pending_receipt")
        if (old != null && !checkSaved) throw Mango9MessagingFailure(0, "pending")
        val body = old?.let(::JSONObject) ?: JSONObject().apply {
            put("requestId", UUID.randomUUID().toString()); put("version", version)
            if (channel == Mango9MessageChannel.social) {
                put("id", conversation); put("messageId", message.toInt()); put("action", if (remove) "unreact" else "react")
                if (!remove) put("emoji", emoji)
            } else {
                put("emoji", emoji); put("remove", remove); put("scope", if (channel == Mango9MessageChannel.sms) "workspace" else "conversation")
            }
        }
        if (!preferences.edit().putString(key, body.toString()).commit()) throw Mango9MessagingFailure(0, "pending")
        try {
            val response = api.request(
                lease, channel,
                if (channel == Mango9MessageChannel.social) listOf("inbox-moderate") else listOf("reactions", channel.name, message), body = body
            )
            require(response.optString("messageId") == message)
            // Existing reaction receipts do not promise an echoed request ID.
            // When provided, it must still match the exact persisted intent.
            if (response.has("requestId")) require(response.getString("requestId") == body.getString("requestId"))
            if (channel == Mango9MessageChannel.social) {
                require(response.optString("id") == conversation && response.optString("action") == body.getString("action"))
            } else {
                require(response.optString("channel") == channel.name)
            }
            if (response.optString("state") in setOf("succeeded", "failed")) preferences.edit().remove(key).commit()
            response
        } catch (error: Exception) {
            if (old == null && error is Mango9MessagingFailure && error.status in 400..499 && error.status !in setOf(408, 429)) {
                preferences.edit().remove(key).commit()
            }
            throw error
        }
    }

    suspend fun visibility(
        lease: Mango9SessionLease, channel: Mango9MessageChannel, conversation: String?,
                           ids: List<String>, hide: Boolean, conversations: Boolean = false
    ): Set<String> = mutex.withLock {
        val result = mutableSetOf<String>()
        for (chunk in ids.distinct().sorted().chunked(200)) {
            val field = if (conversations) "conversationIds" else "messageIds"
            val values: List<Any> = if (!conversations && channel == Mango9MessageChannel.social) {
                chunk.map {
                it.toInt().also { require(it > 0) }
            }
            } else {
                chunk
            }
            val body = JSONObject().put(field, JSONArray(values)).put("hide", hide)
            if (!conversations && channel == Mango9MessageChannel.social) body.put("id", requireNotNull(conversation))
            val key = "delete:${Mango9MessagingApi.account(lease.session)}:$channel:${chunk.joinToString(",")}"
            val requestId = if (conversations && hide) preferences.getString(key, null) ?: UUID.randomUUID().toString() else null
            if (requestId != null) {
                if (!preferences.edit().putString(key, requestId).commit()) throw Mango9MessagingFailure(0, "pending")
                body.put("requestId", requestId)
            }
            val path = if (channel == Mango9MessageChannel.social) {
                listOf(if (conversations) "inbox-conversation-visibility" else "inbox-message-visibility")
            } else {
                listOf(if (conversations) "conversation-visibility" else "visibility", channel.name)
            }
            val response = api.request(lease, channel, path, body = body)
            require(response.getString("scope") == "user" && response.getString("channel") == channel.name && response.getString("state") == "succeeded")
            val hidden = response.getJSONArray(if (conversations) "hiddenConversationIds" else "hiddenIds").strings().toSet()
            require(chunk.containsAll(hidden))
            if (requestId != null) {
                require(response.getString("requestId") == requestId && response.getJSONArray("clearedConversationIds").strings().toSet() == chunk.toSet())
                preferences.edit().remove(key).commit()
            } else if (hide) require(hidden == chunk.toSet())
            result += hidden
        }
        result
    }

    companion object { private val mutex = Mutex() }
}

internal fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
