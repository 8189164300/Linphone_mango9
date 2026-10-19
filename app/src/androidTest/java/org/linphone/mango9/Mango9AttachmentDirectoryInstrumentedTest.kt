// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Mango9AttachmentDirectoryInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun evictedRootLockIsNotRecreatedAndNextAllocationReclaimsTheOrphan() = fixture { root ->
        val store = File(root, "store")
        val owner = Mango9AttachmentDirectory.create(store)
        val marker = File(store, Mango9AttachmentDirectory.ROOT_LOCK)
        try {
            File(owner.directory, "source").writeText("synthetic cache")
            assertTrue(marker.delete())
            owner.close()
            Mango9AttachmentDirectory.collectOrphans(store)
            assertFalse("Cleanup must not recreate the deleted lock inode", marker.exists())
            Mango9AttachmentDirectory.create(store).use {
                assertFalse("Released orphan must be reaped on allocation", owner.directory.exists())
            }
        } finally { owner.close() }
    }

    @Test fun evictedCacheIsNotRecreatedByLastReaderOrSweeper() = fixture { root ->
        val store = File(root, "store")
        val owner = Mango9AttachmentDirectory.create(store)
        try {
            File(owner.directory, "source").writeText("synthetic cache")
            assertTrue(store.deleteRecursively())
            owner.close()
            Mango9AttachmentDirectory.collectOrphans(store)
            assertFalse("Cleanup must not recreate the cache root", store.exists())
        } finally { owner.close() }
    }

    @Test fun liveSnapshotAndUploadPinSurviveCleanupOnAndroid() = fixture { root ->
        val store = File(root, "prepared")
        val source = File(root, "original.bin").apply { writeText("original") }
        val prepared = Mango9AttachmentPreparer(context, store).prepare(Uri.fromFile(source))
        val pin = prepared.retain()
        val file = File(pin.attachment.uri.path!!)
        try {
            prepared.close()
            repeat(10) { Mango9AttachmentDirectory.collectOrphans(store) }
            assertEquals("original", file.readText())
        } finally { prepared.close(); pin.close() }
        await { !file.exists() }
        assertEquals("original", source.readText())
        assertEquals(listOf(Mango9AttachmentDirectory.ROOT_LOCK), store.list()!!.toList())
    }

    @Test fun cameraAndVoiceFilesAreLeasedAndExplicitlyReleased() = fixture { _ ->
        for ((name, mime) in listOf("photo.jpg" to "image/jpeg", "voice.m4a" to "audio/mp4")) {
            val capture = withContext(Dispatchers.Main) { Mango9PreparedAttachment.createCapture(context, name, mime) }
            val file = File(capture.attachment.uri.path!!)
            try {
                file.writeText("synthetic capture")
                Mango9AttachmentDirectory.collectOrphans(file.parentFile!!.parentFile!!)
                assertEquals("synthetic capture", file.readText())
                val marker = File(file.parentFile, Mango9AttachmentDirectory.OWNER_MARKER).readText()
                assertTrue(marker.substringAfterLast('\n').toLong() > System.currentTimeMillis() + 23L * 60 * 60 * 1000)
            } finally { capture.close() }
            await { !file.exists() }
        }
    }

    @Test fun androidSweepReapsOnlyExpiredOwnedOrphansAndNeverFollowsLinks() = fixture { root ->
        val store = File(root, "store").apply { mkdir() }
        val expired = orphan(store, 0)
        val camera = orphan(store, System.currentTimeMillis() + 60_000)
        val unknown = File(store, UUID.randomUUID().toString()).apply { mkdir() }
        File(unknown, "source").writeText("unclaimed")
        val original = File(root, "original").apply { mkdir() }
        File(original, "keep").writeText("user media")
        Files.createSymbolicLink(File(expired, "external").toPath(), original.toPath())
        val link = File(store, UUID.randomUUID().toString()).toPath()
        Files.createSymbolicLink(link, original.toPath())
        try {
            Mango9AttachmentDirectory.collectOrphans(store)
            assertFalse(expired.exists())
            assertTrue(camera.exists())
            assertTrue(unknown.exists())
            assertTrue(Files.isSymbolicLink(link))
            assertEquals("user media", File(original, "keep").readText())
        } finally { Files.deleteIfExists(link) }
    }

    @Test fun simultaneousCopyAndSweepsKeepEveryActiveDraft() = fixture { root ->
        val store = File(root, "store")
        val owners = (0 until 12).map { async(Dispatchers.IO) { Mango9AttachmentDirectory.create(store) } }.awaitAll()
        try {
            owners.map { owner ->
                async(Dispatchers.IO) {
                    val source = File(owner.directory, "source").apply { writeBytes(ByteArray(4096) { 7 }) }
                    repeat(4) { Mango9AttachmentDirectory.collectOrphans(store) }
                    assertEquals(4096L, source.length())
                    assertTrue(source.readBytes().all { it == 7.toByte() })
                }
            }.awaitAll()
        } finally { owners.forEach { it.close() } }
        assertEquals(listOf(Mango9AttachmentDirectory.ROOT_LOCK), store.list()!!.toList())
    }

    @Test fun canceledPreparationDoesNotLeaveAnOwnedSnapshot() = fixture { root ->
        val store = File(root, "store")
        val movie = File(root, "source.mov")
        withContext(Dispatchers.IO) { Mango9MediaTestFixtures.createVideo(movie) }
        val started = CompletableDeferred<File>()
        val writerStopped = CompletableDeferred<Unit>()
        val preparer = Mango9AttachmentPreparer(context, store, videoExporter = { _, output ->
            try {
                output.writeText("partial")
                started.complete(output)
                awaitCancellation()
            } finally {
                assertTrue("Cleanup ran before the writer stopped", output.exists())
                writerStopped.complete(Unit)
            }
        })
        val job = launch(Dispatchers.Main) { preparer.prepare(Uri.fromFile(movie)) }
        val output = started.await()
        Mango9AttachmentDirectory.collectOrphans(store)
        assertTrue(output.exists())
        job.cancelAndJoin()
        writerStopped.await()
        assertFalse(output.exists())
        assertTrue(movie.exists())
        assertEquals(listOf(Mango9AttachmentDirectory.ROOT_LOCK), store.list()!!.toList())
    }

    private fun orphan(root: File, reapAfter: Long) = File(root, UUID.randomUUID().toString()).apply {
        File(root, Mango9AttachmentDirectory.ROOT_LOCK).createNewFile()
        mkdir()
        File(this, Mango9AttachmentDirectory.OWNER_MARKER).writeText("Mango9 attachment v1\n$reapAfter")
        File(this, "source").writeText("abandoned snapshot")
    }

    private fun fixture(block: suspend CoroutineScope.(File) -> Unit) = runBlocking {
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "attachment-directory-regression-").toFile()
        try { withTimeout(30_000) { block(root) } } finally { Mango9MediaTestFixtures.removeReleasedAttachments(root) }
    }

    private suspend fun await(predicate: () -> Boolean) {
        withTimeout(5000) { while (!predicate()) delay(15) }
    }
}
