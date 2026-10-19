/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.core

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class CoreTaskDispatcherTest {
    @Test fun earlyActionsWaitForReadinessAndKeepFifoOrdering() {
        val dispatcher = CoreTaskDispatcher { 100 }
        val ran = mutableListOf<Int>()
        val queued = mutableListOf<Pair<Runnable, Long>>()
        dispatcher.post { ran.add(1) }
        dispatcher.post { ran.add(2) }
        assertTrue(ran.isEmpty())
        dispatcher.start { task, time -> queued.add(task to time) }
        dispatcher.post { ran.add(3) }
        assertEquals(listOf(100L, 100L, 100L), queued.map { it.second })
        queued.forEach { it.first.run() }
        assertEquals(listOf(1, 2, 3), ran)
    }

    @Test fun delayIsMeasuredFromSubmissionNotFromCoreReadiness() {
        var now = 100L
        val dispatcher = CoreTaskDispatcher { now }
        val due = mutableListOf<Long>()
        dispatcher.post(1_000) { }
        now = 900
        dispatcher.start { _, at -> due.add(at) }
        dispatcher.post(1_000) { }
        assertEquals(listOf(1_100L, 1_900L), due)
    }

    @Test fun cancellationBeforeReadinessRemovesTheAction() {
        val dispatcher = CoreTaskDispatcher { 0 }
        var ran = false
        val ticket = dispatcher.post { ran = true }
        ticket.cancel()
        var scheduled = 0
        dispatcher.start { _, _ -> scheduled++; true }
        ticket.run()
        assertEquals(0, scheduled)
        assertFalse(ran)
    }

    @Test fun cancellationAfterSchedulingPreventsLateAction() {
        val dispatcher = CoreTaskDispatcher { 0 }
        val queued = mutableListOf<Runnable>()
        dispatcher.start { task, _ -> queued.add(task) }
        var ran = false
        dispatcher.post { ran = true }.cancel()
        queued.single().run()
        assertFalse(ran)
    }

    @Test fun duplicateDeliveryAndReentrantPostingRunEachActionOnce() {
        val dispatcher = CoreTaskDispatcher { 0 }
        val queued = mutableListOf<Runnable>()
        dispatcher.start { task, _ -> queued.add(task) }
        val ran = mutableListOf<Int>()
        dispatcher.post { ran.add(1); dispatcher.post { ran.add(2) } }
        queued[0].run(); queued[0].run()
        queued[1].run(); queued[1].run()
        assertEquals(listOf(1, 2), ran)
    }

    @Test fun rejectedLooperDoesNotRetainOrExecuteAnAction() {
        val dispatcher = CoreTaskDispatcher { 0 }
        var ran = false
        val early = dispatcher.post { ran = true }
        dispatcher.start { _, _ -> false }
        val late = dispatcher.post { ran = true }
        early.run(); late.run()
        assertFalse(ran)
    }

    @Test fun concurrentStartupAndPostsDoNotLoseOrDuplicateWork() {
        val dispatcher = CoreTaskDispatcher { 1 }
        val queued = Collections.synchronizedList(mutableListOf<Runnable>())
        val pool = Executors.newFixedThreadPool(4)
        val ready = CountDownLatch(1)
        val submitted = CountDownLatch(101)
        val ran = mutableSetOf<Int>()
        try {
            repeat(100) { id ->
                pool.execute { ready.await(); dispatcher.post { assertTrue(ran.add(id)) }; submitted.countDown() }
                if (id == 40) {
                    pool.execute {
                    ready.await()
                    dispatcher.start { task, _ -> queued.add(task) }
                    submitted.countDown()
                }
                }
            }
            ready.countDown()
            assertTrue(submitted.await(5, TimeUnit.SECONDS))
            queued.forEach { it.run() }
            assertEquals((0 until 100).toSet(), ran)
        } finally { pool.shutdownNow() }
    }

    @Test fun startingTwiceFailsWithoutReplayingPendingTasks() {
        val dispatcher = CoreTaskDispatcher { 0 }
        dispatcher.start { _, _ -> true }
        assertThrows(IllegalStateException::class.java) { dispatcher.start { _, _ -> true } }
    }
}
