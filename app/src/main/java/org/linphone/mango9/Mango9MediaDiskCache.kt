/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import coil3.disk.DiskCache
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Path.Companion.toOkioPath
import kotlin.coroutines.coroutineContext

/** A held snapshot cannot be evicted out from under a player or document viewer. */
internal class Mango9MediaAsset(
    val file: File,
    private val discard: () -> Unit = {
    },
        private val release: () -> Unit
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val invalidated = AtomicBoolean(false)

    fun invalidate() { if (invalidated.compareAndSet(false, true)) discard() }

    override fun close() { if (closed.compareAndSet(false, true)) release() }
}

/** The existing Coil cache supplies journaled writes, snapshot pinning and LRU eviction. */
internal class Mango9MediaDiskCache(
    directory: File,
    private val openDirectory: File,
    private val client: OkHttpClient,
    maxBytes: Long = 200L * 1024 * 1024,
    private val maxFileBytes: Long = 50L * 1024 * 1024,
    private val maxAgeMillis: Long = 24L * 60 * 60 * 1000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init { require(maxFileBytes > 0 && maxAgeMillis > 0) }

    private val cache = DiskCache.Builder().directory(directory.toOkioPath()).maxSizeBytes(maxBytes).build()
    private val initialized = Mutex()
    private var ready = false

    private class Pending(val mutex: Mutex = Mutex(), var readers: Int = 0)

    private val pending = mutableMapOf<String, Pending>()
    private val keys = mutableMapOf<String, Pair<String, Long>>()
    // Bound bookkeeping even for many tiny attachments that fit the byte budget.
    private val maxEntries = 512
    // Bounds in-flight files and held snapshots as well as decoder-side resources.
    private val permits = Semaphore(4)

    suspend fun acquire(owner: String, request: Request, extension: String, isCurrent: () -> Boolean): Mango9MediaAsset =
        acquire(owner, request, extension, true, isCurrent)

    suspend fun acquire(owner: String, request: Request, extension: String, cacheAllowed: Boolean, isCurrent: () -> Boolean): Mango9MediaAsset {
        require(extension.matches(Regex("[a-z0-9]{1,8}")))
        var acquired: Mango9MediaAsset? = null
        try {
            return withContext(Dispatchers.IO) {
                checkOwner(isCurrent)
                initialize()
                permits.acquire()
                var snapshot: DiskCache.Snapshot? = null
                var link: File? = null
                try {
                    if (!cacheAllowed) {
                        // VFS mode must not add a persistent plaintext byte cache.
                        // Native decoders still need a short-lived local file, which
                        // is removed on close/cancellation and uses the same bound.
                        evictOwner(owner)
                        val temporary = File(openDirectory, "${UUID.randomUUID()}.$extension")
                        link = temporary
                        download(request, temporary)
                        checkOwner(isCurrent)
                        return@withContext Mango9MediaAsset(temporary) {
                            try { temporary.delete() } finally { permits.release() }
                        }.also { acquired = it }
                    }
                    val day = clock().coerceAtLeast(0) / maxAgeMillis
                    val key = "$owner\u0000${request.url}\u0000$extension\u0000$day"
                    val gate = synchronized(pending) { pending.getOrPut(key) { Pending() }.also { it.readers++ } }
                    try {
                        gate.mutex.withLock {
                            checkOwner(isCurrent)
                            prune(day)
                            remember(key, owner, day)
                            snapshot = cache.openSnapshot(key)
                            if (snapshot == null) {
                                val editor = cache.openEditor(key) ?: throw IOException("Attachment cache is busy")
                                var committed = false
                                try {
                                    editor.metadata.toFile().writeText(day.toString())
                                    download(request, editor.data.toFile())
                                    checkOwner(isCurrent)
                                    snapshot = editor.commitAndOpenSnapshot()
                                    committed = true
                                    if (snapshot == null) throw IOException("Cannot cache attachment")
                                } finally { if (!committed) runCatching { editor.abort() } }
                            }
                        }
                    } finally {
                        synchronized(pending) { if (--gate.readers == 0) pending.remove(key) }
                    }
                    checkOwner(isCurrent)
                    val held = requireNotNull(snapshot)
                    val source = held.data.toFile()
                    check(source.length() in 1..maxFileBytes) { "Invalid cached attachment" }
                    // Keep the extension expected by existing player/export paths without
                    // renaming or modifying Coil's files. Same-filesystem links cost no copy.
                    val visible = File(openDirectory, "${UUID.randomUUID()}.$extension")
                    link = visible
                    try { Files.createLink(visible.toPath(), source.toPath()) } catch (_: IOException) {
                        source.copyTo(visible, overwrite = false)
                    } catch (_: UnsupportedOperationException) {
                        source.copyTo(visible, overwrite = false)
                    }
                    checkOwner(isCurrent)
                    Mango9MediaAsset(visible, discard = { cache.remove(key) }) {
                        try { visible.delete() } finally {
                            try { held.close() } finally { permits.release() }
                        }
                    }.also { acquired = it }
                } finally {
                    if (acquired == null) {
                        try { link?.delete() } finally {
                            try { snapshot?.close() } finally { permits.release() }
                        }
                    }
                }
            }
        } catch (failure: Throwable) {
            // withContext can discard a successfully produced result on cancellation.
            acquired?.close()
            throw failure
        }
    }

    private suspend fun initialize() = initialized.withLock {
        if (!ready) {
            // Login generations/routes are process-owned. Do not reuse a previous
            // process's account cache or orphaned exported links after a restart.
            cache.clear()
            check(openDirectory.mkdirs() || openDirectory.isDirectory)
            openDirectory.listFiles().orEmpty().filter {
                it.isFile && !Files.isSymbolicLink(it.toPath()) &&
                    it.name.matches(Regex("[a-f0-9-]{36}\\.[a-z0-9]{1,8}"))
            }.forEach { it.delete() }
            ready = true
        }
    }

    private fun prune(day: Long) {
        val expired = synchronized(keys) {
            keys.filterValues { it.second != day }.keys.toList().also { list -> list.forEach(keys::remove) }
        }
        expired.forEach(cache::remove)
    }

    private fun remember(key: String, owner: String, day: Long) {
        val expired = synchronized(keys) {
            keys.remove(key)
            keys[key] = owner to day
            val removed = mutableListOf<String>()
            while (keys.size > maxEntries) {
                val oldest = keys.keys.first()
                keys.remove(oldest)
                removed.add(oldest)
            }
            removed
        }
        expired.forEach(cache::remove)
    }

    fun retainOwners(owners: Set<String>) {
        val removed = synchronized(keys) {
            keys.filterValues { it.first !in owners }.keys.toList().also { list -> list.forEach(keys::remove) }
        }
        removed.forEach(cache::remove)
    }

    fun evictOwner(owner: String) {
        val owned = synchronized(keys) {
            keys.filterValues { it.first == owner }.keys.toList().also { list -> list.forEach(keys::remove) }
        }
        owned.forEach(cache::remove)
    }

    private suspend fun checkOwner(isCurrent: () -> Boolean) {
        coroutineContext.ensureActive()
        if (!isCurrent()) throw Mango9ApiException.Unauthorized
    }

    private suspend fun download(request: Request, destination: File) = coroutineScope {
        val call = client.newCall(request)
        val writer = async(Dispatchers.IO) {
            try {
            coroutineContext.ensureActive()
            call.execute().use { response ->
                check(response.isSuccessful) { "Media download failed" }
                val body = response.body
                check(body.contentLength() <= maxFileBytes) { "Attachment is too large" }
                body.byteStream().use { input ->
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var total = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            check(total <= maxFileBytes) { "Attachment is too large" }
                            output.write(buffer, 0, count)
                        }
                        check(total > 0) { "Empty attachment" }
                    }
                }
            }
            } catch (failure: Throwable) {
                // Closing a canceled socket may throw IOException rather than
                // CancellationException. Do not let that cancel the caller's siblings.
                coroutineContext.ensureActive()
                throw failure
            }
        }
        try { writer.await() } finally {
            call.cancel()
            // The editor must not be aborted/reused while its worker still writes.
            withContext(NonCancellable) { writer.join() }
        }
    }

    fun shutdown() { cache.shutdown() }

    internal val storedBytes: Long get() = cache.size
    internal val trackedEntries: Int get() = synchronized(keys) { keys.size }
}
