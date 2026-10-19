/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.notifications

import org.junit.Assert.*
import org.junit.Test

class NotificationActionLifetimeTest {
    @Test fun finishesOnlyOnceAfterActionAndIgnoresDuplicateDelivery() {
        var finished = 0; var actions = 0
        val lifetime = NotificationActionLifetime({ 100 }, 8_000) { finished++ }
        lifetime.run { actions++ }
        lifetime.run { actions++ }
        lifetime.finish()
        assertEquals(1, actions); assertEquals(1, finished)
    }

    @Test fun timeoutBeforeCoreReadinessPreventsAnyLateEffect() {
        var finished = 0
        val lifetime = NotificationActionLifetime({ 0 }, 8_000) { finished++ }
        lifetime.finish()
        lifetime.run { fail("Expired notification must not act on a later call or chat") }
        assertEquals(1, finished)
    }

    @Test fun delayedMainThreadTimerCannotLetAnExpiredActionRun() {
        var now = 100L; var finished = 0
        val lifetime = NotificationActionLifetime({ now }, 8_000) { finished++ }
        now = 8_100
        lifetime.run { fail("Elapsed deadline must be checked on the worker as well") }
        assertEquals(1, finished)
    }

    @Test fun exceptionStillReleasesBroadcastOwnership() {
        var finished = 0
        val lifetime = NotificationActionLifetime({ 0 }, 8_000) { finished++ }
        assertThrows(IllegalStateException::class.java) { lifetime.run { error("fixture") } }
        lifetime.finish()
        assertEquals(1, finished)
    }

    @Test fun timeoutDuringExecutionDoesNotDoubleFinishOrReplay() {
        var finished = 0; var actions = 0
        val lifetime = NotificationActionLifetime({ 0 }, 8_000) { finished++ }
        lifetime.run { actions++; lifetime.finish() }
        lifetime.run { actions++ }
        assertEquals(1, actions); assertEquals(1, finished)
    }
}
