/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.mango9

import org.junit.Assert.assertEquals
import org.junit.Test

class Mango9SipDisplayTest {
    @Test
    fun suggestionsAndContactRowsPreferTheSipUsername() {
        assertEquals(
            "8189164300",
            Mango9SipDisplay.friendlyUsername(
                "8189164300",
                "sip:8189164300@manushak.mango9.com",
            ),
        )
        assertEquals(
            "109",
            Mango9SipDisplay.friendlyUsername(" 109 ", "sip:109@manushak.mango9.com"),
        )
    }

    @Test
    fun missingUsernameStillHidesTheSipSchemeAndDomain() {
        assertEquals(
            "anonymous",
            Mango9SipDisplay.friendlyUsername(null, "SIP:anonymous@manushak.mango9.com"),
        )
        assertEquals(
            "109",
            Mango9SipDisplay.friendlyUsername(null, "sip:109@manushak.mango9.com;transport=tls"),
        )
    }
}
