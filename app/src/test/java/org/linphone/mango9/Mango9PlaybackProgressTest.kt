// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import org.junit.Assert.*
import org.junit.Test

class Mango9PlaybackProgressTest {
    @Test fun zeroPositionCannotEraseARequestedSeekAndRecoveryIsBounded() {
        val progress = Mango9PlaybackProgress()
        progress.resetForUserAction(7_800)
        progress.start(100, 0)
        assertEquals(7_800, progress.sample(3_099, 0))
        assertFalse(progress.isStalled(3_099))
        assertTrue(progress.isStalled(3_100))
        assertTrue(progress.claimRecovery())
        progress.start(3_200, 0)
        assertEquals(7_800, progress.sample(6_200, 0))
        assertTrue(progress.isStalled(6_200))
        assertFalse(progress.claimRecovery())
    }

    @Test fun actualProgressMovesTheBookmarkAndDeadlineWithoutRenewingRetryBudget() {
        val progress = Mango9PlaybackProgress()
        progress.resetForUserAction(1_000)
        progress.start(100, 1_000)
        assertTrue(progress.claimRecovery())
        assertEquals(1_500, progress.sample(2_500, 1_500))
        assertFalse(progress.isStalled(5_499))
        assertTrue(progress.isStalled(5_500))
        assertFalse(progress.claimRecovery())
    }

    @Test fun newUserSeekCanMoveBackwardAndAllowOneNewRecovery() {
        val progress = Mango9PlaybackProgress()
        progress.resetForUserAction(6_000)
        assertTrue(progress.claimRecovery())
        progress.resetForUserAction(500)
        progress.start(100, 500)
        assertEquals(600, progress.sample(200, 600))
        assertTrue(progress.claimRecovery())
        assertFalse(progress.claimRecovery())
    }

    @Test fun backwardsNativeClockDoesNotRewindTheSavedPosition() {
        val progress = Mango9PlaybackProgress()
        progress.resetForUserAction(3_000)
        progress.start(100, 3_000)
        assertEquals(3_500, progress.sample(500, 3_500))
        assertEquals(3_500, progress.sample(1_000, 0))
        assertEquals(3_500, progress.sample(1_100, 100))
        assertEquals(3_600, progress.sample(1_500, 3_600))
        assertFalse(progress.isStalled(4_499))
    }

    @Test fun preparationOrPauseTimeDoesNotCountAgainstNextStart() {
        val progress = Mango9PlaybackProgress()
        progress.resetForUserAction(0)
        progress.start(100, 0)
        progress.sample(500, 400)
        progress.start(60_000, 400)
        assertFalse(progress.isStalled(62_999))
        assertTrue(progress.isStalled(63_000))
    }
}
