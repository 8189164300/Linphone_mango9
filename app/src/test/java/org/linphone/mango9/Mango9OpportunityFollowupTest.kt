// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Mango9OpportunityFollowupTest {
    private fun row() = JSONObject("""{"id":5,"contact_id":101,"revision":"1","title":"Original deal","stage":"open","currency":"USD","value":"100.25","probability":50,"probability_mode":"stage","description":"Keep this","next_action":"","next_due_at":null}""")

    private fun meta() = Mango9OpportunityMetadata(JSONObject("""{"userId":"42","permissions":{"access":true,"edit":true},"users":[],"statusSpecs":[{"id":"open","name":"Open","classification":"open","probability":50,"default":true}]}"""))

    private class Memory { var value: JSONObject? = null

    fun write(v: JSONObject?) { value = v?.let{JSONObject(it.toString())} } }

    @Test fun lostResponseRetriesSameUpdateAndPreservesOtherFields() = runBlocking {
        val memory = Memory(); val row = row(); val ids = mutableListOf<String>()
        val flow = Mango9OpportunityFollowup({row}, {action, body ->
            assertEquals("update", action); ids.add(body.getString("requestId"))
            val values = body.getJSONObject("values")
            assertEquals("100.25", values.getString("value")); assertEquals("Keep this", values.getString("description"))
            assertEquals("Call", values.getString("next_action")); assertEquals("2026-12-01T12:30:00Z", values.getString("next_due_at"))
            if (ids.size == 1)throw IOException("lost")
            JSONObject().put("id", 5).put("action", "update").put("saved", true)
        }, {memory.value}, memory::write)
        try {flow.save(row, meta(), "Call", "2026-12-01T12:30:00Z"); fail()}catch (_: IOException){}
        flow.save(row, meta(), "Must not replace pending", "2026-12-02T12:30:00Z")
        assertEquals(2, ids.size); assertEquals(ids[0], ids[1]); assertNull(memory.value)
    }

    @Test fun lostReadAfterConfirmedSaveNeverResendsUpdate() = runBlocking {
        val memory = Memory(); var reads = 0; var writes = 0
        val flow = Mango9OpportunityFollowup({if (++reads == 1)throw IOException("lost read"); row()}, {_, body ->
            writes++; assertTrue(body.getJSONObject("values").isNull("next_due_at"))
            JSONObject().put("id", 5).put("action", "update").put("saved", true)
        }, {memory.value}, memory::write)
        try {flow.save(row(), meta(), "", null); fail()}catch (_: IOException){}
        flow.save(row(), meta(), "", null); assertEquals(1, writes); assertNull(memory.value)
    }

    @Test fun legacyCalendarRequestsStayFenced() = runBlocking {
        val memory = Memory(); memory.value = JSONObject().put("contactId", 101).put("eventId", 91)
        val flow =
            Mango9OpportunityFollowup({error("No read")}, {_, _ -> error("No mutation")}, {memory.value}, memory::write)
        try {flow.save(row(), meta(), "Call", "2026-12-01T12:30:00Z"); fail()}catch (_: IllegalArgumentException){}
        assertEquals(91, memory.value!!.getInt("eventId"))
    }

    @Test fun conflictClearsPendingAndPermissionDenialDoesNotWrite() = runBlocking {
        val memory = Memory()
        val flow =
            Mango9OpportunityFollowup({
                row()
            }, {_, _ -> throw Mango9OpportunityFailure(409, "Changed")}, {memory.value}, memory::write)
        try {flow.save(row(), meta(), "Call", "2026-12-01T12:30:00Z"); fail()}catch (_: Exception){}
        assertNull(memory.value)
        val denied = meta(); denied.raw.getJSONObject("permissions").put("edit", false)
        try {flow.save(row(), denied, "Call", "2026-12-01T12:30:00Z"); fail()}catch (_: IllegalArgumentException){}
        assertNull(memory.value)
    }

    @Test fun calendarStatusUsesServerConfirmation() {
        assertEquals("Not on Calendar", Mango9OpportunityFollowup.calendarStatus(row()))
        assertEquals("On your Calendar", Mango9OpportunityFollowup.calendarStatus(row().put("onCalendar", true).put("followUp", JSONObject().put("mine", true))))
        assertEquals("On Owner’s Calendar", Mango9OpportunityFollowup.calendarStatus(row().put("onCalendar", true).put("followUp", JSONObject().put("holderName", "Owner"))))
    }
}
