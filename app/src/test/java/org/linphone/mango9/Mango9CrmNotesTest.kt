// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Mango9CrmNotesTest {
    private fun row() = JSONObject().put("note_id", "8").put("contact_id", "42")
        .put("note", "Plain <b>text</b>\nSecond line").put("creator", "Another author")
        .put("create_date", "2026-11-26 10:03:00").put("modifier", JSONObject.NULL)

    private fun page(rows: JSONArray = JSONArray().put(row())) = JSONObject()
        .put("page", "1").put("has_more", false).put("notes", rows)

    @Test fun otherAuthorsAndPlainTextArePreserved() {
        val result = Mango9CrmNotesPage.parse(page(), 42, 1)
        assertFalse(result.more)
        assertEquals("Another author", result.notes.single().author)
        assertEquals("Plain <b>text</b>\nSecond line", result.notes.single().text)
        assertEquals("Nov 26, 2026 · 10:03 AM", result.notes.single().dateLabel)
        assertEquals(8L, result.notes.single().id)
    }

    @Test fun nullMetadataIsNotRenderedAsNull() {
        val value = row().put("creator", JSONObject.NULL).put("create_date", JSONObject.NULL)
        val result = Mango9CrmNotesPage.parse(page(JSONArray().put(value)), 42, 1).notes.single()
        assertEquals("Unknown author", result.author)
        assertEquals("", result.dateLabel)
    }

    @Test fun foreignRecordInvalidIdsAndPageMismatchAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { Mango9CrmNotesPage.parse(page(), 43, 1) }
        assertThrows(IllegalArgumentException::class.java) { Mango9CrmNotesPage.parse(page(), 42, 2) }
        for (id in listOf(true, -1, "0", "7.5", "../7")) {
            assertThrows(IllegalArgumentException::class.java) {
                Mango9CrmNotesPage.parse(page(JSONArray().put(row().put("note_id", id))), 42, 1)
            }
        }
    }

    @Test fun emptyHistoryIsDistinctFromInvalidOrNonAdvancingResponse() {
        assertTrue(Mango9CrmNotesPage.parse(page(JSONArray()), 42, 1).notes.isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            Mango9CrmNotesPage.parse(page(JSONArray()).put("has_more", true), 42, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Mango9CrmNotesPage.parse(page().put("has_more", "false"), 42, 1)
        }
    }
}
