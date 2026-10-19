// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import org.junit.Assert.*
import org.junit.Test

class Mango9MmsPolicyTest {
    @Test fun budgetsCoverSingleAndMultipleAttachmentsWithoutUsingChatLimit() {
        assertEquals(550_000L, Mango9MmsPolicy.perAttachmentBudget(1))
        for (count in 1..10) {
            val budget = Mango9MmsPolicy.perAttachmentBudget(count)
            assertTrue(budget <= 550_000)
            assertTrue(budget * count <= 850_000)
            Mango9MmsPolicy.validateSizes(List(count) { budget })
        }
    }

    @Test fun oversizedFilesAndTotalsAreRejectedBeforeUpload() {
        for (sizes in listOf(listOf(6_160_010L), listOf(550_001L), listOf(500_000L, 500_000L), listOf(0L), List(11) { 1L })) {
            assertThrows(Mango9AttachmentFailure::class.java) { Mango9MmsPolicy.validateSizes(sizes) }
        }
    }

    @Test fun longVideosAreRejectedRatherThanDestroyingQuality() {
        for (duration in listOf(10_000L, 20_000L, 0L)) {
            val error = assertThrows(Mango9AttachmentFailure::class.java) {
                Mango9MmsPolicy.videoSettings(duration, 550_000, 1920, 1080)
            }
            assertEquals(Mango9AttachmentFailure.Reason.MMS_VIDEO_TOO_LONG, error.reason)
            assertTrue(error.userMessage.contains("9 seconds"))
            assertFalse(error.userMessage.contains("50 MB"))
            assertEquals(error.userMessage, Mango9AttachmentFailure.message(error))
        }
    }

    @Test fun userMaximumIsTwentySecondsEvenBeforeQualityChecks() {
        assertEquals(20_000L, Mango9MmsPolicy.MAX_VIDEO_DURATION_MILLIS)
        for (duration in listOf(20_001L, 40_000L, 43_047L)) {
            val error = assertThrows(Mango9AttachmentFailure::class.java) {
                Mango9MmsPolicy.videoSettings(duration, 550_000, 1920, 1080)
            }
            assertEquals(Mango9AttachmentFailure.Reason.MMS_VIDEO_DURATION_LIMIT, error.reason)
            assertTrue(error.userMessage.contains("20 seconds"))
        }
    }

    @Test fun floorAccountsForPixelsFramesAndAudio() {
        val wide = Mango9MmsPolicy.videoSettings(7_000, 550_000, 1920, 1080)
        val standard = Mango9MmsPolicy.videoSettings(7_000, 550_000, 1440, 1080)
        assertEquals(Mango9VideoDimensions.Size(480, 270), wide.size)
        assertEquals(311_040, wide.minimumVideoBitrate)
        assertEquals(414_720, standard.minimumVideoBitrate)
        assertEquals(48_000, wide.audioBitrate)
        assertEquals(24, Mango9MmsPolicy.VIDEO_FRAME_RATE)
        assertEquals(9, wide.maxDurationSeconds)
        assertEquals(7, standard.maxDurationSeconds)
        assertTrue(wide.videoBitrate >= wide.minimumVideoBitrate)
        assertTrue(standard.videoBitrate >= standard.minimumVideoBitrate)
    }

    @Test fun moreAttachmentsShortenAllowedDuration() {
        val budget = Mango9MmsPolicy.perAttachmentBudget(3)
        val settings = Mango9MmsPolicy.videoSettings(4_000, budget, 1920, 1080)
        assertEquals(4, settings.maxDurationSeconds)
        assertThrows(Mango9AttachmentFailure::class.java) {
            Mango9MmsPolicy.videoSettings(5_000, budget, 1920, 1080)
        }
    }

    @Test fun retryNeverDropsBelowQualityFloor() {
        var settings = Mango9MmsPolicy.videoSettings(2_000, 550_000, 1920, 1080)
        while (true) {
            assertTrue(settings.videoBitrate >= settings.minimumVideoBitrate)
            settings = settings.lowerBitrateRetry() ?: break
        }
        assertEquals(settings.minimumVideoBitrate, settings.videoBitrate)
        assertNull(settings.lowerBitrateRetry())
    }

    @Test fun portraitKeepsAspectRatioAndEvenDimensions() {
        val settings = Mango9MmsPolicy.videoSettings(6_000, 550_000, 1080, 2316)
        assertEquals(Mango9VideoDimensions.Size(222, 480), settings.size)
        assertEquals(Mango9VideoDimensions.Size(270, 480), Mango9MmsPolicy.videoSettings(6_000, 550_000, 1920, 1080, 90).size)
    }
}
