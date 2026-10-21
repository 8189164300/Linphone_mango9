// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal fun JSONObject.liveText(key: String): String = (opt(key) as? String).orEmpty()
internal fun JSONArray.liveObjects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

/** Account-scoped display preferences on this installation, separate from server permissions. */
internal class Mango9ChatTabPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("mango9_chat_tabs", Context.MODE_PRIVATE)
    private val sessions = Mango9SessionStore(context)
    private fun key(tab: String) = sessions.captureSession()?.session?.let { "${Mango9MessagingApi.account(it)}:$tab" }
    fun visible(tab: String): Boolean = key(tab)?.let { preferences.getBoolean(it, true) } ?: true
    fun set(tab: String, enabled: Boolean) { key(tab)?.let { preferences.edit().putBoolean(it, enabled).apply() } }
}

internal class Mango9LiveChatFailure(val status: Int) : Exception(
    when (status) {
        401 -> "Please sign in again to open Live Chat."
        403 -> "Live Chat is not available to this account. Ask your workspace owner to check access."
        404 -> "This conversation or Live Chat connection is no longer available."
        409 -> "This conversation changed. Refresh before trying again."
        413 -> "Choose an image, PDF, or voice recording smaller than 5 MB."
        429 -> "Please wait a moment before trying again."
        400 -> "The action could not be completed. Refresh and check the website’s chat settings."
        else -> "We couldn’t reach Live Chat. Check your connection and try again."
    }
)

internal data class Mango9LiveConversation(
    val id: String, val site: String, val name: String, val siteName: String, val summary: String,
    val status: String, val unread: Boolean, val updated: String, val assignee: Int?, val context: JSONObject, val email: String,
) {
    companion object {
        fun parse(data: JSONObject): Mango9LiveConversation {
            val id = data.getString("id"); UUID.fromString(id)
            return Mango9LiveConversation(
                id, data.getString("site_id"), data.liveText("name").ifBlank { "Website visitor" },
                data.liveText("site_name").ifBlank { "Live Chat" }, data.liveText("summary"), data.liveText("status"),
                data.opt("unread") == true, data.liveText("updated_at"), data.optInt("assignee_id").takeIf { it > 0 },
                data.optJSONObject("visitorContext") ?: data.optJSONObject("context") ?: JSONObject(), data.liveText("email"),
            )
        }
    }
}

internal data class Mango9LiveMessage(val id: Long, val data: JSONObject) {
    val text get() = data.liveText("content")
    val outgoing get() = data.liveText("direction") == "outgoing"
    val context get() = data.optJSONObject("content_attributes")?.optJSONObject("visitorContext") ?: JSONObject()
    val hidden get() = data.optBoolean("hiddenForMe")
    val sender get() = data.optJSONObject("sender")?.liveText("name").orEmpty().ifBlank { "Support" }
    val created get() = data.optLong("created_at")
    val status get() = data.liveText("status")
    val reactions get() = Mango9LiveReactions.parse(data.optJSONObject("reactions"))
    val selectedText get() = (listOf(sender, text) + files.map { it.liveText("name") } +
        Mango9LiveVisitorContext.rows(context).map { "${it.first}: ${it.second}" }).filter(String::isNotBlank).joinToString("\n")
    val presentationKey get() = JSONObject(data.toString()).apply {
        optJSONArray("attachments")?.liveObjects()?.forEach { it.remove("data_url"); it.remove("thumb_url") }
    }.toString()
    val files get() = data.optJSONArray("attachments")?.liveObjects().orEmpty()
    companion object {
        fun parse(data: JSONObject): Mango9LiveMessage {
            require(data.liveText("direction") in setOf("incoming", "outgoing", "note"))
            return Mango9LiveMessage(data.getLong("id"), data)
        }
    }
}

/** Only server-supplied fields survive into the UI; collection/privacy policy remains on the website. */
internal object Mango9LiveVisitorContext {
    fun compact(value: JSONObject): String {
        val country = value.optJSONObject("country") ?: JSONObject()
        val place = country.liveText("city").ifBlank { country.liveText("name") }
        val location = listOf(country.liveText("flag"), place).filter(String::isNotBlank).joinToString(" ")
        val ip = value.liveText("ipAddress").takeIf(String::isNotBlank)?.let { "IP $it" }.orEmpty()
        val page = runCatching { java.net.URI(value.liveText("entryPage")).host }.getOrNull().orEmpty()
        return listOf(location, ip, page).filter(String::isNotBlank).joinToString(" · ")
    }

