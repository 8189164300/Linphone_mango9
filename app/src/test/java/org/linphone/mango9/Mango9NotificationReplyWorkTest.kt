// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Mango9NotificationReplyWorkTest {
    @Test(timeout = 5000)
    fun deadlineFinishesOnceWithoutReplayingAnUncertainSend() = runBlocking {
        var attempts = 0; var finished = 0
        val states = mutableListOf<Mango9SmsReplyState>()
        runMango9NotificationReply(50, { finished++ }, { states += it }, { throw AssertionError(it) }) {
            attempts++; awaitCancellation()
        }
        assertEquals(1, attempts); assertEquals(1, finished)
        assertEquals(listOf(Mango9SmsReplyState.CheckDelivery), states)
    }

    @Test(timeout = 5000)
    fun cancellationFinishesWithoutAnIncorrectFailureNotification() = runBlocking {
        val started = CompletableDeferred<Unit>(); var finished = 0
        val task = async {
            runMango9NotificationReply(finish = {
                finished++
            }, publish = { throw AssertionError(it) }, onFailure = { throw AssertionError(it) }) {
                started.complete(Unit); awaitCancellation()
            }
        }
        started.await(); task.cancelAndJoin()
        assertTrue(task.isCancelled); assertEquals(1, finished)
    }

    @Test fun successFailureAndRejectedOwnershipAlwaysFinishExactlyOnce() = runBlocking {
        for (result in listOf(true, false, null)) {
            var finished = 0; val states = mutableListOf<Mango9SmsReplyState>()
            runMango9NotificationReply(finish = {
                finished++
            }, publish = { states += it }, onFailure = { throw AssertionError(it) }) { result }
            assertEquals(1, finished)
            assertEquals(when (result) { true -> listOf(Mango9SmsReplyState.Sent); false -> listOf(Mango9SmsReplyState.Failed); null -> emptyList() }, states)
        }
    }

    @Test fun failuresIncludingNotificationPublicationStillFinish() = runBlocking {
        var finished = 0; var reported = 0
        val result = runCatching {
            runMango9NotificationReply(finish = {
                finished++
            }, publish = { error("fixture publication failure") }, onFailure = { reported++ }) {
                error("fixture send failure")
            }
        }
        assertTrue(result.isSuccess); assertEquals(2, reported); assertEquals(1, finished)
    }
}
