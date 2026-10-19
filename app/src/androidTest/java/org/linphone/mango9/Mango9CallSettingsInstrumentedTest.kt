/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.Account
import org.linphone.core.Core
import org.linphone.core.Factory
import org.linphone.ui.main.settings.viewmodel.Mango9CallSettingsViewModel
import org.linphone.ui.main.viewmodel.DrawerMenuViewModel

/** Actual ViewModels, lifecycle stores and encrypted sessions; no CRM writes or SIP registrations. */
@RunWith(AndroidJUnit4::class)
class Mango9CallSettingsInstrumentedTest {
    @org.junit.After fun persistSdkFixtureCleanup() = Mango9SdkTestCleanup.flush()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val sessions = Mango9SessionStore(context)
    private val lines = Mango9LineIdentityStore(context)
    private val a = session("settings-a")
    private val b = session("settings-b")

    @Test fun provisioningLineDecoderSupportsBothServerKeysWithoutDependingOnSipSecrets() {
        assertEquals(
            Mango9LineIdentity("700", "2025550199"),
            Mango9LineIdentity.fromProvisioning(
            JSONObject().put("username", "700").put("active_number", "2025550199"),
        )
        )
        assertEquals(
            Mango9LineIdentity("701", "2025550188"),
            Mango9LineIdentity.fromProvisioning(
            JSONObject().put("username", "701").put("activeNumber", "2025550188"),
        )
        )
        assertEquals(Mango9LineIdentity("702", null), Mango9LineIdentity.fromProvisioning(JSONObject().put("username", "702")))
        assertTrue(
            runCatching {
            Mango9LineIdentity.fromProvisioning(JSONObject())
        }.exceptionOrNull() is Mango9ApiException.InvalidResponse
        )
    }

    @Test fun settingsCannotSaveBeforeLoadingAndCachedResumeRemainsEditable() = fixture { f ->
        main { f.model.save(); assertFalse(f.model.canSave.value == true); f.model.resume() }
        val read = f.read(0)
        main { f.model.save(); f.model.updateForwardingDestination("should-not-edit") }
        assertTrue(f.backend.saves.isEmpty())
        finish(read, setting("100"))
        main {
            assertTrue(f.model.canSave.value == true)
            f.model.updateForwardingDestination("+1 202 555 0100")
            f.model.pause()
            assertFalse(f.model.canSave.value == true)
            f.model.resume()
            assertTrue(f.model.canSave.value == true)
            assertEquals("+1 202 555 0100", f.model.forwardingDestination.value)
        }
        assertEquals(1, f.backend.reads.size)
    }

    @Test fun switchingAccountsStartsNewLoadWithoutWaitingForOldReply() = fixture { f ->
        main { f.model.resume() }; val old = f.read(0)
        main { sessions.activate(b.sipIdentity) }
        val current = f.read(1)
        assertEquals(a.sipIdentity, old.lease.session.sipIdentity)
        assertEquals(b.sipIdentity, current.lease.session.sipIdentity)
        finish(old, setting("old"))
        main {
            assertTrue(f.model.loading.value == true)
            assertNull(f.model.settings.value)
            assertEquals("", f.model.lineLabel.value)
            assertNull(f.model.errorMessage.value)
        }
        assertNull(lines.load(a.sipIdentity))
        finish(current, setting("200"))
        main { assertEquals("200", f.model.settings.value?.extension); assertFalse(f.model.loading.value == true) }
        assertEquals("200", lines.load(b.sipIdentity)?.extensionNumber)
    }

    @Test fun sameAddressReplacementRejectsLateSaveAndRetainsNewSpinner() = fixture { f ->
        f.load()
        main { f.model.updateForwardingDestination("2025550111"); f.model.requestForwardingEnabled(true); f.model.save() }
        val oldSave = f.save(0)
        main { sessions.save(a.copy(accessToken = "replacement-access"), persist = false, makeActive = true) }
        val replacement = f.read(1)
        assertNotEquals(oldSave.lease.loginGeneration, replacement.lease.loginGeneration)
        finish(oldSave, setting("stale", true, "2025550111"))
        main {
            assertTrue(f.model.loading.value == true)
            assertNull(f.model.settings.value)
            assertNull(f.model.statusMessage.value)
        }
        assertEquals("100", lines.load(a.sipIdentity)?.extensionNumber)
        finish(replacement, setting("101"))
        assertEquals("101", lines.load(a.sipIdentity)?.extensionNumber)
    }

