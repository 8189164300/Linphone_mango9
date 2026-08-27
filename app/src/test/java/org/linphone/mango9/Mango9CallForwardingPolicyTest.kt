/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.mango9

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Mango9CallForwardingPolicyTest {
    @Test
    fun enablingRequiresARealNumber() {
        assertFalse(Mango9CallForwardingPolicy.isValidDestination(""))
        assertFalse(Mango9CallForwardingPolicy.isValidDestination(" + () - "))
        assertFalse(Mango9CallForwardingPolicy.isValidDestination("818-CALL-NOW"))
        assertTrue(Mango9CallForwardingPolicy.isValidDestination("9"))
        assertTrue(Mango9CallForwardingPolicy.isValidDestination("12"))
        assertTrue(Mango9CallForwardingPolicy.isValidDestination("700"))
        assertTrue(Mango9CallForwardingPolicy.isValidDestination("+1 (818) 916-4300"))
    }

    @Test
    fun apiValidationRejectsEnabledWithoutDestination() {
        assertThrows(Mango9ApiException.InvalidForwardingDestination::class.java) {
            Mango9CallForwardingPolicy.validatedDestination(true, "   ")
        }
        assertEquals("", Mango9CallForwardingPolicy.validatedDestination(false, "   "))
        assertEquals("700", Mango9CallForwardingPolicy.validatedDestination(true, " 700 "))
        assertThrows(Mango9ApiException.InvalidForwardingDestination::class.java) {
            Mango9CallForwardingPolicy.validatedDestination(false, "not-a-number")
        }
    }
}
