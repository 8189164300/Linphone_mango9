/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Mango9AuthorizedRequestsTest {
    @Test
    fun refreshRotatesTokensWithoutChangingLoginOrAccount() = runBlocking {
        val store = MemorySessions()
        val lease = store.captureSession()!!
        var attempts = 0
        val requests = Mango9AuthorizedRequests(store) { it.renewed() }
        assertEquals(
            "new-access",
            requests.execute {
            attempts++
            if (attempts == 1) throw Mango9ApiException.Unauthorized
            it.accessToken
        }
        )
        assertEquals(2, attempts)
        assertEquals("new-refresh", store.currentSession(lease)?.refreshToken)
        assertEquals(1, store.writes)
    }

    @Test
    fun logoutDuringRefreshCannotRecreateSessionOrRetryAction() = runBlocking {
        val store = MemorySessions()
        var attempts = 0
        val result = runCatching {
            Mango9AuthorizedRequests(store) {
                store.logout()
                it.renewed()
            }.execute<Unit> {
                attempts++
                throw Mango9ApiException.InvalidCredentials
            }
        }
        assertTrue(result.exceptionOrNull() is Mango9ApiException.Unauthorized)
        assertNull(store.session)
        assertEquals(0, store.writes)
        assertEquals(1, attempts)
    }

    @Test
    fun switchingAwayAndBackInvalidatesAnOldResponse() = runBlocking {
        val store = MemorySessions()
        val result = runCatching {
            Mango9AuthorizedRequests(store) { error("No refresh expected") }.execute {
                store.select("sip:other@tenant.example.com")
                store.select(it.sipIdentity)
                "stale response"
            }
        }
        assertTrue(result.exceptionOrNull() is Mango9ApiException.Unauthorized)
    }

    @Test
    fun refreshCannotReactivatePreviousAccount() = runBlocking {
        val store = MemorySessions()
        val other = "sip:other@tenant.example.com"
        val result = runCatching {
            Mango9AuthorizedRequests(store) {
                store.select(other)
                it.renewed()
            }.execute<Unit> { throw Mango9ApiException.InvalidCredentials }
        }
        assertTrue(result.isFailure)
        assertEquals(other, store.active)
        assertEquals(0, store.writes)
    }

    @Test
    fun sameIdentityNewLoginRejectsOldRefreshEvenWithIdenticalTokens() = runBlocking {
        val store = MemorySessions()
        val result = runCatching {
            Mango9AuthorizedRequests(store) {
                store.generations.loginChanged(it.sipIdentity!!)
                it.renewed()
            }.execute<Unit> { throw Mango9ApiException.InvalidCredentials }
        }
        assertTrue(result.isFailure)
        assertEquals("old-access", store.session?.accessToken)
        assertEquals(0, store.writes)
    }

    @Test
    fun concurrentFailuresShareOneRotatedRefreshToken() = runBlocking {
        val store = MemorySessions()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var refreshes = 0
        val requests = Mango9AuthorizedRequests(store) {
            refreshes++
            started.complete(Unit)
            finish.await()
            it.renewed()
        }

        suspend fun request() = requests.execute {
            if (it.accessToken == "old-access") throw Mango9ApiException.InvalidCredentials
            it.accessToken
        }
        val first = async { request() }
        started.await()
        val second = async { request() }
        finish.complete(Unit)
        assertEquals("new-access", first.await())
        assertEquals("new-access", second.await())
        assertEquals(1, refreshes)
        assertEquals(1, store.writes)
    }

    @Test
    fun cancellationStillSavesButDoesNotRetryScreenAction() = runBlocking {
        val store = MemorySessions()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val requests = Mango9AuthorizedRequests(store) {
            started.complete(Unit)
            finish.await()
            it.renewed()
        }
        val job = launch { requests.execute<Unit> { throw Mango9ApiException.InvalidCredentials } }
        started.await()
        job.cancelAndJoin()
        finish.complete(Unit)
        val result = requests.execute {
            if (it.accessToken == "old-access") throw Mango9ApiException.InvalidCredentials
            it.accessToken
        }
        assertEquals("new-access", result)
        assertEquals(1, store.writes)
    }

    @Test
    fun unrelatedAccountChangesDoNotInvalidateActiveLogin() {
        val store = MemorySessions()
        val lease = store.captureSession()!!
        store.generations.loginChanged("sip:other@tenant.example.com")
        assertEquals(store.session, store.currentSession(lease))
        assertFalse(store.generations.matches(lease, null))
    }

    @Test fun queuedReadUsesOriginalSelectionAndDoesNotSendAfterSwitchAwayAndBack() = runBlocking {
        val store = MemorySessions()
        val original = store.captureSession()!!
        val dispatch = CompletableDeferred<Unit>()
        var sent = false
        val read = async {
            dispatch.await()
            runCatching {
                Mango9AuthorizedRequests(store) { error("No refresh expected") }.execute(original) { sent = true; "record" }
            }
        }
        store.select("sip:other@tenant.example.com"); store.select(original.session.sipIdentity)
        dispatch.complete(Unit)
        assertTrue(read.await().exceptionOrNull() is Mango9ApiException.Unauthorized)
        assertFalse(sent)
    }

    private class MemorySessions : Mango9SessionAccess {
        val generations = Mango9SessionGenerations()
        var session: Mango9Session? = Mango9Session(
            "crm", "https://crm.example.com", "https://crm.example.com/app/api/v2", "1", "2", "agent",
            "user@example.com", "User", "old-access", "old-refresh", "https://chat.example.com/api",
            "wss://chat.example.com/socket", 1_900_000_000, "sip:1@tenant.example.com",
        )
        var active = session?.sipIdentity
        var writes = 0

        override fun captureSession() = session?.let(generations::capture)

        override fun currentSession(lease: Mango9SessionLease) =
            session.takeIf { generations.matches(lease, active) }

        override fun replaceTokens(
            lease: Mango9SessionLease,
            expected: Mango9Session,
            refreshed: Mango9Session,
        ): Boolean {
            if (currentSession(lease) != expected || session == null) return false
            session = refreshed
            writes++
            return true
        }

        fun select(identity: String?) {
            if (active != identity) generations.selectionChanged()
            active = identity
        }

        fun logout() {
            session?.sipIdentity?.let(generations::loginChanged)
            session = null
            select(null)
        }
    }

    private fun Mango9Session.renewed() = copy(accessToken = "new-access", refreshToken = "new-refresh")
}
