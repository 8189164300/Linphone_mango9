// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import org.linphone.core.ChatRoom
import org.linphone.core.Content
import org.linphone.core.EventLog
import org.linphone.core.ChatRoom.HistoryFilter

/** Core-thread only. A cursor counts source contents, including skipped/non-media entries. */
internal class Mango9NativeMediaHistory(private val room: ChatRoom) {
    data class Cursor(val message: String, val content: Int, val event: EventLog? = null)

    data class Entry(val cursor: Cursor, val content: Content, val time: Long, val ephemeral: Boolean)

    data class Slice(val entries: List<Entry>, val next: Cursor?, val exhausted: Boolean)

    fun anchor(messageId: String, path: String): Entry? {
        if (messageId.isEmpty()) return null
        return room.findEventLog(messageId)?.let { anchor(it, path) }
    }

    fun anchor(event: EventLog, path: String): Entry? {
        val message = event.chatMessage ?: return null
        return message.contents.withIndex().firstOrNull { (_, content) ->
            content.filePath == path && playable(content)
        }?.let { (index, content) -> Entry(Cursor(message.messageId.orEmpty(), index, event), content, message.time, message.isEphemeral) }
    }

    /** At most 50 messages per query; the caller yields between sparse-history slices. */
    fun slice(cursor: Cursor, older: Boolean, limit: Int): Slice {
        require(limit in 1..8)
        val anchor = cursor.event ?: room.findEventLog(cursor.message) ?: return Slice(emptyList(), null, true)
        val events = room.getHistoryRangeNear(
            if (older) 50 else 0, if (older) 0 else 50, anchor, HistoryFilter.ChatMessage.toInt()
        )
        val ordered = if (older) events.reversed() else events.toList()
        val output = arrayListOf<Entry>()
        var last = cursor
        for (event in ordered) {
            val message = event.chatMessage ?: continue
            val id = message.messageId.orEmpty()
            val same = if (cursor.event != null) event == cursor.event else id == cursor.message
            val contents = message.contents
            val indices = if (older) contents.indices.toList() else contents.indices.reversed()
            for (index in indices) {
                if (same && (if (older) index <= cursor.content else index >= cursor.content)) continue
                last = Cursor(id, index, event)
                val content = contents[index]
                if (playable(content)) {
                    output.add(Entry(last, content, message.time, message.isEphemeral))
                    if (output.size == limit) return Slice(output, last, false)
                }
            }
            last = Cursor(id, if (older) Int.MAX_VALUE else -1, event)
        }
        return Slice(output, last, last == cursor || events.isEmpty())
    }

    private fun playable(content: Content): Boolean {
        if ((!content.isFile && !content.isFileTransfer) || content.isVoiceRecording || content.filePath.isNullOrEmpty()) return false
        val type = Mango9NativeAttachment.mimeType(content.filePath.orEmpty(), content.name, content.attachmentMimeType())
        return type.startsWith("image/") || type.startsWith("video/") || type.startsWith("audio/")
    }
}
