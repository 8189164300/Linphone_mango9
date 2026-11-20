// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Mango9OpportunityTest {
    private fun metadata() = Mango9OpportunityMetadata(JSONObject("""{"userId":"7","permissions":{"access":true,"create":true},"users":[{"id":"7","name":"Fixture"}],"statusSpecs":[{"id":"custom","name":"Assessment","classification":"open","probability":37,"default":true},{"id":"lost","name":"Lost","classification":"lost","probability":0},{"id":"retired","name":"Old","classification":"open","probability":10,"retired":true}]}"""))

    @Test fun duplicateReceiptsAndActivityTargetsAreStrict() {
        val receipt = JSONObject().put("id", 42).put("action", "create").put("saved", false).put("duplicate", true).put("unchanged", true)
        assertEquals(42, Mango9Opportunities.creationId(receipt))
        assertThrows(IllegalArgumentException::class.java){
            Mango9Opportunities.creationId(JSONObject(receipt.toString()).put("unchanged", false))
        }
        val command = JSONObject().put("contactId", 7).put("values", JSONObject().put("title", "Deal").put("value", "1.00"))
        val row = JSONObject().put("contact_id", 7).put("title", "Deal").put("value", "1.0")
        assertTrue(Mango9Opportunities.duplicateMatches(row, command)); assertFalse(Mango9Opportunities.duplicateMatches(JSONObject(row.toString()).put("value", "2.00"), command))
        assertEquals("documents/quotes/42", Mango9CrmActivityTarget.parse(JSONObject().put("screen", "quote").put("id", 42))?.path)
        assertNull(Mango9CrmActivityTarget.parse(JSONObject().put("screen", "quote").put("id", "../other")))
        assertNull(Mango9CrmActivityTarget.parse(JSONObject().put("screen", "web").put("id", "https://other.invalid")))
        assertNull(Mango9CrmActivityTarget.parse(JSONObject().put("screen", "activity").put("contactId", 7).put("key", "sms:42")))
    }

    @Test fun customStagesAndDecimalAmountsUseExactServerFields() {
        val m = metadata(); val draft = Mango9OpportunityDraft(title = "Fixture", stage = "custom", value = "999999999999.99", ownerId = 7)
        val values = draft.values(m)
        assertEquals(14, values.length()); assertEquals("999999999999.99", values.getString("value")); assertEquals(37, values.getInt("probability"))
        assertTrue(values.isNull("actual_close_date")); assertEquals(listOf("custom", "lost"), m.activeStages.map {it.id})
        val command = draft.command(m, 12); assertEquals(12, command.getInt("contactId")); assertEquals(7, command.getInt("ownerId")); assertTrue(command.getString("requestId").isNotBlank())
    }

    @Test fun invalidAmountsUnknownStagesAndMissingOutcomeAreRejected() {
        val m = metadata(); val d = Mango9OpportunityDraft(title = "Fixture", stage = "custom")
        for (amount in listOf("-1", "1.234", "1,000", "NaN", "1000000000000")){
            d.value = amount; assertThrows(IllegalArgumentException::class.java){d.values(m)}
        }
        d.value = ""; d.stage = "retired"; assertThrows(IllegalArgumentException::class.java){d.values(m)}
        d.stage = "lost"; assertThrows(IllegalArgumentException::class.java){d.values(m)}
        d.lossReason = "Timing"; assertEquals("Timing", d.values(m).getString("loss_reason"))
    }

    @Test fun successDoesNotRequireMessageAndFailureDoesNotBecomeEmptyList() {
        assertTrue(Mango9Opportunities.decode(Mango9CalendarResponse(200, """{"success":true,"data":{"items":[]}}""")).has("items"))
        assertThrows(Mango9OpportunityFailure::class.java){
            Mango9Opportunities.decode(Mango9CalendarResponse(403, """{"success":false,"message":"Unavailable"}"""))
        }
        assertThrows(Mango9OpportunityFailure::class.java){
            Mango9Opportunities.decode(Mango9CalendarResponse(200, """{"success":true}"""))
        }
    }

    @Test fun incomingAndOutgoingNamesAreKeptDistinct() {
        val s = Mango9ContactSharing.parse(JSONObject("""{"shared_with_me":true,"shared_by_me":false,"entries":[{"id":1,"shared_by_name":"Alex","shared_with_name":"Jordan","via":"appointment"}]}"""))
        assertTrue(s.incoming); assertFalse(s.outgoing); assertEquals("Alex", s.entries.single().sharedBy); assertEquals("Jordan", s.entries.single().sharedWith)
    }

    @Test fun protectedContactAndManagementPermissionsAreIndependent() {
        val meta = metadata()
        meta.permissions.put("edit", true).put("share", true).put("remove", true)
        val row =
            JSONObject("""{"revision":"8","canManage":false,"canOpenContact":false,"contact_state":"restricted","contact":{"name":"Hidden"}}""")
        assertNull(Mango9OpportunityPresentation.contact(row))
        assertTrue(Mango9OpportunityPresentation.can(row, meta, "update"))
        assertFalse(Mango9OpportunityPresentation.can(row, meta, "share"))
        row.put("canManage", true).put("archived_at", "2026-11-19")
        assertFalse(Mango9OpportunityPresentation.can(row, meta, "update"))
        assertTrue(Mango9OpportunityPresentation.can(row, meta, "restore"))
    }

    @Test fun moneyAndDatesKeepUnknownSeparateFromZero() {
        assertEquals("369999999999.9963", Mango9OpportunityPresentation.weighted("999999999999.99", 37))
        assertEquals("Not entered", Mango9OpportunityPresentation.money(null, "USD"))
        assertEquals("$0", Mango9OpportunityPresentation.money("0", "USD"))
        assertEquals("Dec 5, 2026", Mango9OpportunityPresentation.date("2026-12-05"))
        assertEquals("Nov 20, 2026", Mango9OpportunityPresentation.date(java.time.Instant.parse("2026-11-20T12:00:00Z").epochSecond.toString()))
        assertEquals("2026-11-20T17:00:00+00:00", Mango9OpportunityPresentation.iso("2026-11-20 17:00:00+00"))
    }

    @Test fun retiredStageMayBeRetainedButNotSelectedForAnotherRecord() {
        val m = metadata(); val d = Mango9OpportunityDraft(title = "Fixture", stage = "retired")
        assertThrows(IllegalArgumentException::class.java){d.values(m)}
        assertThrows(IllegalArgumentException::class.java){d.values(m, "custom")}
        assertEquals("retired", d.values(m, "retired").getString("stage"))
        d.stage = "custom"; d.value = "01"; assertThrows(IllegalArgumentException::class.java){d.values(m)}
    }

}
