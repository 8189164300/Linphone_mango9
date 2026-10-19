/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.Account
import org.linphone.core.Core
import org.linphone.core.Factory
import org.linphone.ui.main.crm.viewmodel.Mango9MessagingViewModel

@RunWith(AndroidJUnit4::class)
class Mango9PushNavigationInstrumentedTest {
    @org.junit.After fun persistSdkFixtureCleanup() = Mango9SdkTestCleanup.flush()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val sessions = Mango9SessionStore(context)

    @Test fun rapidCrossAccountPushesSelectOnlyLatestTargetWithoutWaitingForNetwork() = withAccounts { a, b ->
        runBlocking {
            val gate = Mango9OpenRequestGate()
            val release = blockCore()
            try {
                val oldTicket = gate.begin()
                val old = async(start = CoroutineStart.UNDISPATCHED) {
                    Mango9MessagePushCoordinator.activateForOpen(context, push(b), oldTicket)
                }
                val newTicket = gate.begin()
                val latest = async(start = CoroutineStart.UNDISPATCHED) {
                    Mango9MessagePushCoordinator.activateForOpen(context, push(a), newTicket)
                }
                release.countDown()
                withTimeout(5_000) {
                    assertNull(old.await())
                    assertEquals(a.sipIdentity, latest.await()?.session?.sipIdentity)
                }
                assertEquals(a.sipIdentity, sessions.activeIdentity)
                onCore { assertEquals(a.sipIdentity, it.defaultAccount?.params?.identityAddress?.asStringUriOnly()) }
            } finally { release.countDown() }
        }
    }

    @Test fun canceledActivationCannotMutateTheNativeAccountAfterItsCallerIsGone() = withAccounts { a, b ->
        runBlocking {
            val release = blockCore()
            try {
                val gate = Mango9OpenRequestGate()
                val task = async(start = CoroutineStart.UNDISPATCHED) {
                    Mango9MessagePushCoordinator.activateForOpen(context, push(b), gate.begin())
                }
                task.cancelAndJoin()
                release.countDown()
                onCore { assertEquals(a.sipIdentity, it.defaultAccount?.params?.identityAddress?.asStringUriOnly()) }
                assertEquals(a.sipIdentity, sessions.activeIdentity)
            } finally { release.countDown() }
        }
    }

    @Test fun replacementLoginAndManualSelectionDefeatPreviouslyQueuedActivation() = withAccounts { a, b ->
        runBlocking {
            for (replaceLogin in listOf(true, false)) {
                val release = blockCore()
                try {
                    val gate = Mango9OpenRequestGate()
                    val task = async(start = CoroutineStart.UNDISPATCHED) {
                        Mango9MessagePushCoordinator.activateForOpen(context, push(b), gate.begin())
                    }
                    if (replaceLogin) {
                        sessions.save(b, persist = false)
                    } else {
                        sessions.activate(b.sipIdentity)
                        sessions.activate(a.sipIdentity) // A → B → A is still a newer choice.
                    }
                    release.countDown()
                    assertNull(withTimeout(5_000) { task.await() })
                    onCore { assertEquals(a.sipIdentity, it.defaultAccount?.params?.identityAddress?.asStringUriOnly()) }
                    assertEquals(a.sipIdentity, sessions.activeIdentity)
                } finally { release.countDown() }
            }
        }
    }

    @Test fun accountResolutionRejectsAmbiguousTenantAndConflictingExplicitIdentity() = withAccounts { a, b ->
        assertNull(sessions.resolvePushLogin(push(a).copy(sipIdentity = null)))
        assertNull(sessions.resolvePushLogin(push(a).copy(crmId = "different-tenant")))
        assertNull(sessions.resolvePushLogin(push(a).copy(sipIdentity = "sip:missing@crm.example.invalid")))
        assertEquals(b.sipIdentity, sessions.resolvePushLogin(push(b))?.session?.sipIdentity)
        sessions.remove(b.sipIdentity!!)
        assertEquals(a.sipIdentity, sessions.resolvePushLogin(push(a).copy(sipIdentity = null))?.session?.sipIdentity)
        // A stored CRM login with no native account must not silently open the active account.
        val absent = fixture("route-absent")
        try {
            sessions.save(absent, persist = false)
            runBlocking {
                assertNull(Mango9MessagePushCoordinator.activateForOpen(context, push(absent), Mango9OpenRequestGate().begin()))
            }
        } finally { sessions.remove(absent.sipIdentity!!) }
    }

    @Test fun restoredConversationArgumentsContainNoCredentialsAndNeverBindToReplacementLogin() = withAccounts { a, b ->
        val arguments = Bundle()
        val lease = sessions.captureSession()!!
        Mango9NavigationSession.bind(arguments, lease)
        val restored = Bundle(arguments)
        assertEquals(lease, Mango9NavigationSession.resolve(restored, sessions))
        assertFalse(restored.toString().contains(a.accessToken))
        assertFalse(restored.toString().contains(a.refreshToken))
        sessions.activate(b.sipIdentity)
        assertNull(Mango9NavigationSession.resolve(restored, sessions))
        sessions.activate(a.sipIdentity)
        assertNull(Mango9NavigationSession.resolve(restored, sessions))
        sessions.save(a, persist = false)
        assertNull(Mango9NavigationSession.resolve(restored, sessions))
        val models = ViewModelStore()
        try {
            instrumentation.runOnMainSync {
                val model = ViewModelProvider(models, ViewModelProvider.NewInstanceFactory())[Mango9MessagingViewModel::class.java]
                model.bindConversation(lease)
                assertFalse(model.isCurrentConversation())
                assertTrue(model.currentState().messages.isEmpty())
                assertTrue(model.currentState().smsMessages.isEmpty())
                assertNotNull(model.currentState().errorMessage)
                model.openTeamConversation(42, "same-id", "Fixture")
                model.openSmsConversation("12025550123")
                assertFalse(model.openingConversation.value == true)
            }
        } finally { instrumentation.runOnMainSync { models.clear() } }
    }

