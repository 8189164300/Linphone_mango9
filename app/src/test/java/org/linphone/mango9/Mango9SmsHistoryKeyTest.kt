/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import org.junit.Assert.*
import org.junit.Test

class Mango9SmsHistoryKeyTest {
    private val session = Mango9Session(
        "fixture", "https://crm.example.invalid", "https://crm.example.invalid/api/v2", "fixture", "fixture", "agent",
        "fixture@example.invalid", "Fixture", "fixture-access", "fixture-refresh", "", "", 1_900_000_000,
        "sip:fixture@crm.example.invalid",
    )

    @Test fun phoneFormattingAndAccountReselectionReuseOnlyTheSameLoginCache() {
        val generations = Mango9SessionGenerations()
        val first = Mango9SmsHistoryKey.from(generations.capture(session), "+1 (202) 555-0123")
        generations.selectionChanged()
        generations.selectionChanged()
        val returned = Mango9SmsHistoryKey.from(generations.capture(session), "2025550123")
        assertEquals(first, returned)
    }

    @Test fun identicalCredentialsOnReplacementLoginCannotReadOldPhoneHistory() {
        val generations = Mango9SessionGenerations()
        val old = Mango9SmsHistoryKey.from(generations.capture(session), "12025550123")
        val cache = mapOf(old to "old account messages")
        generations.loginChanged(session.sipIdentity!!)
        val replacement = Mango9SmsHistoryKey.from(generations.capture(session), "12025550123")
        assertNotEquals(old, replacement)
        assertNull(cache[replacement])
    }

    @Test fun samePhoneOnTwoAccountsNeverSharesHistory() {
        val generations = Mango9SessionGenerations()
        val first = Mango9SmsHistoryKey.from(generations.capture(session), "12025550123")
        val second = Mango9SmsHistoryKey.from(
            generations.capture(session.copy(sipIdentity = "sip:other@crm.example.invalid")), "12025550123",
        )
        assertNotEquals(first, second)
    }
}
