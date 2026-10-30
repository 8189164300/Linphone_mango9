// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Mango9SupervisorTest {
    @Test fun selectedServersKeepBearerAndVoiceOnTheirOwnOrigin() {
        for (host in listOf("2.mango9.com", "us.mango9.com")) {
            val session = Mango9CalendarTestFixtures.session().copy(crmApiBaseUrl = "https://$host/app/api/v2/")
            val request = Mango9SupervisorWire.request(session, "chat", JSONObject().put("requestId", "fixture"))
            assertEquals("https://$host/app/api/v2/assistant/chat", request.url.toString())
            assertEquals("Bearer test-access", request.header("Authorization"))
            assertEquals("wss://$host/app/command-center/voice-stream", Mango9SupervisorWire.voiceUrl(session, "/app/command-center/voice-stream"))
            assertThrows(IllegalArgumentException::class.java) {
                Mango9SupervisorWire.voiceUrl(session, "wss://foreign.invalid/voice")
            }
            assertThrows(IllegalArgumentException::class.java) { Mango9SupervisorWire.request(session, "../auth") }
        }
    }

    @Test fun permissionIsVersionedAndSeparatedByAccountAndServer() {
        assertFalse(Mango9SupervisorConsent.valid(null))
        assertTrue(Mango9SupervisorConsent.valid(Mango9SupervisorConsent.receipt(1000), 1001))
        assertFalse(Mango9SupervisorConsent.valid(Mango9SupervisorConsent.receipt(1000), 999))
        assertFalse(Mango9SupervisorConsent.valid("broken"))
        assertFalse(Mango9SupervisorConsent.valid(JSONObject(Mango9SupervisorConsent.receipt()).put("version", "old").toString()))
        val session = Mango9CalendarTestFixtures.session()
        assertNotEquals(Mango9SupervisorConsent.key(session), Mango9SupervisorConsent.key(session.copy(userId = "43")))
        assertNotEquals(Mango9SupervisorConsent.key(session), Mango9SupervisorConsent.key(session.copy(crmApiBaseUrl = "https://other.example.invalid/app/api/v2")))
    }

    @Test fun noAiRequestsOrRefreshBeforeConsent() = runBlocking {
        val sessions = Mango9CalendarTestFixtures.Sessions(); var requests = 0; var refreshes = 0
        val api =
            Mango9SupervisorApi(sessions, { refreshes++; it }, { false }) { requests++; error("Must not access network") }
        for ((path, body) in listOf("chat" to JSONObject(), "voice/ticket" to JSONObject(), "actions/62000000-0000-4000-8000-000000000001/decision" to JSONObject().put("decision", "approve"))) {
            try {
                api.request(sessions.captureSession()!!, path, body); fail("Must require consent")
            } catch (e: Mango9SupervisorFailure) { assertEquals("ai_consent_required", e.code) }
        }
        try {
            api.ticket(sessions.captureSession()!!, "fixture"); fail("Must require consent")
        } catch (e: Mango9SupervisorFailure) { assertEquals("ai_consent_required", e.code) }
        assertEquals(0, requests); assertEquals(0, refreshes)
        assertFalse(Mango9SupervisorConsent.required("history", JSONObject().put("action", "read")))
        assertFalse(Mango9SupervisorConsent.required("actions/fixture/decision", JSONObject().put("decision", "reject")))
    }

    @Test fun aLostChatResponseIsNeverAutomaticallyResent() = runBlocking {
        val sessions = Mango9CalendarTestFixtures.Sessions(); var requests = 0
        val api =
            Mango9SupervisorApi(sessions, {
                error("No refresh expected")
            }, { true }) { requests++; throw IOException("lost response") }
        try {
            api.request(sessions.captureSession()!!, "chat", Mango9SupervisorWire.command("fixture", "Hi")); fail("Expected network error")
        } catch (_: IOException) { }
        assertEquals(1, requests)
    }

    @Test fun refreshKeepsCommandIdentityButRechecksConsent() = runBlocking {
        val sessions = Mango9CalendarTestFixtures.Sessions(); var allowed = true; var requests = 0
        val api =
            Mango9SupervisorApi(sessions, {
                allowed = false; it.copy(accessToken = "renewed", refreshToken = "renewed-refresh")
            }, { allowed }) {
            requests++; Mango9CalendarResponse(401, "{}")
        }
        try {
            api.request(sessions.captureSession()!!, "chat", Mango9SupervisorWire.command("fixture", "Hi")); fail("Must recheck consent")
        } catch (e: Mango9SupervisorFailure) { assertEquals("ai_consent_required", e.code) }
        assertEquals(1, requests)
    }

    @Test fun ownerMustMatchBothTenantAndUser() {
        val session = Mango9CalendarTestFixtures.session()
        val identity = JSONObject().put("enabled", true).put("ownerOnly", true).put("supervisorAuth", "mobile_owner").put("userId", 42).put("tenantId", 42)
        assertTrue(Mango9SupervisorWire.owner(identity, session))
        identity.put("tenantId", 43); assertFalse(Mango9SupervisorWire.owner(identity, session))
        identity.put("tenantId", 42).put("supervisorAuth", "browser_owner"); assertFalse(Mango9SupervisorWire.owner(identity, session))
    }

    @Test fun changedSelectionDiscardsLateResult() = runBlocking {
        val sessions = Mango9CalendarTestFixtures.Sessions()
        val api = Mango9SupervisorApi(sessions, { error("No refresh expected") }, { true }) {
            sessions.awayAndBack(); Mango9CalendarResponse(200, Mango9CalendarTestFixtures.envelope("{\"items\":[]}"))
        }
        try {
            api.request(sessions.captureSession()!!, "history", JSONObject().put("action", "list")); fail("Must reject stale account")
        } catch (e: Mango9ApiException) { assertEquals(Mango9ApiException.Unauthorized, e) }
    }

    @Test fun voiceTicketReturnsExactRefreshedBearer() = runBlocking {
        val sessions = Mango9CalendarTestFixtures.Sessions(); var sentBearer = ""
        val api =
            Mango9SupervisorApi(sessions, { it.copy(accessToken = "voice-access", refreshToken = "voice-refresh") }, { true }) {
            sentBearer = it.header("Authorization").orEmpty(); Mango9CalendarResponse(200, Mango9CalendarTestFixtures.envelope("{\"ticket\":\"fixture-ticket\"}"))
        }
        val result = api.ticket(sessions.captureSession()!!, "62000000-0000-4000-8000-000000000001")
        assertEquals("Bearer voice-access", sentBearer); assertEquals("voice-access", result.second.accessToken); assertEquals(1, sessions.writes)
    }

    @Test fun urlsBecomeFullAndOnlySafeWebTargetsAreClickable() {
        assertEquals("View task\nhttps://us.mango9.com/app/tasks?id=42", Mango9SupervisorWire.fullLinks("[View task](/app/tasks?id=42)", "https://us.mango9.com"))
        for (bad in listOf("javascript:alert(1)", "//foreign.invalid/app", "file:///tmp/file", "https://name:password@crm.example.invalid/app/tasks", "/app/api/v2/tasks", "/app/../api/tasks")) assertNull(Mango9SupervisorWire.webUrl(bad, "https://crm.example.invalid"))
    }

    @Test fun commandRoundTripRetainsIdsAndUtf8Limits() {
        val row = Mango9SupervisorWire.command("62000000-0000-4000-8000-000000000001", "Hello 👋")
        val restored = JSONObject(row.toString())
        assertEquals(row.getString("requestId"), restored.getString("requestId"))
        assertEquals(row.getJSONObject("message").getString("id"), restored.getJSONObject("message").getString("id"))
        assertTrue(Mango9SupervisorWire.validText("😀".repeat(2000))); assertFalse(Mango9SupervisorWire.validText("😀".repeat(2001))); assertFalse(Mango9SupervisorWire.validText("  "))
        assertFalse(restored.has("tenantId")); assertFalse(restored.has("userId"))
    }
}
