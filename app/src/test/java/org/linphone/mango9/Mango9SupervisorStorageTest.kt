// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Mango9SupervisorStorageTest {
    private val now = 1_900_000_000_000L
    private val chatId = "63000000-0000-4000-8000-000000000002"
    private val session = Mango9Session(
        "crm", "https://fixture.example.invalid", "https://fixture.example.invalid/app/api/v2",
        "42", "1", "client", "qa@example.invalid", "QA", "access", "refresh", "", "", 1_900_000_000, "sip:qa@fixture.example.invalid"
    ).copy(loginEpoch = "fixture-login")

    private fun chat() = JSONObject().put("id", chatId).put("savedAt", now).put(
        "messages",
        JSONArray().put(
        JSONObject().put("id", "message").put("role", "assistant").put("text", "Hello 👍🏽 👨‍👩‍👧‍👦 🇺🇸")
    )
    ).put("configurations", JSONArray())

    @Test fun fullAccountScopeIsRequiredAndTokenRefreshKeepsTheCache() {
        val same = Mango9SupervisorCache.scope(session)
        assertEquals(same, Mango9SupervisorCache.scope(session.copy(accessToken = "renewed", refreshToken = "renewed")))
        val changed =
            listOf(
                session.copy(crmId = "other"), session.copy(userId = "other"), session.copy(parentClientId = "other"),
            session.copy(role = "other"), session.copy(sipIdentity = "sip:other@fixture.example.invalid"),
            session.copy(crmApiBaseUrl = "https://other.example.invalid/app/api/v2"), session.copy(loginEpoch = "another-login")
            )
        changed.forEach { assertNotEquals(same, Mango9SupervisorCache.scope(it)) }
    }

    @Test fun cacheExpiresAfterThirtyDaysAndRejectsFutureAndLegacyData() {
        val encoded = Mango9SupervisorCache.encode(listOf(chat()), now)
        assertEquals(1, Mango9SupervisorCache.decode(encoded, now + Mango9SupervisorCache.MAX_AGE - 1).size)
        assertTrue(Mango9SupervisorCache.decode(encoded, now + Mango9SupervisorCache.MAX_AGE).isEmpty())
        assertTrue(Mango9SupervisorCache.decode(encoded, now - 60_001).isEmpty())
        assertTrue(Mango9SupervisorCache.decode(JSONArray(listOf(chat())).toString(), now).isEmpty())
        assertTrue(Mango9SupervisorCache.decode("{broken", now).isEmpty())
    }

    @Test fun snapshotsAreValidatedBoundedAndDeduplicated() {
        val rows = (1..25).map { chat().put("id", "63000000-0000-4000-8000-" + it.toString().padStart(12, '0')) }
        assertEquals(20, Mango9SupervisorCache.decode(Mango9SupervisorCache.encode(rows, now), now).size)
        assertEquals(1, Mango9SupervisorCache.bounded(listOf(chat(), chat()), now).size)
        assertTrue(Mango9SupervisorCache.bounded(listOf(chat().put("savedAt", 0)), now).isEmpty())
        assertTrue(Mango9SupervisorCache.bounded(listOf(chat().put("id", "bad")), now).isEmpty())
        assertTrue(Mango9SupervisorCache.bounded(listOf(chat().put("conversation", JSONObject().put("id", "wrong"))), now).isEmpty())
        assertTrue(Mango9SupervisorCache.bounded(listOf(chat().put("messages", JSONArray().put("not a message"))), now).isEmpty())
        val oversized = JSONObject().put("version", 2).put("padding", "x".repeat(Mango9SupervisorCache.MAX_BYTES)).put("chats", JSONArray(listOf(chat())))
        assertTrue(Mango9SupervisorCache.decode(oversized.toString(), now).isEmpty())
    }

    @Test fun unreadableOrInvalidPendingRequestsFailClosedButValidIdsAreUnchanged() {
        val request = Mango9SupervisorWire.command(chatId, "Reply 👍🏽 👨‍👩‍👧‍👦 🇺🇸")
        assertEquals(request.toString(), Mango9SupervisorWire.savedCommand(request.toString()).toString())
        val invalid = listOf(
            "{broken", "{}", JSONObject(request.toString()).put("requestId", "bad").toString(),
            JSONObject(request.toString()).put("conversationId", "").toString(),
            JSONObject(request.toString()).apply { getJSONObject("message").put("role", "assistant") }.toString(),
            JSONObject(request.toString()).apply {
                getJSONObject("message").getJSONArray("parts").getJSONObject(0).put("text", " ")
            }.toString()
        )
        invalid.forEach { raw ->
            val failure = runCatching { Mango9SupervisorWire.savedCommand(raw) }.exceptionOrNull()
            assertTrue(failure is Mango9SupervisorFailure)
            assertEquals("storage_unavailable", (failure as Mango9SupervisorFailure).code)
        }
    }

    @Test fun nativeTaskRoutesAreExactSameOriginAndPositiveIds() {
        val origin = session.crmApiBaseUrl
        assertEquals(42, Mango9SupervisorPresentation.nativeTaskId("https://fixture.example.invalid/app/tasks/42", origin))
        for (url in listOf(
            "https://foreign.example.invalid/app/tasks/42", "http://fixture.example.invalid/app/tasks/42",
            "https://fixture.example.invalid/app/tasks/0", "https://fixture.example.invalid/app/tasks/2147483648",
            "https://fixture.example.invalid/app/tasks/42/edit", "https://fixture.example.invalid/app/tasks/new",
            "https://fixture.example.invalid/app/tasks/42?owner=other", "https://fixture.example.invalid/app/tasks/%34%32",
            "https://fixture.example.invalid:444/app/tasks/42", "https://user@fixture.example.invalid/app/tasks/42"
        )) {
            assertNull(url, Mango9SupervisorPresentation.nativeTaskId(url, origin))
        }
    }
}
