// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class Mango9SupervisorParityTest {
    private val actionId = "62000000-0000-4000-8000-000000000001"

    private fun detail() = JSONObject().put("id", actionId).put("state", "PENDING_APPROVAL").put("approvalDigest", "a".repeat(64))
        .put("args", JSONObject().put("secret", "must-not-save"))
        .put("recap", JSONObject().put("version", 1).put("title", "Follow-up task").put("fields", JSONArray().put(JSONObject().put("label", "Owner").put("value", "Alex"))))

    @Test fun cacheContainsDisplayOnlyAndNeverExecutableSettings() {
        val saved = requireNotNull(Mango9SupervisorPresentation.savedConfiguration(detail()))
        assertTrue(saved.getBoolean("summaryOnly")); assertFalse(saved.has("approvalDigest")); assertFalse(saved.has("args"))
        assertEquals("Alex", saved.getJSONObject("recap").getJSONArray("fields").getJSONObject(0).getString("value"))
        assertNull(Mango9SupervisorPresentation.savedConfiguration(detail().put("id", "bad")))
        val bad = detail(); bad.getJSONObject("recap").getJSONArray("fields").getJSONObject(0).put("value", JSONObject())
        assertNull(Mango9SupervisorPresentation.savedConfiguration(bad))
    }

    @Test fun messageCacheDropsToolsAndUntrustedMetadataButRetainsUtf8() {
        val row = JSONObject().put("id", "message").put("role", "assistant")
            .put(
                "parts",
                JSONArray().put(JSONObject().put("type", "text").put("text", "Hello 👍🏽 👨‍👩‍👧‍👦 🇺🇸"))
                .put(JSONObject().put("type", "tool").put("secret", "must-not-save"))
            )
            .put("metadata", JSONObject().put("actionIds", JSONArray(listOf(actionId, "bad"))).put("token", "must-not-save"))
        val saved = requireNotNull(Mango9SupervisorPresentation.savedMessage(row))
        assertFalse(saved.toString().contains("must-not-save")); assertTrue(saved.getString("text").contains("👨‍👩‍👧‍👦"))
        assertEquals(listOf(actionId), Mango9SupervisorPresentation.actionIds(listOf(saved)))
        assertNull(Mango9SupervisorPresentation.savedMessage(row.put("role", "tool")))
    }

    @Test fun historyDatesUseTodayYesterdayWeekAndUsDate() {
        val now = Instant.parse("2026-10-31T18:00:00Z"); val zone = ZoneId.of("America/Los_Angeles")
        assertEquals("Today, 10:00 AM", Mango9SupervisorPresentation.date("2026-10-31T17:00:00Z", now, zone))
        assertEquals("Yesterday, 10:00 AM", Mango9SupervisorPresentation.date("2026-10-30T17:00:00Z", now, zone))
        assertEquals("Thu, 10:00 AM", Mango9SupervisorPresentation.date("2026-10-29T17:00:00Z", now, zone))
        assertEquals("10/20/2026, 10:00 AM", Mango9SupervisorPresentation.date("2026-10-20T17:00:00Z", now, zone))
        assertEquals("", Mango9SupervisorPresentation.date("bad", now, zone))
    }

    @Test fun historySearchAndVoiceFiltersStaySeparate() {
        val row = JSONObject().put("title", "Client follow-up").put("hasVoice", true)
        assertTrue(Mango9SupervisorPresentation.historyMatches(row, "FOLLOW", "all"))
        assertTrue(Mango9SupervisorPresentation.historyMatches(row, "", "voice"))
        assertFalse(Mango9SupervisorPresentation.historyMatches(row, "", "text"))
        assertFalse(Mango9SupervisorPresentation.historyMatches(row, "missing", "voice"))
    }

    @Test fun successProofRequiresSuccessAndSameOriginLinks() {
        val row = detail().put("state", "SUCCEEDED")
        val proof = JSONObject().put("version", 1).put("statusText", "Task created")
            .put("links", JSONArray().put(JSONObject().put("label", "Open task").put("href", "/app/tasks/42")))
            .put("recipients", JSONArray().put(JSONObject().put("name", "Alex").put("email", "alex@example.invalid")))
        row.getJSONObject("recap").put("creation", proof)
        val value = requireNotNull(Mango9SupervisorPresentation.creation(row, "https://crm.example.invalid"))
        assertEquals("Task created", value.status); assertEquals("https://crm.example.invalid/app/tasks/42", value.links.single().second)
        assertEquals("Alex · alex@example.invalid", value.recipients.single())
        assertNull(Mango9SupervisorPresentation.creation(row.put("state", "OUTCOME_UNKNOWN"), "https://crm.example.invalid"))
        row.put("state", "SUCCEEDED")
        for (href in listOf("https://foreign.invalid/app/tasks/42", "/app/api", "/app/%61pi/token", "/app/../api/token", "/app/tools/API/token", "/app/tools%2Fapi/token")) {
            proof.getJSONArray("links").getJSONObject(0).put("href", href)
            assertNull(Mango9SupervisorPresentation.creation(row, "https://crm.example.invalid"))
        }
    }

    @Test fun socialProfilesUseCanonicalHttpsAndNeverScopedIds() {
        assertEquals("https://www.instagram.com/test.user/", Mango9SocialProfileLink.url("instagram", "@test.user"))
        assertEquals("https://www.facebook.com/test.user/", Mango9SocialProfileLink.url("facebook", "@test.user"))
        assertEquals("https://www.tiktok.com/@test.user", Mango9SocialProfileLink.url("tiktok", "@test.user"))
        for (bad in listOf("Test User", "@../login", "@test?x=1", "@test/user", "@test\n", "@.user", "@user..name")) {
            for (platform in listOf("instagram", "facebook", "tiktok")) assertNull(Mango9SocialProfileLink.url(platform, bad))
        }
        assertNull(Mango9SocialProfileLink.url("facebook", "@123456789"))
        assertNull(Mango9SocialProfileLink.url("facebook", "@profile.php"))
        assertNull(Mango9SocialProfileLink.url("instagram", "@accounts"))
        assertNull(Mango9SocialProfileLink.url("unsupported", "@test.user"))
    }
}
