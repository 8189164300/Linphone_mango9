/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

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
import org.linphone.core.MediaDirection
import org.linphone.core.MediaEncryption
import org.linphone.utils.LinphoneUtils

/** Real SDK account/params and encrypted-session state, with INVITE replaced to avoid real calls. */
@RunWith(AndroidJUnit4::class)
class Mango9OutgoingCallsInstrumentedTest {
    @org.junit.After fun persistSdkFixtureCleanup() = Mango9SdkTestCleanup.flush()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun permissionIsBeforeHoldAndInviteAndRepeatedTapsDialOnlyOnce() = fixture { f ->
        f.start()
        assertEquals(0, f.holds); assertEquals(0, f.dials)
        f.start()
        assertEquals(listOf(false), f.results)
        assertEquals(1, f.prompts.size)
        f.granted = true
        f.controller.complete(f.prompts.single(), true)
        f.controller.complete(f.prompts.single(), true)
        assertEquals(listOf(false, true), f.results)
        assertEquals(1, f.holds); assertEquals(1, f.dials)
    }

    @Test fun denyingAndReturningFromSettingsNeverAutomaticallyDials() = fixture { f ->
        f.start(); val id = f.prompts.single()
        f.controller.complete(id, false)
        f.granted = true
        f.controller.complete(id, true)
        assertEquals(0, f.dials); assertEquals(0, f.holds)
        f.start()
        assertEquals(1, f.dials); assertEquals(1, f.prompts.size)
    }

    @Test fun changedDefaultAndSwitchBackInvalidateThePendingIntent() = fixture { f ->
        f.start()
        f.core.defaultAccount = f.b
        f.core.defaultAccount = f.a
        f.granted = true
        f.controller.complete(f.prompts.single(), true)
        assertEquals(0, f.dials); assertEquals(listOf(false), f.results)
    }

    @Test fun removedExplicitAccountNeverFallsBackToDefault() = fixture { f ->
        val removedIdentity = f.b.params.identityAddress!!.clone()
        f.core.removeAccount(f.b)
        f.granted = true
        f.controller.start(f.target(), localAddress = removedIdentity, skipNetworkReachabilityTest = true, onResult = f.results::add)
        assertEquals(0, f.holds); assertEquals(0, f.dials); assertEquals(0, f.prompts.size)
        assertEquals(listOf(false), f.results)
    }

    @Test fun replacingNonDefaultAccountWithSameUriInvalidatesRequest() = fixture { f ->
        f.controller.start(f.target(), localAddress = f.b.params.identityAddress, skipNetworkReachabilityTest = true, onResult = f.results::add)
        val params = f.b.params.clone()
        f.core.removeAccount(f.b)
        val replacement = f.core.createAccount(params)
        f.core.addAccount(replacement)
        try {
            f.granted = true
            f.controller.complete(f.prompts.single(), true)
            assertEquals(0, f.dials); assertEquals(listOf(false), f.results)
        } finally { f.core.removeAccount(replacement) }
    }

    @Test fun permissionReplyCannotDialAReplacementCrmLogin() = fixture { f ->
        val session = f.session()
        f.sessions.save(session, persist = false)
        f.start()
        f.sessions.save(session.copy(accessToken = "replacement-fixture"), persist = false)
        f.granted = true
        f.controller.complete(f.prompts.single(), true)
        assertEquals(0, f.dials); assertEquals(listOf(false), f.results)
    }

    @Test fun originalAccountDestinationAndMediaParamsArePreserved() = fixture { f ->
        val target = f.target()
        val params = f.core.createCallParams(null)!!.apply {
            account = f.b
            isVideoEnabled = true
            videoDirection = MediaDirection.RecvOnly
            isMicEnabled = false
            addCustomHeader("X-Fixture", "original")
        }
        f.controller.start(target, params, forceZRTP = true, skipNetworkReachabilityTest = true)
        target.username = "changed"
        params.account = f.a
        params.isVideoEnabled = false
        params.addCustomHeader("X-Fixture", "changed")
        f.granted = true
        f.controller.complete(f.prompts.single(), true)
        assertSame(f.b, f.lastAccount)
        assertEquals("sip:callee@remote.example.invalid", f.lastAddress)
        assertTrue(f.video); assertFalse(f.mic)
        assertEquals(MediaDirection.RecvOnly, f.direction)
        assertEquals(MediaEncryption.ZRTP, f.encryption)
        assertEquals("original", f.header)
    }

    @Test fun cancelAllAndStaleResultsCannotCreateConferenceOrASecondCall() = fixture { f ->
        var conferences = 0
        f.controller.withMicrophone(f.a, skipNetworkCheck = true, onResult = f.results::add) { conferences++; true }
        val old = f.prompts.single()
        f.controller.cancelAll()
        f.start()
        f.granted = true
        f.controller.complete(old, true)
        assertEquals(0, conferences); assertEquals(0, f.dials)
        f.controller.complete(f.prompts.last(), true)
        assertEquals(1, f.dials)
    }

