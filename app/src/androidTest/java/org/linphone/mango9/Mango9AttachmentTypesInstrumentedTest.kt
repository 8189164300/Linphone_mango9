// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.ui.main.chat.model.FileModel

@RunWith(AndroidJUnit4::class)
class Mango9AttachmentTypesInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val provider = Uri.parse("content://${InstrumentationRegistry.getInstrumentation().context.packageName}.outgoing/file")

    @Test fun audioOnlyMp4IsNotSentToTheVideoExporterAndKeepsItsBytes() = fixture { root ->
        val original = File(root, "original.m4a")
        withContext(Dispatchers.IO) { Mango9MediaTestFixtures.createAudio(original) }
        for (name in listOf("voice.mp4", "voice.MOV", "voice.m4a", "voice.webm")) {
            val source = original.copyTo(File(root, name))
            val prepared = Mango9AttachmentPreparer(context, File(root, "prepared"), videoExporter = { _, _ ->
                fail("Audio-only MP4 must not invoke the video exporter")
            }, audioExporter = { _, _ -> fail("Already compatible AAC must not be re-encoded") }).prepare(Uri.fromFile(source))
            try {
                assertEquals("voice.m4a", prepared.attachment.name)
                assertEquals("audio/mp4", prepared.attachment.mimeType)
                assertArrayEquals(original.readBytes(), File(prepared.attachment.uri.path!!).readBytes())
                assertPlayableAac(File(prepared.attachment.uri.path!!))
            } finally { prepared.close() }
            assertTrue(source.exists())
        }
    }

    @Test fun providerAudioTypeWithParametersOverridesFilenameAndUsesActualTracks() = fixture { root ->
        val original = File(root, "original.m4a")
        withContext(Dispatchers.IO) { Mango9MediaTestFixtures.createAudio(original) }
        withProvider(original.readBytes(), "voice.jpg", " Audio/MP4 ; codecs=mp4a.40.2 ") { uri ->
            Mango9AttachmentPreparer(context, File(root, "prepared")).prepare(uri).use { prepared ->
                assertEquals("voice.m4a", prepared.attachment.name)
                assertEquals("audio/mp4", prepared.attachment.mimeType)
                assertArrayEquals(original.readBytes(), File(prepared.attachment.uri.path!!).readBytes())
                assertEquals("audio/mp4", Mango9AttachmentRequestBody(context, prepared.attachment, {}).contentType().toString())
                assertPlayableAac(File(prepared.attachment.uri.path!!))
            }
        }
    }

    @Test fun realVideoWithM4aNameAndAudioMimeStillUsesTheVideoEncoder() = fixture { root ->
        val source = File(root, "original.mp4")
        withContext(Dispatchers.IO) { Mango9MediaTestFixtures.createVideo(source) }
        withProvider(source.readBytes(), "movie.m4a", "audio/mp4") { uri ->
            Mango9AttachmentPreparer(context, File(root, "prepared")).prepare(uri).use { prepared ->
                assertEquals("movie.mp4", prepared.attachment.name)
                assertEquals("video/mp4", prepared.attachment.mimeType)
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(prepared.attachment.uri.path!!)
                    assertEquals("video/avc", extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME))
                    extractor.selectTrack(0)
                    assertTrue(extractor.sampleSize > 0)
                } finally { extractor.release() }
            }
        }
    }

    @Test fun audioInAMisnamedVideoContainerIsPreparedAsAacNotVideo() = fixture { root ->
        val source = File(root, "voice.mp4")
        source.writeBytes(wave())
        Mango9AttachmentPreparer(context, File(root, "prepared")).prepare(Uri.fromFile(source)).use { prepared ->
            assertEquals("voice.m4a", prepared.attachment.name)
            assertEquals("audio/mp4", prepared.attachment.mimeType)
            assertPlayableAac(File(prepared.attachment.uri.path!!))
            assertArrayEquals(wave(), source.readBytes())
        }
    }

    @Test fun audioOnlyWebmIsConvertedToPlayableAacWithoutAVideoTrack() = fixture { root ->
        val source = File(root, "voice.webm")
        withContext(Dispatchers.IO) { Mango9MediaTestFixtures.createAudio(source, webm = true) }
        Mango9AttachmentPreparer(context, File(root, "prepared")).prepare(Uri.fromFile(source)).use { prepared ->
            assertEquals("voice.m4a", prepared.attachment.name)
            assertEquals("audio/mp4", prepared.attachment.mimeType)
            assertPlayableAac(File(prepared.attachment.uri.path!!))
        }
        assertTrue(source.exists())
    }

    @Test fun multipleCompatibleAudioTracksArePreservedWithoutDroppingOne() = fixture { root ->
        val audio = File(root, "audio.m4a")
        val combined = File(root, "programs.mp4")
        withContext(Dispatchers.IO) {
            Mango9MediaTestFixtures.createAudio(audio)
            combine(combined, listOf(audio, audio))
        }
        Mango9AttachmentPreparer(context, File(root, "prepared"), audioExporter = { _, _ ->
            fail("Compatible audio tracks must be preserved, not selected or converted")
        }).prepare(Uri.fromFile(combined)).use { prepared ->
            assertEquals("programs.m4a", prepared.attachment.name)
            assertEquals("audio/mp4", prepared.attachment.mimeType)
            assertArrayEquals(combined.readBytes(), File(prepared.attachment.uri.path!!).readBytes())
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(prepared.attachment.uri.path!!)
                assertEquals(2, extractor.trackCount)
                for (index in 0 until extractor.trackCount) {
                    assertEquals("audio/mp4a-latm", extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME))
                    extractor.selectTrack(index)
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    assertTrue(extractor.sampleSize > 0)
                    extractor.unselectTrack(index)
                }
            } finally { extractor.release() }
        }
    }

    @Test fun videoWithAudioDoesNotLoseItsAudioWhenMisnamedAsARecording() = fixture { root ->
        val video = File(root, "video.mp4")
        val audio = File(root, "audio.m4a")
        val mixed = File(root, "both.m4a")
        withContext(Dispatchers.IO) {
            Mango9MediaTestFixtures.createVideo(video)
            Mango9MediaTestFixtures.createAudio(audio)
            combine(mixed, listOf(video, audio))
        }
        Mango9AttachmentPreparer(context, File(root, "prepared")).prepare(Uri.fromFile(mixed)).use { prepared ->
            assertEquals("both.mp4", prepared.attachment.name)
            assertEquals("video/mp4", prepared.attachment.mimeType)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(prepared.attachment.uri.path!!)
                assertEquals(2, extractor.trackCount)
                assertEquals(
                    setOf("video/avc", "audio/mp4a-latm"),
                    (0 until extractor.trackCount).map {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                }.toSet()
                )
                for (index in 0 until extractor.trackCount) {
                    extractor.selectTrack(index)
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    assertTrue(extractor.sampleSize > 0)
                    extractor.unselectTrack(index)
                }
            } finally { extractor.release() }
        }
        assertTrue(mixed.exists())
    }

    @Test fun ordinaryWaveImageAndDocumentsAreNotLossilyConverted() = fixture { root ->
        val image = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val png = java.io.ByteArrayOutputStream().use { output ->
            try {
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray()
            } finally { image.recycle() }
        }
        for ((name, mime, bytes) in listOf(
            Triple("voice.wav", "audio/wav", wave()),
            Triple("image.mp4", "image/png", png),
            Triple("document.mp4", "application/pdf", "%PDF-1.4 synthetic document".toByteArray()),
        )) {
            withProvider(bytes, name, mime) { uri ->
                Mango9AttachmentPreparer(
                    context, File(root, "prepared"), videoExporter = { _, _ -> fail("Not video") },
                    audioExporter = { _, _ -> fail("Must preserve ordinary attachments") }
                ).prepare(uri).use { prepared ->
                    assertEquals(name, prepared.attachment.name)
                    assertEquals(mime, prepared.attachment.mimeType)
                    assertArrayEquals(bytes, File(prepared.attachment.uri.path!!).readBytes())
                }
            }
        }
    }

    @Test fun corruptOrTracklessContainersFailWithoutLeavingSnapshotsOrChangingOriginals() = fixture { root ->
        val store = File(root, "prepared")
        for ((name, bytes) in listOf(
            "broken.mp4" to "not a movie".toByteArray(),
            "empty.m4a" to byteArrayOf(0, 0, 0, 16, 102, 116, 121, 112, 105, 115, 111, 109, 0, 0, 0, 0),
        )) {
            val source = File(root, name).apply { writeBytes(bytes) }
            assertNotNull(runCatching { Mango9AttachmentPreparer(context, store).prepare(Uri.fromFile(source)) }.exceptionOrNull())
            assertArrayEquals(bytes, source.readBytes())
            assertEquals(listOf(Mango9AttachmentDirectory.ROOT_LOCK), store.list()!!.toList())
        }
    }

    @Test fun canceledAudioConversionStopsWriterBeforeReleasingSnapshot() = fixture { root ->
        val source = File(root, "voice.mp4").apply { writeBytes(wave()) }
        val store = File(root, "prepared")
        val started = CompletableDeferred<File>()
        val stopped = CompletableDeferred<Unit>()
        val preparer = Mango9AttachmentPreparer(context, store, audioExporter = { _, output ->
            try {
                output.writeText("partial"); started.complete(output); awaitCancellation()
            } finally { assertTrue(output.exists()); stopped.complete(Unit) }
        })
        val job = launch(Dispatchers.Main) { preparer.prepare(Uri.fromFile(source)) }
        val output = started.await()
        job.cancelAndJoin()
        stopped.await()
        assertFalse(output.exists())
        assertTrue(source.exists())
        assertEquals(listOf(Mango9AttachmentDirectory.ROOT_LOCK), store.list()!!.toList())
    }

    @Test fun incomingAudioMp4SelectsAudioInBothParserAndNativeViewer() = fixture { root ->
        val media = Mango9ChatMedia.parse("https://cdn.example.com/clip?ffName=recording.mp4&ffType=audio%2Fmp4&ffExt=mp4").single()
        assertEquals(Mango9ChatMedia.Kind.Audio, media.kind)
        val file = File(root, "cached." + Mango9MediaCache.extensionFor(media))
        withContext(Dispatchers.IO) { Mango9MediaTestFixtures.createAudio(file) }
        val model = FileModel(file.path, media.name, file.length(), 0, false, file.path, false, loadMetadata = false)
        try {
            assertTrue(model.isAudio); assertFalse(model.isVideoPreview); assertPlayableAac(file)
        } finally { model.destroy() }
        assertEquals("mka", Mango9MediaCache.extensionFor(media.copy(mimeType = "audio/webm")))
        assertEquals("heic", Mango9MediaCache.extensionFor(media.copy(mimeType = "image/heif")))
    }

    private suspend fun assertPlayableAac(file: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            assertEquals(1, extractor.trackCount)
            assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME))
            extractor.selectTrack(0)
            assertTrue(extractor.sampleSize > 0)
        } finally { extractor.release() }
        val player = MediaPlayer()
        try {
            player.setDataSource(file.path)
            player.prepare()
            assertTrue(player.duration >= 1900)
            player.start()
            withTimeout(3000) { while (player.currentPosition < 100) delay(20) }
        } finally { player.release() }
    }

    private fun wave(): ByteArray {
        val samples = 8000 * 2
        return ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples * 2)
            repeat(samples) { putShort(0) }
        }.array()
    }

    private fun combine(output: File, sources: List<File>) {
        val readers = mutableListOf<MediaExtractor>()
        val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            val tracks = sources.map { source ->
                val reader = MediaExtractor().also { readers.add(it); it.setDataSource(source.path) }
                assertEquals(1, reader.trackCount)
                reader.selectTrack(0)
                muxer.addTrack(reader.getTrackFormat(0))
            }
            muxer.start(); started = true
            val buffer = ByteBuffer.allocateDirect(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            readers.forEachIndexed { index, reader ->
                var samples = 0
                while (reader.sampleTime >= 0) {
                    assertTrue(++samples < 10000)
                    assertTrue(reader.sampleSize in 1..buffer.capacity().toLong())
                    buffer.clear()
                    val size = reader.readSampleData(buffer, 0)
                    info.set(0, size, reader.sampleTime, reader.sampleFlags)
                    muxer.writeSampleData(tracks[index], buffer, info)
                    reader.advance()
                }
                assertTrue(samples > 0)
            }
        } finally {
            try { if (started) muxer.stop() } finally { muxer.release(); readers.forEach { it.release() } }
        }
    }

    private suspend fun withProvider(bytes: ByteArray, name: String, mime: String, block: suspend (Uri) -> Unit) {
        context.contentResolver.call(provider, "fixture", null, Bundle().apply { putByteArray("bytes", bytes) })
        try {
            block(
                provider.buildUpon().appendQueryParameter("mode", "fixture").appendQueryParameter("name", name)
            .appendQueryParameter("mime", mime).build()
            )
        } finally { context.contentResolver.call(provider, "fixture", null, null) }
    }

    private fun fixture(block: suspend CoroutineScope.(File) -> Unit) = runBlocking {
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "attachment-types-regression-").toFile()
        try { withTimeout(60_000) { block(root) } } finally { Mango9MediaTestFixtures.removeReleasedAttachments(root) }
    }
}
