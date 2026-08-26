/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.mango9

import android.net.Uri
import java.net.URI
import java.net.URLDecoder

enum class Mango9ChatConnectionState {
    Disconnected,
    Connecting,
    Connected,
}

data class Mango9ChatTarget(
    val userId: Int,
    val name: String,
    val roomId: String? = null,
)

data class Mango9SmsTarget(val phone: String, val name: String)

data class Mango9ChatUser(
    val id: Int,
    val name: String,
    val avatar: String,
    val category: String,
)

data class Mango9ChatRoom(
    val id: String,
    val userIds: List<Int>,
    val latest: String,
    val lastMessage: String,
    val unread: Int,
    val isDirect: Boolean,
)

data class Mango9ChatMessage(
    val id: String,
    val fromUserId: Int,
    val roomId: String,
    val text: String,
    val time: String,
    val status: Int,
    val files: String,
)

data class Mango9SmsParty(
    val phone: String,
    val latest: String,
    val lastMessage: String,
    val unread: Int,
    val avatar: String,
)

data class Mango9SmsMessage(
    val id: String,
    val phone: String,
    val text: String,
    val time: String,
    val senderId: String,
    val status: Int,
    val isIncoming: Boolean,
    val files: String,
)

data class Mango9SmsSender(val id: Int, val senderId: String)

data class Mango9PendingAttachment(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val size: Long,
)

data class Mango9ChatState(
    val connection: Mango9ChatConnectionState = Mango9ChatConnectionState.Disconnected,
    val connectedIdentity: String? = null,
    val currentUserId: Int? = null,
    val users: List<Mango9ChatUser> = emptyList(),
    val rooms: List<Mango9ChatRoom> = emptyList(),
    val messages: List<Mango9ChatMessage> = emptyList(),
    val smsParties: List<Mango9SmsParty> = emptyList(),
    val smsMessages: List<Mango9SmsMessage> = emptyList(),
    val smsSenders: List<Mango9SmsSender> = emptyList(),
    val onlineUserIds: Set<Int> = emptySet(),
    val typingUserIds: Set<Int> = emptySet(),
    val activeRoomId: String? = null,
    val activeSmsPhone: String? = null,
    val errorMessage: String? = null,
) {
    val isConnected: Boolean get() = connection == Mango9ChatConnectionState.Connected
}

data class Mango9ChatMedia(
    val source: String,
    val url: String,
    val name: String,
    val mimeType: String,
    val kind: Kind,
) {
    enum class Kind {
        Image,
        Video,
        Audio,
        File,
    }

    companion object {
        fun parse(value: String): List<Mango9ChatMedia> = value
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .mapNotNull(::parseOne)

        private fun parseOne(raw: String): Mango9ChatMedia? {
            val metadata = Mango9Configuration.verifiedHttpsUrl(raw)?.toURI() ?: return null
            val query = queryParameters(metadata)
            val download = listOf("&ffName=", "?ffName=")
                .map(raw::indexOf)
                .filter { it >= 0 }
                .minOrNull()
                ?.let { raw.substring(0, it) }
                ?: raw
            val verifiedDownload = Mango9Configuration.verifiedHttpsUrl(download)?.toString() ?: return null
            val downloadUri = runCatching { URI(verifiedDownload) }.getOrNull() ?: return null
            val name = query["ffName"]?.takeIf(String::isNotBlank)
                ?: downloadUri.path.substringAfterLast('/').takeIf(String::isNotBlank)?.let(::decodeComponent)
                ?: "Attachment"
            val extension = (query["ffExt"] ?: name.substringAfterLast('.', "")).lowercase()
            val mime = query["ffType"].orEmpty().ifBlank { mimeFromExtension(extension) }
            val kind = when {
                mime.startsWith("image/") || extension in setOf("jpg", "jpeg", "png", "gif", "webp") -> Kind.Image
                mime.startsWith("video/") || extension in setOf("mp4", "mov", "webm") -> Kind.Video
                mime.startsWith("audio/") || extension in setOf("m4a", "mka", "mp3", "ogg", "wav") -> Kind.Audio
                else -> Kind.File
            }
            return Mango9ChatMedia(raw, verifiedDownload, name, mime, kind)
        }

        private fun queryParameters(uri: URI): Map<String, String> = uri.rawQuery.orEmpty()
            .split('&')
            .filter(String::isNotBlank)
            .associate { item ->
                decodeComponent(item.substringBefore('=')) to decodeComponent(item.substringAfter('=', ""))
            }

        private fun decodeComponent(value: String): String = runCatching {
            URLDecoder.decode(value, Charsets.UTF_8.name())
        }.getOrDefault(value)

        private fun mimeFromExtension(extension: String): String = when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "m4a" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "ogg" -> "audio/ogg"
            "wav" -> "audio/wav"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
    }
}