    @Test fun callbackGrantWithoutLivePermissionDoesNotDial() = fixture { f ->
        f.start()
        f.controller.complete(f.prompts.single(), true)
        assertEquals(0, f.dials); assertEquals(0, f.holds)
        assertEquals(listOf(false), f.results)
    }

    @Test fun groupCreationWithMissingOrRemovedAccountNeverEntersTheSdkOrFallsBack() = fixture { f ->
        assertNull(LinphoneUtils.createGroupCall(null, "No account fixture"))
        f.core.removeAccount(f.b)
        assertNull(LinphoneUtils.createGroupCall(f.b, "Removed account fixture"))
        assertSame(f.a, f.core.defaultAccount)
        assertEquals(0, f.core.callsNb)
    }

    private inner class Fixture(val core: Core, val a: Account, val b: Account) {
        val sessions = Mango9SessionStore(instrumentation.targetContext)
        var granted = false; var holds = 0; var dials = 0
        val prompts = mutableListOf<Long>(); val results = mutableListOf<Boolean>()
        var lastAccount: Account? = null; var lastAddress: String? = null
        var video = false; var mic = true; var header: String? = null
        var direction: MediaDirection? = null; var encryption: MediaEncryption? = null
        val controller = Mango9OutgoingCalls(core, sessions, { granted }, { prompts.add(it) }, {}, { address, params ->
            dials++; lastAccount = params.account; lastAddress = address.asStringUriOnly()
            video = params.isVideoEnabled; mic = params.isMicEnabled
            header = params.getCustomHeader("X-Fixture"); direction = params.videoDirection; encryption = params.mediaEncryption
            true
        }, { holds++ })

        fun target() = Factory.instance().createAddress("sip:callee@remote.example.invalid")!!

        fun start() = controller.start(target(), skipNetworkReachabilityTest = true, onResult = { results.add(it) })

        fun session() = Mango9Session(
            crmId = "fixture", crmBaseUrl = "https://crm.example.invalid", crmApiBaseUrl = "https://crm.example.invalid/api",
            userId = "fixture", parentClientId = "tenant", role = "agent", loginId = "fixture@example.invalid", displayName = "Fixture",
            accessToken = "fixture-access", refreshToken = "fixture-refresh", smsChatApi = "https://chat.example.invalid/api",
            connectWebsocket = "wss://chat.example.invalid/socket", enrollmentExpiresAtEpochSeconds = 1_900_000_000,
            sipIdentity = a.params.identityAddress!!.asStringUriOnly(),
        )
    }

    private fun fixture(test: (Fixture) -> Unit) {
        // A cold instrumentation process may expose the core queue before SDK startup.
        // Exercise the permission gate only after the real core reaches On.
        kotlinx.coroutines.runBlocking { coreContext.awaitReady() }
        val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
        while (true) {
            val checked = CountDownLatch(1)
            var ready = false
            coreContext.postOnCoreThread { core ->
                ready = core.globalState == org.linphone.core.GlobalState.On
                checked.countDown()
            }
            assertTrue("Core readiness check timed out", checked.await(5, TimeUnit.SECONDS))
            if (ready) break
            check(android.os.SystemClock.elapsedRealtime() < deadline) { "App Core did not start" }
            Thread.sleep(25)
        }
        val latch = CountDownLatch(1)
        var failure: Throwable? = null
        coreContext.postOnCoreThread { core ->
            val sessions = Mango9SessionStore(instrumentation.targetContext)
            val oldIdentity = sessions.activeIdentity
            val oldDefault = core.defaultAccount
            val accounts = mutableListOf<Account>()
            try {
                fun account(name: String): Account {
                    val params = core.createAccountParams().apply {
                        identityAddress = Factory.instance().createAddress("sip:mic-$name@fixture.example.invalid")
                        serverAddress = Factory.instance().createAddress("sip:fixture.example.invalid")
                        isRegisterEnabled = false; pushNotificationAllowed = false
                    }
                    return core.createAccount(params).also { accounts.add(it); core.addAccount(it) }
                }
                val a = account("a"); val b = account("b")
                core.defaultAccount = a
                val f = Fixture(core, a, b)
                try { test(f) } finally { f.controller.cancelAll() }
            } catch (error: Throwable) { failure = error } finally {
                core.defaultAccount = oldDefault
                accounts.forEach { account ->
                    account.params.identityAddress?.asStringUriOnly()?.let(sessions::remove)
                    if (core.accountList.any { it === account }) core.removeAccount(account)
                }
                sessions.activate(oldIdentity)
                latch.countDown()
            }
        }
        assertTrue("Core fixture timed out", latch.await(10, TimeUnit.SECONDS))
        failure?.let { throw it }
    }
}
