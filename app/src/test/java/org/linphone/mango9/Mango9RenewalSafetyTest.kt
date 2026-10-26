// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class Mango9RenewalSafetyTest {
    private fun session() = pushTestSession().copy(
        accessToken = UUID.randomUUID().toString(), refreshToken = UUID.randomUUID().toString(),
        loginEpoch = UUID.randomUUID().toString()
    )

    @Test fun switchingScreensStillSavesOriginalLoginWithoutChangingSelection() = runBlocking {
        val original = session(); val other = pushTestSession("2", "2")
        val store = Mango9PushTestSessions(original, other)
        val lease = store.captureLogins().first { it.session == original }
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val requests = Mango9AuthorizedRequests(store) {
            started.complete(Unit); finish.await(); it.copy(accessToken = "renewed", refreshToken = "rotated")
        }
        val task = async { runCatching { requests.execute<Unit> { throw Mango9ApiException.InvalidCredentials } } }
        started.await(); store.select(other.sipIdentity); finish.complete(Unit)
        assertEquals(Mango9ApiException.Unauthorized, task.await().exceptionOrNull())
        assertEquals("rotated", store.currentLogin(lease)?.refreshToken)
        assertEquals(other.sipIdentity, store.active)
    }

    @Test fun confirmedExpiredGrantStopsRenewalAndResourcePolling() = runBlocking {
        val saved = session(); val store = Mango9PushTestSessions(saved)
        var refreshes = 0; var reads = 0
        val requests = Mango9AuthorizedRequests(store) { refreshes++; throw Mango9ApiException.SessionExpired }
        repeat(3) {
            val failure =
                runCatching { requests.execute<Unit> { reads++; throw Mango9ApiException.InvalidCredentials } }
            assertEquals(Mango9ApiException.SessionExpired, failure.exceptionOrNull())
        }
        assertEquals(1, reads); assertEquals(1, refreshes)
        assertEquals(saved.refreshToken, store.captureSession()?.session?.refreshToken)
    }

    @Test fun transientRenewalFailurePreservesCredentialsAndHonorsCooldown() = runBlocking {
        val saved = session(); val store = Mango9PushTestSessions(saved)
        var count = 0
        val requests = Mango9AuthorizedRequests(store) { count++; throw Mango9ApiException.RetryAfter(60) }
        repeat(2) {
            val failure = runCatching { requests.execute<Unit> { throw Mango9ApiException.InvalidCredentials } }.exceptionOrNull()
            assertTrue(failure is Mango9ApiException.RetryAfter)
            assertTrue((failure as Mango9ApiException.RetryAfter).seconds >= 59)
        }
        assertEquals(1, count); assertEquals(saved, store.captureSession()?.session)
    }

    @Test fun storageFailureDoesNotPublishReplacementOrReplayAction() = runBlocking {
        val saved = session(); val generation = Mango9SessionGenerations()
        val store = object : Mango9LoginAccess {
            override fun captureLogins() = listOf(generation.captureLogin(saved))

            override fun currentLogin(lease: Mango9LoginLease) = saved

            override fun replaceLoginTokens(lease: Mango9LoginLease, expected: Mango9Session, refreshed: Mango9Session): Boolean =
                throw Mango9ApiException.SecureStorage
        }
        var calls = 0
        val result = runCatching {
            Mango9AuthorizedLoginRequests(store) { it.copy(accessToken = "new", refreshToken = "new") }
                .execute<Unit>(store.captureLogins().single()) { calls++; throw Mango9ApiException.InvalidCredentials }
        }
        assertEquals(Mango9ApiException.SecureStorage, result.exceptionOrNull())
        assertEquals(1, calls); assertEquals(saved, store.captureLogins().single().session)
    }

    @Test fun freshLoginFencesLateRefreshFailure() = runBlocking {
        val old = session(); val store = Mango9PushTestSessions(old)
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val task = async {
            runCatching {
            Mango9AuthorizedRequests(store) {
                started.complete(Unit); finish.await(); throw Mango9ApiException.SessionExpired
            }.execute<Unit> { throw Mango9ApiException.InvalidCredentials }
        }
        }
        started.await(); val fresh = session(); store.save(fresh); finish.complete(Unit)
        assertEquals(Mango9ApiException.Unauthorized, task.await().exceptionOrNull())
        Mango9TokenRefresh.check(fresh)
        assertEquals(fresh, store.captureSession()?.session); assertEquals(0, store.writes)
    }
}
