// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/** A private snapshot directory, locked until its last writer/reader releases ownership.
 * All operations perform disk I/O and must run off the main thread.
 */
internal class Mango9AttachmentDirectory private constructor(
    val directory: File,
    private val channel: FileChannel,
    private val lease: FileLock,
) : Closeable {
    private var closed = false

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        synchronized(rootMutex) {
            try {
                // Keep the lease held while unlinking; another allocator cannot reap a live writer.
                withRootLock(directory.parentFile!!, create = false) { removeTree(directory.toPath()) }
            } catch (_: IOException) {
                // A later sweep retries abandoned files; failing cleanup must not fail a sent message.
            } finally {
                runCatching { lease.release() }
                runCatching { channel.close() }
                activeDirectories.remove(directory.absolutePath)
            }
        }
    }

    companion object {
        internal const val ROOT_LOCK = ".store-lock"
        internal const val OWNER_MARKER = ".owner-v1"
        private const val MAGIC = "Mango9 attachment v1\n"
        const val CAMERA_GRACE_MILLIS = 24L * 60 * 60 * 1000
        // FileChannel locks overlap (rather than block) inside one JVM. Serialize root mutations
        // locally as well as between processes; never hold this mutex while copying/exporting.
        private val rootMutex = Any()
        private val activeDirectories = mutableSetOf<String>()

        fun create(root: File, orphanGraceMillis: Long = 0): Mango9AttachmentDirectory {
            require(orphanGraceMillis >= 0 && orphanGraceMillis <= CAMERA_GRACE_MILLIS)
            return withRootLock(root) { storage ->
                collectUnlocked(storage, System.currentTimeMillis())
                val directory = File(storage, UUID.randomUUID().toString())
                if (!directory.mkdir()) throw IOException("Unable to prepare attachment storage.")
                var channel: FileChannel? = null
                var lease: FileLock? = null
                try {
                    channel = FileChannel.open(File(directory, OWNER_MARKER).toPath(), CREATE, READ, WRITE, NOFOLLOW_LINKS)
                    lease = channel.lock()
                    val reapAfter = System.currentTimeMillis() + orphanGraceMillis
                    val bytes = ByteBuffer.wrap((MAGIC + reapAfter).toByteArray(Charsets.US_ASCII))
                    while (bytes.hasRemaining()) channel.write(bytes)
                    channel.force(true)
                    activeDirectories.add(directory.absolutePath)
                    Mango9AttachmentDirectory(directory, channel, lease)
                } catch (error: Throwable) {
                    try { removeTree(directory.toPath()) } finally {
                        try { lease?.release() } finally { channel?.close() }
                    }
                    throw error
                }
            }
        }

        fun collectOrphans(root: File) {
            if (!root.exists()) return
            try {
                withRootLock(root, create = false) { storage -> collectUnlocked(storage, System.currentTimeMillis()) }
            } catch (_: NoSuchFileException) {
                // Cache eviction is normal. Only a subsequent allocation may create a new store.
            }
        }

        private fun collectUnlocked(root: File, now: Long) {
            root.listFiles().orEmpty().forEach { directory ->
                // POSIX close() on a second descriptor can release this process's fcntl locks.
                // Never open another channel for a marker already owned by this process.
                if (directory.absolutePath in activeDirectories) return@forEach
                if (runCatching { UUID.fromString(directory.name).toString() }.getOrNull() != directory.name ||
                    !Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS)
                ) {
                    return@forEach
                }
                val marker = File(directory, OWNER_MARKER).toPath()
                // Do not assume ownership of legacy, unknown or symlinked files.
                if (!Files.isRegularFile(marker, NOFOLLOW_LINKS)) return@forEach
                try {
                    FileChannel.open(marker, READ, WRITE, NOFOLLOW_LINKS).use { channel ->
                        val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                        if (lock != null) {
                            lock.use owner@{
                                if (channel.size() !in MAGIC.length.toLong()..128L) return@owner
                                val bytes = ByteBuffer.allocate(128)
                                while (bytes.hasRemaining() && channel.read(bytes) > 0) { /* small, bounded marker */ }
                                val value = String(bytes.array(), 0, bytes.position(), Charsets.US_ASCII)
                                val reapAfter = value.takeIf { it.startsWith(MAGIC) }?.removePrefix(MAGIC)?.toLongOrNull()
                                if (reapAfter != null && reapAfter <= now) removeTree(directory.toPath())
                            }
                        }
                    }
                } catch (_: IOException) { /* Missing/inaccessible files can be retried next time. */ }
            }
        }

        private fun <T> withRootLock(root: File, create: Boolean = true, action: (File) -> T): T = synchronized(rootMutex) {
            if (!root.exists()) {
                if (!create) throw NoSuchFileException(root.path)
                if (!root.mkdirs()) throw IOException("Attachment storage is unavailable.")
            }
            if (!Files.isDirectory(root.toPath(), NOFOLLOW_LINKS)) throw IOException("Invalid attachment storage.")
            val storage = root.canonicalFile
            val options: Array<OpenOption> = if (create) arrayOf(CREATE, WRITE, NOFOLLOW_LINKS) else arrayOf(WRITE, NOFOLLOW_LINKS)
            FileChannel.open(File(storage, ROOT_LOCK).toPath(), *options).use { channel ->
                channel.lock().use { action(storage) }
            }
        }

        /** walkFileTree does not follow symlinks, including links inside an owned directory. */
        private fun removeTree(root: Path) {
            if (!Files.exists(root, NOFOLLOW_LINKS)) return
            val marker = root.resolve(OWNER_MARKER)
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    if (file != marker) Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(directory: Path, error: IOException?): FileVisitResult {
                    if (error != null) throw error
                    // Retain the ownership marker until all payloads are gone, so partial cleanup
                    // failures remain identifiable and retryable on the next sweep.
                    if (directory == root) Files.deleteIfExists(marker)
                    Files.deleteIfExists(directory)
                    return FileVisitResult.CONTINUE
                }
            }
            )
        }
    }
}
