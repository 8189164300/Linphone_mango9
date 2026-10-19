/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.core

import org.junit.Assert.*
import org.junit.Test

class CoreServiceSubscriptionTest {
    @Test fun repeatedStartsAttachOnceAndRepeatedStopsDetachOnce() {
        val queue = ArrayDeque<(Any) -> Unit>(); val core = Any()
        var adds = 0; var removes = 0
        val subscription = CoreServiceSubscription<Any>(queue::add, { adds++ }, { removes++ })
        subscription.start(); subscription.start()
        assertEquals(0, adds)
        while (queue.isNotEmpty()) queue.removeFirst()(core)
        assertEquals(1, adds)
        subscription.close(); subscription.close(); subscription.start()
        assertTrue(subscription.isClosed); assertEquals(0, removes)
        while (queue.isNotEmpty()) queue.removeFirst()(core)
        assertEquals(1, removes); assertEquals(1, adds)
    }

    @Test fun stopBeforeCoreReadinessNeverAttachesOrDereferencesTheCore() {
        val queue = ArrayDeque<(Any) -> Unit>()
        val subscription = CoreServiceSubscription<Any>(queue::add, { fail("Late attach") }, { fail("Never attached") })
        subscription.start(); subscription.close()
        while (queue.isNotEmpty()) queue.removeFirst()(Any())
        assertTrue(subscription.isClosed)
    }

    @Test fun replacementDetachesTheExactOriginallyObservedCore() {
        val queue = ArrayDeque<(Any) -> Unit>(); val first = Any(); val next = Any()
        val adds = mutableListOf<Any>(); val removes = mutableListOf<Any>()
        val subscription = CoreServiceSubscription<Any>(queue::add, { adds.add(it) }, { removes.add(it) })
        subscription.start(); queue.removeFirst()(first)
        subscription.start(); queue.removeFirst()(next)
        subscription.close(); queue.removeFirst()(Any())
        assertEquals(listOf(first, next), adds); assertEquals(listOf(first, next), removes)
    }

    @Test fun destructionDuringAttachStillQueuesExactlyOneDetach() {
        val queue = ArrayDeque<(Any) -> Unit>(); val core = Any(); var removes = 0
        lateinit var subscription: CoreServiceSubscription<Any>
        subscription = CoreServiceSubscription(queue::add, { subscription.close() }, { assertSame(core, it); removes++ })
        subscription.start(); queue.removeFirst()(core)
        assertTrue(subscription.isClosed)
        queue.removeFirst()(core)
        assertEquals(1, removes); assertTrue(queue.isEmpty())
    }
}
