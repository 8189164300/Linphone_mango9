// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject

internal data class Mango9SocialAccount(val id: String, val platform: String, val name: String)

data class Mango9SocialThread(
    val id: String, val version: Int, val sourceId: String, val platform: String, val title: String,
    val summary: String, val accountId: String?, val accountName: String, val activityAt: String,
    val unread: Boolean, val connected: Boolean, val canReply: Boolean, val deleted: Boolean,
    val capabilities: Mango9ReactionCapabilities?, val readThrough: Int?, val openSend: Boolean,
    val participantHandle: String? = null, val accountHandle: String? = null,
) {
    // Preparing a local draft does not authorize a provider send. Keep those gates separate,
    // like SMS/Team, so an unavailable reply window does not make the attachment button inert.
    val canDraft get() = !deleted && platform in setOf("facebook", "instagram", "tiktok")
    val canSend get() = canDraft && connected && canReply

    fun canReact(message: Mango9SocialMessage, remove: Boolean): Boolean = connected && !deleted &&
        platform in setOf("facebook", "instagram") && !message.deleted && !message.unavailable &&
        capabilities?.permits(Mango9MessageChannel.social, remove) == true &&
        message.capabilities?.permits(Mango9MessageChannel.social, remove) == true

    companion object {
        fun parse(j: JSONObject): Mango9SocialThread = Mango9SocialThread(
            j.getString("id").also { UUID.fromString(it) }, j.getInt("version"), j.getString("sourceId"), j.getString("platform"),
            j.stringOrNull("name") ?: j.stringOrNull("participantHandle") ?: "Social conversation",
            j.stringOrNull("summary") ?: j.optJSONObject("source")?.stringOrNull("summary") ?: "View conversation",
            j.stringOrNull("accountId"), j.stringOrNull("accountHandle") ?: j.optJSONObject("source")?.stringOrNull("accountName") ?: "Connected account",
            j.stringOrNull("activityAt").orEmpty(), j.opt("unread") == true, j.opt("connectionCurrent") == true,
            j.opt("canReply") == true, j.opt("deleted") == true, Mango9ReactionCapabilities.parse(j.optJSONObject("reactionCapabilities")),
            if (j.isNull("readThrough")) null else j.getInt("readThrough"), j.optJSONObject("openSend") != null,
            j.stringOrNull("participantHandle"), j.stringOrNull("accountHandle")
        )
    }
}

data class Mango9SocialMessage(
    val id: Int, val text: String, val sender: String?, val outgoing: Boolean, val time: String,
    val deleted: Boolean, val unavailable: Boolean, val reactions: Mango9SocialReactions,
    val version: Int, val capabilities: Mango9ReactionCapabilities?, val status: String,
    val attachments: List<Media>,
) {
    data class Media(val index: Int, val type: String, val name: String)

    companion object {
        fun parse(j: JSONObject) = Mango9SocialMessage(
            j.getInt("id").also { require(it > 0) }, j.optString("text"),
            j.stringOrNull("senderName"), j.optString("direction") in setOf("outbound", "outgoing"), j.stringOrNull("createdAt").orEmpty(),
            j.opt("deleted") == true, j.opt("unavailable") == true, Mango9SocialReactions.parse(j.optJSONObject("reactions")),
            j.optInt("reactionVersion", j.optInt("version", 0)), Mango9ReactionCapabilities.parse(j.optJSONObject("reactionCapabilities")),
            j.stringOrNull("deliveryStatus").orEmpty(),
                j.optJSONArray("attachments")?.objects()?.map {
                Media(it.getInt("index"), it.getString("type"), it.getString("name"))
            }.orEmpty()
        )
    }
}

internal data class Mango9SocialPage<T>(val items: List<T>, val next: String?)

internal object Mango9SocialSearch {
    fun normalize(value: String): String {
        val text = value.trim()
        return text.substring(0, text.offsetByCodePoints(0, minOf(200, text.codePointCount(0, text.length))))
    }
}

