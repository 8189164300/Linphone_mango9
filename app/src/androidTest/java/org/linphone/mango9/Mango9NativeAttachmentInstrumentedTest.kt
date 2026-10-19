// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.content.Intent
import android.graphics.pdf.PdfDocument
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.ui.fileviewer.FileViewerActivity
import org.linphone.ui.fileviewer.MediaViewerActivity
import org.linphone.ui.fileviewer.adapter.MediaListAdapter
import org.linphone.ui.fileviewer.fragment.MediaViewerFragment
import org.linphone.ui.fileviewer.viewmodel.FileViewModel
import org.linphone.ui.fileviewer.viewmodel.MediaListViewModel
import org.linphone.ui.fileviewer.viewmodel.MediaViewModel
import org.linphone.ui.main.chat.model.FileModel

@RunWith(AndroidJUnit4::class)
class Mango9NativeAttachmentInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun decryptedFileSurvivesCallerRemovalAndViewerRotationThenIsReleased() = file { file ->
        Mango9MediaTestFixtures.createAudio(file)
        lateinit var source: FileModel
        val args = android.os.Bundle().apply {
            putString("path", file.path); putString("originalPath", "/synthetic/encrypted")
            putBoolean("isEncrypted", true)
            putString(Mango9NativeAttachment.NAME, "Voice recording.m4a")
            putString(Mango9NativeAttachment.MIME, "audio/mp4")
        }
        main {
            source = FileModel(file.path, "recording.m4a", file.length(), 0, true, "/synthetic/encrypted", false, loadMetadata = false)
            Mango9NativeFileTransfers.bind(args, source)
            source.destroy()
        }
        assertTrue(file.exists())
        ActivityScenario.launch<MediaViewerActivity>(Intent(context, MediaViewerActivity::class.java).putExtras(args)).use { scenario ->
            assertAudioViewer(scenario)
            scenario.recreate()
            assertAudioViewer(scenario)
            assertTrue(file.exists())
        }
        await { !file.exists() }
    }

    @Test fun closingViewerDoesNotDeleteTheChatsDecryptedFile() = file { file ->
        Mango9MediaTestFixtures.createAudio(file)
        lateinit var source: FileModel
        val args = android.os.Bundle().apply {
            putString("path", file.path); putBoolean("isEncrypted", true)
            putString(Mango9NativeAttachment.NAME, "Voice recording.m4a")
            putString(Mango9NativeAttachment.MIME, "audio/mp4")
        }
        main {
            source = FileModel(file.path, "recording.m4a", file.length(), 0, true, "/synthetic/encrypted", false, loadMetadata = false)
            Mango9NativeFileTransfers.bind(args, source)
        }
        try {
            ActivityScenario.launch<MediaViewerActivity>(Intent(context, MediaViewerActivity::class.java).putExtras(args)).use {
                assertAudioViewer(it)
            }
            assertTrue(file.exists())
        } finally { main { source.destroy() } }
        await { !file.exists() }
    }

    @Test fun consumedFileTicketCannotBeReplayedIntoAnotherViewer() = file { file ->
        main {
            val source = FileModel(file.path, "opaque", 0, 0, true, "/synthetic/encrypted", false, loadMetadata = false)
            val args = android.os.Bundle().apply {
                putString("path", file.path); putBoolean("isEncrypted", true)
            putString(Mango9NativeAttachment.NAME, "Voice recording.m4a")
            }
            Mango9NativeFileTransfers.bind(args, source)
            val first = org.linphone.ui.fileviewer.viewmodel.Mango9NativeViewerLease()
            val replay = org.linphone.ui.fileviewer.viewmodel.Mango9NativeViewerLease()
            assertTrue(first.accept(args)); assertTrue(first.accept(args))
            assertFalse(replay.accept(args))
            first.release(); replay.release(); source.destroy()
        }
        await { !file.exists() }
    }

    @Test fun payloadMetadataWinsAndTransferEnvelopeIsNotAFileType() {
        assertEquals("audio/mp4", Mango9NativeAttachment.mimeType("opaque.jpg", "voice.mov", " Audio/MP4 ; codecs=mp4a.40.2 "))
        assertEquals("video/mp4", Mango9NativeAttachment.mimeType("opaque", "movie.mp4", "application/vnd.gsma.rcs-ft-http+xml"))
        assertEquals("application/pdf", Mango9NativeAttachment.mimeType("opaque", "document.PDF", "application/octet-stream"))
        assertEquals("image/png", Mango9NativeAttachment.mimeType("cache.png", "opaque", null))
        assertEquals("audio/mp4", Mango9NativeAttachment.mimeType("cached.m4a", "recording.mp4", null))
        assertEquals("application/octet-stream", Mango9NativeAttachment.mimeType("opaque", null, "audio/*"))
        assertEquals("application/octet-stream", Mango9NativeAttachment.mimeType("opaque", null, "audio/mp4\r\nX: injected"))
        assertEquals("application/json", Mango9NativeAttachment.mimeType("cache", "data.json", null))
    }

    @Test fun messageFileModelUsesTheOriginalNameAndLeavesPendingTransferUnknown() = file { file ->
        main {
            val ready = FileModel(file.path, "voice.m4a", 1234, 0, false, file.path, false, loadMetadata = false)
            val pending = FileModel(
                file.path, "voice.m4a", 1234, 0, false, file.path, false,
                isWaitingToBeDownloaded = true, loadMetadata = false, mimeTypeOverride = "audio/mp4"
            )
            assertTrue(ready.isAudio)
            assertEquals("audio/mp4", ready.mimeTypeString)
            assertFalse(pending.isAudio)
            assertEquals("application/octet-stream", pending.mimeTypeString)
            ready.destroy()
            pending.destroy()
        }
    }

    @Test fun recordingWithOpaquePathPlaysInActualViewerAfterRotation() = file { file ->
        Mango9MediaTestFixtures.createAudio(file)
        ActivityScenario.launch<MediaViewerActivity>(viewerIntent(file, "audio/mp4", "Voice recording.m4a")).use { scenario ->
            assertAudioViewer(scenario)
            scenario.recreate()
            assertAudioViewer(scenario)
        }
    }

    @Test fun videoWithOpaquePathUsesTheActualVideoPlayer() = file { file ->
        Mango9MediaTestFixtures.createVideo(file)
        ActivityScenario.launch<MediaViewerActivity>(viewerIntent(file, "video/mp4", "Video.mp4")).use { scenario ->
            await {
                var ready = false
                scenario.onActivity { activity ->
                    childModel(activity)?.let { model ->
                        ready = model.isPrepared.value == true && model.isVideo.value == true && model.hasPlaybackError.value != true
                    }
                }
                ready
            }
        }
    }

    @Test fun extensionlessPdfIsRenderedAsPdfNotLoadedAsText() = file { file ->
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(100, 100, 1).create())
            document.finishPage(page)
            file.outputStream().use(document::writeTo)
        } finally { document.close() }
        val intent = Intent(context, FileViewerActivity::class.java).putExtra("path", file.path)
            .putExtra(Mango9NativeAttachment.MIME, "application/pdf")
            .putExtra(Mango9NativeAttachment.NAME, "Document.pdf")
        ActivityScenario.launch<FileViewerActivity>(intent).use { scenario ->
            await {
                var ready = false
                scenario.onActivity {
                    val model = ViewModelProvider(it)[FileViewModel::class.java]
                    ready = model.isPdf.value == true && model.pdfPages.value == "1"
                    assertFalse(model.isText.value == true)
                    assertEquals("Document.pdf", model.fileName.value)
                }
                ready
            }
        }
    }

    @Test fun correctedMimeForSamePathReplacesPagerFragmentAndItsArguments() = file { file ->
        Mango9MediaTestFixtures.createAudio(file)
        ActivityScenario.launch<MediaViewerActivity>(viewerIntent(file, "audio/mp4", "Voice.m4a")).use { scenario ->
            assertAudioViewer(scenario, "Voice.m4a")
            scenario.onActivity { activity ->
                val adapter = MediaListAdapter(activity)
                val audio = FileModel(
                    file.path, "Voice.m4a", 0, 0, false, file.path, false,
                    loadMetadata = false, mimeTypeOverride = "audio/mp4"
                )
                val video = FileModel(
                    file.path, "Voice.m4a", 0, 0, false, file.path, false,
                    loadMetadata = false, mimeTypeOverride = "video/mp4"
                )
                adapter.submitList(listOf(audio))
                val originalId = adapter.getItemId(0)
                assertEquals("audio/mp4", adapter.createFragment(0).arguments!!.getString(Mango9NativeAttachment.MIME))
                adapter.submitList(listOf(video))
                assertNotEquals(originalId, adapter.getItemId(0))
                assertFalse(adapter.containsItem(originalId))
                assertEquals("video/mp4", adapter.createFragment(0).arguments!!.getString(Mango9NativeAttachment.MIME))
                audio.destroy()
                video.destroy()
            }
        }
    }

    @Test fun viewModelCanRetrySameOpaqueFileAfterTypeIsCorrected() = file { file ->
        Mango9MediaTestFixtures.createAudio(file)
        val store = ViewModelStore()
        lateinit var model: MediaViewModel
        try {
            main {
                model = MediaViewModel()
                store.put("player", model)
                model.loadFile(file.path)
                assertTrue(model.hasPlaybackError.value == true)
                model.loadFile(file.path, mimeType = "audio/mp4")
            }
            await {
                var ready = false
                main { ready = model.isPrepared.value == true && model.isAudio.value == true && model.hasPlaybackError.value != true }
                ready
            }
        } finally { main { store.clear() } }
    }

    private fun viewerIntent(file: File, mime: String, name: String) =
        Intent(context, MediaViewerActivity::class.java).putExtra("path", file.path)
            .putExtra(Mango9NativeAttachment.MIME, mime).putExtra(Mango9NativeAttachment.NAME, name)

    private fun assertAudioViewer(scenario: ActivityScenario<MediaViewerActivity>, name: String = "Voice recording.m4a") {
        await {
            var ready = false
            scenario.onActivity { activity ->
                childModel(activity)?.let { model ->
                    ready = model.isPrepared.value == true && model.isAudio.value == true && model.hasPlaybackError.value != true
                    assertEquals(name, model.fileName.value)
                }
                assertEquals("audio/mp4", ViewModelProvider(activity)[MediaListViewModel::class.java].mediaList.value!!.single().mimeTypeString)
            }
            ready
        }
    }

    private fun childModel(activity: MediaViewerActivity): MediaViewModel? =
        activity.supportFragmentManager.fragments.filterIsInstance<MediaViewerFragment>().firstOrNull()?.let {
            ViewModelProvider(it)[MediaViewModel::class.java]
        }

    private fun file(block: (File) -> Unit) {
        val file = File.createTempFile("native-attachment-regression-", ".blob", context.cacheDir)
        try { block(file) } finally { check(file.delete() || !file.exists()) }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(25)
        }
        fail("Native attachment did not reach its expected state")
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
