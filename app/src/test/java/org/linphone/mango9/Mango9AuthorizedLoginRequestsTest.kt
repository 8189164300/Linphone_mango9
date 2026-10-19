/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.Timeout

class Mango9AuthorizedLoginRequestsTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(10)

    @Test fun inactiveRefreshSurvivesSelectionChangesWithoutChangingSelectionItself() = runBlocking {
        val a = pushTestSession(); val b = pushTestSession("2")
        val sessions = Mango9PushTestSessions(a, b)
        val login = sessions.captureLogins().last()
        val requests = Mango9AuthorizedLoginRequests(sessions) {
            sessions.select(null)
            it.copy(accessToken = "new-access", refreshToken = "new-refresh")
        }
        assertEquals(
            "new-access",
            requests.execute(login) {
            if (it.accessToken == "old-access") throw Mango9ApiException.InvalidCredentials
            it.accessToken
        }
        )
        assertNull(sessions.active)
        assertEquals(1, sessions.writes)
        assertEquals(
            "old-access",
            sessions.captureLogins().first {
            it.session.sipIdentity == a.sipIdentity
        }.session.accessToken
        )
    }

    @Test fun uiAndBackgroundFailureShareOneRefreshAndNeitherInvalidatesTheLogin() = runBlocking {
        val sessions = Mango9PushTestSessions(pushTestSession())
        val login = sessions.captureLogins().single(); val uiLease = sessions.captureSession()!!
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        var refreshes = 0
        val refresh: suspend (Mango9Session) -> Mango9Session = {
            refreshes++; started.complete(Unit); finish.await()
            it.copy(accessToken = "new-access", refreshToken = "new-refresh")
        }
        val action: suspend (Mango9Session) -> String = {
            if (it.accessToken == "old-access") throw Mango9ApiException.InvalidCredentials
            it.accessToken
        }
        val ui = async { Mango9AuthorizedRequests(sessions, refresh).execute(uiLease, action) }
        started.await()
        val background = async(start = CoroutineStart.UNDISPATCHED) {
            Mango9AuthorizedLoginRequests(sessions, refresh).execute(login, action)
        }
        finish.complete(Unit)
        assertEquals("new-access", ui.await()); assertEquals("new-access", background.await())
        assertEquals(1, refreshes); assertEquals(1, sessions.writes)
        assertNotNull(sessions.currentSession(uiLease)); assertNotNull(sessions.currentLogin(login))
    }

    @Test fun cancelledBackgroundRefreshDoesNotPersistTokens() = runBlocking {
        val sessions = Mango9PushTestSessions(pushTestSession())
        val started = CompletableDeferred<Unit>()
        val job = async {
            Mango9AuthorizedLoginRequests(sessions) {
                started.complete(Unit); CompletableDeferred<Unit>().await(); it
            }.execute<Unit>(sessions.captureLogins().single()) { throw Mango9ApiException.InvalidCredentials }
        }
        started.await(); job.cancelAndJoin()
        assertEquals(0, sessions.writes)
    }

    @Test fun removedLoginCannotDispatchAnyBackgroundRequest() = runBlocking {
        val a = pushTestSession(); val sessions = Mango9PushTestSessions(a)
        val lease = sessions.captureLogins().single()
        sessions.remove(a); sessions.save(a)
        var invoked = false
        val result = runCatching {
            Mango9AuthorizedLoginRequests(sessions) { error("Unexpected refresh") }
                .execute(lease) { invoked = true }
        }
        assertTrue(result.exceptionOrNull() is Mango9ApiException.Unauthorized)
        assertFalse(invoked)
    }
}
