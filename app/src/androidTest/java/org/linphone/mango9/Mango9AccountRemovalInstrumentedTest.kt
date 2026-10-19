/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.Account
import org.linphone.core.Core
import org.linphone.core.Factory
import org.linphone.ui.main.settings.viewmodel.AccountProfileViewModel

@RunWith(AndroidJUnit4::class)
class Mango9AccountRemovalInstrumentedTest {
    @org.junit.After fun persistSdkFixtureCleanup() = Mango9SdkTestCleanup.flush()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val sessions = Mango9SessionStore(instrumentation.targetContext)

    @Test fun nonSelectedLoginCanBeRemovedButOldProfileCannotRemoveAnIdenticalNewLogin() {
        val a = fixture("logout-a")
        val b = fixture("logout-b")
        val originalActive = sessions.activeIdentity
        try {
            sessions.save(a, persist = false, makeActive = true)
            val old = sessions.captureLogin(a.sipIdentity!!)!!
            sessions.save(b, persist = false, makeActive = true)
            var cleanupCalls = 0
            assertTrue(sessions.removeIfCurrent(old) { cleanupCalls++ })
            assertNull(sessions.load(a.sipIdentity))
            assertEquals(b, sessions.load())
            assertEquals(1, cleanupCalls)
            sessions.save(a, persist = false, makeActive = false)
            assertFalse(sessions.removeIfCurrent(old) { cleanupCalls++ })
            assertEquals(a, sessions.load(a.sipIdentity))
            assertEquals(b.sipIdentity, sessions.activeIdentity)
            assertEquals(1, cleanupCalls)
        } finally {
            sessions.remove(a.sipIdentity!!); sessions.remove(b.sipIdentity!!)
            sessions.activate(originalActive?.takeIf(sessions::hasSession))
        }
    }

