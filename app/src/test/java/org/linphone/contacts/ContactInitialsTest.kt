/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.contacts

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactInitialsTest {
    @Test fun namesUseLettersAndAllWhitespaceSeparators() {
        assertEquals("ML", initials("  Maya  Lane "))
        assertEquals("ML", initials("Maya\tLane"))
        assertEquals("ML", initials("Maya\nLane"))
        assertEquals("ML", initials("Maya\u00a0Lane"))
        assertEquals("AM", initials("👩🏽‍💻Ana ❤️Maria"))
        assertEquals("AB", initials("12Alice (Bob) Carol"))
        assertEquals("AT", initials("A. Team"))
    }

    @Test fun numbersEmptyAndEmojiOnlyUsePersonFallback() {
        for (name in listOf("", "   ", "700", "+1 (202) 555-0142", "👩🏽‍💻 ❤️", "---")) {
            assertEquals(name, "", initials(name))
        }
    }

    @Test fun schemesAndDomainsDoNotBecomeContactInitials() {
        for (name in listOf("sip:700@example.invalid", "SIPS:+12025550142@example.invalid;transport=tls", "tel:+12025550142;ext=100", "700@example.invalid")) {
            assertEquals(name, "", initials(name))
        }
        assertEquals("A", initials("SIP:alice@example.invalid"))
        assertEquals("A", initials("alice@example.invalid"))
    }

    @Test fun unicodeLettersAndAccentsAreNotSplit() {
        assertEquals("ԱՊ", initials("Արամ Պետրոսյան"))
        assertEquals("李王", initials("李 王"))
        assertEquals("E\u0301Z", initials("e\u0301mile Zoë"))
        assertEquals("\uD801\uDC00A", initials("\uD801\uDC28name Alice"))
        assertEquals("İI", ContactInitials.fromName("ipek ışık", locale = Locale.forLanguageTag("tr")))
    }

    @Test fun malformedAndLongNamesHaveBoundedWorkAndOutput() {
        assertEquals("", initials("\uD800 \uDC00"))
        assertEquals("", initials("9".repeat(100_000) + " Alice"))
        assertTrue(initials("A" + "\u0301".repeat(100_000)).length <= 8)
        assertEquals("A", ContactInitials.fromName("Alice Bob", 1, Locale.US))
        assertEquals("", ContactInitials.fromName("Alice Bob", 0, Locale.US))
        assertEquals("AB", ContactInitials.fromName("Alice Bob Carol", Int.MAX_VALUE, Locale.US))
    }

    private fun initials(name: String) = ContactInitials.fromName(name, locale = Locale.US)
}