    @Test fun nativeManualSelectionInvalidatesQueuedPushBeforeAnyActivityObserverRuns() = withAccounts { a, b ->
        runBlocking {
            var manualSelected: String? = null
            val release = blockCore {
                val core = coreContext.core
                val original = core.defaultAccount
                core.defaultAccount = core.accountList.first { it.params.identityAddress?.asStringUriOnly() == b.sipIdentity }
                manualSelected = sessions.activeIdentity
                core.defaultAccount = original
            }
            try {
                val gate = Mango9OpenRequestGate()
                val pending = async(start = CoroutineStart.UNDISPATCHED) {
                    Mango9MessagePushCoordinator.activateForOpen(context, push(b), gate.begin())
                }
                release.countDown()
                assertNull(withTimeout(5_000) { pending.await() })
                assertEquals(b.sipIdentity, manualSelected)
                assertEquals(a.sipIdentity, sessions.activeIdentity)
                onCore { assertEquals(a.sipIdentity, it.defaultAccount?.params?.identityAddress?.asStringUriOnly()) }
            } finally { release.countDown() }
        }
    }

    @Test fun chatSmsAndLeadPushesUseTheSameLocalAccountActivation() = withAccounts { a, b ->
        val targets = listOf(
            Mango9MessagePushTarget.Chat(42, "Fixture", "room-42"),
            Mango9MessagePushTarget.Sms("12025550123", "Fixture"),
            Mango9MessagePushTarget.Lead(42),
        )
        for (target in targets) {
            onCore { core ->
                core.defaultAccount = core.accountList.first { it.params.identityAddress?.asStringUriOnly() == a.sipIdentity }
            }
            runBlocking {
                val lease = withTimeout(5_000) {
                    Mango9MessagePushCoordinator.activateForOpen(context, push(b).copy(target = target), Mango9OpenRequestGate().begin())
                }
                assertEquals(b.sipIdentity, lease?.session?.sipIdentity)
            }
            onCore { assertEquals(b.sipIdentity, it.defaultAccount?.params?.identityAddress?.asStringUriOnly()) }
        }
    }

    @Test fun queuedMessagingOperationsCannotAdoptAnIdenticalReplacementLogin() = withAccounts { a, _ ->
        val old = sessions.captureSession()!!
        sessions.save(a, persist = false)
        val store = Mango9ChatStore.get(context)
        val before = store.state.value
        runBlocking {
            withTimeout(5_000) {
                store.connect(expectedSession = old)
                store.openRoom("room-42", "old-view", old)
                store.openSmsConversation("12025550123", "old-view", old)
                assertNull(store.createGroup(setOf(42, 43), old))
                assertFalse(store.sendChatMessage("fixture", emptyList(), old))
                assertFalse(store.sendSms("12025550123", "12025550456", "fixture", emptyList(), old))
                assertFalse(store.sendSmsFromNotification("12025550123", "fixture", old))
            }
        }
        assertEquals(before, store.state.value)
    }

    private fun push(session: Mango9Session) = Mango9MessagePush(
        Mango9MessagePushTarget.Chat(42, "Fixture", "room-42"), session.sipIdentity, session.crmId,
    ).let { push -> sessions.captureLogin(session.sipIdentity!!)?.let { sessions.bindNotification(push, it) } ?: push }

    private fun withAccounts(test: (Mango9Session, Mango9Session) -> Unit) {
        val a = fixture("route-a")
        val b = fixture("route-b")
        val previous = sessions.activeIdentity
        var previousDefault: Account? = null
        val owned = mutableListOf<Account>()
        try {
            sessions.save(a, persist = false, makeActive = true)
            sessions.save(b, persist = false)
            onCore { core ->
                previousDefault = core.defaultAccount
                for (session in listOf(a, b)) {
                    val params = core.createAccountParams().apply {
                        identityAddress = Factory.instance().createAddress(session.sipIdentity!!)
                        serverAddress = Factory.instance().createAddress("sip:crm.example.invalid")
                        isRegisterEnabled = false
                        pushNotificationAllowed = false
                    }
                    val account = core.createAccount(params)
                    assertEquals(0, core.addAccount(account))
                    owned += account
                }
                core.defaultAccount = owned.first()
            }
            test(a, b)
        } finally {
            onCore { core ->
                owned.filter { core.accountList.contains(it) }.forEach(core::removeAccount)
                previousDefault?.takeIf { core.accountList.contains(it) }?.let { core.defaultAccount = it }
            }
            sessions.remove(a.sipIdentity!!); sessions.remove(b.sipIdentity!!)
            sessions.activate(previous?.takeIf(sessions::hasSession))
        }
    }

    private fun blockCore(afterWait: () -> Unit = {}): CountDownLatch {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        coreContext.postOnCoreThread {
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
            afterWait()
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        return release
    }

    private fun onCore(block: (Core) -> Unit) {
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        coreContext.postOnCoreThread { core ->
            try { block(core) } catch (error: Throwable) { failure = error } finally { done.countDown() }
        }
        assertTrue("Core queue timed out", done.await(15, TimeUnit.SECONDS))
        failure?.let { throw AssertionError(it) }
    }

    private fun fixture(name: String) = Mango9Session(
        "route-fixture", "https://crm.example.invalid", "https://crm.example.invalid/api/v2", name, "fixture", "agent",
        "$name@example.invalid", "Fixture", "route-access-token", "route-refresh-token", "", "", 1_900_000_000,
        "sip:$name@crm.example.invalid",
    )
}