/** Provider summaries are shared; personal deletion must be applied to saved history first. */
internal object Mango9SocialPreview {
    fun text(messages: List<Mango9SocialMessage>, hidden: Set<String>, hasOlder: Boolean): String {
        val latest = messages.filterNot { it.deleted || it.id.toString() in hidden }
            .maxWithOrNull(compareBy<Mango9SocialMessage>({ Mango9ConversationRecency.time(it.time) }, { it.id }))
        return when {
            latest == null -> if (hasOlder) "Open conversation for earlier messages" else "No visible messages"
            latest.unavailable -> "Content unavailable"
            latest.text.isNotBlank() -> latest.text
            latest.attachments.isNotEmpty() -> "Attachment"
            else -> "Content unavailable"
        }
    }
}

internal enum class Mango9SocialSendOutcome(val notice: String?) {
    Sent(null),
    Failed("Message not sent. Your draft has been kept."),
    Unknown("Delivery unconfirmed. Refresh to check the saved receipt; do not resend.");

    companion object {
        fun of(state: String) = when (state) {
            "sent", "confirmed_sent" -> Sent
            "failed", "confirmed_unsent" -> Failed
            else -> Unknown
        }
    }
}

internal object Mango9SocialPages {
    fun inbox(previous: List<Mango9SocialThread>, page: Mango9SocialPage<Mango9SocialThread>, more: Boolean): List<Mango9SocialThread> {
        // A refresh replaces the visible query window, not the server history.
        // Older pages remain available through the fresh cursor, as on iOS.
        val retained = if (more) previous else emptyList()
        val observed = previous.associateBy { it.id }
        return (
            retained + page.items.map {
            Mango9ConversationRecency.socialSnapshot(it, observed[it.id])
        }
        ).associateBy { it.id }.values
            .sortedWith(compareByDescending<Mango9SocialThread> { Mango9ConversationRecency.time(it.activityAt) }.thenBy { it.id })
    }

    fun cursor(previous: String?, page: Mango9SocialPage<*>, more: Boolean, hadRows: Boolean): String? = when {
        more -> page.next?.takeUnless { it == previous }
        else -> page.next
    }
}

internal object Mango9SocialMediaPolicy {
    fun mimeTypes(platform: String): Array<String> = if (platform == "tiktok") {
        arrayOf("image/jpeg", "image/png")
    } else {
        arrayOf("image/jpeg", "image/png", "video/mp4")
    }

    fun validate(platform: String, mime: String, size: Long, caption: String) {
        require(platform in setOf("facebook", "instagram", "tiktok"))
        require(size > 0 && size <= (if (platform == "tiktok") 3L else 25L) * 1024 * 1024) {
            "Attachment is too large for this channel."
        }
        require(mime in mimeTypes(platform)) {
            "Choose a JPEG or PNG photo, or an MP4 video for Facebook/Instagram."
        }
        require(platform != "tiktok" || caption.isBlank()) { "Send a TikTok photo without a caption." }
    }
}

internal class Mango9SocialRepository(context: Context, private val api: Mango9MessagingApi = Mango9MessagingApi(context)) {
    private val sessions = Mango9SessionStore(context)
    private val intents = context.applicationContext.getSharedPreferences("mango9_social_send_intents", Context.MODE_PRIVATE)
    private val channel = Mango9MessageChannel.social

    fun isCurrent(lease: Mango9SessionLease) = sessions.currentSession(lease) != null

    suspend fun accounts(lease: Mango9SessionLease): List<Mango9SocialAccount> = api.request(lease, channel, listOf("accounts"))
        .getJSONArray("items").objects().map {
            Mango9SocialAccount(it.getString("id"), it.getString("platform"), it.stringOrNull("name") ?: it.stringOrNull("username") ?: it.getString("platform"))
        }
        .filter { it.platform in setOf("facebook", "instagram", "tiktok") }

