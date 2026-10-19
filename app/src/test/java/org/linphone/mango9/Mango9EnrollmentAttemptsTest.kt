/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class Mango9EnrollmentAttemptsTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(10)

    private class Fixture {
        val gate = Mango9EnrollmentAttempts()
        val session = pushTestSession("enrollment")
        val enrollment =
            Mango9SipEnrollment(session.sipIdentity!!, "enrollment", "tenant.example.com", "realm", "digest")
        val response =
            Mango9LoginResponse("https://provisioning.example.com/once", 60, session.copy(sipIdentity = null))
        var selection = 0L
        var fetches = 0
        var commits = 0
        var remembered: Boolean? = null
        var stored: Mango9Session? = null
        var fetch: suspend () -> Mango9SipEnrollment = { enrollment }
        val flow = Mango9EnrollmentLogin(
            selectionVersion = { selection },
            fetchEnrollment = { fetches++; fetch() },
            commit = { value, _, remember, expected ->
                if (selection != expected) {
                    null
                } else {
                    commits++; remembered = remember; stored = value
                    Mango9SessionLease(value, 1, ++selection)
                }
            },
        )
    }

    @Test fun lateAuthenticationCannotFetchOrCommitAfterLeavingTheScreen() = runBlocking {
        val f = Fixture(); val reply = CompletableDeferred<Mango9LoginResponse>()
        val attempt = f.gate.begin()
        val old = async(start = CoroutineStart.UNDISPATCHED) {
            f.flow.authenticate(true, attempt) { reply.await() }
        }
        attempt.cancel(); reply.complete(f.response)
        assertTrue(runCatching { old.await() }.exceptionOrNull() is CancellationException)
        assertEquals(0, f.fetches); assertEquals(0, f.commits); assertNull(f.remembered)
    }

    @Test fun newerLoginWinsEvenWhenCancelledTransportIgnoresCancellation() = runBlocking {
        val f = Fixture(); val reply = CompletableDeferred<Mango9LoginResponse>()
        val first = f.gate.begin()
        val old = async(start = CoroutineStart.UNDISPATCHED) {
            f.flow.authenticate(true, first) { withContext(NonCancellable) { reply.await() } }
        }
        old.cancel()
        val next = f.gate.begin()
        val result = f.flow.authenticate(false, next) { f.response }
        first.cancel() // late disposal must not cancel next
        reply.complete(f.response)
        assertTrue(runCatching { old.await() }.exceptionOrNull() is CancellationException)
        assertTrue(next.isCurrent())
        assertEquals(f.enrollment.identity, result.lease.session.sipIdentity)
        assertEquals(1, f.commits); assertEquals(false, f.remembered)
    }

    @Test fun delayedEnrollmentCannotCommitAfterCancellation() = runBlocking {
        val f = Fixture(); val reply = CompletableDeferred<Mango9SipEnrollment>()
        f.fetch = { reply.await() }
        val attempt = f.gate.begin()
        val old = async(start = CoroutineStart.UNDISPATCHED) {
            f.flow.authenticate(true, attempt) { f.response }
        }
        attempt.cancel(); reply.complete(f.enrollment)
        assertTrue(runCatching { old.await() }.exceptionOrNull() is CancellationException)
        assertEquals(1, f.fetches); assertEquals(0, f.commits)
    }

    @Test fun aSelectionChangedDuringAuthenticationCannotBeOverwritten() = runBlocking {
        val f = Fixture()
        val result = runCatching {
            f.flow.authenticate(true, f.gate.begin()) { f.selection += 2; f.response }
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(2L, f.selection); assertEquals(0, f.commits)
    }

    @Test fun invalidEnrollmentDoesNotChangeRememberPreferenceOrStorePartialCredentials() = runBlocking {
        val f = Fixture(); f.fetch = { throw Mango9ApiException.InvalidResponse }
        val result = runCatching { f.flow.authenticate(false, f.gate.begin()) { f.response } }
        assertTrue(result.exceptionOrNull() is Mango9ApiException.InvalidResponse)
        assertEquals(0, f.commits); assertNull(f.stored); assertNull(f.remembered)
    }

    @Test fun completedOwnerCannotRunAnotherSideEffect() = runBlocking {
        val f = Fixture(); val attempt = f.gate.begin()
        async { f.flow.authenticate(true, attempt) { f.response } }.await()
        assertFalse(attempt.isCurrent())
        assertNull(attempt.ifCurrent { error("Completed login must not run queued work") })
        assertEquals(1, f.commits)
    }

    @Test fun cancellationOrdersAfterAnAlreadyRunningShortCommitButRejectsTheNextOne() = runBlocking {
        val gate = Mango9EnrollmentAttempts(); val attempt = gate.begin()
        val inside = CountDownLatch(1); val release = CountDownLatch(1)
        val cancelling = CountDownLatch(1)
        var commits = 0
        val commit = async(Dispatchers.Default) {
            attempt.requireCurrent {
                inside.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                commits++
            }
        }
        try {
            assertTrue(inside.await(3, TimeUnit.SECONDS))
            val cancelled = async(Dispatchers.Default) { cancelling.countDown(); attempt.cancel() }
            assertTrue(cancelling.await(3, TimeUnit.SECONDS))
            assertFalse(cancelled.isCompleted)
            release.countDown(); commit.await(); cancelled.await()
            assertNull(attempt.ifCurrent { commits++ })
            assertEquals(1, commits)
        } finally {
            release.countDown()
        }
    }
}
