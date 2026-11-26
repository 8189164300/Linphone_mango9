// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class Mango9CrmNote(val id: Long, val text: String, val author: String, val created: String) {
    val dateLabel: String get() = runCatching {
        // The CRM's timestamp has no offset. Keep its wall time unchanged.
        LocalDateTime.parse(created, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            .format(DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a", Locale.US))
    }.getOrDefault(created)
}

internal data class Mango9CrmNotesPage(val notes: List<Mango9CrmNote>, val more: Boolean) {
    companion object {
        private fun id(value: Any?): Long {
            require(value is String || value is Number) { "The notes response could not be verified. Please retry." }
            val raw = value.toString()
            require(raw.matches(Regex("[1-9][0-9]*"))) { "The notes response could not be verified. Please retry." }
            return raw.toLong()
        }

        fun parse(data: JSONObject, contactId: Int, page: Int): Mango9CrmNotesPage {
            require(id(data.opt("page")) == page.toLong() && data.opt("has_more") is Boolean)
            val more = data.getBoolean("has_more")
            val raw = data.getJSONArray("notes")
            require(!more || raw.length() > 0)
            val notes = (0 until raw.length()).map { index ->
                val row = raw.getJSONObject(index)
                require(id(row.opt("contact_id")) == contactId.toLong() && row.opt("note") is String)
                val author = listOf("creator_name", "creator").mapNotNull { row.opt(it) as? String }
                    .map(String::trim).firstOrNull(String::isNotEmpty) ?: "Unknown author"
                Mango9CrmNote(id(row.opt("note_id")), row.getString("note"), author, row.opt("create_date") as? String ?: "")
            }
            return Mango9CrmNotesPage(notes, more)
        }
    }
}
