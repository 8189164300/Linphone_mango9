// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class Mango9AttachmentDirectoryTest {
    @Test fun partialCacheEvictionMustNotRecreateTheRootLock() = fixture { root ->
        val store = File(root, "store")
        val owner = Mango9AttachmentDirectory.create(store)
        val marker = File(store, Mango9AttachmentDirectory.ROOT_LOCK)
        try {
            File(owner.directory, "source").writeText("owned")
            assertTrue(marker.delete())
            owner.close()
            assertFalse("Cleanup must not recreate an evicted root-lock inode", marker.exists())
            Mango9AttachmentDirectory.collectOrphans(store)
            assertFalse(marker.exists())
            Mango9AttachmentDirectory.create(store).use {
                assertFalse("The next allocator must reclaim the released orphan", owner.directory.exists())
            }
        } finally { owner.close() }
    }

    @Test fun lateReleaseMustNotRecreateAnEvictedCacheDirectory() = fixture { root ->
        val store = File(root, "store")
        val owner = Mango9AttachmentDirectory.create(store)
        try {
            File(owner.directory, "source").writeText("owned")
            // Android may evict cache files independently of the asynchronous last-reader close.
            assertTrue(store.deleteRecursively())
            owner.close()
            assertFalse("Last-reader cleanup must never recreate an evicted cache root", store.exists())
        } finally { owner.close() }
    }

    @Test fun activeOwnersSurviveRepeatedSweepsAndDeleteOnlyOnClose() = fixture { root ->
        val store = File(root, "store")
        val first = Mango9AttachmentDirectory.create(store)
        val second = Mango9AttachmentDirectory.create(store)
        try {
            val a = File(first.directory, "source").apply { writeText("draft") }
            val b = File(second.directory, "source").apply { writeText("upload") }
            repeat(10) { Mango9AttachmentDirectory.collectOrphans(store) }
            assertEquals("draft", a.readText())
            assertEquals("upload", b.readText())
            first.close()
            first.close()
            assertFalse(first.directory.exists())
            assertTrue(b.exists())
        } finally { first.close(); second.close() }
        assertEquals(listOf(Mango9AttachmentDirectory.ROOT_LOCK), store.list()!!.toList())
    }

    @Test fun unknownDirectoriesAndSymlinksAreNotFollowed() = fixture { root ->
        val store = File(root, "store").apply { mkdir() }
        val original = File(root, "original").apply { mkdir() }
        val keep = File(original, "keep").apply { writeText("original") }
        val unknown = File(store, UUID.randomUUID().toString()).apply { mkdir() }
        File(unknown, "source").writeText("legacy")
        val malformed = orphan(store, marker = "not an ownership record")
        val link = File(store, UUID.randomUUID().toString()).toPath()
        Files.createSymbolicLink(link, original.toPath())
        val marked = orphan(store)
        Files.createSymbolicLink(File(marked, "linked-original").toPath(), original.toPath())
        Mango9AttachmentDirectory.collectOrphans(store)
        assertTrue(Files.isSymbolicLink(link))
        assertEquals("original", keep.readText())
        assertTrue(unknown.exists())
        assertTrue(malformed.exists())
        assertFalse(marked.exists())
        // File.deleteRecursively follows directory symlinks on some runtimes; remove links first.
        Files.delete(link)
    }

    @Test fun externalCameraGracePreservesRecentlyAbandonedOutputs() = fixture { root ->
        val store = File(root, "store").apply { mkdir() }
        val future = orphan(store, marker = marker(System.currentTimeMillis() + 60_000))
        val expired = orphan(store, marker = marker(System.currentTimeMillis() - 60_000))
        Mango9AttachmentDirectory.collectOrphans(store)
        assertTrue(future.exists())
        assertFalse(expired.exists())
        Mango9AttachmentDirectory.create(store, Mango9AttachmentDirectory.CAMERA_GRACE_MILLIS).use { owner ->
            val value = File(owner.directory, Mango9AttachmentDirectory.OWNER_MARKER).readText().substringAfterLast('\n').toLong()
            assertTrue(value > System.currentTimeMillis() + 23L * 60 * 60 * 1000)
        }
    }

    @Test fun simultaneousAllocationNeverReapsAnActiveDirectory() = fixture { root ->
        val store = File(root, "store")
        val workers = Executors.newFixedThreadPool(6)
        try {
            val tasks = (0 until 36).map {
                workers.submit<Unit> {
                    Mango9AttachmentDirectory.create(store).use { owner ->
                        val source = File(owner.directory, "source").apply { writeText("kept") }
                        Mango9AttachmentDirectory.collectOrphans(store)
                        assertEquals("kept", source.readText())
                    }
                }
            }
            tasks.forEach { it.get(15, TimeUnit.SECONDS) }
            assertEquals(listOf(Mango9AttachmentDirectory.ROOT_LOCK), store.list()!!.toList())
        } finally { workers.shutdownNow() }
    }

    @Test fun separateProcessOwnerSurvivesUntilProcessDies() = fixture { root ->
        val store = File(root, "store").apply { mkdir() }
        val directory = orphan(store)
        val child = child(root, File(directory, Mango9AttachmentDirectory.OWNER_MARKER), "hold")
        try {
            assertEquals("ACQUIRED", child.inputStream.bufferedReader().readLine())
            repeat(4) { Mango9AttachmentDirectory.collectOrphans(store) }
            assertTrue(File(directory, "source").exists())
            child.destroyForcibly()
            assertTrue(child.waitFor(10, TimeUnit.SECONDS))
            Mango9AttachmentDirectory.collectOrphans(store)
            assertFalse("A dead process must not leave an unreapable media file", directory.exists())
        } finally { child.destroyForcibly(); child.waitFor(10, TimeUnit.SECONDS) }
    }

    @Test fun sweepingLocalOwnerDoesNotDropItsRealOperatingSystemLock() = fixture { root ->
        val store = File(root, "store")
        Mango9AttachmentDirectory.create(store).use { owner ->
            repeat(5) { Mango9AttachmentDirectory.collectOrphans(store) }
            val child = child(root, File(owner.directory, Mango9AttachmentDirectory.OWNER_MARKER), "probe")
            try {
                assertTrue(child.waitFor(15, TimeUnit.SECONDS))
                assertEquals("BUSY", child.inputStream.bufferedReader().readLine())
                assertEquals(0, child.exitValue())
            } finally { child.destroyForcibly() }
        }
    }

    private fun marker(reapAfter: Long) = "Mango9 attachment v1\n$reapAfter"

    private fun orphan(root: File, marker: String = marker(0)): File = File(root, UUID.randomUUID().toString()).apply {
        // A normally allocated store keeps this inode after a process dies. Missing-lock
        // eviction is a separate case tested above, not a complete abandoned allocation.
        File(root, Mango9AttachmentDirectory.ROOT_LOCK).createNewFile()
        mkdir()
        File(this, Mango9AttachmentDirectory.OWNER_MARKER).writeText(marker)
        File(this, "source").writeText("owned snapshot")
    }

    private fun child(root: File, marker: File, mode: String): Process {
        val source = File(root, "AttachmentLeaseProcess.java")
        javaClass.getResourceAsStream("/AttachmentLeaseProcess.source")!!.use { input -> source.outputStream().use(input::copyTo) }
        return ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").path, source.path, marker.path, mode,
        ).redirectErrorStream(true).start()
    }

    private fun fixture(block: (File) -> Unit) {
        val root = Files.createTempDirectory("mango9-attachment-directory-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
}
