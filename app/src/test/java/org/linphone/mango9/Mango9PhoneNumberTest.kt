/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.mango9

import org.junit.Assert.assertEquals
import org.junit.Test

class Mango9PhoneNumberTest {
    @Test
    fun manualAndSelectedContactValuesResolveToTheSameDestination() {
        val expected = "18189164300"

        assertEquals(expected, Mango9PhoneNumber.normalized("8189164300"))
        assertEquals(expected, Mango9PhoneNumber.normalized("+1 (818) 916-4300"))
        assertEquals(expected, Mango9PhoneNumber.normalized("sip:8189164300@manushak.mango9.com"))
        assertEquals(
            expected,
            Mango9PhoneNumber.normalized("George <sip:+18189164300@manushak.mango9.com;user=phone>"),
        )
        assertEquals(expected, Mango9PhoneNumber.normalized("tel:+1-818-916-4300;ext=12"))
    }
}
