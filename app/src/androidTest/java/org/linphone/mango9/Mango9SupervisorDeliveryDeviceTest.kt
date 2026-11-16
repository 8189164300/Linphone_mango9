// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.ui.main.crm.viewmodel.Mango9SupervisorViewModel
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Native model only, with fixture transport. Never sends to an AI service or alters a real account. */
@RunWith(AndroidJUnit4::class)
class Mango9SupervisorDeliveryDeviceTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()

    private fun main(action: () -> Unit) = instrument.runOnMainSync(action)

    private fun waitFor(check: () -> Boolean) {
        val until = System.currentTimeMillis() + 15000
        while (!check()) { if (System.currentTimeMillis() > until) fail("Timed out waiting for fixture"); Thread.sleep(25) }
    }

    @Test fun independentSendsAndReceiptRecovery() {
        val id = UUID.randomUUID().toString()
        val session = Mango9Session("fixture-$id", "https://fixture.example.invalid", "https://fixture.example.invalid/app/api/v2", "42", "42", "client", "qa@example.invalid", "Fixture", "fixture-access", "fixture-refresh", "", "", 1_900_000_000, "sip:$id@fixture.example.invalid").copy(loginEpoch = id)
        val lease = Mango9SessionLease(session, 1, 1)
        val sessions = object : Mango9SessionAccess {
            override fun captureSession() = lease

            override fun currentSession(lease: Mango9SessionLease) = session.takeIf { lease == objectLease }

            private val objectLease = lease

            override fun replaceTokens(lease: Mango9SessionLease, expected: Mango9Session, refreshed: Mango9Session) = false
        }
        val store = Mango9SupervisorStore(instrument.targetContext)
        val posts = CopyOnWriteArrayList<JSONObject>()
        val first = CompletableDeferred<Unit>()
        var acknowledge = false
        var historyOffline = false
        val api = Mango9SupervisorApi(sessions, { error("No refresh") }, store::accepted) { request ->
            val body = request.body?.let { okio.Buffer().also(it::writeTo).readUtf8().let(::JSONObject) } ?: JSONObject()
            if (request.url.pathSegments.last() == "history" && body.optString("action") == "read" && historyOffline) throw IOException("Fixture history disconnected")
            val data = when (request.url.pathSegments.last()) {
                "identity" -> JSONObject().put("enabled", true).put("ownerOnly", true).put("supervisorAuth", "mobile_owner").put("userId", "42").put("tenantId", "42")
                "supervisor" -> JSONObject().put("enabled", true)
                "history" -> if (body.optString("action") == "list") {
                    JSONObject().put("items", JSONArray())
                } else {
                    JSONObject().put("id", body.optString("conversationId")).put(
                        "messages",
                        JSONArray(
                            if (acknowledge) {
                        posts.map {
                    it.getJSONObject("message")
                }
                    } else {
                        emptyList<JSONObject>()
                    }
                        )
                    ).put("pending", true)
                }
                "transport" -> JSONObject().put("version", 1).put("enabled", false).put("conversationId", body.optString("conversationId")).put("phase", "waiting")
                "notifications" -> JSONObject().put("enabled", true).put("unread", 0).put("conversations", JSONObject()).put("readThrough", "0")
                "chat" -> {
                    posts.add(body)
                    if (posts.size == 1) {
                        first.await(); JSONObject().put("id", body.getString("requestId")).put("role", "assistant").put("parts", JSONArray().put(JSONObject().put("type", "text").put("text", "Done")))
                    } else {
                        throw IOException("Fixture disconnected")
                    }
                }
                else -> error("Unexpected fixture path")
            }
            Mango9CalendarResponse(200, JSONObject().put("success", true).put("data", data).toString())
        }
        lateinit var model: Mango9SupervisorViewModel
        val models = ViewModelStore()
        store.consent(session, true)
        try {
            main { model = Mango9SupervisorViewModel(sessions, store, api); models.put("fixture", model); model.bind(lease) }
            waitFor { var ready = false; main { ready = model.authorized && !model.loading }; ready }
            historyOffline = true
            main { model.foreground() }
            waitFor { var failed = false; main { failed = model.historyRefreshError != null }; failed }
            main {
                assertNull(model.error); assertTrue(model.authorized); model.draft = "Usable offline"; assertTrue(model.canSend())
            }
            historyOffline = false
            main { model.foreground() }
            waitFor { var recovered = false; main { recovered = model.historyRefreshError == null }; recovered }
            main { model.draft = "First"; assertTrue(model.canSend()); model.send() }
            waitFor { posts.size == 1 }
            main { model.draft = "Second"; assertTrue(model.canSend()); model.send() }
            waitFor { posts.size == 2 }
            waitFor {
                var done = false; main {
                    done = store.deliveries(session).size == 2 && model.delivery(posts[1].getJSONObject("message").getString("id")) == "Delivery unconfirmed"
                }; done
            }
            main { model.draft = "Third draft"; assertTrue(model.canSend()) }
            first.complete(Unit)
            waitFor { store.deliveries(session).size == 1 }
            main { assertEquals("Third draft", model.draft); assertTrue(model.canSend()); assertEquals(2, posts.size) }
            acknowledge = true
            main { model.foreground() }
            waitFor { store.deliveries(session).isEmpty() }
            main { assertTrue(model.canSend()); assertNull(model.pending); assertEquals(2, posts.size) }
            val legacy = Mango9SupervisorWire.command(UUID.randomUUID().toString(), "Earlier uncertain message")
            main { models.clear() }; store.pending(session, legacy)
            main { model = Mango9SupervisorViewModel(sessions, store, api); models.put("restored", model); model.bind(lease) }
            waitFor { var ready = false; main { ready = model.authorized && !model.loading }; ready }
            main {
                assertEquals(1, store.deliveries(session).size); assertNull(store.pending(session))
                model.draft = "New after reconnect"; assertTrue(model.canSend()); assertEquals(2, posts.size)
                model.newConversation()
            }
            waitFor { var ready = false; main { ready = !model.loading }; ready }
            main { assertNull(model.pending); assertEquals(1, store.deliveries(session).size); assertEquals(2, posts.size) }
        } finally {
            first.complete(Unit)
            main { models.clear() }
            store.deliveries(session, emptyList()); store.pending(session, null); store.consent(session, false); store.clearDisplay(session)
        }
    }
}