    fun rows(value: JSONObject): List<Pair<String, String>> = buildList {
        value.optJSONObject("country")?.let { country ->
            val place = listOf("city", "region", "name").map(country::liveText).filter(String::isNotBlank).distinct().joinToString(", ")
            if (place.isNotBlank()) add("Approximate location" to listOf(country.liveText("flag"), place).filter(String::isNotBlank).joinToString(" "))
        }
        for ((key, label) in listOf("website" to "Website", "pageUrl" to "Current page", "entryPage" to "Entry page", "pageTitle" to "Page title",
            "ipAddress" to "IP address", "language" to "Language", "timezone" to "Time zone", "referrerUrl" to "Referrer",
            "mode" to "Chat mode", "routingReason" to "Routing", "observedAt" to "Observed at", "siteVisitStartedAt" to "Visit started")) {
            (if (key == "timezone") value.liveText("timeZone").ifBlank { value.liveText(key) } else value.liveText(key)).takeIf(String::isNotBlank)?.let { add(label to it) }
        }
        value.optJSONObject("device")?.let { device ->
            for ((key, label) in listOf("os" to "Operating system", "browser" to "Browser", "type" to "Device", "versionAccuracy" to "Device accuracy")) {
                device.liveText(key).takeIf(String::isNotBlank)?.let { add(label to it) }
            }
        }
        if (value.opt("timeBeforeFirstMessageSeconds") is Number) add("Time before first message" to "${value.getLong("timeBeforeFirstMessageSeconds")} seconds")
    }
}

internal class Mango9LiveChatApi internal constructor(
    private val sessions: Mango9SessionAccess,
    refresh: suspend (Mango9Session) -> Mango9Session,
    private val transport: Mango9CalendarTransport,
) {
    constructor(context: Context) : this(Mango9SessionStore(context), Mango9ApiClient(context)::refresh, Mango9CalendarHttpTransport())
    private val authorized = Mango9AuthorizedRequests(sessions, refresh)
    suspend fun request(lease: Mango9SessionLease, operation: String, query: Map<String, String> = emptyMap(), body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        authorized.execute(lease) { session ->
            val response = transport.execute(wire(session, operation, query, body))
            if (response.status == 401) throw Mango9ApiException.InvalidCredentials
            if (response.status !in 200..299) throw Mango9LiveChatFailure(response.status)
            val envelope = JSONObject(response.body)
            if (envelope.opt("success") != true) throw Mango9LiveChatFailure(0)
            envelope.optJSONObject("data") ?: throw Mango9LiveChatFailure(0)
        }
    }
    companion object {
        fun wire(session: Mango9Session, operation: String, query: Map<String, String>, body: JSONObject?): Request {
            val reads = setOf("sites", "list", "messages", "capabilities", "availability", "analytics")
            val writes = setOf("send", "read", "typing", "status", "assign", "presence", "preferences", "visibility", "reaction")
            require((body == null && operation in reads) || (body != null && operation in writes && query.isEmpty()))
            val base = Mango9Configuration.verifiedHttpsUrl(session.crmApiBaseUrl)?.toString()?.toHttpUrl() ?: throw Mango9LiveChatFailure(0)
            require(base.query == null)
            val url = base.newBuilder().apply {
                if (base.pathSegments.lastOrNull() == "") removePathSegment(base.pathSize - 1)
                addPathSegment("mobile"); addPathSegment("livechat"); addPathSegment(operation)
                query.forEach { (key, value) -> addQueryParameter(key, value) }
            }.build()
            return Request.Builder().url(url).header("Authorization", "Bearer ${session.accessToken}").header("Accept", "application/json")
                .header("Cache-Control", "no-cache").method(if (body == null) "GET" else "POST", body?.toString()?.toRequestBody("application/json".toMediaType())).build()
        }
        fun message(error: Throwable): String = when (error) {
            is Mango9LiveChatFailure -> error.message.orEmpty()
            is Mango9ApiException.Unauthorized -> "This account is no longer active. Reopen Live Chat."
            else -> Mango9LiveChatFailure(0).message.orEmpty()
        }
    }
}

