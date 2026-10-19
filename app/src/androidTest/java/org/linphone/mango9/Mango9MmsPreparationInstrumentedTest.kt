// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** All media stays on the device; these checks never call an upload or send endpoint. */
@RunWith(AndroidJUnit4::class)
class Mango9MmsPreparationInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun longDeviceClipIsRejectedWithTwentySecondLimit() = runBlocking {
        val source = File(context.cacheDir, "mango9-video-regression/input.mp4")
        assumeTrue("Optional local clip", source.isFile)
        try {
            Mango9AttachmentPreparer(context, mmsByteLimit = Mango9MmsPolicy.MAX_FILE_BYTES).prepare(Uri.fromFile(source)).close()
            fail("A long video must not be crushed to the MMS budget")
        } catch (failure: Mango9AttachmentFailure) {
            assertEquals(Mango9AttachmentFailure.Reason.MMS_VIDEO_DURATION_LIMIT, failure.reason)
            assertTrue(failure.userMessage.contains("20 seconds"))
        }
        assertTrue(source.isFile)
    }

    @Test fun shortRealClipKeepsDurationAndHigherResolution() = runBlocking {
        val source = File(context.cacheDir, "mango9-video-regression/input.mp4")
        assumeTrue("Optional local clip", source.isFile)
        val shortClip = File(source.parentFile, "short-fixture.mp4")
        // Only construct a six-second regression fixture. Production never trims a user's clip.
        val extractor = MediaExtractor()
        val muxer = MediaMuxer(shortClip.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            extractor.setDataSource(source.path)
            val videoIndex = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            extractor.selectTrack(videoIndex)
            val track = muxer.addTrack(extractor.getTrackFormat(videoIndex))
            muxer.start()
            val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
            while (extractor.sampleTime in 0 until 6_000_000L) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val info = MediaCodec.BufferInfo().apply { set(0, size, extractor.sampleTime, extractor.sampleFlags) }
                muxer.writeSampleData(track, buffer, info)
                extractor.advance()
            }
            muxer.stop()
        } finally { extractor.release(); muxer.release() }
        try {
            val prepared = Mango9AttachmentPreparer(context, mmsByteLimit = 550_000).prepare(Uri.fromFile(shortClip))
            try {
                val output = File(requireNotNull(prepared.attachment.uri.path))
                assertTrue(prepared.attachment.size in 1..550_000)
                assertTrue(kotlin.math.abs(duration(shortClip) - duration(output)) < 500)
                val reader = MediaMetadataRetriever()
                try {
                    reader.setDataSource(output.path)
                    val width = requireNotNull(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)).toInt()
                    val height = requireNotNull(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)).toInt()
                    assertEquals(480, maxOf(width, height))
                    assertEquals(222, minOf(width, height))
                } finally { reader.release() }
                android.util.Log.i("Mango9MmsTest", "Quality-limited short clip: ${prepared.attachment.size} bytes")
            } finally { prepared.close() }
        } finally { shortClip.delete() }
        Unit
    }

    @Test fun compressedVideoKeepsItsAudioTrack() = runBlocking {
        val root = File(context.cacheDir, "mms-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val video = File(root, "video.mp4").also { Mango9MediaTestFixtures.createVideo(it) }
            val audio = File(root, "audio.m4a").also { Mango9MediaTestFixtures.createAudio(it) }
            val source = File(root, "combined.mp4")
            val muxer = MediaMuxer(source.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val extractors = listOf(video, audio).map { file ->
                MediaExtractor().apply {
                setDataSource(file.path); selectTrack(0)
            }
            }
            try {
                val tracks = extractors.map { muxer.addTrack(it.getTrackFormat(0)) }
                muxer.start()
                val buffer = ByteBuffer.allocate(1024 * 1024)
                extractors.forEachIndexed { index, extractor ->
                    while (true) {
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        val info = MediaCodec.BufferInfo().apply { set(0, size, extractor.sampleTime, extractor.sampleFlags) }
                        muxer.writeSampleData(tracks[index], buffer, info)
                        extractor.advance()
                    }
                }
                muxer.stop()
            } finally { extractors.forEach { it.release() }; muxer.release() }
            val prepared = Mango9AttachmentPreparer(context, mmsByteLimit = 550_000).prepare(Uri.fromFile(source))
            try {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(requireNotNull(prepared.attachment.uri.path))
                    val types = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
                    assertTrue(types.containsAll(listOf("video/avc", "audio/mp4a-latm")))
                    assertTrue(prepared.attachment.size in 1..550_000)
                    assertTrue(kotlin.math.abs(duration(source) - duration(File(requireNotNull(prepared.attachment.uri.path)))) < 500)
                } finally { extractor.release() }
            } finally { prepared.close() }
        } finally { root.deleteRecursively() }
    }

    @Test fun largePhotoFitsMultiAttachmentBudgetAndOversizedDocumentStaysUnsent() = runBlocking {
        val root = File(context.cacheDir, "mms-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val source = File(root, "image.png")
            val bitmap = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)
            try {
                val random = java.util.Random(42)
                bitmap.setPixels(IntArray(1600 * 1200) { random.nextInt() or (0xff shl 24) }, 0, 1600, 0, 0, 1600, 1200)
                source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
            val originalSize = source.length()
            assertTrue(originalSize > 550_000)
            val budget = Mango9MmsPolicy.perAttachmentBudget(3)
            val prepared = Mango9AttachmentPreparer(context, mmsByteLimit = budget).prepare(Uri.fromFile(source))
            try {
                assertEquals("image/jpeg", prepared.attachment.mimeType)
                assertTrue(prepared.attachment.size in 1..budget)
                assertEquals(originalSize, source.length())
            } finally { prepared.close() }
            val pdf = File(root, "large.pdf").apply { writeBytes(ByteArray(600_000)) }
            try {
                Mango9AttachmentPreparer(context, mmsByteLimit = 550_000).prepare(Uri.fromFile(pdf)).close()
                fail("Oversized MMS must be rejected")
            } catch (failure: Mango9AttachmentFailure) {
                assertEquals(Mango9AttachmentFailure.Reason.MMS_TOO_LARGE, failure.reason)
            }
            assertTrue(pdf.isFile)
        } finally { root.deleteRecursively() }
    }

    private fun duration(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            requireNotNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong()
        } finally { retriever.release() }
    }
}