    @Test fun switchingAwayAndBackStillRejectsOriginalRequest() = fixture { f ->
        main { f.model.resume() }; val old = f.read(0)
        main { sessions.activate(b.sipIdentity) }; val intermediate = f.read(1)
        main { sessions.activate(a.sipIdentity) }; val fresh = f.read(2)
        assertEquals(b.sipIdentity, intermediate.lease.session.sipIdentity)
        assertEquals(a.sipIdentity, fresh.lease.session.sipIdentity)
        assertNotEquals(old.lease.selectionGeneration, fresh.lease.selectionGeneration)
        finish(old, setting("stale"))
        finish(intermediate, setting("stale-b"))
        main { assertTrue(f.model.loading.value == true); assertNull(f.model.settings.value) }
        finish(fresh, setting("current"))
        assertEquals("current", lines.load(a.sipIdentity)?.extensionNumber)
        assertNull(lines.load(b.sipIdentity))
    }

    @Test fun tokenRefreshKeepsSameLoginAndDoesNotDiscardItsSettings() = fixture { f ->
        main { f.model.resume() }; val read = f.read(0)
        main {
            assertTrue(
                sessions.replaceTokens(
                    read.lease, read.lease.session,
                    read.lease.session.copy(
                accessToken = "renewed-access", refreshToken = "renewed-refresh",
            )
                )
            )
        }
        finish(read, setting("renewed"))
        assertEquals(1, f.backend.reads.size)
        main { assertEquals("renewed", f.model.settings.value?.extension); assertTrue(f.model.canSave.value == true) }
    }

    @Test fun saveCapturesDraftPreventsDuplicateAndDoesNotAllowRefreshToOverwriteIt() = fixture { f ->
        f.load()
        main {
            f.model.updateForwardingDestination("  +1 (202) 555-0123  ")
            assertTrue(f.model.requestForwardingEnabled(true))
            f.model.save(); f.model.save(); f.model.reload(force = true)
            f.model.updateForwardingDestination("2025550999")
            assertFalse(f.model.requestForwardingEnabled(false))
        }
        val save = f.save(0)
        assertEquals(true, save.enabled); assertEquals("+1 (202) 555-0123", save.destination)
        assertEquals(1, f.backend.saves.size); assertEquals(1, f.backend.reads.size)
        finish(save, setting("100", true, "+12025550123"))
        main {
            assertTrue(f.model.forwardingEnabled.value == true)
            assertEquals("+12025550123", f.model.forwardingDestination.value)
            assertTrue(f.model.statusMessage.value.orEmpty().contains("enabled successfully"))
            assertTrue(f.model.canSave.value == true)
        }
    }

    @Test fun saveFailureRestoresServerStateAndAllowsRetry() = fixture { f ->
        f.load()
        main { f.model.updateForwardingDestination("2025550123"); f.model.requestForwardingEnabled(true); f.model.save() }
        fail(f.save(0), Mango9ApiException.Network)
        main {
            assertFalse(f.model.saving.value == true)
            assertFalse(f.model.forwardingEnabled.value == true)
            assertEquals("", f.model.forwardingDestination.value)
            assertEquals(Mango9ApiException.Network.userMessage, f.model.errorMessage.value)
            assertTrue(f.model.canSave.value == true)
            f.model.updateForwardingDestination("2025550144")
            f.model.requestForwardingEnabled(true)
            f.model.save()
        }
        finish(f.save(1), setting("100", true, "2025550144"))
        main { assertNull(f.model.errorMessage.value); assertTrue(f.model.forwardingEnabled.value == true) }
    }

    @Test fun leavingDuringSaveDoesNotAutoRetryOrPublishItsLateResult() = fixture { f ->
        f.load()
        main { f.model.updateForwardingDestination("2025550123"); f.model.requestForwardingEnabled(true); f.model.save() }
        val save = f.save(0)
        main { f.model.pause() }
        finish(save, setting("late", true, "2025550123"))
        assertEquals("100", lines.load(a.sipIdentity)?.extensionNumber)
        main { assertNull(f.model.statusMessage.value); f.model.resume() }
        val reload = f.read(1)
        assertEquals(1, f.backend.saves.size)
        finish(reload, setting("100", true, "2025550123"))
        main { assertTrue(f.model.forwardingEnabled.value == true); assertNull(f.model.statusMessage.value) }
    }