    @Test fun replacementLoginWaitsUntilRemovalHasReservedCleanup() {
        val session = fixture("logout-atomic")
        val originalActive = sessions.activeIdentity
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val saved = CountDownLatch(1)
        var failure: Throwable? = null
        var removal: Thread? = null
        var login: Thread? = null
        try {
            sessions.save(session, persist = false)
            val lease = sessions.captureLogin(session.sipIdentity!!)!!
            removal = Thread {
                try {
                    assertTrue(
                        sessions.removeIfCurrent(lease) {
                        entered.countDown()
                        assertTrue(release.await(5, TimeUnit.SECONDS))
                    }
                    )
                } catch (error: Throwable) { failure = error }
            }.also { it.start() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            login = Thread {
                try {
                    sessions.save(session, persist = false)
                } catch (error: Throwable) { failure = error } finally { saved.countDown() }
            }.also { it.start() }
            assertFalse("Login must not overtake removal's cleanup reservation", saved.await(150, TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue(saved.await(5, TimeUnit.SECONDS))
            removal.join(5_000)
            failure?.let { throw AssertionError(it) }
            assertEquals(session, sessions.load(session.sipIdentity))
            assertNull(sessions.currentLogin(lease))
        } finally {
            release.countDown(); removal?.join(5_000); login?.join(5_000)
            sessions.remove(session.sipIdentity!!)
            sessions.activate(originalActive?.takeIf(sessions::hasSession))
        }
    }

    @Test fun sharedCrmPushOwnerSurvivesRemovingOneOfItsSipAccounts() {
        val first = fixture("push-owner-a").copy(smsChatApi = "https://chat.example.invalid/api")
        val second = first.copy(sipIdentity = "sip:push-owner-b@crm.example.invalid")
        val owner = Mango9PushOwner.from(first)!!
        val previous = sessions.activeIdentity
        try {
            sessions.save(first, persist = false)
            sessions.save(second, persist = false)
            assertEquals(owner, Mango9PushOwner.from(second))
            assertNotEquals(owner, Mango9PushOwner.from(second.copy(userId = "different-user")))
            assertNotEquals(owner, Mango9PushOwner.from(second.copy(crmId = "different-tenant")))
            assertNotEquals(owner, Mango9PushOwner.from(second.copy(smsChatApi = "https://other.example.invalid/api")))
            val firstLogin = sessions.captureLogin(first.sipIdentity!!)!!
            assertTrue(
                sessions.removeIfCurrent(firstLogin) {
                assertNull(sessions.currentLogin(firstLogin))
                assertTrue(sessions.hasPushOwner(owner))
            }
            )
            assertTrue(
                sessions.removeIfCurrent(sessions.captureLogin(second.sipIdentity!!)!!) {
                assertFalse(sessions.hasPushOwner(owner))
            }
            )
        } finally {
            sessions.remove(first.sipIdentity!!); sessions.remove(second.sipIdentity!!)
            sessions.activate(previous?.takeIf(sessions::hasSession))
        }
    }

    @Test fun delayedDefaultFallbackDoesNotOverrideANewLoginSelection() {
        lateinit var removed: Account
        lateinit var fallback: Account
        lateinit var newest: Account
        var previous: Account? = null
        onCore { core ->
            previous = core.defaultAccount
            removed = account(core, "default-old")
            fallback = account(core, "default-fallback")
            core.defaultAccount = removed
            core.removeAccount(removed) // Queues fallback selection on Core's handler.
            newest = account(core, "default-new")
            core.defaultAccount = newest
        }
        try {
            onCore { core -> assertEquals(newest.nativePointer, core.defaultAccount?.nativePointer) }
        } finally {
            onCore { core ->
                core.accountList.filter { it === removed || it === fallback || it === newest }.forEach(core::removeAccount)
                previous?.takeIf { core.accountList.contains(it) }?.let { core.defaultAccount = it }
            }
        }
    }

    @Test fun coreRemovalIsImmediateAndRetiredAuthCannotRemoveReplacementCredentials() {
        onCore { core ->
            val previousDefault = core.defaultAccount
            val original = account(core, "logout-auth")
            val auth = Factory.instance().createAuthInfo("logout-auth", null, "fixture-old", null, "fixture", "crm.example.invalid")
            core.addAuthInfo(auth)
            val captured = original.findAuthInfo()!!
            var replacement: Account? = null
            try {
                Mango9AccountRemoval.remove(core, original)
                assertFalse(core.accountList.any { it.nativePointer == original.nativePointer })
                assertTrue("Auth is retained for unREGISTER", core.authInfoList.any { it.nativePointer == captured.nativePointer })
                core.removeAuthInfo(captured)
                core.addAuthInfo(Factory.instance().createAuthInfo("logout-auth", null, "fixture-new", null, "fixture", "crm.example.invalid"))
                replacement = account(core, "logout-auth")
                val fresh = replacement.findAuthInfo()!!
                assertTrue(fresh.nativePointer != captured.nativePointer)
                Mango9AccountRemoval.removeRetiredAuth(core, captured)
                assertEquals(fresh.nativePointer, replacement.findAuthInfo()?.nativePointer)
                assertTrue(core.accountList.any { it.nativePointer == replacement.nativePointer })
            } finally {
                replacement?.let { core.removeAccount(it) }
                core.accountList.filter { it.params.identityAddress?.username == "logout-auth" }.forEach(core::removeAccount)
                core.authInfoList.filter { it.username == "logout-auth" }.forEach(core::removeAuthInfo)
                previousDefault?.takeIf { core.accountList.contains(it) }?.let { core.defaultAccount = it }
            }
        }
    }

    @Test fun sharedCredentialsRemainWhileAnotherAccountUsesThem() {
        onCore { core ->
            val previousDefault = core.defaultAccount
            val first = account(core, "logout-shared")
            val second = account(core, "logout-shared")
            core.addAuthInfo(Factory.instance().createAuthInfo("logout-shared", null, "fixture-shared", null, "fixture", "crm.example.invalid"))
            val auth = first.findAuthInfo()!!
            try {
                Mango9AccountRemoval.remove(core, first)
                Mango9AccountRemoval.removeRetiredAuth(core, auth)
                assertEquals(auth.nativePointer, second.findAuthInfo()?.nativePointer)
                assertTrue(core.accountList.contains(second))
                core.removeAccount(second)
                Mango9AccountRemoval.removeRetiredAuth(core, auth)
                assertFalse(core.authInfoList.any { it.nativePointer == auth.nativePointer })
            } finally {
                core.accountList.filter { it.params.identityAddress?.username == "logout-shared" }.forEach(core::removeAccount)
                core.authInfoList.filter { it.username == "logout-shared" }.forEach(core::removeAuthInfo)
                previousDefault?.takeIf { core.accountList.contains(it) }?.let { core.defaultAccount = it }
            }
        }
    }

    @Test fun repeatedProfileDeleteUsesCoreQueueAndRemovesOnlyItsAccount() {
        lateinit var target: Account
        lateinit var retained: Account
        var previousDefault: Account? = null
        lateinit var viewModel: AccountProfileViewModel
        val models = ViewModelStore()
        onCore { core ->
            previousDefault = core.defaultAccount
            target = account(core, "logout-profile")
            retained = account(core, "logout-retained")
        }
        try {
            instrumentation.runOnMainSync {
                viewModel = ViewModelProvider(models, ViewModelProvider.NewInstanceFactory())[AccountProfileViewModel::class.java]
                viewModel.findAccountMatchingIdentity("sip:logout-profile@crm.example.invalid")
            }
            onCore { } // Flush profile lookup before the UI action.
            instrumentation.runOnMainSync { repeat(3) { viewModel.deleteAccount() } }
            onCore { core ->
                assertFalse(core.accountList.contains(target))
                assertTrue(core.accountList.contains(retained))
            }
        } finally {
            instrumentation.runOnMainSync { models.clear() }
            onCore { core ->
                core.accountList.filter { it === target || it === retained }.forEach(core::removeAccount)
                previousDefault?.takeIf { core.accountList.contains(it) }?.let { core.defaultAccount = it }
            }
        }
    }

    private fun account(core: Core, username: String): Account {
        val params = core.createAccountParams().apply {
            identityAddress = Factory.instance().createAddress("sip:$username@crm.example.invalid")
            serverAddress = Factory.instance().createAddress("sip:crm.example.invalid")
            isRegisterEnabled = false // No SIP request is sent by these fixtures.
            pushNotificationAllowed = false
        }
        return core.createAccount(params).also { assertEquals(0, core.addAccount(it)) }
    }

    private fun fixture(name: String) = Mango9Session(
        "fixture", "https://crm.example.invalid", "https://crm.example.invalid/api/v2", "fixture", "fixture", "agent",
        "$name@example.invalid", "Fixture", "fixture-access", "fixture-refresh", "", "", 1_900_000_000,
        "sip:$name@crm.example.invalid",
    )

    private fun onCore(block: (Core) -> Unit) {
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        coreContext.postOnCoreThread { core ->
            try { block(core) } catch (error: Throwable) { failure = error } finally { done.countDown() }
        }
        assertTrue("Core queue timed out", done.await(15, TimeUnit.SECONDS))
        failure?.let { throw AssertionError(it) }
    }
}
