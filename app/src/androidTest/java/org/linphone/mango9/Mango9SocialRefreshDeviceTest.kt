// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.linphone.ui.main.crm.viewmodel.Mango9SocialViewModel

/** Deterministic API fixtures on the device; never calls a provider or a customer conversation. */
class Mango9SocialRefreshDeviceTest {
    private val runner = InstrumentationRegistry.getInstrumentation()
    private val context = runner.targetContext
    private val id = "75000000-0000-4000-8000-000000000001"
    private val other = "75000000-0000-4000-8000-000000000002"

    private fun row(value: String = id) = JSONObject().put("id", value).put("version", 1)
        .put("sourceId", "fixture").put("platform", "instagram").put("connectionCurrent", true)
        .put("canReply", true).put("activityAt", "2026-10-31T12:00:00Z")

    private fun ok(value: JSONObject) = Mango9CalendarResponse(200, JSONObject().put("success", true).put("data", value).toString())

    private fun page(items: JSONArray = JSONArray(), next: String? = null) =
        ok(JSONObject().put("items", items).put("next", next ?: JSONObject.NULL))

    private fun fallback(request: okhttp3.Request): Mango9CalendarResponse {
        check(request.url.host == "fixture.example.invalid")
        return when (request.url.pathSegments.last()) {
            "accounts", "inbox", "inbox-messages" -> page()
            "inbox-detail" -> ok(row(request.url.queryParameter("id") ?: id))
            "inbox-refresh-messages" -> ok(JSONObject())
            "inbox-conversation-visibility", "inbox-message-visibility" -> ok(
                JSONObject().put("scope", "user")
                .put("channel", "social").put("state", "succeeded").put("hiddenIds", JSONArray()).put("hiddenConversationIds", JSONArray())
            )
            else -> error("Unexpected fixture path: ${request.url.encodedPath}")
        }
    }

    private fun main(block: () -> Unit) = runner.runOnMainSync(block)

    private fun await(check: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 7000
        while (SystemClock.elapsedRealtime() < end) {
            var result = false; main { result = check() }
            if (result) return
            SystemClock.sleep(20)
        }
        fail("Fixture did not settle")
    }

    private data class Fixture(val model: Mango9SocialViewModel, val lease: Mango9SessionLease, val completed: AtomicInteger)

    private fun fixture(clock: AtomicLong = AtomicLong(1000), transport: (okhttp3.Request) -> Mango9CalendarResponse = ::fallback, block: (Fixture) -> Unit) {
        assumeTrue("Synthetic session requires isolated debug package", context.packageName.endsWith(".debug"))
        val sessions = Mango9SessionStore(context) {}
        val original = sessions.activeIdentity
        val identity = "sip:fixture-${UUID.randomUUID()}@fixture.example.invalid"
        val session = Mango9Session(
            "fixture", "https://fixture.example.invalid", "https://fixture.example.invalid/api",
            "fixture", "fixture", "agent", "fixture@example.invalid", "Fixture", "fixture-access", "fixture-refresh",
            "https://fixture.example.invalid/chat", "wss://fixture.example.invalid/socket", 1_900_000_000, identity
        )
        val preferences = context.getSharedPreferences("social-refresh-fixture", 0)
        var models: ViewModelStore? = null
        try {
            sessions.save(session, persist = false, makeActive = true)
            val lease = requireNotNull(sessions.captureSession())
            val api =
                Mango9MessagingApi(
                    sessions, { error("Unexpected renewal") }, { error("Unexpected chat token") },
                    Mango9CalendarTransport {
                check(it.url.host == "fixture.example.invalid"); transport(it)
            }
                )
            val completed = AtomicInteger()
            lateinit var model: Mango9SocialViewModel
            main {
                model = Mango9SocialViewModel(
                    Mango9SocialRepository(context, api), Mango9MessageCommands(preferences, api),
                    { _, _, _ -> completed.incrementAndGet(); false }, clock::get
                )
                models = ViewModelStore().apply { put("fixture", model) }
                model.bind(lease)
            }
            block(Fixture(model, lease, completed))
        } finally {
            models?.let { main { it.clear() } }
            sessions.remove(identity); sessions.activate(original)
            preferences.edit().clear().commit()
        }
    }

