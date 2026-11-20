// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Mango9OpportunityFollowupTest {
    private val event = Mango9CalendarTestFixtures.event()

    private fun row() = JSONObject("""{"id":5,"contact_id":101,"contact_state":"available","canOpenContact":true,"contact":{"id":101},"revision":"1","title":"Original deal","stage":"open","currency":"USD","value":"100.25","probability":50,"probability_mode":"stage","owner_id":42,"description":"Keep this","next_action":"","next_due_at":null}""")

    private fun meta() = Mango9OpportunityMetadata(JSONObject("""{"userId":"42","permissions":{"access":true,"edit":true},"users":[],"statusSpecs":[{"id":"open","name":"Open","classification":"open","probability":50,"default":true}]}"""))

    private class Memory { var value: JSONObject? = null

    fun write(v: JSONObject?) {value = v?.let{JSONObject(it.toString())}} }

    @Test fun savedEventLinksAndUpdatesWithoutChangingDealFields() = runBlocking {
        val memory = Memory(); val row = row(); val changes = mutableListOf<String>(); var creates = 0
        val flow = Mango9OpportunityFollowup({row}, {meta()}, {action, body ->
            changes.add(action)
            if (action == "activity"){
                row.put("activity_key", body.getString("activityKey"))
            } else {
                val v = body.getJSONObject("values"); assertEquals("100.25", v.getString("value")); assertEquals("Keep this", v.getString("description")); assertEquals(event.title, v.getString("next_action")); assertEquals(event.startAt.toString(), v.getString("next_due_at"))
            }
            JSONObject().put("id", 5).put("saved", true).put("action", action)
        }, {event}, {memory.value}, memory::write)
        flow.save(101, null, event.title, event.startAt.toString(), event.endAt.toString()){creates++; event}
        assertEquals(1, creates); assertEquals(listOf("activity", "update"), changes); assertNull(memory.value)
    }

    @Test fun lostCalendarCreateResponseCannotCreateTwice() = runBlocking {
        val memory = Memory(); var creates = 0
        val flow =
            Mango9OpportunityFollowup({
                row()
            }, {meta()}, {_, _ -> error("No link before receipt")}, {event}, {memory.value}, memory::write)
        repeat(2){
            try {
                flow.save(101, null, event.title, event.startAt.toString(), event.endAt.toString()){
                    creates++; throw IOException("lost")
                }; fail("Expected uncertain failure")
            }catch (_: Exception){}
        }
        assertEquals(1, creates); assertNotNull(memory.value)
    }

    @Test fun linkFailureRetriesSameRequestAndReusesCalendarReceipt() = runBlocking {
        val memory = Memory(); val row = row(); var creates = 0; val requests = mutableListOf<String>(); var fail = true
        val flow = Mango9OpportunityFollowup({row}, {meta()}, {action, body ->
            if (action == "activity") {
                requests.add(body.getString("requestId")); if (fail){
                    fail = false; throw IOException("lost link")
                }; row.put("activity_key", body.getString("activityKey"))
            }
            JSONObject().put("id", 5).put("saved", true).put("action", action)
        }, {event}, {memory.value}, memory::write)
        try {
            flow.save(101, null, event.title, event.startAt.toString(), event.endAt.toString()){
                creates++; event
            }; fail("Expected failure")
        }catch (_: IOException){}
        flow.save(101, null, event.title, event.startAt.toString(), event.endAt.toString()){creates++; event}
        assertEquals(1, creates); assertEquals(2, requests.size); assertEquals(requests[0], requests[1]); assertNull(memory.value)
    }

    @Test fun interruptedRescheduleIsReadBackWithoutReplayingCalendarWrite() = runBlocking {
        val memory = Memory(); val row = row().put("activity_key", "event-${event.id}"); var writes = 0
        val flow =
            Mango9OpportunityFollowup({
                row
            }, {
                meta()
            }, {action, _ -> JSONObject().put("id", 5).put("saved", true).put("action", action)}, {
                event
            }, {memory.value}, memory::write)
        try {
            flow.save(101, event.id, event.title, event.startAt.toString(), event.endAt.toString()){
                writes++; throw IOException("lost response")
            }
        }catch (_: IOException){}
        flow.save(101, event.id, event.title, event.startAt.toString(), event.endAt.toString()){writes++; event}
        assertEquals(1, writes); assertNull(memory.value)
    }

    @Test fun wrongContactReceiptNeverLinksToOpportunity() = runBlocking {
        val memory = Memory(); var mutations = 0
        val flow =
            Mango9OpportunityFollowup({
                row()
            }, {meta()}, {_, _ -> mutations++; JSONObject()}, {event}, {memory.value}, memory::write)
        try {
            flow.save(999, null, event.title, event.startAt.toString(), event.endAt.toString()){
                event
            }; fail("Expected wrong contact rejection")
        }catch (_: IllegalArgumentException){}
        assertEquals(0, mutations); assertNotNull(memory.value)
    }
}
