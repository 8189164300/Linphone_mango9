/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.notifications

import org.junit.Assert.*
import org.junit.Test

class CallNotificationRegistryTest {
    private fun registry() = CallNotificationRegistry { id, token -> "$id:$token" }

    @Test fun updatingTheSameLiveCallKeepsItsNotificationAndTarget() {
        var creations = 0
        val registry = CallNotificationRegistry { _: Int, _: String -> ++creations }
        val first = registry.getOrCreate(42)
        repeat(50) { assertSame(first, registry.getOrCreate(42)) }
        assertEquals(1, creations)
    }

    @Test fun concurrentCallsHaveDistinctTargetsEvenWithCollidingLongHashes() {
        val registry = registry()
        val first = registry.getOrCreate(1)
        val second = registry.getOrCreate(1L shl 32)
        assertEquals(first.callKey.hashCode(), second.callKey.hashCode())
        assertNotEquals(first.token, second.token)
        assertNotEquals(first.notificationId, second.notificationId)
        assertEquals(1L, registry.find(first.token)?.callKey)
        assertEquals(1L shl 32, registry.find(second.token)?.callKey)
    }

    @Test fun endedCallCannotTargetANewCallReusingItsNativePointer() {
        val registry = registry()
        val old = registry.getOrCreate(42)
        registry.remove(42)
        val replacement = registry.getOrCreate(42)
        assertNull(registry.find(old.token))
        assertNotEquals(old.token, replacement.token)
        assertNotEquals(old.notificationId, replacement.notificationId)
        assertSame(replacement, registry.find(replacement.token))
    }

    @Test fun removingOneCallDoesNotInvalidateTheOther() {
        val registry = registry()
        val first = registry.getOrCreate(42)
        val second = registry.getOrCreate(43)
        assertSame(first, registry.remove(42))
        assertNull(registry.find(first.token))
        assertSame(second, registry.find(second.token))
        assertEquals(listOf(second), registry.entries)
    }

    @Test fun stoppedCoreInvalidatesAllPreviousTargets() {
        val registry = registry()
        val old = registry.getOrCreate(42)
        registry.getOrCreate(43)
        registry.clear()
        assertTrue(registry.entries.isEmpty())
        registry.getOrCreate(42)
        assertNull(registry.find(old.token))
    }

    @Test fun processRestartCannotResolveAPreviousProcessToken() {
        val old = registry().getOrCreate(42)
        val restarted = registry()
        restarted.getOrCreate(42)
        assertNull(restarted.find(old.token))
    }

    @Test fun missingOrLegacyAddressTargetsNeverPickAnArbitraryCall() {
        val registry = registry()
        registry.getOrCreate(42)
        for (token in listOf(null, "", " ", "sip:caller@example.invalid", "42")) {
            assertNull(registry.find(token))
        }
    }

    @Test fun snapshotIterationSurvivesRemovalsWithoutRetainingEntries() {
        val registry = registry()
        registry.getOrCreate(42)
        registry.getOrCreate(43)
        registry.entries.forEach { registry.remove(it.callKey) }
        assertTrue(registry.entries.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { registry.getOrCreate(0) }
    }
}
