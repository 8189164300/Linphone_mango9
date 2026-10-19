// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in device regression: the operator supplies a local clip; nothing is uploaded or sent. */
@RunWith(AndroidJUnit4::class)
class Mango9DeviceVideoPreparationTest {
    @Test fun deviceVideoCanBePreparedForMessaging() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "mango9-video-regression/input.mp4")
        assumeTrue("Supply a local video fixture in the app cache to run this check", source.isFile)
        val prepared = Mango9AttachmentPreparer(context).prepare(Uri.fromFile(source))
        try {
            assertEquals("video/mp4", prepared.attachment.mimeType)
            assertTrue(prepared.attachment.size in 1..Mango9AttachmentPreparer.MAX_BYTES)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(requireNotNull(prepared.attachment.uri.path))
                val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                val video = formats.single { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                assertEquals("video/avc", video.getString(MediaFormat.KEY_MIME))
                assertEquals(0, video.getInteger(MediaFormat.KEY_WIDTH) % 2)
                assertEquals(0, video.getInteger(MediaFormat.KEY_HEIGHT) % 2)
                assertTrue(source.isFile)
            } finally { extractor.release() }
        } finally { prepared.close() }
    }
}
