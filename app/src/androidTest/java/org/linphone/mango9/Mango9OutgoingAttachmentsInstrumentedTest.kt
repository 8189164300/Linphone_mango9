// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Mango9OutgoingAttachmentsInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun provider(query: String = "") = Uri.parse("content://${InstrumentationRegistry.getInstrumentation().context.packageName}.outgoing/file?$query")

    @Test fun unknownAndIncorrectProviderSizesUseBoundedActualBytes() = fixture { root ->
        for (declared in listOf(-1, 1, 999999)) {
            val prepared = Mango9AttachmentPreparer(context, File(root, "prepared")).prepare(provider("declared=$declared"))
            val file = File(prepared.attachment.uri.path!!)
            assertEquals(32777L, prepared.attachment.size)
            assertEquals("cloud-file.bin", prepared.attachment.name)
            assertTrue(file.readBytes().all { it == 7.toByte() })
            prepared.close()
            await { !file.exists() }
        }
    }

    @Test fun oversizedEmptyDeniedAndMissingSourcesLeaveNoSnapshot() = fixture { root ->
        val preparedRoot = File(root, "prepared")
        val preparer = Mango9AttachmentPreparer(context, preparedRoot, byteLimit = 64 * 1024)
        val uris =
            listOf(provider("bytes=70000&declared=1"), provider("bytes=0"), provider("mode=denied"), Uri.fromFile(File(root, "missing")))
        for (uri in uris) {
            val failure = runCatching { preparer.prepare(uri) }.exceptionOrNull()
            assertNotNull("Invalid source was accepted: $uri", failure)
            assertNoSnapshots(preparedRoot)
        }
    }

    @Test fun blockedProviderQueryIsOffMainCancellableAndDoesNotLeakFiles() = fixture { root ->
        val preparer = Mango9AttachmentPreparer(context, File(root, "prepared"))

        fun queries(key: String): Int = context.contentResolver.call(provider(), "state", null, null)!!.getInt(key)
        val before = queries("queries")
        val stopped = queries("stopped")
        val job = CoroutineScope(Dispatchers.Main).launch { preparer.prepare(provider("mode=slow")) }
        await { queries("queries") > before }
        withTimeout(1000) { withContext(Dispatchers.Main) { assertTrue(job.isActive) } }
        val start = SystemClock.elapsedRealtime()
        withTimeout(2500) { job.cancelAndJoin() }
        assertTrue(SystemClock.elapsedRealtime() - start < 2500)
        assertEquals(stopped + 1, queries("stopped"))
        assertNoSnapshots(File(root, "prepared"))
    }

    @Test fun cancellationClosesAStreamingProviderBeforeRemovingItsSnapshot() = fixture { root ->
        val preparedRoot = File(root, "prepared")
        val job = CoroutineScope(Dispatchers.Main).launch {
            Mango9AttachmentPreparer(context, preparedRoot).prepare(provider("mode=stream&bytes=1000000"))
        }
        await { preparedRoot.walkTopDown().any { it.isFile && it.name == "source" && it.length() > 0 } }
        withTimeout(2500) { job.cancelAndJoin() }
        assertNoSnapshots(preparedRoot)
    }

    @Test fun uploadChecksActualLengthAndOwnerBeforeWriting() = fixture { root ->
        val original = File(root, "original.bin").apply { writeBytes(ByteArray(40000) { 3 }) }
        val prepared = Mango9AttachmentPreparer(context, File(root, "prepared")).prepare(Uri.fromFile(original))
        try {
            val sink = Buffer()
            Mango9AttachmentRequestBody(context, prepared.attachment, {}).writeTo(sink)
            assertEquals(40000L, sink.size)
            assertThrows(IOException::class.java) {
                Mango9AttachmentRequestBody(context, prepared.attachment.copy(size = 1), {}).writeTo(Buffer())
            }
            var checks = 0
            val partial = Buffer()
            assertThrows(CancellationException::class.java) {
                Mango9AttachmentRequestBody(context, prepared.attachment, {
                    if (++checks >= 4) throw CancellationException("Synthetic account replacement")
                }).writeTo(partial)
            }
            assertTrue(partial.size < 40000L)
            original.delete()
            val independent = Buffer()
            Mango9AttachmentRequestBody(context, prepared.attachment, {}).writeTo(independent)
            assertEquals(40000L, independent.size)
        } finally { prepared.close() }
    }

    @Test fun sendPinsOriginalFilesAndPreservesNewDraftEdits() = fixture { root ->
        val source = File(root, "first.bin").apply { writeText("first") }
        val second = File(root, "second.bin").apply { writeText("second") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val composer =
            Mango9MessageComposer(context, scope, { true }, Mango9AttachmentPreparer(context, File(root, "prepared"))::prepare)
        try {
            withContext(Dispatchers.Main) { composer.changeText("sending this"); composer.import(listOf(Uri.fromFile(source))) }
            await { composer.state.value.preparing == 0 && composer.state.value.attachments.size == 1 }
            val sentFile = File(composer.state.value.attachments.single().uri.path!!)
            val started = CompletableDeferred<Unit>()
            val acknowledge = CompletableDeferred<Boolean>()
            withContext(Dispatchers.Main) {
                composer.send { text, files ->
                    assertEquals("sending this", text)
                    assertEquals(sentFile.path, files.single().uri.path)
                    started.complete(Unit)
                    acknowledge.await()
                }
            }
            started.await()
            withContext(Dispatchers.Main) {
                composer.remove(composer.state.value.attachments.single().uri)
                composer.changeText("next message")
                composer.import(listOf(Uri.fromFile(second)))
            }
            await { composer.state.value.preparing == 0 }
            assertTrue("Removed chip must not unlink an active upload", sentFile.exists())
            acknowledge.complete(true)
            await { !composer.state.value.sending }
            assertEquals("next message", composer.state.value.text)
            assertEquals("second.bin", composer.state.value.attachments.single().name)
            await { !sentFile.exists() }
            assertTrue(source.exists())
            assertTrue(second.exists())
        } finally { withContext(Dispatchers.Main) { composer.dispose(); scope.cancel() } }
    }

    @Test fun logoutCancelsSendRejectsLateImportAndInvalidatesPickerTickets() = fixture { root ->
        val source = File(root, "source.bin").apply { writeText("kept original") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val ready = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val preparer = Mango9AttachmentPreparer(context, File(root, "prepared"))
        val composer = Mango9MessageComposer(context, scope, { true }) { uri ->
            val item = preparer.prepare(uri)
            ready.complete(Unit)
            withContext(NonCancellable) { release.await() }
            item
        }
        try {
            withContext(Dispatchers.Main) {
                assertTrue(composer.beginRequest("media"))
                composer.changeText("private draft")
                composer.import(listOf(Uri.fromFile(source)))
            }
            ready.await()
            withContext(Dispatchers.Main) {
                composer.dispose()
                assertFalse(composer.finishRequest("media"))
            }
            release.complete(Unit)
            await { File(root, "prepared").listFiles().orEmpty().none { it.name != Mango9AttachmentDirectory.ROOT_LOCK } }
            assertEquals("", composer.state.value.text)
            assertTrue(composer.state.value.attachments.isEmpty())
            assertTrue(source.exists())
        } finally { release.complete(Unit); withContext(Dispatchers.Main) { composer.dispose(); scope.cancel() } }
    }

    @Test fun draftAndExternalCameraAreOwnedIndependentlyOfViewCollectors() = fixture { root ->
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val composer =
            Mango9MessageComposer(context, scope, { true }, Mango9AttachmentPreparer(context, File(root, "prepared"))::prepare)
        val camera = CompletableDeferred<File>()
        try {
            withContext(Dispatchers.Main) {
                composer.beginRequest("capture")
                composer.createCapture { camera.complete(it); true }
            }
            val file = camera.await()
            file.writeBytes(byteArrayOf(1, 2, 3))
            // View collectors can come and go; neither owns or deletes the external camera result.
            var observed = ""
            val firstView = scope.launch { composer.state.collect { observed = it.text } }
            withContext(Dispatchers.Main) { composer.changeText("retained") }
            await { observed == "retained" }
            firstView.cancelAndJoin()
            assertTrue(file.exists())
            withContext(Dispatchers.Main) {
                assertTrue(composer.finishRequest("capture"))
                composer.finishCapture(true)
            }
            await { composer.state.value.attachments.size == 1 && composer.state.value.preparing == 0 }
            assertEquals("retained", composer.state.value.text)
            await { !file.exists() }
        } finally { withContext(Dispatchers.Main) { composer.dispose(); scope.cancel() } }
    }

    @Test fun exportFailureNeverFallsBackToOriginalMovie() = fixture { root ->
        val movie = File(root, "source.mov")
        withContext(Dispatchers.IO) { Mango9MediaTestFixtures.createVideo(movie) }
        val preparedRoot = File(root, "prepared")
        var exported = false
        val preparer = Mango9AttachmentPreparer(context, preparedRoot, videoExporter = { _, output ->
            exported = true
            output.writeText("partial export")
            throw IOException("Synthetic encoder failure")
        })
        assertNotNull(runCatching { preparer.prepare(Uri.fromFile(movie)) }.exceptionOrNull())
        assertTrue("The failure must exercise the encoder path", exported)
        assertTrue(movie.exists())
        assertNoSnapshots(preparedRoot)
    }

    @Test fun realWebmBecomesPlayableH264Mp4WithCorrectMetadata() = fixture { root ->
        val movie = File(root, "recording.webm")
        withContext(Dispatchers.IO) { Mango9MediaTestFixtures.createVideo(movie, webm = true) }
        val prepared = Mango9AttachmentPreparer(context, File(root, "prepared")).prepare(Uri.fromFile(movie))
        try {
            assertEquals("recording.mp4", prepared.attachment.name)
            assertEquals("video/mp4", prepared.attachment.mimeType)
            val file = File(prepared.attachment.uri.path!!)
            assertEquals(file.length(), prepared.attachment.size)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.path)
                assertEquals("video/avc", extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME))
            } finally { extractor.release() }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.path)
                val frame = requireNotNull(retriever.getFrameAtTime(500000))
                assertTrue(frame.width > 0 && frame.height > 0)
                frame.recycle()
                assertTrue(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() >= 7000)
            } finally { retriever.release() }
            assertTrue(movie.exists())
            assertEquals(
                setOf("video.mp4", Mango9AttachmentDirectory.OWNER_MARKER),
                file.parentFile!!.listFiles()!!.map {
                it.name
            }.toSet()
            )
        } finally { prepared.close() }
    }

    @Test fun aacVoiceSnapshotRetainsActualAudioAndM4aName() = fixture { root ->
        val audio = File(root, "voice.m4a")
        withContext(Dispatchers.IO) { Mango9MediaTestFixtures.createAudio(audio) }
        val prepared = Mango9AttachmentPreparer(context, File(root, "prepared")).prepare(Uri.fromFile(audio))
        try {
            assertEquals("voice.m4a", prepared.attachment.name)
            assertEquals("audio/mp4", prepared.attachment.mimeType)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(prepared.attachment.uri.path!!)
                val track = extractor.getTrackFormat(0)
                assertEquals("audio/mp4a-latm", track.getString(MediaFormat.KEY_MIME))
                assertEquals(1, track.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                assertEquals(44100, track.getInteger(MediaFormat.KEY_SAMPLE_RATE))
            } finally { extractor.release() }
            assertArrayEquals(audio.readBytes(), File(prepared.attachment.uri.path!!).readBytes())
        } finally { prepared.close() }
    }

    private fun fixture(block: suspend (File) -> Unit) = runBlocking {
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "outgoing-regression-").toFile()
        try { withTimeout(60_000) { block(root) } } finally { Mango9MediaTestFixtures.removeReleasedAttachments(root) }
    }

    private suspend fun await(predicate: () -> Boolean) {
        withTimeout(8000) { while (!predicate()) delay(15) }
    }

    private fun assertNoSnapshots(root: File) {
        assertEquals(listOf(Mango9AttachmentDirectory.ROOT_LOCK), root.list().orEmpty().toList())
        assertEquals(0L, File(root, Mango9AttachmentDirectory.ROOT_LOCK).length())
    }
}
