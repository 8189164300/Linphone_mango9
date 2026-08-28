/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.mango9

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun historySipNumberResolvesToMango9SmsTarget() {
        assertEquals(
            Mango9SmsTarget("18189164300", "Ani Paytyan"),
            Mango9SmsRoutePolicy.target("sip:8189164300@manushak.mango9.com", "Ani Paytyan"),
        )
    }

    @Test
    fun historyExtensionStaysOnSipChatPath() {
        assertNull(Mango9SmsRoutePolicy.target("sip:109@manushak.mango9.com", "Extension 109"))
        assertNull(Mango9SmsRoutePolicy.target("sip:user1234567890@manushak.mango9.com", "User"))
    }

    @Test
    fun unnamedHistoryNumberGetsReadableTitle() {
        assertEquals(
            Mango9SmsTarget("18189164300", "818-916-4300"),
            Mango9SmsRoutePolicy.target("+1 (818) 916-4300", " "),
        )
    }
}
