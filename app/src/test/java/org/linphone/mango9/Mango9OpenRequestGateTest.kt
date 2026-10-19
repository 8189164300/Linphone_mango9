/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import org.junit.Assert.*
import org.junit.Test

class Mango9OpenRequestGateTest {
    @Test fun onlyLatestTapCanSelectOrNavigateRegardlessOfCompletionOrder() {
        val gate = Mango9OpenRequestGate()
        val old = gate.begin()
        val latest = gate.begin()
        val actions = mutableListOf<String>()
        assertNull(old.ifCurrent { actions += "old-selection" })
        latest.ifCurrent { actions += "latest-selection" }
        latest.ifCurrent { actions += "latest-navigation" }
        assertNull(old.ifCurrent { actions += "old-navigation" })
        assertEquals(listOf("latest-selection", "latest-navigation"), actions)
    }

    @Test fun destroyingActivityOrExplicitNavigationInvalidatesQueuedCallbacks() {
        val gate = Mango9OpenRequestGate()
        val old = gate.begin()
        gate.cancel()
        assertNull(old.ifCurrent { fail("Canceled callback executed") })
        val fresh = gate.begin()
        assertEquals("new", fresh.ifCurrent { "new" })
        assertNull(old.ifCurrent { "stale" })
    }
}
