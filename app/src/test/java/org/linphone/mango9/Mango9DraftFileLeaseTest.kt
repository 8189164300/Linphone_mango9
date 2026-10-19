// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class Mango9DraftFileLeaseTest {
    @Test fun removedDraftKeepsFileUntilQueuedSendReleasesIt() {
        var deletes = 0
        val draft = Mango9DraftFileLease { deletes++ }
        val pending = draft.retain()
        draft.close()
        draft.close()
        assertEquals(0, deletes)
        pending.close()
        pending.close()
        assertEquals(1, deletes)
    }

    @Test fun sdkOwnershipOutlivesDraftAndPendingSend() {
        var deletes = 0
        val draft = Mango9DraftFileLease { deletes++ }
        val pending = draft.retain()
        draft.close()
        pending.keepForHistory()
        pending.close()
        assertEquals(0, deletes)
    }

    @Test fun failedHandoffKeepsCurrentDraftButReleasesRemovedDraft() {
        var deletes = 0
        val draft = Mango9DraftFileLease { deletes++ }
        draft.retain().close()
        assertEquals(0, deletes)
        draft.close()
        assertEquals(1, deletes)
    }

    @Test fun concurrentReleaseDeletesExactlyOnce() {
        val deletes = AtomicInteger()
        val draft = Mango9DraftFileLease { deletes.incrementAndGet() }
        val pins = List(32) { draft.retain() }
        val start = CountDownLatch(1)
        val threads = pins.map { pin -> Thread { start.await(); pin.close(); pin.close() }.apply { start() } }
        draft.close()
        start.countDown()
        threads.forEach { it.join(5000); assertFalse(it.isAlive) }
        assertEquals(1, deletes.get())
    }

    @Test fun releasedHandleCannotBeResurrected() {
        val draft = Mango9DraftFileLease {}
        draft.close()
        assertThrows(IllegalStateException::class.java) { draft.retain() }
        assertThrows(IllegalStateException::class.java) { draft.keepForHistory() }
    }
}
