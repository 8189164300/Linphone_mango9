// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Mango9ChatTemplatesTest {
    @Test fun insertionPreservesDraftAroundCursorAndEmoji() {
        assertEquals("Hi 👋 there", Mango9TemplateText.insert("Hi  there", "👋", 3))
        assertEquals("Hello", Mango9TemplateText.insert("", "Hello", 0))
        assertEquals("DraftTemplate", Mango9TemplateText.insert("Draft", "Template", 500))
    }
    @Test fun utf8LimitAndEmptyFieldsAreEnforcedSeparately() {
        assertTrue(Mango9TemplateText.valid("Title", "😀".repeat(2500), 255, 10000))
        assertFalse(Mango9TemplateText.valid("Title", "😀".repeat(2501), 255, 10000))
        assertFalse(Mango9TemplateText.valid(" ", "Text", 255, 10000))
        assertFalse(Mango9TemplateText.valid("Title", "\n", 255, 10000))
    }
    @Test fun templateRoutesUseCrmBearerAndKeepExactMutation() {
        val session = Mango9CalendarTestFixtures.session().copy(crmApiBaseUrl = "https://crm.example.invalid/app/api/v2/")
        val body = JSONObject().put("requestId", "62000000-0000-4000-8000-000000000001").put("revision", "a".repeat(64)).put("values", JSONObject().put("title", "Reply").put("content", "Hello 👋"))
        val request = Mango9TemplateApi.wire(session, "42", "PATCH", body = body)
        assertEquals("https://crm.example.invalid/app/api/v2/mobile/messaging/templates/42", request.url.toString())
        assertEquals("PATCH", request.method); assertEquals("Bearer test-access", request.header("Authorization"))
        val actual = okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        assertEquals(body.toString(), actual); assertFalse(JSONObject(actual).has("user_id"))
        assertThrows(IllegalArgumentException::class.java) { Mango9TemplateApi.wire(session, "../other") }
    }
    @Test fun uncertainSaveIsNotAutomaticallyRepeated() = runBlocking {
        val sessions = Mango9CalendarTestFixtures.Sessions(); var attempts = 0
        val api = Mango9TemplateApi(sessions, { error("No refresh expected") }) { attempts++; throw IOException("Connection interrupted") }
        try { api.request(sessions.captureSession()!!, method = "POST", body = JSONObject().put("requestId", "fixture")); fail("Expected network failure") } catch (_: IOException) { }
        assertEquals(1, attempts)
    }
    @Test fun serverPermissionErrorsStayDistinctFromEmptyLibrary() = runBlocking {
        val sessions = Mango9CalendarTestFixtures.Sessions()
        val api = Mango9TemplateApi(sessions, { error("No refresh expected") }) { Mango9CalendarResponse(403, """{"success":false,"error":{"code":"templates_access_denied"}}""") }
        try { api.request(sessions.captureSession()!!); fail("Expected denial") } catch (e: Mango9TemplateFailure) { assertEquals(403, e.status); assertEquals("templates_access_denied", e.code) }
    }
}
