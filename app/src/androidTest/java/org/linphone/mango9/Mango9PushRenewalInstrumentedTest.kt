/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Mango9PushRenewalInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val sessions = Mango9SessionStore(context)

    @Test fun renewsInactiveLoginWithRealSessionStoreWithoutTouchingSelectionOrChat() = withSessions { a, b ->
        runBlocking {
            val uiLease = sessions.captureSession()!!
            val chatBefore = Mango9ChatStore.get(context).state.value
            val requests = mutableListOf<Request>()
            val access = fixtureAccess(a, b)
            val lifecycle = Mango9MessagePushLifecycle(
                access, this, { TOKEN }, { "fixture-device" },
                bootstrap = {
                    if (it.sipIdentity == b.sipIdentity && it.accessToken == "access") throw Mango9ApiException.InvalidCredentials
                    "bearer"
                },
                refresh = { it.copy(accessToken = "renewed", refreshToken = "renewed-refresh") },
                send = { requests += it; 200 }, pause = {},
            )
            withTimeout(5_000) { assertTrue(lifecycle.refreshAll().map { it.await() }.all { it }) }
            assertEquals(2, requests.size)
            assertEquals(a.sipIdentity, sessions.activeIdentity)
            assertEquals(a, sessions.currentSession(uiLease))
            assertEquals("renewed", sessions.load(b.sipIdentity)?.accessToken)
            assertEquals(chatBefore, Mango9ChatStore.get(context).state.value)
        }
    }

    @Test fun inactiveTokenCasRejectsWrongOwnerRemovedAndIdenticalReplacementLogins() = withSessions { a, b ->
        val login = sessions.captureLogin(b.sipIdentity!!)!!
        val version = Mango9SessionStore.contextChanges.value
        assertFalse(sessions.replaceLoginTokens(login, b, b.copy(userId = "wrong")))
        assertFalse(sessions.replaceLoginTokens(login, b, b.copy(accessToken = "")))
        assertTrue(sessions.replaceLoginTokens(login, b, b.copy(accessToken = "new", refreshToken = "new-refresh")))
        assertEquals(version, Mango9SessionStore.contextChanges.value)
        assertEquals(a.sipIdentity, sessions.activeIdentity)
        sessions.remove(b.sipIdentity)
        sessions.save(b, persist = false)
        assertFalse(sessions.replaceLoginTokens(login, b, b.copy(accessToken = "stale")))
        val replacement = requireNotNull(sessions.load(b.sipIdentity))
        assertNotEquals(b.loginEpoch, replacement.loginEpoch)
        assertEquals(b.copy(loginEpoch = replacement.loginEpoch), replacement)
    }

    @Test fun removingSharedAliasRetargetsRemainingInactiveLoginWithinRealRemovalBoundary() = withSessions { a, originalB ->
        val b = originalB.copy(userId = a.userId)
        sessions.save(b, persist = false)
        runBlocking {
            val requests = mutableListOf<Request>()
            val lifecycle = Mango9MessagePushLifecycle(
                fixtureAccess(a, b), this, { TOKEN }, { "fixture-device" },
                bootstrap = { "bearer" }, refresh = { error("Unexpected refresh") },
                send = { requests += it; 200 }, pause = {},
            )
            var cleanup: Mango9PushOperationQueue.Result<Boolean>? = null
            assertTrue(sessions.removeIfCurrent(sessions.captureLogin(a.sipIdentity!!)!!) { cleanup = lifecycle.retire(it) })
            withTimeout(5_000) { assertTrue(cleanup!!.await()) }
            assertNull(sessions.activeIdentity)
            val request = requests.single()
            assertEquals("POST", request.method)
            assertEquals(
                b.sipIdentity,
                JSONObject(
                    Buffer().also {
                request.body!!.writeTo(it)
            }.readUtf8()
                ).getString("sip_identity")
            )
            assertNotNull(sessions.captureLogin(b.sipIdentity!!))
        }
    }

    private fun fixtureAccess(a: Mango9Session, b: Mango9Session) = object : Mango9LoginAccess by sessions {
        override fun captureLogins() = sessions.captureLogins().filter {
            it.session.sipIdentity in setOf(a.sipIdentity, b.sipIdentity)
        }
    }

    private fun withSessions(test: (Mango9Session, Mango9Session) -> Unit) {
        val old = sessions.activeIdentity
        val a = fixture("renew-a"); val b = fixture("renew-b")
        try {
            sessions.save(a, persist = false, makeActive = true)
            sessions.save(b, persist = false)
            test(requireNotNull(sessions.load(a.sipIdentity!!)), requireNotNull(sessions.load(b.sipIdentity!!)))
        } finally {
            sessions.remove(a.sipIdentity!!); sessions.remove(b.sipIdentity!!)
            sessions.activate(old?.takeIf(sessions::hasSession))
        }
    }

    private fun fixture(id: String) = Mango9Session(
        crmId = "crm", crmBaseUrl = "https://crm.example.invalid", crmApiBaseUrl = "https://crm.example.invalid/app/api/v2",
        userId = id, parentClientId = "fixture-tenant", role = "agent", loginId = "fixture@example.invalid",
        displayName = "Fixture", accessToken = "access", refreshToken = "refresh", smsChatApi = "https://chat.example.invalid/api",
        connectWebsocket = "wss://chat.example.invalid/socket", enrollmentExpiresAtEpochSeconds = 1_900_000_000,
        sipIdentity = "sip:$id@crm.example.invalid",
    )

    companion object { private const val TOKEN = "fcm:abcdefghijklmnopqrstuvwxyz-0123456789" }
}
