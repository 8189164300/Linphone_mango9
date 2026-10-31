// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.ui.main.crm.viewmodel.Mango9SupervisorViewModel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real ViewModel and encrypted storage; delayed synthetic transport only. */
@RunWith(AndroidJUnit4::class)
class Mango9SupervisorSafetyDeviceTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context = instrument.targetContext
    private val actionId = "63000000-0000-4000-8000-000000000001"
    private val chatId = "63000000-0000-4000-8000-000000000002"

    private inner class Fixture {
        val session = Mango9Session("crm", "https://fixture.example.invalid", "https://fixture.example.invalid/app/api/v2", "42", "1", "client", "audit@example.invalid", "QA ONLY", "fixture-access", "fixture-refresh", "", "", 1_900_000_000, "sip:notes-audit@fixture.example.invalid").copy(loginEpoch = "notes-audit-fixture")
        val lease = Mango9SessionLease(session, 1, 1)

        @Volatile var active = true
        val sessions = object : Mango9SessionAccess {
            override fun captureSession() = lease

            override fun currentSession(lease: Mango9SessionLease) = session.takeIf { active && lease == this@Fixture.lease }

            override fun replaceTokens(lease: Mango9SessionLease, expected: Mango9Session, refreshed: Mango9Session) = false
        }
        val store = Mango9SupervisorStore(context)
        val history = CopyOnWriteArrayList<JSONObject>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val olderEntered = CountDownLatch(1)
        val olderRelease = CountDownLatch(1)
        val olderReturned = CountDownLatch(1)
        val afterEntered = CountDownLatch(1)
        val afterRelease = CountDownLatch(1)

        @Volatile var blockAfter = false

        @Volatile var blockOlder = false

        @Volatile var tailText = "Settings"

        @Volatile var blockRead = false

        @Volatile var state = "PENDING_APPROVAL"

        @Volatile var revokeOnSendRefresh = false
        lateinit var model: Mango9SupervisorViewModel
        lateinit var models: ViewModelStore

        fun configuration() = JSONObject().put("id", actionId).put("state", state).put("approvalDigest", "a".repeat(64))
            .put("recap", JSONObject().put("version", 1).put("title", "Task").put("fields", JSONArray().put(JSONObject().put("label", "Owner").put("value", "QA"))))

        val api = Mango9SupervisorApi(sessions, { error("No refresh expected") }, store::accepted) { request ->
            val buffer = okio.Buffer(); request.body?.writeTo(buffer)
            val body = buffer.readUtf8().let { if (it.isBlank()) JSONObject() else JSONObject(it) }
            val result = when {
                request.url.encodedPath.endsWith("/identity") -> JSONObject().put("enabled", true).put("ownerOnly", true).put("supervisorAuth", "mobile_owner").put("userId", "42").put("tenantId", "42")
                request.url.encodedPath.endsWith("/supervisor") -> JSONObject().put("enabled", true)
                request.url.encodedPath.endsWith("/chat") -> JSONObject().put("id", "sent-reply").put("role", "assistant").put("text", "Saved")
                request.url.encodedPath.endsWith("/decision") -> { state = "SUCCEEDED"; configuration() }
                request.url.encodedPath.endsWith("/actions/$actionId") -> {
                    val captured = configuration()
                    if (blockRead) {
                        blockRead = false; entered.countDown()
                        check(release.await(15, TimeUnit.SECONDS)); returned.countDown()
                    }
                    captured
                }
                body.optString("action") == "list" -> JSONObject().put("items", JSONArray())
                body.optString("action") == "read" -> {
                    history += JSONObject(body.toString())
                    if (body.has("after") && blockAfter) {
                        blockAfter = false; afterEntered.countDown(); check(afterRelease.await(15, TimeUnit.SECONDS))
                    }
                    if (revokeOnSendRefresh) active = false
                    if (body.has("before")) {
                        olderEntered.countDown()
                        if (blockOlder) check(olderRelease.await(15, TimeUnit.SECONDS))
                        olderReturned.countDown()
                        JSONObject().put("messages", JSONArray().put(JSONObject().put("id", "older-message").put("role", "assistant").put("text", "Older history"))).put("cursor", "0")
                    } else {
                    val message = JSONObject().put("id", "fixture-message").put("role", "assistant").put("text", tailText)
                        .put("metadata", JSONObject().put("actionIds", JSONArray(listOf(actionId))))
                    JSONObject().put("messages", if (body.optBoolean("latest")) JSONArray().put(message) else JSONArray()).put("cursor", "40").put("olderCursor", "10")
                    }
                }
                else -> error("Unexpected fixture request")
            }
            Mango9CalendarResponse(200, JSONObject().put("success", true).put("data", result).toString())
        }
    }

    private fun await(check: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 10000
        while (SystemClock.elapsedRealtime() < end) {
            var done = false; instrument.runOnMainSync { done = check() }
            if (done) return
            SystemClock.sleep(30)
        }
        fail("Fixture state did not settle")
    }

    private fun fixture(test: (Fixture) -> Unit) {
        assumeTrue("Isolated package only", context.packageName.endsWith(".debug"))
        val f = Fixture()
        f.store.clearDisplay(f.session); f.store.pending(f.session, null)
        f.store.consent(f.session, true); f.store.conversation(f.session, chatId)
        try {
            instrument.runOnMainSync {
                f.model = Mango9SupervisorViewModel(f.sessions, f.store, f.api)
                f.models = ViewModelStore().apply { put("audit", f.model) }
                f.model.bind(f.lease)
            }
            await { f.model.authorized && !f.model.loading && actionId in f.model.configurationDetails }
            test(f)
        } finally {
            f.release.countDown()
            f.olderRelease.countDown()
            f.afterRelease.countDown()
            instrument.runOnMainSync { f.models.clear() }
            f.store.clearDisplay(f.session); f.store.pending(f.session, null)
            f.store.consent(f.session, false); f.store.conversation(f.session, "")
        }
    }

    @Test fun baselineSettingsLoadAndCacheWithoutExecutableDigest() = fixture { f ->
        val saved = f.store.savedChats(f.session).single().getJSONArray("configurations").getJSONObject(0)
        assertTrue(saved.optBoolean("summaryOnly")); assertFalse(saved.has("approvalDigest"))
    }

    @Test fun olderSettingsResponseMustNotUndoSuccessfulApply() = fixture { f ->
        f.blockRead = true
        instrument.runOnMainSync { f.model.loadConfiguration(actionId, true) }
        assertTrue(f.entered.await(5, TimeUnit.SECONDS))
        instrument.runOnMainSync { f.model.decide("approve", f.model.configurationDetails.getValue(actionId)) }
        await { f.model.configurationDetails[actionId]?.optString("state") == "SUCCEEDED" && !f.model.actionBusy }
        f.release.countDown(); assertTrue(f.returned.await(5, TimeUnit.SECONDS))
        SystemClock.sleep(400); instrument.waitForIdleSync()
        instrument.runOnMainSync {
            assertEquals("A stale GET must not replace the saved successful receipt", "SUCCEEDED", f.model.configurationDetails[actionId]?.optString("state"))
        }
    }

    @Test fun resumeAtLatestMustRequestAuthoritativeLatestPage() = fixture { f ->
        val previous = f.history.size
        f.tailText = "Updated on server"
        instrument.runOnMainSync { f.model.background(); f.model.foreground() }
        await { f.history.size > previous }
        assertTrue("iOS refreshes the authoritative tail when resuming at the latest message", f.history.last().optBoolean("latest"))
        await { f.model.messages.single().optString("text") == "Updated on server" }
    }

    @Test fun cacheMustSeparateRoleAndTenantWithinSameLoginGeneration() = fixture { f ->
        val reused = mapOf("tenant" to f.session.copy(parentClientId = "other-tenant"), "role" to f.session.copy(role = "other-role"), "CRM" to f.session.copy(crmId = "other-crm"))
            .filterValues { f.store.savedChats(it).isNotEmpty() }.keys
        assertTrue("Cache scope fields omitted: $reused", reused.isEmpty())
    }

    @Test fun accountChangeDuringPostSendHistoryMustKeepPendingReceipt() = fixture { f ->
        f.revokeOnSendRefresh = true
        instrument.runOnMainSync { f.model.draft = "Synthetic audit request"; f.model.send() }
        await { !f.model.sending }
        assertNotNull("A different login scope must not acknowledge the original outbox", f.store.pending(f.session))
    }

    @Test fun malformedPendingReceiptMustNotLookLikeAnEmptyOutbox() = fixture { f ->
        val key = androidx.security.crypto.MasterKey.Builder(context, "mango9_supervisor_key").setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build()
        val prefs = androidx.security.crypto.EncryptedSharedPreferences.create(
            context, "mango9_supervisor_private", key,
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        assertTrue(prefs.edit().putString("pending." + Mango9SupervisorConsent.key(f.session), "{malformed").commit())
        val read = runCatching { f.store.pending(f.session) }
        assertTrue("Unreadable saved requests must block sends, not become null", read.isFailure)
        instrument.runOnMainSync { f.model.draft = "Must not send"; f.model.retry() }
        await { !f.model.loading && !f.model.authorized }
        assertFalse(f.model.canSend())
        f.store.pending(f.session, null)
        instrument.runOnMainSync { f.model.retry() }
        await { !f.model.loading && f.model.canSend() }
    }

    @Test fun oldPageFromBeforeBackgroundCannotReplaceFreshHistory() = fixture { f ->
        f.blockOlder = true
        instrument.runOnMainSync { f.model.viewModelScope.launch { f.model.loadOlder() } }
        assertTrue(f.olderEntered.await(5, TimeUnit.SECONDS))
        f.tailText = "Authoritative latest"
        instrument.runOnMainSync { f.model.background(); f.model.foreground(latest = true) }
        await { f.model.messages.single().optString("text") == "Authoritative latest" }
        f.olderRelease.countDown(); assertTrue(f.olderReturned.await(5, TimeUnit.SECONDS))
        SystemClock.sleep(200); instrument.waitForIdleSync()
        assertEquals(listOf("fixture-message"), f.model.messages.map { it.optString("id") })
    }

    @Test fun foregroundWhileReadingOlderHistoryPreservesThatWindow() = fixture { f ->
        instrument.runOnMainSync { f.model.viewModelScope.launch { f.model.loadOlder() } }
        await { !f.model.loadingOlder && f.model.messages.size == 2 }
        val ids = f.model.messages.map { it.optString("id") }; val count = f.history.size
        instrument.runOnMainSync { f.model.background(); f.model.foreground(latest = false) }
        await { f.history.size > count }
        assertFalse(f.history.last().optBoolean("latest"))
        assertEquals(ids, f.model.messages.map { it.optString("id") })
    }

    @Test fun latestRefreshIsCoalescedWithoutAcceptingTheEarlierIncrementalRead() = fixture { f ->
        f.blockAfter = true
        instrument.runOnMainSync { f.model.viewModelScope.launch { f.model.refresh() } }
        assertTrue(f.afterEntered.await(5, TimeUnit.SECONDS))
        f.tailText = "New latest window"
        instrument.runOnMainSync { f.model.foreground(latest = true) }
        f.afterRelease.countDown()
        await { f.model.messages.single().optString("text") == "New latest window" }
        assertTrue(f.history.last().optBoolean("latest"))
    }

    @Test fun backgroundClearsAnObsoleteQueuedJumpToLatest() = fixture { f ->
        f.blockAfter = true
        instrument.runOnMainSync { f.model.viewModelScope.launch { f.model.refresh() } }
        assertTrue(f.afterEntered.await(5, TimeUnit.SECONDS))
        instrument.runOnMainSync { f.model.foreground(latest = true); f.model.background(); f.model.foreground(latest = false) }
        await { f.history.size >= 3 }
        f.afterRelease.countDown()
        assertFalse(f.history.last().optBoolean("latest"))
    }
}
