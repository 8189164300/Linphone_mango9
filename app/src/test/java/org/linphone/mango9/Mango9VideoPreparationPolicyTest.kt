// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class Mango9VideoPreparationPolicyTest {
    @Test fun tallScreenRecordingUsesAnEncoderSafeCanvas() {
        assertEquals(Mango9VideoDimensions.Size(596, 1280), Mango9VideoDimensions.fit(1080, 2316))
        assertEquals(Mango9VideoDimensions.Size(1280, 596), Mango9VideoDimensions.fit(2316, 1080))
    }

    @Test fun rotationAndOrdinaryCameraFormatsKeepTheirOrientation() {
        assertEquals(Mango9VideoDimensions.Size(720, 1280), Mango9VideoDimensions.fit(1920, 1080, 90))
        assertEquals(Mango9VideoDimensions.Size(1280, 720), Mango9VideoDimensions.fit(3840, 2160))
        assertEquals(Mango9VideoDimensions.Size(640, 480), Mango9VideoDimensions.fit(640, 480))
        assertEquals(Mango9VideoDimensions.Size(596, 1280), Mango9VideoDimensions.fit(2316, 1080, 270))
    }

    @Test fun variedAspectRatiosStayBoundedAndEvenOnBothAxes() {
        for ((w, h) in listOf(1080 to 2316, 1440 to 3088, 4032 to 3024, 719 to 1279, 853 to 480, 5000 to 1000)) {
            val size = Mango9VideoDimensions.fit(w, h)
            assertEquals(0, size.width % 2)
            assertEquals(0, size.height % 2)
            assertTrue(maxOf(size.width, size.height) <= 1280)
            assertTrue(minOf(size.width, size.height) <= 720)
            assertTrue(size.width <= w && size.height <= h)
        }
    }

    @Test fun onlyActualSizeFailuresMentionTheSizeLimit() {
        for (reason in Mango9AttachmentFailure.Reason.entries) {
            assertEquals(
                reason == Mango9AttachmentFailure.Reason.TOO_LARGE,
                Mango9AttachmentFailure.message(Mango9AttachmentFailure(reason)).contains("50 MB")
            )
        }
    }

    @Test fun providerDetailsAreNotExposedAsComposerErrors() {
        for (error in listOf(IOException("private provider path"), SecurityException("private provider path"))) {
            val message = Mango9AttachmentFailure.message(error)
            assertFalse(message.contains("private provider path"))
            assertFalse(message.contains("50 MB"))
        }
    }
}
