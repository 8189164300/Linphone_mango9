/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import org.junit.Assert.*
import org.junit.Test

class Mango9PendingCallTest {
    @Test fun aNewManagerDoesNotReuseARestoredActivityTicket() {
        val old = Mango9PendingCall<String>().begin("old")!!
        val fresh = Mango9PendingCall<String>()
        val current = fresh.begin("new")!!
        assertNotEquals(0L, current)
        assertNotEquals(old, current)
        assertNull(fresh.take(old))
        assertEquals("new", fresh.take(current))
    }

    @Test fun repeatedTapsKeepOnlyTheFirstIntent() {
        val gate = Mango9PendingCall<String>()
        val first = gate.begin("original")!!
        assertNull(gate.begin("second"))
        assertEquals("original", gate.take(first))
        assertNull(gate.take(first))
    }

    @Test fun oldCallbacksCannotConsumeOrCancelANewerIntent() {
        val gate = Mango9PendingCall<String>()
        val old = gate.begin("old")!!
        assertEquals("old", gate.take(old))
        val current = gate.begin("new")!!
        assertNull(gate.take(old))
        assertTrue(gate.matches(current))
        assertEquals("new", gate.take(current))
    }

    @Test fun shutdownDropsPendingIntentAndLatePermissionResults() {
        val gate = Mango9PendingCall<String>()
        val id = gate.begin("call")!!
        assertEquals("call", gate.clear())
        assertNull(gate.take(id))
        assertFalse(gate.hasPending())
    }

    @Test fun wrongTicketLeavesOriginalUntouched() {
        val gate = Mango9PendingCall<String>()
        val id = gate.begin("call")!!
        assertNull(gate.take(id + 1))
        assertTrue(gate.matches(id))
        assertEquals("call", gate.take(id))
    }
}