    @Test fun removingSelectedLoginClearsFormAndStaleErrors() = fixture { f ->
        f.load()
        main { f.model.reload(force = true) }; val old = f.read(1)
        main { sessions.remove(a.sipIdentity!!); sessions.activate(null) }
        await { var cleared = false; main { cleared = f.model.hasSession.value == false }; cleared }
        fail(old, Mango9ApiException.Network)
        main {
            assertNull(f.model.settings.value)
            assertEquals("", f.model.forwardingDestination.value)
            assertEquals("", f.model.lineLabel.value)
            assertNull(f.model.errorMessage.value)
            assertFalse(f.model.loading.value == true)
            assertFalse(f.model.canSave.value == true)
            f.model.save()
        }
        assertTrue(f.backend.saves.isEmpty())
    }

    @Test fun forcedRefreshOwnsItsResultAndOldFailureCannotReplaceNewState() = fixture { f ->
        main { f.model.resume() }; val old = f.read(0)
        main { f.model.reload(force = true) }; val fresh = f.read(1)
        finish(fresh, setting("fresh"))
        fail(old, Mango9ApiException.Network)
        main {
            assertEquals("fresh", f.model.settings.value?.extension)
            assertNull(f.model.errorMessage.value)
            assertFalse(f.model.loading.value == true)
        }
    }

    @Test fun labelRefreshCoalescesAndDoesNotOverwriteANewerSettingsSave() = fixture { f ->
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val refresh = Mango9LineIdentityRefresh(sessions, lines, scope, f.backend::loadLine)
        try {
            main { refresh.refresh(); refresh.refresh() }; val read = f.read(0)
            assertEquals(1, f.backend.reads.size)
            main { lines.save(Mango9LineIdentity("saved", "2025550188"), a.sipIdentity) }
            finish(read, setting("stale"))
            assertEquals("saved", lines.load(a.sipIdentity)?.extensionNumber)
            main { refresh.refresh() }; finish(f.read(1), setting("fresh"))
            assertEquals("fresh", lines.load(a.sipIdentity)?.extensionNumber)
        } finally { main { refresh.cancel(); scope.cancel() } }
    }

    @Test fun labelRefreshRejectsReplacementAndBackgroundReplies() = fixture { f ->
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val refresh = Mango9LineIdentityRefresh(sessions, lines, scope, f.backend::loadLine)
        try {
            main { refresh.refresh() }; val aRead = f.read(0)
            main { sessions.activate(b.sipIdentity); refresh.refresh() }; val bRead = f.read(1)
            finish(aRead, setting("old-a"))
            assertNull(lines.load(a.sipIdentity))
            main { refresh.cancel() }; finish(bRead, setting("late-b"))
            assertNull(lines.load(b.sipIdentity))
            main { refresh.refresh() }; val final = f.read(2)
            main { sessions.save(b.copy(accessToken = "new-login"), persist = false) }
            finish(final, setting("old-login"))
            assertNull(lines.load(b.sipIdentity))
        } finally { main { refresh.cancel(); scope.cancel() } }
    }

    @Test fun accountMenuUpdatesLineLabelsAndRemovedModelsCannotSelectAnAccount() {
        val store = ViewModelStore()
        val beforeIdentity = sessions.activeIdentity
        var beforeDefault: Account? = null
        val added = mutableListOf<Account>()
        lateinit var menu: DrawerMenuViewModel
        try {
            core { core ->
                beforeDefault = core.defaultAccount
                for (name in listOf("menu-a", "menu-b")) {
                    val params = core.createAccountParams().apply {
                        identityAddress = Factory.instance().createAddress("sip:$name@fixture.example.invalid")
                        serverAddress = Factory.instance().createAddress("sip:fixture.example.invalid")
                        isRegisterEnabled = false; pushNotificationAllowed = false
                    }
                    added += core.createAccount(params).also { core.addAccount(it) }
                }
                core.defaultAccount = added.last()
            }
            main { menu = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[DrawerMenuViewModel::class.java] }
            await { var ready = false; main { ready = menu.accounts.value.orEmpty().any { it.account === added.first() } }; ready }
            val identity = "sip:menu-a@fixture.example.invalid"
            main { lines.save(Mango9LineIdentity("700", "2025550199"), identity) }
            await {
                var label: String? = null
                main { label = menu.accounts.value.orEmpty().find { it.identity == identity }?.lineIdentityLabel?.value }
                label == "202-555-0199 · Ext 700"
            }
            val version = Mango9LineIdentityStore.changes.value
            main { lines.save(Mango9LineIdentity("700", "2025550199"), identity) }
            assertEquals(version, Mango9LineIdentityStore.changes.value)
            var old: org.linphone.ui.main.model.AccountModel? = null
            main { old = menu.accounts.value.orEmpty().first { it.account === added.first() } }
            core { it.removeAccount(added.first()) }
            await {
                var removed = false; main { removed = menu.accounts.value.orEmpty().none { it.account === added.first() } }; removed
            }
            main { old!!.setAsDefault() }
            core { assertSame(added.last(), it.defaultAccount) }
            main { lines.clear(identity) }
        } finally {
            main { store.clear() }
            core { core ->
                core.defaultAccount = beforeDefault
                added.forEach { account -> if (core.accountList.any { it === account }) core.removeAccount(account) }
                lines.clear("sip:menu-a@fixture.example.invalid")
                sessions.activate(beforeIdentity)
            }
        }
    }

