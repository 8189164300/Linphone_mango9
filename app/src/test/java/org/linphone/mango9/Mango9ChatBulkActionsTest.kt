// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Mango9ChatBulkActionsTest {
    private fun id(n: Int) = "11111111-1111-4111-8111-${n.toString().padStart(12, '0')}"

    private inner class Fixture(val channel: String = "social") {
        fun chatId(n: Int) = when(channel) { "sms" -> (15550000000L+n).toString(); "team" -> n.toString(); else -> id(n) }
        var active = true; var fail = false; var partial = false; var sequence = 9000
        val calls = mutableListOf<Pair<String, JSONObject>>()
        var selected = emptyList<Int>()
        val client = Mango9ChatBulkActions(channel, { path, body ->
            calls.add(path to JSONObject(body.toString()))
            if (path == "select") {
                val ids = body.optJSONArray("ids")?.let { array -> (0 until array.length()).map { array.getString(it) } }
                selected = (1..500).filter {
                    (ids == null || chatId(it) in ids) && when (body.getString("mode")) { "unread" -> it <= 250; "read" -> it > 250; else -> true }
                }
                JSONObject().put("type", if(channel == "social") "inbox-selection" else "chat-selection").put("channel", channel).put("selectionId", body.getString("requestId")).put("items", rows(selected))
            } else {
                if (fail) { fail = false; error("lost response") }
                JSONObject().put("type", if(channel == "social" && body.getString("action") != "delete") "inbox-bulk-read" else "chat-bulk-result").put("channel", channel).put("requestId", body.getString("requestId")).put("action", body.getString("action"))
                    .put("items", if(body.getString("action") == "delete") JSONArray() else rows(if (partial) selected.drop(1) else selected))
                    .put("hiddenIds", if(body.getString("action") == "delete") JSONArray((if(partial) selected.drop(1) else selected).map(::chatId)) else JSONArray()).put("skippedIds", JSONArray())
            }
        }, { active }, { id(sequence++) })

        fun rows(values: List<Int>) = JSONArray(
            values.map {
            JSONObject().put("id", chatId(it)).put("readThrough", it).put("unread", it <= 250).put("markedUnread", false)
        }
        )
    }

    @Test fun fiveHundredChatsUseTwoRequests() = runBlocking {
        val f = Fixture(); val query = mapOf("platform" to "instagram", "search" to "example")
        val rows = f.client.select("all", query)
        f.client.apply(rows.map { it.id }, "read", query)
        assertEquals(2, f.calls.size); assertEquals(500, f.calls.last().second.getJSONArray("ids").length())
        assertEquals("example", f.calls.first().second.getString("search"))
    }

    @Test fun stateSelectionAndLostResponseRetry() = runBlocking {
        val f = Fixture(); val rows = f.client.select("unread", emptyMap())
        assertEquals(250, rows.size); assertTrue(rows.all { it.unread })
        f.fail = true
        try {
            f.client.apply(rows.map { it.id }, "read", emptyMap()); fail("Expected failure")
        } catch (_: IllegalStateException) { }
        f.client.apply(rows.map { it.id }, "read", emptyMap())
        assertEquals(3, f.calls.size)
        assertEquals(f.calls[1].second.toString(), f.calls[2].second.toString())
        assertTrue(f.client.select("read", emptyMap()).all { !it.unread })
    }

    @Test fun partialReceiptCannotClearSelection() = runBlocking {
        val f = Fixture(); f.partial = true
        try {
            f.client.apply(listOf(id(1), id(2)), "unread", emptyMap()); fail("Expected rejection")
        } catch (_: IllegalStateException) { }
        assertEquals(2, f.calls.first().second.getJSONArray("ids").length())
    }

    @Test fun accountChangesPreventRequests() = runBlocking {
        val f = Fixture(); f.client.select("all", emptyMap()); f.active = false
        try {
            f.client.apply(listOf(id(1)), "read", emptyMap()); fail("Expected rejection")
        } catch (_: IllegalStateException) { }
        assertEquals(1, f.calls.size)
    }
    @Test fun everyChannelAndActionUsesOneBatch() = runBlocking {
        for (channel in listOf("sms", "team", "social", "live")) for (action in listOf("read", "unread", "delete")) {
            val f = Fixture(channel); val rows = f.client.select("all")
            val receipt = f.client.apply(rows.map { it.id }, action)
            assertEquals(2, f.calls.size); assertEquals(500, receipt.items.size + receipt.hiddenIds.size)
        }
    }
    @Test fun deletionRetryPreservesSnapshotForEveryChannel() = runBlocking {
        for (channel in listOf("sms", "team", "social", "live")) {
            val f = Fixture(channel); val rows = f.client.select("unread"); f.fail = true
            try { f.client.apply(rows.map { it.id }, "delete"); fail("Expected lost response") } catch (_: IllegalStateException) { }
            f.client.apply(rows.map { it.id }, "delete")
            assertEquals(3, f.calls.size); assertEquals(f.calls[1].second.toString(), f.calls[2].second.toString())
        }
    }
    @Test fun assistantCannotUseBatch() {
        try { Fixture("assistant"); fail("Assistant must be excluded") } catch (_: IllegalArgumentException) { }
    }

}
