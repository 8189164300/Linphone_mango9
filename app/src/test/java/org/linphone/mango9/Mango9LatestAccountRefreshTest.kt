// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class Mango9LatestAccountRefreshTest {
    @Test(timeout = 5000)
    fun directoryDoesNotDelayConnectionAndDuplicateRequestsCoalesce() = runBlocking {
        val directoryStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val connected = CompletableDeferred<Unit>()
        var loads = 0
        val refresher = Mango9LatestAccountRefresh<String>(
            this,
            { loads++; directoryStarted.complete(Unit); release.await() },
            { connected.complete(Unit) }
        )
        refresher.refresh("a")
        directoryStarted.await(); connected.await()
        refresher.refresh("a")
        assertEquals(1, loads)
        release.complete(Unit)
        Unit
    }

    @Test(timeout = 5000)
    fun replacementCancelsOldWorkWithoutWaitingForIt() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val canceled = CompletableDeferred<Unit>()
        val connected = CompletableDeferred<Unit>()
        val refresher = Mango9LatestAccountRefresh<String>(
            this,
            { if (it == "a") try { started.complete(Unit); awaitCancellation() } finally { canceled.complete(Unit) } },
            { if (it == "b") connected.complete(Unit) }
        )
        refresher.refresh("a"); started.await(); refresher.refresh("b")
        connected.await(); canceled.await()
    }

    @Test(timeout = 5000)
    fun removalCancelsDirectoryAndConnectionAndAllowsLaterLogin() = runBlocking {
        val started = CompletableDeferred<Unit>(); val canceled = CompletableDeferred<Unit>()
        val refreshed = CompletableDeferred<Unit>()
        val refresher = Mango9LatestAccountRefresh<String>(
            this,
            { if (it == "a") try { started.complete(Unit); awaitCancellation() } finally { canceled.complete(Unit) } },
            { if (it == "b") refreshed.complete(Unit) }
        )
        refresher.refresh("a"); started.await(); refresher.cancel(); canceled.await()
        refresher.refresh("b"); refreshed.await()
    }

    @Test(timeout = 5000)
    fun finishedRefreshCanRunAgain() = runBlocking {
        var loads = 0
        val refresher = Mango9LatestAccountRefresh<String>(this, { loads++ }, {})
        refresher.refresh("a")
        coroutineContext[Job]!!.children.toList().forEach { it.join() }
        refresher.refresh("a")
        coroutineContext[Job]!!.children.toList().forEach { it.join() }
        assertEquals(2, loads)
    }
}