    suspend fun inbox(lease: Mango9SessionLease, platform: String = "", account: String = "", before: String? = null, search: String = ""): Mango9SocialPage<Mango9SocialThread> {
        val query = mutableMapOf("kind" to "dms", "limit" to "50")
        if (platform.isNotBlank()) query["platform"] = platform
        if (account.isNotBlank()) query["accountId"] = account
        Mango9SocialSearch.normalize(search).takeIf { it.isNotEmpty() }?.let { query["search"] = it }
        before?.let { query["before"] = it }
        val page = api.request(lease, channel, listOf("inbox"), query)
        return Mango9SocialPage(
            page.getJSONArray("items").objects().map(Mango9SocialThread::parse).distinctBy {
            it.id
        },
            page.stringOrNull("next")
        )
    }

    suspend fun thread(lease: Mango9SessionLease, id: String): Mango9SocialThread = Mango9SocialThread.parse(
        api.request(lease, channel, listOf("inbox-detail"), mapOf("id" to id))
    ).also { require(it.id == id) }

    suspend fun messages(lease: Mango9SessionLease, id: String, before: String? = null): Mango9SocialPage<Mango9SocialMessage> {
        val query = mutableMapOf("id" to id, "limit" to "50")
        before?.let { query["before"] = it }
        val page = api.request(lease, channel, listOf("inbox-messages"), query)
        return Mango9SocialPage(page.getJSONArray("items").objects().map(Mango9SocialMessage::parse), page.stringOrNull("next"))
    }

    suspend fun markRead(lease: Mango9SessionLease, thread: Mango9SocialThread) {
        val readThrough = thread.readThrough ?: return
        api.request(
            lease, channel, listOf("inbox-read"),
            body = JSONObject().put("id", thread.id)
            .put("requestId", UUID.randomUUID().toString()).put("readThrough", readThrough)
        )
    }

    suspend fun refreshMessages(lease: Mango9SessionLease, sourceId: String) {
        api.request(lease, channel, listOf("inbox-refresh-messages"), body = JSONObject().put("id", sourceId))
    }

    suspend fun mute(lease: Mango9SessionLease, id: String, muted: Boolean? = null): Boolean {
        val result = api.request(
            lease, channel, listOf(if (muted == null) "inbox-notification-get" else "inbox-notification-set"),
            if (muted == null) mapOf("id" to id) else emptyMap(), muted?.let { JSONObject().put("id", id).put("muted", it) }
        )
        require(result.getString("id") == id && result.get("muted") is Boolean)
        return result.getBoolean("muted")
    }

    suspend fun attachment(lease: Mango9SessionLease, id: String, message: Int, media: Mango9SocialMessage.Media): Mango9ChatMedia {
        val link = api.request(lease, channel, listOf("inbox-attachment"), mapOf("id" to id, "messageId" to message.toString(), "index" to media.index.toString()))
        val url = Mango9Configuration.verifiedHttpsUrl(link.getString("url"))?.toString() ?: throw Mango9MessagingFailure(0, "invalid_response")
        val mime = if (media.type == "video") "video/mp4" else if (media.name.endsWith(".png", true)) "image/png" else "image/jpeg"
        return Mango9ChatMedia(url, url, media.name, mime, if (media.type == "video") Mango9ChatMedia.Kind.Video else Mango9ChatMedia.Kind.Image)
    }

    private fun key(lease: Mango9SessionLease, id: String) = Mango9MessagingApi.account(lease.session) + ":" + id

    fun hasPending(lease: Mango9SessionLease, id: String) = intents.contains(key(lease, id))

