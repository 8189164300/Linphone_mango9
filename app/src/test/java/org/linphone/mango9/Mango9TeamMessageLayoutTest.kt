/* Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later */
package org.linphone.mango9

import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class Mango9TeamMessageLayoutTest {
    private fun message(id: String, sender: Int, time: String) =
        Mango9ChatMessage(id, sender, "room", "Message", time, 3, "")

    @Test fun dayBoundariesFollowTheReaderTimeZone() {
        val zone = ZoneId.of("America/Los_Angeles")
        assertTrue(Mango9TeamMessageLayout.sameDay("2026-10-03T01:00:00Z", "2026-10-02T23:30:00Z", zone))
        assertFalse(Mango9TeamMessageLayout.sameDay("2026-10-03T07:01:00Z", "2026-10-03T06:59:00Z", zone))
        assertTrue(Mango9TeamMessageLayout.sameDay("2026-10-03 01:00:00", "2026-10-02T23:30:00Z", zone))
        assertFalse(Mango9TeamMessageLayout.sameDay("invalid", "2026-10-02T23:30:00Z", zone))
    }

    @Test fun senderLabelsReturnAfterAnInterveningMessageOrLongPause() {
        val first = message("first", 1, "2026-10-02T18:00:00Z")
        val reply = message("reply", 2, "2026-10-02T18:01:00Z")
        val file = message("file", 1, "2026-10-02T18:02:00Z")
        assertTrue(Mango9TeamMessageLayout.startsSender(first, null))
        assertTrue(Mango9TeamMessageLayout.startsSender(file, reply))
        // Hiding/deleting the intervening reply recomputes the neighboring row.
        assertFalse(Mango9TeamMessageLayout.startsSender(file, first))
        assertTrue(Mango9TeamMessageLayout.startsSender(file.copy(time = "2026-10-02T18:06:00Z"), first))
    }

    @Test fun missingTimestampsNeverMergeUnrelatedGroups() {
        val first = message("first", 1, "")
        assertTrue(Mango9TeamMessageLayout.startsSender(first.copy(id = "second"), first))
        assertEquals("", Mango9TeamMessageLayout.timeLabel(""))
        assertEquals("", Mango9TeamMessageLayout.dayLabel("not a date"))
    }
}
