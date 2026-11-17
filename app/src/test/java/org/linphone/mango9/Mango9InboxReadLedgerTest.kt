package org.linphone.mango9
import org.junit.Test
import org.junit.Assert.*

class Mango9InboxReadLedgerTest {
    @Test fun acknowledgedReadsRejectStaleSnapshotsButKeepNewArrivals() {
        val reads = Mango9InboxReadLedger()
        reads.read("chat", "social", 100)
        assertFalse(reads.unread(true, "chat", "social", 99))
        assertFalse(reads.unread(true, "chat", "social", 100))
        assertTrue(reads.unread(true, "chat", "social", 101))
        reads.read("chat", "social", 90)
        assertFalse(reads.unread(true, "chat", "social", 100))
        assertTrue(reads.unread(true, "chat", "live", 100))
        assertTrue(reads.unread(true, "other", "social", 100))
        assertTrue(reads.unread(true, "chat", "social", null))
        assertFalse(reads.unread(false, "chat", "social", 101))
        assertTrue(Mango9InboxReadLedger().unread(true, "chat", "social", 100))
    }

}
