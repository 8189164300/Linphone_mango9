// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import okio.Buffer

class Mango9LiveChatTest {
    private val fixture = Mango9CalendarTestFixtures
    @Test fun requestsStayOnProvisionedApiAndNeverPutCredentialsInQuery() {
        val session = fixture.session().copy(crmApiBaseUrl = "https://crm.example.invalid/app/api/v2/")
        val request = Mango9LiveChatApi.wire(session, "list", mapOf("search" to "Taylor & team", "siteId" to "website"), null)
        assertEquals("/app/api/v2/mobile/livechat/list", request.url.encodedPath)
        assertEquals("Taylor & team", request.url.queryParameter("search"))
        assertEquals("Bearer test-access", request.header("Authorization"))
        assertFalse(request.url.toString().contains("test-access"))
        for (operation in listOf("reaction", "visibility", "send")) {
            val body = JSONObject().put("id", "conversation").put("requestId", "unchanged-command")
            val wire = Mango9LiveChatApi.wire(session, operation, emptyMap(), body)
            assertEquals("POST", wire.method)
            assertEquals("unchanged-command", JSONObject(Buffer().also { wire.body!!.writeTo(it) }.readUtf8()).getString("requestId"))
        }
        assertThrows(IllegalArgumentException::class.java) { Mango9LiveChatApi.wire(session, "send", emptyMap(), null) }
        assertThrows(IllegalArgumentException::class.java) { Mango9LiveChatApi.wire(session, "../accounts", emptyMap(), null) }
        assertThrows(Mango9LiveChatFailure::class.java) { Mango9LiveChatApi.wire(session.copy(crmApiBaseUrl = "http://crm.example.invalid"), "sites", emptyMap(), null) }
    }
    @Test fun aConfirmedUnauthorizedResponseRefreshesOnceAndKeepsTheWriteRequest() = runBlocking {
        val sessions = Mango9CalendarTestFixtures.Sessions(); var calls = 0; var refreshes = 0
        val commands = mutableListOf<String>()
        val api = Mango9LiveChatApi(sessions, { refreshes++; it.copy(accessToken = "renewed") }) { request ->
            calls++; commands += Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            if (calls == 1) Mango9CalendarResponse(401, "{}") else {
                assertEquals("Bearer renewed", request.header("Authorization"))
                Mango9CalendarResponse(200, """{"success":true,"data":{"accepted":true}}""")
            }
        }
        val command = JSONObject().put("id", "conversation").put("requestId", "original-command").put("text", "Fixture")
        assertTrue(api.request(sessions.captureSession()!!, "send", body = command).getBoolean("accepted"))
        assertEquals(2, calls); assertEquals(1, refreshes); assertEquals(commands[0], commands[1])
    }
    @Test fun uncertainOrForbiddenWritesAreNeverSilentlyRetried() = runBlocking {
        for (status in listOf(403, 409, 413, 500)) {
            val sessions = Mango9CalendarTestFixtures.Sessions(); var calls = 0
            val api = Mango9LiveChatApi(sessions, { error("Must not refresh") }) { calls++; Mango9CalendarResponse(status, "{}") }
            val error = runCatching { api.request(sessions.captureSession()!!, "send", body = JSONObject()) }.exceptionOrNull()
            assertEquals(status, (error as Mango9LiveChatFailure).status); assertEquals(1, calls)
        }
        val sessions = Mango9CalendarTestFixtures.Sessions(); var calls = 0
        val api = Mango9LiveChatApi(sessions, { error("Must not refresh") }) { calls++; throw IOException("Fixture connection loss") }
        assertTrue(runCatching { api.request(sessions.captureSession()!!, "send", body = JSONObject()) }.exceptionOrNull() is IOException)
        assertEquals(1, calls)
    }
    @Test fun switchingAwayAndBackRejectsLateHistoryFromTheOldLogin() = runBlocking {
        val sessions = Mango9CalendarTestFixtures.Sessions(); val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val api = Mango9LiveChatApi(sessions, { error("Must not refresh") }) {
            entered.complete(Unit); finish.await(); Mango9CalendarResponse(200, """{"success":true,"data":{"messages":[]}}""")
        }
        val lease = sessions.captureSession()!!
        val pending = async { runCatching { api.request(lease, "messages", mapOf("id" to "conversation")) } }
        entered.await(); sessions.awayAndBack(); finish.complete(Unit)
        assertTrue(pending.await().exceptionOrNull() is Mango9ApiException.Unauthorized)
    }
    @Test fun selectionKeepsVisitorContextButNeverInventsMissingMetadata() {
        val raw = JSONObject("""{"id":1,"direction":"incoming","content":"Hello","sender":{"name":"Visitor"},"content_attributes":{"visitorContext":{"country":{"flag":"🇺🇸","city":"Los Angeles","name":"United States"},"entryPage":"https://example.invalid/contact","timeZone":"America/Los_Angeles"}},"hiddenForMe":true}""")
        val message = Mango9LiveMessage.parse(raw)
        assertTrue(message.hidden); assertTrue(message.selectedText.contains("Approximate location")); assertTrue(message.selectedText.contains("Time zone: America/Los_Angeles"))
        assertFalse(Mango9LiveVisitorContext.compact(message.context).contains("IP"))
        assertTrue(Mango9LiveVisitorContext.rows(JSONObject()).isEmpty())
        assertFalse(message.reactions.supported)
    }
    @Test fun reactionCapabilitiesRejectMalformedSnapshots() {
        val valid = JSONObject("""{"version":5,"supported":true,"allowed":["👍","❤️"],"items":[{"emoji":"👍","count":1,"mine":true}]}""")
        assertEquals(5, Mango9LiveReactions.parse(valid).version)
        assertTrue(Mango9LiveReactions.parse(valid).items.single().mine)
        valid.getJSONArray("items").put(JSONObject("""{"emoji":"👍","count":2,"mine":false}"""))
        assertFalse(Mango9LiveReactions.parse(valid).supported)
        assertFalse(Mango9LiveReactions.parse(JSONObject().put("supported", true)).supported)
    }
    @Test fun staffVoiceCapabilityIsIndependentOfVisitorUploadSetting() {
        val site = JSONObject().put("settings", JSONObject().put("attachmentsEnabled", false)).put("capabilities", JSONObject().put("attachments", JSONArray(listOf("audio/mp4"))))
        assertEquals(listOf("audio/mp4"), Mango9LivePolicy.attachments(site))
        val payload = Mango9LivePolicy.payload(byteArrayOf(1, 2, 3), "../voice.m4a", "audio/mp4", listOf("audio/mp4"))
        assertEquals("voice.m4a", payload.getString("name")); assertEquals("AQID", payload.getString("base64"))
        assertThrows(IllegalArgumentException::class.java) { Mango9LivePolicy.payload(ByteArray(Mango9LivePolicy.MAX_BYTES + 1), "large.m4a", "audio/mp4", listOf("audio/mp4")) }
        assertThrows(IllegalArgumentException::class.java) { Mango9LivePolicy.payload(byteArrayOf(1), "clip.mp4", "video/mp4", listOf("audio/mp4")) }
    }
    @Test fun signedAttachmentLinksCannotEscapeTheProvisionedChatServer() {
        val session = fixture.session().copy(smsChatApi = "https://chat.example.invalid/sms_chat_api/")
        fun file(url: String) = JSONObject().put("data_url", url).put("name", "photo.jpg").put("mime", "image/jpeg")
        assertEquals(Mango9ChatMedia.Kind.Image, Mango9LivePolicy.media(file("/sms_chat_api/livechat/file/id?signature=fixture"), session).kind)
        for (url in listOf("https://other.example.invalid/sms_chat_api/livechat/file/id", "http://chat.example.invalid/sms_chat_api/livechat/file/id", "https://user:pass@chat.example.invalid/sms_chat_api/livechat/file/id", "/api/admin", "/sms_chat_api/livechat/file/../../admin")) {
            assertThrows(IllegalArgumentException::class.java) { Mango9LivePolicy.media(file(url), session) }
        }
    }
}
