/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.telecom

import org.junit.Assert.*
import org.junit.Test

class TelecomCallRegistryTest {
    @Test fun aLateSipCallIdCannotEnrollTheSameNativeCallTwice() {
        val calls = TelecomCallRegistry<String>()
        val first = calls.claim(100L, "no SIP Call-ID yet")!!
        assertNull(calls.claim(100L, "now has a SIP Call-ID"))
        assertSame(first, calls[100L])
    }

    @Test fun twoCallsToTheSamePeerRemainIndependent() {
        val calls = TelecomCallRegistry<String>()
        val one = calls.claim(100L, "same-peer")!!
        val two = calls.claim(101L, "same-peer")!!
        calls.release(one)
        assertTrue(calls.isCurrent(two))
        assertFalse(calls.isCurrent(one))
    }

    @Test fun anOldFinallyCannotRemoveANewerCallAtTheSameNativeAddress() {
        val calls = TelecomCallRegistry<String>()
        val old = calls.claim(100L, "old")!!
        calls.release(old)
        val current = calls.claim(100L, "new")!!
        calls.release(old)
        assertSame(current, calls[100L])
    }

    @Test fun stoppingTheCoreInvalidatesOwnersBeforeClosingTheirControls() {
        val calls = TelecomCallRegistry<String>()
        val first = calls.claim(100L, "first")!!
        val second = calls.claim(101L, "second")!!
        assertEquals(listOf(first, second), calls.clear())
        assertFalse(calls.isCurrent(first))
        assertFalse(calls.isCurrent(second))
        assertTrue(calls.clear().isEmpty())
    }

    @Test fun aFailedRegistrationCanBeRetriedWithoutPoisoningAnotherCall() {
        val calls = TelecomCallRegistry<String>()
        val failed = calls.claim(100L, "failed")!!
        val other = calls.claim(200L, "other")!!
        calls.release(failed)
        assertNotNull(calls.claim(100L, "retry"))
        assertTrue(calls.isCurrent(other))
    }

    @Test fun anInvalidNativeCallIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { TelecomCallRegistry<String>().claim(0, "invalid") }
    }
}