    /** Returns true only for a confirmed send; callers retain their draft otherwise. */
    suspend fun send(lease: Mango9SessionLease, thread: Mango9SocialThread, text: String, attachments: List<Mango9PendingAttachment>): Boolean = sendLock.withLock {
        require(thread.canSend && !thread.openSend) { "Replies are unavailable for this conversation." }
        require(attachments.size <= 1) { "Social supports one attachment per message." }
        if (hasPending(lease, thread.id)) throw Mango9MessagingFailure(0, "pending")
        require(text.isNotBlank() || attachments.isNotEmpty())
        val mediaId = attachments.firstOrNull()?.let { upload(lease, thread, it, text) }
        val requestId = UUID.randomUUID().toString()
        if (!intents.edit().putString(key(lease, thread.id), requestId).commit()) throw Mango9MessagingFailure(0, "pending")
        try {
            val body = JSONObject().put("id", thread.id).put("version", thread.version).put("message", text).put("requestId", requestId)
            mediaId?.let { body.put("mediaId", it) }
            val result = api.request(lease, channel, listOf("inbox-send"), body = body)
            require(result.getString("id") == thread.id && result.getString("requestId") == requestId)
            finishReceipt(lease, thread.id, result)
        } catch (error: Exception) {
            if (error is Mango9MessagingFailure && error.status in 400..499 && error.status !in setOf(408, 429)) {
                intents.edit().remove(key(lease, thread.id)).commit()
            }
            throw error
        }
    }

    suspend fun reconcile(lease: Mango9SessionLease, id: String): JSONObject? {
        val page = api.request(lease, channel, listOf("inbox-sends"), mapOf("id" to id))
        val pending = intents.getString(key(lease, id), null)
        val receipt = page.getJSONArray("items").objects().firstOrNull { it.optString("requestId") == pending }
        if (receipt != null) {
            finishReceipt(lease, id, receipt)
            return receipt
        }
        return null
    }

    private fun finishReceipt(lease: Mango9SessionLease, id: String, receipt: JSONObject): Boolean {
        require(receipt.getString("id") == id && isCurrent(lease))
        val outcome = Mango9SocialSendOutcome.of(receipt.getString("state"))
        if (outcome != Mango9SocialSendOutcome.Unknown) intents.edit().remove(key(lease, id)).commit()
        return outcome == Mango9SocialSendOutcome.Sent
    }

    private suspend fun upload(lease: Mango9SessionLease, thread: Mango9SocialThread, attachment: Mango9PendingAttachment, text: String): String = withContext(Dispatchers.IO) {
        Mango9SocialMediaPolicy.validate(thread.platform, attachment.mimeType, attachment.size, text)
        require(attachment.uri.scheme == "file")
        val file = File(requireNotNull(attachment.uri.path))
        require(file.length() == attachment.size)
        val id = UUID.randomUUID().toString()
        val ticket = api.request(
            lease, channel, listOf("media-prepare"),
            body = JSONObject().put("id", id)
            .put("name", attachment.name).put("mime", attachment.mimeType).put("size", attachment.size)
        )
        require(ticket.getJSONObject("asset").getString("id") == id)
        if (ticket.getJSONObject("asset").optString("state") == "ready") return@withContext id
        val upload = ticket.getJSONObject("upload")
        require(upload.getString("method") == "PUT" && upload.getJSONObject("headers").getString("Content-Type") == attachment.mimeType)
        val url = Mango9Configuration.verifiedHttpsUrl(upload.getString("url")) ?: throw Mango9MessagingFailure(0, "invalid_response")
        if (!isCurrent(lease)) throw Mango9ApiException.Unauthorized
        // Signed storage request has no CRM authorization or cookies.
        val response = Mango9CalendarHttpTransport().execute(Request.Builder().url(url).put(file.asRequestBody(attachment.mimeType.toMediaType())).build())
        if (!isCurrent(lease)) throw Mango9ApiException.Unauthorized
        require(response.status in 200..299)
        val result = api.request(lease, channel, listOf("media-complete"), body = JSONObject().put("id", id))
        require(result.getString("id") == id && result.getString("state") == "ready")
        id
    }

    companion object { private val sendLock = Mutex() }
}

internal fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) {
    null
} else {
    optString(key).trim().takeIf {
    it.isNotEmpty()
}
}
