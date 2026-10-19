/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class Mango9PushOperationQueueTest {
    @Test fun wireOrderIsOldPostThenDeleteThenNewPostWithoutBlockingOtherAccounts() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val queue = Mango9PushOperationQueue<String>(scope)
            val oldStarted = CompletableDeferred<Unit>()
            val finishOld = CompletableDeferred<Unit>()
            val deleteStarted = CompletableDeferred<Unit>()
            val finishDelete = CompletableDeferred<Unit>()
            val order = mutableListOf<String>()
            val old = queue.enqueue("a") {
                order += "old-post"; oldStarted.complete(Unit); finishOld.await()
            }
            oldStarted.await()
            val removed = queue.enqueue("a") {
                order += "delete"; deleteStarted.complete(Unit); finishDelete.await()
            }
            val replacement = queue.enqueue("a") { order += "new-post" }
            withTimeout(2_000) { queue.enqueue("b") { "other-account" }.await() }
            assertEquals(listOf("old-post"), order)
            finishOld.complete(Unit)
            deleteStarted.await()
            assertEquals(listOf("old-post", "delete"), order)
            finishDelete.complete(Unit)
            old.await(); removed.await(); replacement.await()
            assertEquals(listOf("old-post", "delete", "new-post"), order)
        } finally { scope.cancel() }
    }

    @Test fun cancellingAWaitingScreenCannotSkipItsReservedCleanup() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val queue = Mango9PushOperationQueue<String>(scope)
            val gate = CompletableDeferred<Unit>()
            val order = mutableListOf<String>()
            queue.enqueue("a") { gate.await(); order += "old-post" }
            val deletion = queue.enqueue("a") { order += "delete" }
            val screen = async { deletion.await() }
            yield()
            screen.cancelAndJoin()
            val registration = queue.enqueue("a") { order += "new-post" }
            assertTrue(order.isEmpty())
            gate.complete(Unit)
            registration.await()
            assertEquals(listOf("old-post", "delete", "new-post"), order)
        } finally { scope.cancel() }
    }

    @Test fun failedCleanupReleasesQueueAndDoesNotCancelOtherAccountsOrNewLogin() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val queue = Mango9PushOperationQueue<String>(scope)
            val failure = queue.enqueue("a") { error("synthetic unavailable cleanup") }
            val newLogin = queue.enqueue("a") { "registered" }
            val otherAccount = queue.enqueue("b") { "other account" }
            assertTrue(runCatching { failure.await() }.isFailure)
            assertEquals("registered", newLogin.await())
            assertEquals("other account", otherAccount.await())
        } finally { scope.cancel() }
    }

    @Test fun queuedRegistrationCannotUseALogoutLeaseAfterIdenticalCredentialsAreSavedAgain() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val queue = Mango9PushOperationQueue<String>(scope)
            val versions = Mango9SessionGenerations()
            val session = Mango9CalendarTestFixtures.session()
            val identity = session.sipIdentity!!
            val old = versions.captureLogin(session)
            val gate = CompletableDeferred<Unit>()
            queue.enqueue(identity) { gate.await() }
            var oldRequests = 0
            val oldPost = queue.enqueue(identity) {
                if (versions.matchesLogin(old)) oldRequests++
            }
            versions.loginChanged(identity) // Logout.
            versions.loginChanged(identity) // Identical-identity relogin is a new login.
            val fresh = versions.captureLogin(session)
            versions.selectionChanged() // Removing a non-selected account must still be possible.
            assertTrue(versions.matchesLogin(fresh))
            gate.complete(Unit)
            oldPost.await()
            assertEquals(0, oldRequests)
        } finally { scope.cancel() }
    }
}
