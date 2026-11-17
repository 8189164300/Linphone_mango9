// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9
import org.junit.Test
import org.junit.Assert.*

class Mango9CrmUnreadTest {
    @Test fun historicalBaselineAndRepeatedSnapshotsDoNotInflateCounts() {
        val state = Mango9CrmNewRecords(); state.reconcile(setOf(1, 2)); assertTrue(state.unread.isEmpty())
        state.reconcile(setOf(1, 2, 3, 4)); state.reconcile(setOf(1, 2, 3, 4)); assertEquals(setOf(3, 4), state.unread)
    }

    @Test fun sameTotalDeletionAndArrivalStillDetectsNewRecord() {
        val state = Mango9CrmNewRecords(); state.reconcile(setOf(1, 2)); state.reconcile(setOf(2, 3))
        assertEquals(setOf(3), state.unread); state.reconcile(setOf(2, 4)); assertEquals(setOf(4), state.unread)
    }

    @Test fun readingDuringFetchWinsAndKeepsUnrelatedRecordsNew() {
        val state = Mango9CrmNewRecords(); state.reconcile(setOf(1)); state.reconcile(setOf(1, 2, 3))
        state.read(2); state.reconcile(setOf(1, 2, 3, 4)); assertEquals(setOf(3, 4), state.unread)
        state.read(3); state.read(4); state.reconcile(setOf(1, 2, 3, 4)); assertTrue(state.unread.isEmpty())
    }

    @Test fun independentAccountsAndKindsDoNotClearEachOthersBadges() {
        val first = Mango9CrmNewRecords(); first.reconcile(setOf(1)); first.reconcile(setOf(1, 2))
        val second = Mango9CrmNewRecords(); second.reconcile(setOf(1, 2)); second.read(2)
        assertTrue(second.unread.isEmpty()); assertEquals(setOf(2), first.unread)
        val restored = first.copy(); restored.reconcile(setOf(1, 2)); assertEquals(setOf(2), restored.unread)
    }

    @Test fun deletedRecordsLeaveCounterAndFreshArrivalAfterEmptyIsNew() {
        val state = Mango9CrmNewRecords(); state.reconcile(setOf(1)); state.reconcile(setOf(1, 2))
        state.reconcile(emptySet()); assertTrue(state.unread.isEmpty()); state.reconcile(setOf(3)); assertEquals(setOf(3), state.unread)
    }
}