    private data class Request(
        val lease: Mango9SessionLease, val job: Job, val enabled: Boolean? = null, val destination: String? = null,
        val response: CompletableDeferred<Mango9CallSettings> = CompletableDeferred(),
    )

    private class Backend {
        val reads = CopyOnWriteArrayList<Request>()
        val saves = CopyOnWriteArrayList<Request>()

        suspend fun load(lease: Mango9SessionLease): Mango9CallSettings = respond(Request(lease, currentCoroutineContext().job), reads)

        suspend fun loadLine(lease: Mango9SessionLease): Mango9LineIdentity = load(lease).let {
            Mango9LineIdentity(it.extension, it.activeNumber)
        }

        suspend fun save(lease: Mango9SessionLease, enabled: Boolean, destination: String): Mango9CallSettings =
            respond(Request(lease, currentCoroutineContext().job, enabled, destination), saves)

        private suspend fun respond(request: Request, into: MutableList<Request>): Mango9CallSettings {
            into.add(request)
            // Simulate a server/transport completion that arrives even after cancellation.
            return withContext(NonCancellable) { request.response.await() }
        }
    }

    private inner class Fixture(val model: Mango9CallSettingsViewModel, val backend: Backend) {
        fun read(index: Int): Request { await { backend.reads.size > index }; return backend.reads[index] }

        fun save(index: Int): Request { await { backend.saves.size > index }; return backend.saves[index] }

        fun load() { main { model.resume() }; finish(read(0), setting("100")) }
    }

    private fun fixture(test: (Fixture) -> Unit) {
        val old = sessions.activeIdentity
        val backend = Backend(); val store = ViewModelStore()
        lateinit var model: Mango9CallSettingsViewModel
        try {
            main {
                sessions.save(a, persist = false, makeActive = true); sessions.save(b, persist = false)
                model = ViewModelProvider(
                    store,
                    object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        @Suppress("UNCHECKED_CAST")
                        return Mango9CallSettingsViewModel(sessions, lines, backend::load, backend::save) as T
                    }
                }
                )[Mango9CallSettingsViewModel::class.java]
            }
            test(Fixture(model, backend))
        } finally {
            main { store.clear() }
            (backend.reads + backend.saves).forEach {
                it.response.completeExceptionally(CancellationException("Fixture cleanup"))
                join(it.job)
            }
            main { sessions.remove(a.sipIdentity!!); sessions.remove(b.sipIdentity!!); sessions.activate(old) }
        }
    }

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun finish(request: Request, settings: Mango9CallSettings) {
        request.response.complete(settings); join(request.job)
    }

    private fun fail(request: Request, error: Exception) {
        request.response.completeExceptionally(error); join(request.job)
    }

    private fun join(job: Job) = runBlocking { withTimeout(10_000) { job.join() } }

    private fun await(check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) { if (check()) return; SystemClock.sleep(30) }
        assertTrue("Expected account/settings state was not reached", check())
    }

    private fun core(action: (Core) -> Unit) {
        val done = CountDownLatch(1); var failure: Throwable? = null
        coreContext.postOnCoreThread { core ->
            try { action(core) } catch (error: Throwable) { failure = error } finally { done.countDown() }
        }
        assertTrue(done.await(10, TimeUnit.SECONDS)); failure?.let { throw it }
    }

    private fun setting(extension: String, enabled: Boolean = false, destination: String = "") =
        Mango9CallSettings("2025550100", extension, enabled, destination)

    private fun session(id: String) = Mango9Session(
        crmId = "fixture", crmBaseUrl = "https://crm.example.invalid", crmApiBaseUrl = "https://crm.example.invalid/api",
        userId = id, parentClientId = "tenant", role = "agent", loginId = "fixture@example.invalid", displayName = "Fixture",
        accessToken = "fixture-access", refreshToken = "fixture-refresh", smsChatApi = "https://chat.example.invalid/api",
        connectWebsocket = "wss://chat.example.invalid/socket", enrollmentExpiresAtEpochSeconds = 1_900_000_000,
        sipIdentity = "sip:$id@fixture.example.invalid",
    )
}