    @Test fun queuedRefreshSurvivesFailedReadAndCoalescesStrongestRequest() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val reads = AtomicInteger(); val providers = AtomicInteger()
        try {
            fixture(transport = {
                when (it.url.pathSegments.last()) {
                    "inbox-detail" -> {
                        if (reads.incrementAndGet() == 1) {
                            entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); throw IOException("Synthetic read failure")
                        }
                        fallback(it)
                    }
                    "inbox-refresh-messages" -> { providers.incrementAndGet(); fallback(it) }
                    else -> fallback(it)
                }
            }) { f ->
                try {
                    main { f.model.state.value = f.model.current().copy(thread = Mango9SocialThread.parse(row())); f.model.open(id) }
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    main { f.model.open(id, refreshProvider = true); f.model.open(id); f.model.poll(id) }
                    release.countDown(); await { f.completed.get() == 1 }
                    assertEquals(3, reads.get()) // failed read, queued saved read, post-provider read
                    assertEquals(1, providers.get())
                } finally { release.countDown() }
            }
        } finally { release.countDown() }
    }

    @Test fun savedMessagesAppearBeforeSlowProviderAndWarningSurvivesSavedOnlyPoll() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val provider = AtomicInteger(); val clock = AtomicLong(1000)
        try {
            fixture(clock, transport = {
                when (it.url.pathSegments.last()) {
                    "inbox-messages" -> page(JSONArray().put(JSONObject().put("id", 1).put("text", "Saved fixture").put("createdAt", "2026-10-31T12:00:00Z")))
                    "inbox-refresh-messages" -> {
                        if (provider.incrementAndGet() == 1) {
                            entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); throw IOException("Synthetic provider failure")
                        }
                        fallback(it)
                    }
                    else -> fallback(it)
                }
            }) { f ->
                try {
                    main { f.model.poll(id) }; assertTrue(entered.await(5, TimeUnit.SECONDS))
                    main { assertEquals("Saved fixture", f.model.current().messages.single().text); assertFalse(f.model.current().loading) }
                    release.countDown(); await { f.completed.get() == 1 && f.model.current().providerWarning != null }
                    clock.set(16_000); main { f.model.poll(id) }; await { f.completed.get() == 2 }
                    main { assertNotNull(f.model.current().providerWarning) }; assertEquals(1, provider.get())
                    clock.set(61_000); main { f.model.poll(id) }; await { f.completed.get() == 3 }
                    main { assertNull(f.model.current().providerWarning) }; assertEquals(2, provider.get())
                } finally { release.countDown() }
            }
        } finally { release.countDown() }
    }

    @Test fun manualRetryIsNotSuppressedByPeriodicThrottle() {
        val refreshes = AtomicInteger()
        fixture(transport = {
            if (it.url.pathSegments.last() == "inbox-refresh-messages") refreshes.incrementAndGet(); fallback(it)
        }) { f ->
            main { f.model.poll(id) }; await { f.completed.get() == 1 }
            main { f.model.open(id, refreshProvider = true) }; await { f.completed.get() == 2 }
            assertEquals(2, refreshes.get())
        }
    }

    @Test fun accountInventoryRefreshesOnRequestAndExpiryAndClearsRemovedSelection() {
        val accounts = AtomicInteger(); val clock = AtomicLong(1000)
        fixture(clock, transport = {
            if (it.url.pathSegments.last() == "accounts") {
                val index = accounts.incrementAndGet()
                page(if (index == 1) JSONArray().put(JSONObject().put("id", "old").put("platform", "instagram").put("name", "Old")) else JSONArray())
            } else {
                fallback(it)
            }
        }) { f ->
            main { f.model.inbox(forceAccounts = false) }; await { !f.model.current().loading }
            main { f.model.filters("instagram", "old") }; await { !f.model.current().loading }
            assertEquals(1, accounts.get())
            main { f.model.inbox() }; await { !f.model.current().loading }
            main { assertEquals("", f.model.current().account); assertTrue(f.model.current().accounts.isEmpty()) }
            assertEquals(2, accounts.get())
            clock.set(301_000); main { f.model.inbox(forceAccounts = false) }; await { !f.model.current().loading }
            assertEquals(3, accounts.get())
        }
    }

    @Test fun inventoryFailureKeepsKnownOptionsWithVisibleWarning() {
        fixture(transport = {
            if (it.url.pathSegments.last() == "accounts") throw IOException("Fixture offline") else fallback(it)
        }) { f ->
            main {
                f.model.state.value = f.model.current().copy(accounts = listOf(Mango9SocialAccount("known", "instagram", "Known")), account = "known")
                f.model.inbox()
            }
            await { !f.model.current().loading }
            main { assertEquals("known", f.model.current().account); assertNotNull(f.model.current().accountWarning) }
        }
    }

    @Test fun searchIsDebouncedUnicodeSafeAndCarriesPlatformAccountButNotOldCursor() {
        val queries = java.util.concurrent.CopyOnWriteArrayList<okhttp3.HttpUrl>()
        fixture(transport = {
            if (it.url.pathSegments.last() == "inbox") queries.add(it.url)
            fallback(it)
        }) { f ->
            main {
                f.model.state.value = f.model.current().copy(accounts = listOf(Mango9SocialAccount("one", "instagram", "One")), account = "one", platform = "instagram", nextThread = "stale")
                f.model.search("first"); f.model.search("  Հայերեն 👍🏽  ")
            }
            await { !f.model.current().loading && queries.size == 1 }
            // Inventory is authoritative; this fixture has no connected accounts, so stale selection is removed.
            assertEquals("Հայերեն 👍🏽", queries.single().queryParameter("search"))
            assertEquals("instagram", queries.single().queryParameter("platform"))
            assertNull(queries.single().queryParameter("before"))
            assertNull(queries.single().queryParameter("accountId"))
        }
    }

    @Test fun connectedAccountAndPlatformStayAttachedToSearchRequests() {
        val queries = java.util.concurrent.CopyOnWriteArrayList<okhttp3.HttpUrl>()
        fixture(transport = {
            if (it.url.pathSegments.last() == "accounts") {
                page(JSONArray().put(JSONObject().put("id", "one").put("platform", "instagram").put("name", "Connected")))
            } else {
                if (it.url.pathSegments.last() == "inbox") queries.add(it.url)
                fallback(it)
            }
        }) { f ->
            main { f.model.filters("instagram", "one") }; await { !f.model.current().loading }
            queries.clear()
            main { f.model.search("matched") }; await { !f.model.current().loading && queries.size == 1 }
            assertEquals("one", queries.single().queryParameter("accountId"))
            assertEquals("instagram", queries.single().queryParameter("platform"))
            assertEquals("matched", queries.single().queryParameter("search"))
            assertNull(queries.single().queryParameter("before"))
        }
    }

    @Test fun freshPageReplacesRowsAndPaginationCanResumeWithoutCursorLoop() {
        val calls = AtomicInteger()
        fixture(transport = {
            if (it.url.pathSegments.last() == "inbox") {
                calls.incrementAndGet()
                if (it.url.queryParameter("before") == "next") {
                    page(JSONArray().put(row(other)), "next")
                } else {
                    page(JSONArray().put(row()), "next")
                }
            } else {
                fallback(it)
            }
        }) { f ->
            main { f.model.inbox() }; await { !f.model.current().loading }
            main { f.model.inbox(more = true) }; await { !f.model.current().loading }
            main { assertEquals(2, f.model.current().threads.size); assertNull(f.model.current().nextThread) }
            main { f.model.inbox() }; await { !f.model.current().loading }
            main {
                assertEquals(listOf(id), f.model.current().threads.map { it.id }); assertEquals("next", f.model.current().nextThread)
            }
            assertEquals(3, calls.get())
        }
    }

    @Test fun queuedOlderPageAndHeadRefreshBothCompleteWithOlderAttachments() {
        val attachments = java.util.concurrent.CopyOnWriteArrayList<String>()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        try {
            fixture(transport = {
            when (it.url.pathSegments.last()) {
                "inbox-refresh-messages" -> {
                    entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); fallback(it)
                }
                "inbox-messages" -> {
                    if (it.url.queryParameter("before") == "older") {
                        page(
                            JSONArray().put(
                                JSONObject().put("id", 1).put("text", "Older image")
                            .put("createdAt", "2026-10-30T12:00:00Z")
                            .put("attachments", JSONArray().put(JSONObject().put("index", 0).put("type", "image").put("name", "fixture.png")))
                            )
                        )
                    } else {
                        page(
                            JSONArray().apply {
                                for (index in 100..149) put(
                                    JSONObject().put("id", index)
                            .put("text", "Recent fixture").put("createdAt", "2026-10-31T12:00:00Z")
                                )
                            },
                                "older"
                        )
                    }
                }
                "inbox-attachment" -> {
                    attachments.add(requireNotNull(it.url.queryParameter("messageId")))
                    ok(JSONObject().put("url", "https://fixture.example.invalid/image.png"))
                }
                else -> fallback(it)
            }
        }) { f ->
            main { f.model.poll(id) }; assertTrue(entered.await(5, TimeUnit.SECONDS))
            main { f.model.open(id, more = true); f.model.open(id) }
            release.countDown()
            await { f.model.current().media.containsKey("1:0") && f.completed.get() == 3 }
            main { assertEquals(51, f.model.current().messages.size); assertNull(f.model.current().nextMessage) }
            assertEquals(listOf("1"), attachments.toList())
        }
        } finally { release.countDown() }
    }

    @Test fun revokedProviderClearsPrivateStateAndQueuedReads() {
        val refreshes = AtomicInteger()
        fixture(transport = {
            if (it.url.pathSegments.last() == "inbox-refresh-messages") {
                refreshes.incrementAndGet()
                Mango9CalendarResponse(403, JSONObject().put("success", false).put("error", "posting_session_required").toString())
            } else {
                fallback(it)
            }
        }) { f ->
            main { f.model.retainDraft("Private fixture"); f.model.poll(id) }
            await { f.model.current().error != null && !f.model.current().loading }
            main {
                assertNull(f.model.current().thread); assertTrue(f.model.current().messages.isEmpty()); assertNull(f.model.current().providerWarning)
            }
            assertEquals(0, f.completed.get()); assertEquals(1, refreshes.get())
        }
    }

    @Test fun accountSwitchCancelsQueuedRefreshAndPreventsLatePublication() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val reads = AtomicInteger()
        try {
            fixture(transport = {
                if (it.url.pathSegments.last() == "inbox-detail") {
                    reads.incrementAndGet(); entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
                }
                fallback(it)
            }) { f ->
                try {
                    main { f.model.open(id) }; assertTrue(entered.await(5, TimeUnit.SECONDS))
                    main { f.model.open(id, refreshProvider = true); f.model.bind(null) }
                    release.countDown()
                    SystemClock.sleep(200)
                    main {
                        assertNull(f.model.current().thread); assertTrue(f.model.current().messages.isEmpty()); assertNull(f.model.activeLease())
                    }
                    assertEquals(1, reads.get())
                } finally { release.countDown() }
            }
        } finally { release.countDown() }
    }
}
