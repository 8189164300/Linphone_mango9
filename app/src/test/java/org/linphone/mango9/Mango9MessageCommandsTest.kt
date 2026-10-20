// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.content.SharedPreferences
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import okhttp3.Request
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Mango9MessageCommandsTest {
    private fun body(request: Request) = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())

    private fun response(json: JSONObject) = Mango9CalendarResponse(200, JSONObject().put("success", true).put("data", json).toString())

    private fun api(sessions: Mango9CalendarTestFixtures.Sessions, transport: suspend (Request) -> Mango9CalendarResponse) =
        Mango9MessagingApi(sessions, {
            error("Unexpected auth refresh")
        }, { error("Social must not request a chat bearer") }, Mango9CalendarTransport { transport(it) })

    @Test fun unknownReactionRetainsExactIntentAcrossRestartAndRejectsANewTap() = runBlocking {
        val sessions = Mango9CalendarTestFixtures.Sessions()
        val preferences = MemoryPreferences()
        val calls = mutableListOf<JSONObject>()
        var fail = true
        val api = api(sessions) { request ->
            val input = body(request); calls += input
            if (fail) throw IOException("Response lost")
            response(JSONObject().put("id", "fixture-thread").put("messageId", 7).put("action", "react").put("state", "succeeded"))
        }
        val lease = sessions.captureSession()!!
        val original = Mango9MessageCommands(preferences, api)
        assertTrue(
            runCatching {
            original.react(lease, Mango9MessageChannel.social, "fixture-thread", "7", 4, "👍🏽", false)
        }.isFailure
        )
        val restarted = Mango9MessageCommands(preferences, api)
        assertTrue(restarted.hasPending(lease, Mango9MessageChannel.social, "fixture-thread", "7"))
        assertFalse(restarted.hasPending(lease, Mango9MessageChannel.team, "fixture-thread", "7"))
        assertFalse(restarted.hasPending(lease, Mango9MessageChannel.social, "other-thread", "7"))
        assertFalse(restarted.hasPending(lease, Mango9MessageChannel.social, "fixture-thread", "8"))
        val blocked = runCatching {
            restarted.react(lease, Mango9MessageChannel.social, "fixture-thread", "7", 5, "❤️", false)
        }.exceptionOrNull()
        assertEquals("pending", (blocked as Mango9MessagingFailure).code); assertEquals(1, calls.size)
        fail = false
        restarted.react(lease, Mango9MessageChannel.social, "fixture-thread", "7", 999, "not sent", true, checkSaved = true)
        assertEquals(calls[0].toString(), calls[1].toString())
        assertEquals(0, preferences.all.size)
        assertFalse(restarted.hasPending(lease, Mango9MessageChannel.social, "fixture-thread", "7"))
    }

    @Test fun confirmedFailureReleasesIntentButUnknownNeverDoes() = runBlocking {
        val sessions = Mango9CalendarTestFixtures.Sessions(); val prefs = MemoryPreferences()
        var state = "unknown"
        val calls = mutableListOf<JSONObject>()
        val commands = Mango9MessageCommands(
            prefs,
            api(sessions) { request ->
            calls += body(request)
            response(JSONObject().put("id", "fixture-thread").put("messageId", 7).put("action", "react").put("state", state))
        }
        )
        val lease = sessions.captureSession()!!
        assertEquals("unknown", commands.react(lease, Mango9MessageChannel.social, "fixture-thread", "7", 4, "👨‍👩‍👧‍👦", false).getString("state"))
        assertTrue(commands.hasPending(lease, Mango9MessageChannel.social, "fixture-thread", "7"))
        state = "failed"
        commands.react(lease, Mango9MessageChannel.social, "fixture-thread", "7", 99, "different", true, true)
        assertEquals(calls[0].toString(), calls[1].toString())
        assertFalse(commands.hasPending(lease, Mango9MessageChannel.social, "fixture-thread", "7"))
    }

    @Test fun rapidRepeatedTapCannotReplaceAnInFlightUnknownCommand() = runBlocking {
        val sessions = Mango9CalendarTestFixtures.Sessions(); val prefs = MemoryPreferences()
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var calls = 0
        val commands = Mango9MessageCommands(
            prefs,
            api(sessions) {
            calls++; started.complete(Unit); release.await()
            response(JSONObject().put("id", "fixture-thread").put("messageId", 7).put("action", "react").put("state", "unknown"))
        }
        )
        val lease = sessions.captureSession()!!
        val first = async { commands.react(lease, Mango9MessageChannel.social, "fixture-thread", "7", 4, "👍", false) }
        started.await()
        val second =
            async { runCatching { commands.react(lease, Mango9MessageChannel.social, "fixture-thread", "7", 4, "❤️", false) } }
        release.complete(Unit); first.await()
        assertEquals("pending", (second.await().exceptionOrNull() as Mango9MessagingFailure).code)
        assertEquals(1, calls)
    }

    @Test fun staffReactionRetryUsesOnlyReactionRouteNeverMessageSend() = runBlocking {
        for (channel in listOf(Mango9MessageChannel.team, Mango9MessageChannel.sms)) {
            val sessions = Mango9CalendarTestFixtures.Sessions(); val prefs = MemoryPreferences()
            sessions.session = sessions.session!!.copy(smsChatApi = "https://messages.example.invalid/api")
            val requests = mutableListOf<Request>()
            val api = Mango9MessagingApi(
                sessions, { error("Unexpected refresh") },
                { Mango9ChatBootstrap("wss://messages.example.invalid", "fixture-bearer", 900, 7, "websocket") },
                Mango9CalendarTransport { request ->
                    requests += request
                    if (requests.size == 1) throw IOException("Lost response")
                    response(
                        JSONObject().put("channel", channel.name).put("messageId", "fixture-message").put("state", "succeeded")
                        .put("version", 5).put("reactions", JSONObject().put("👍🏽", org.json.JSONArray(listOf(7))))
                    )
                }
            )
            val commands = Mango9MessageCommands(prefs, api); val lease = sessions.captureSession()!!
            runCatching { commands.react(lease, channel, "fixture-conversation", "fixture-message", 4, "👍🏽", false) }
            commands.react(lease, channel, "fixture-conversation", "fixture-message", 999, "ignored", true, true)
            assertEquals(2, requests.size)
            assertEquals(body(requests[0]).toString(), body(requests[1]).toString())
            requests.forEach { assertTrue(it.url.encodedPath.endsWith("/reactions/${channel.name}/fixture-message")) }
            assertEquals(if (channel == Mango9MessageChannel.sms) "workspace" else "conversation", body(requests[0]).getString("scope"))
            assertTrue(prefs.all.isEmpty())
        }
    }

    @Test fun staleVersionRejectsWithoutAutomaticRetryAndAllowsAFreshExplicitCommand() = runBlocking {
        val sessions = Mango9CalendarTestFixtures.Sessions(); val prefs = MemoryPreferences()
        val calls = mutableListOf<JSONObject>()
        val commands = Mango9MessageCommands(
            prefs,
            api(sessions) { request ->
            calls += body(request)
            if (calls.size == 1) {
                Mango9CalendarResponse(409, """{"error":"version_changed"}""")
            } else {
                response(JSONObject().put("id", "fixture-thread").put("messageId", 7).put("action", "react").put("state", "succeeded"))
            }
        }
        )
        val lease = sessions.captureSession()!!
        assertTrue(
            runCatching {
            commands.react(lease, Mango9MessageChannel.social, "fixture-thread", "7", 4, "🇦🇲", false)
        }.isFailure
        )
        assertEquals(1, calls.size)
        assertFalse(commands.hasPending(lease, Mango9MessageChannel.social, "fixture-thread", "7"))
        commands.react(lease, Mango9MessageChannel.social, "fixture-thread", "7", 5, "🇦🇲", false)
        assertEquals(5, calls[1].getInt("version"))
        assertNotEquals(calls[0].getString("requestId"), calls[1].getString("requestId"))
    }

    @Test fun unknownReceiptAndWrongMessageCannotDiscardThePendingCommand() = runBlocking {
        for (state in listOf("unknown", "pending", "succeeded")) {
            val sessions = Mango9CalendarTestFixtures.Sessions(); val prefs = MemoryPreferences()
            val commands = Mango9MessageCommands(
                prefs,
                api(sessions) {
                response(JSONObject().put("id", "fixture-thread").put("messageId", if (state == "succeeded") 99 else 7).put("action", "unreact").put("state", state))
            }
            )
            runCatching {
                commands.react(sessions.captureSession()!!, Mango9MessageChannel.social, "fixture-thread", "7", 4, "👍", true)
            }
            assertEquals(1, prefs.all.size)
        }
    }

    @Test fun personalDeletionCannotAcceptSharedScopeOrAnUnrequestedMessage() = runBlocking {
        for (payload in listOf(
            """{"scope":"workspace","channel":"social","state":"succeeded","hiddenIds":["7"]}""",
            """{"scope":"user","channel":"social","state":"succeeded","hiddenIds":["99"]}""",
            """{"scope":"user","channel":"team","state":"succeeded","hiddenIds":["7"]}"""
        )) {
            val sessions = Mango9CalendarTestFixtures.Sessions()
            val commands = Mango9MessageCommands(MemoryPreferences(), api(sessions) { response(JSONObject(payload)) })
            assertTrue(
                runCatching {
                commands.visibility(sessions.captureSession()!!, Mango9MessageChannel.social, "fixture-thread", listOf("7"), true)
            }.isFailure
            )
        }
    }

    @Test fun restoredSocialVisibilityDoesNotRetainOldHiddenIds() = runBlocking {
        val sessions = Mango9CalendarTestFixtures.Sessions()
        var hidden = "[\"7\"]"
        val commands = Mango9MessageCommands(
            MemoryPreferences(),
            api(sessions) { request ->
            val input = body(request)
            assertFalse(input.getBoolean("hide"))
            assertEquals("fixture-thread", input.getString("id"))
            response(JSONObject("""{"scope":"user","channel":"social","state":"succeeded","hiddenIds":$hidden}"""))
        }
        )
        val lease = sessions.captureSession()!!
        assertEquals(setOf("7"), commands.visibility(lease, Mango9MessageChannel.social, "fixture-thread", listOf("7"), false))
        hidden = "[]"
        assertTrue(commands.visibility(lease, Mango9MessageChannel.social, "fixture-thread", listOf("7"), false).isEmpty())
    }

    @Test fun conversationDeletionReplaysTheSameSnapshotReceiptAfterLostResponse() = runBlocking {
        val sessions = Mango9CalendarTestFixtures.Sessions(); val prefs = MemoryPreferences()
        val bodies = mutableListOf<JSONObject>(); var failure = true
        val endpoint = api(sessions) { request ->
            val input = body(request); bodies += input
            if (failure) throw IOException("Lost response")
            response(
                JSONObject().put("scope", "user").put("channel", "social").put("state", "succeeded")
                .put("requestId", input.getString("requestId")).put("conversationIds", input.getJSONArray("conversationIds"))
                .put("clearedConversationIds", input.getJSONArray("conversationIds")).put("hiddenConversationIds", input.getJSONArray("conversationIds"))
            )
        }
        runCatching {
            Mango9MessageCommands(prefs, endpoint).visibility(sessions.captureSession()!!, Mango9MessageChannel.social, null, listOf("fixture-thread"), true, true)
        }
        failure = false
        Mango9MessageCommands(prefs, endpoint).visibility(sessions.captureSession()!!, Mango9MessageChannel.social, null, listOf("fixture-thread"), true, true)
        assertEquals(bodies[0].toString(), bodies[1].toString()); assertTrue(prefs.all.isEmpty())
    }

    @Test fun expiredSelectionCannotPerformACommandOrUseAnOwnerCookie() = runBlocking {
        val sessions = Mango9CalendarTestFixtures.Sessions(); var calls = 0
        val api = api(sessions) { calls++; error("No network after account change") }
        val lease = sessions.captureSession()!!
        sessions.identity = "sip:other@example.invalid"
        assertTrue(runCatching { api.request(lease, Mango9MessageChannel.social, listOf("inbox")) }.isFailure)
        assertEquals(0, calls)
    }

    private class MemoryPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): Map<String, *> = values.toMap()

        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = error("unused")

        override fun getInt(key: String?, defValue: Int) = values[key] as? Int ?: defValue

        override fun getLong(key: String?, defValue: Long) = values[key] as? Long ?: defValue

        override fun getFloat(key: String?, defValue: Float) = values[key] as? Float ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean) = values[key] as? Boolean ?: defValue

        override fun contains(key: String?) = values.containsKey(key)

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>(); private var clearing = false

            override fun putString(key: String?, value: String?) = apply { pending[requireNotNull(key)] = value }

            override fun putStringSet(key: String?, values: MutableSet<String>?) = error("unused")

            override fun putInt(key: String?, value: Int) = apply { pending[requireNotNull(key)] = value }

            override fun putLong(key: String?, value: Long) = apply { pending[requireNotNull(key)] = value }

            override fun putFloat(key: String?, value: Float) = apply { pending[requireNotNull(key)] = value }

            override fun putBoolean(key: String?, value: Boolean) = apply { pending[requireNotNull(key)] = value }

            override fun remove(key: String?) = apply { pending[requireNotNull(key)] = null }

            override fun clear() = apply { clearing = true }

            override fun commit(): Boolean {
                if (clearing) values.clear(); pending.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }; return true
            }

            override fun apply() { commit() }
        }
    }
}
