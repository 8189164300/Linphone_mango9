// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class Mango9NativeFileLeaseTest {
    @Test fun callerCanCloseBeforeViewerAndDecoder() {
        var removed = 0
        val caller = Mango9NativeFileLease { removed++ }
        val viewer = caller.retain()
        val decoder = viewer.retain()
        caller.close(); viewer.close(); viewer.close()
        assertEquals(0, removed)
        decoder.close(); decoder.close()
        assertEquals(1, removed)
    }

    @Test fun closedViewerDoesNotDeleteCallersFile() {
        var removed = 0
        val caller = Mango9NativeFileLease { removed++ }
        caller.retain().close()
        assertEquals(0, removed)
        caller.close()
        assertEquals(1, removed)
    }

    @Test fun concurrentReleaseUnlinksExactlyOnce() {
        val removed = AtomicInteger()
        val caller = Mango9NativeFileLease { removed.incrementAndGet() }
        val go = CountDownLatch(1)
        val threads = List(64) {
            val lease = caller.retain()
            Thread { go.await(); lease.close(); lease.close() }.apply { start() }
        }
        caller.close(); go.countDown()
        threads.forEach { it.join(5000); assertFalse(it.isAlive) }
        assertEquals(1, removed.get())
    }

    @Test(expected = IllegalStateException::class)
    fun closedHandleCannotAcquireANewConsumer() {
        val lease = Mango9NativeFileLease {}
        lease.close(); lease.retain()
    }
}