/** Encrypted, durable payload and original request UUID survive an uncertain send or process death. */
@Suppress("DEPRECATION")
internal class Mango9LiveChatOutbox(context: Context) {
    private val preferences by lazy {
        EncryptedSharedPreferences.create(context.applicationContext, "mango9_live_chat_outbox",
            MasterKey.Builder(context.applicationContext, "mango9_live_chat_outbox_key").setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }
    private fun key(lease: Mango9SessionLease, id: String) = "${Mango9MessagingApi.account(lease.session)}:$id"
    fun load(lease: Mango9SessionLease, id: String): JSONObject? = preferences.getString(key(lease, id), null)?.let(::JSONObject)
    fun save(lease: Mango9SessionLease, id: String, command: JSONObject?) {
        val edit = preferences.edit()
        if (command == null) edit.remove(key(lease, id)) else edit.putString(key(lease, id), command.toString())
        check(edit.commit()) { "The pending message could not be saved. Nothing was sent." }
    }
}

/** Explicit provider snapshot; absent or malformed support never enables a reaction. */
internal data class Mango9LiveReactions(val version: Int, val supported: Boolean, val allowed: List<String>, val items: List<Item>) {
    data class Item(val emoji: String, val count: Int, val mine: Boolean)
    companion object {
        val empty = Mango9LiveReactions(0, false, emptyList(), emptyList())
        fun parse(raw: JSONObject?): Mango9LiveReactions = runCatching {
            requireNotNull(raw)
            val version = raw.getInt("version"); require(version >= 0)
            val items = raw.getJSONArray("items").liveObjects().map {
                Item(it.getString("emoji"), it.getInt("count"), it.getBoolean("mine")).also { row -> require(row.emoji.isNotBlank() && row.count > 0) }
            }
            require(items.map { it.emoji }.distinct().size == items.size)
            val allowed = raw.getJSONArray("allowed").let { list -> (0 until list.length()).map { list.getString(it) } }
            Mango9LiveReactions(version, raw.getBoolean("supported"), allowed, items)
        }.getOrDefault(empty)
    }
}

internal object Mango9LivePolicy {
    const val MAX_BYTES = 5 * 1024 * 1024
    fun capabilities(site: JSONObject?): JSONObject {
        site?.optJSONObject("capabilities")?.let { return it }
        val settings = site?.optJSONObject("settings") ?: JSONObject()
        return JSONObject().put("text", true).put("typing", settings.optBoolean("typingIndicators", true))
            .put("readState", settings.optBoolean("readReceipts", true)).put("attachments", JSONArray(
                if (settings.optBoolean("attachmentsEnabled")) listOf("image/png", "image/jpeg", "image/webp", "application/pdf") else emptyList<String>()))
    }
    fun attachments(site: JSONObject?): List<String> = capabilities(site).optJSONArray("attachments")?.let { items ->
        (0 until items.length()).map { items.getString(it) }
    }.orEmpty()
    fun payload(bytes: ByteArray, name: String, mime: String, allowed: List<String>): JSONObject {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "Choose an attachment smaller than 5 MB." }
        require(mime in allowed) { "Choose a supported image, PDF, or voice recording." }
        return JSONObject().put("name", Mango9AttachmentPreparer.safeName(name)).put("mime", mime)
            .put("base64", java.util.Base64.getEncoder().encodeToString(bytes))
    }
    fun media(file: JSONObject, session: Mango9Session): Mango9ChatMedia {
        val base = session.smsChatApi.toHttpUrl()
        val url = requireNotNull(base.resolve(file.getString("data_url")))
        require(url.isHttps && url.host == base.host && url.port == base.port && url.username.isBlank() && url.password.isBlank())
        require(url.encodedPath.startsWith("/sms_chat_api/livechat/file/"))
        val mime = file.liveText("mime")
        val kind = when { mime.startsWith("image/") -> Mango9ChatMedia.Kind.Image; mime.startsWith("audio/") -> Mango9ChatMedia.Kind.Audio; else -> Mango9ChatMedia.Kind.File }
        return Mango9ChatMedia(source = "live:" + file.liveText("id").ifBlank { url.encodedPath }, url = url.toString(), name = file.liveText("name").ifBlank { "Attachment" }, mimeType = mime, kind = kind)
    }
}
