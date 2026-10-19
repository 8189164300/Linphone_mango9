// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Only UUID-owned snapshots are deleted. Provider originals are never modified. */
internal class Mango9PreparedAttachment private constructor(
    val attachment: Mango9PendingAttachment,
    private val owner: Owner,
) : Closeable {
    private var closed = false

    @Synchronized fun retain(): Mango9PreparedAttachment {
        check(!closed)
        owner.retain()
        return Mango9PreparedAttachment(attachment, owner)
    }

    @Synchronized override fun close() {
        if (!closed) { closed = true; owner.release() }
    }

    private class Owner(private val directory: Mango9AttachmentDirectory) {
        private var references = 1

        @Synchronized fun retain() { check(references > 0); references++ }

        @Synchronized fun release() {
            check(references > 0)
            if (--references == 0) cleanup.launch { directory.close() }
        }
    }

    companion object {
        private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

        internal fun owned(directory: Mango9AttachmentDirectory, file: File, name: String, mime: String): Mango9PreparedAttachment {
            require(file.parentFile == directory.directory)
            return Mango9PreparedAttachment(Mango9PendingAttachment(Uri.fromFile(file), name, mime, file.length()), Owner(directory))
        }

        /** Acquires ownership off-main, including the cancellation race at the dispatcher boundary. */
        internal suspend fun createCapture(context: Context, name: String, mime: String): Mango9PreparedAttachment {
            require(name in setOf("photo.jpg", "voice.m4a"))
            var result: Mango9PreparedAttachment? = null
            try {
                withContext(Dispatchers.IO) {
                    val directory = Mango9AttachmentDirectory.create(
                        File(context.cacheDir, "mango9-capture"), Mango9AttachmentDirectory.CAMERA_GRACE_MILLIS,
                    )
                    try {
                        val file = File(directory.directory, name)
                        check(file.createNewFile())
                        result = owned(directory, file, name, mime)
                    } catch (error: Throwable) { directory.close(); throw error }
                }
                currentCoroutineContext().ensureActive()
                return requireNotNull(result)
            } catch (error: Throwable) { result?.close(); throw error }
        }
    }
}

internal class Mango9AttachmentPreparer(
    context: Context,
    private val root: File = File(context.cacheDir, "mango9-outgoing"),
    private val byteLimit: Long = MAX_BYTES,
    private val videoExporter: suspend (File, File) -> Unit = { source, output ->
        Mango9MediaExporter(context.applicationContext).exportVideo(source, output)
    },
    private val audioExporter: suspend (File, File) -> Unit = { source, output ->
        Mango9MediaExporter(context.applicationContext).exportAudio(source, output)
    },
    private val mmsByteLimit: Long? = null,
) {
    private val appContext = context.applicationContext
    private val resolver = context.applicationContext.contentResolver

    suspend fun prepare(uri: Uri): Mango9PreparedAttachment = prepare(uri, null)

    suspend fun prepare(uri: Uri, mimeHint: String?): Mango9PreparedAttachment = slots.withPermit {
        var directory: Mango9AttachmentDirectory? = null
        var result: Mango9PreparedAttachment? = null
        try {
            withTimeout(120_000) {
                withContext(Dispatchers.IO) {
                    directory = Mango9AttachmentDirectory.create(root)
                }
                val source = File(requireNotNull(directory).directory, "source")
                val metadata = copySource(uri, source, mimeHint)
                val preparation = if (metadata.mime.startsWith("video/") || metadata.mime.startsWith("audio/")) {
                    withContext(Dispatchers.IO) { Mango9AttachmentInspection.inspect(source) }
                } else {
                    null
                }
                val output: File
                val name: String
                val mime: String
                if (preparation == Mango9AttachmentInspection.Preparation.Video) {
                    output = File(requireNotNull(directory).directory, "video.mp4")
                    if (mmsByteLimit != null) {
                        Mango9MediaExporter(appContext).exportMmsVideo(source, output, mmsByteLimit)
                    } else {
                        videoExporter(source, output)
                    }
                    name = metadata.name.substringBeforeLast('.', metadata.name).take(210) + ".mp4"
                    mime = "video/mp4"
                } else if (preparation == Mango9AttachmentInspection.Preparation.Audio && metadata.mime in standaloneAudioTypes) {
                    // Ordinary MP3/WAV/etc attachments already have an unambiguous audio type.
                    // Do not add a lossy conversion simply because we inspected their tracks.
                    output = source
                    name = metadata.name
                    mime = metadata.mime
                } else if (preparation != null) {
                    output = if (preparation == Mango9AttachmentInspection.Preparation.ReadyAudio) {
                        source
                    } else {
                        File(requireNotNull(directory).directory, "audio.m4a").also { audioExporter(source, it) }
                    }
                    name = metadata.name.substringBeforeLast('.', metadata.name).take(210) + ".m4a"
                    mime = "audio/mp4"
                } else if (mmsByteLimit != null && Mango9MmsImages.needsConversion(metadata.mime, source.length(), mmsByteLimit)) {
                    output = File(requireNotNull(directory).directory, "photo.jpg")
                    Mango9MmsImages.compress(source, output, mmsByteLimit)
                    name = metadata.name.substringBeforeLast('.', metadata.name).take(210) + ".jpg"
                    mime = "image/jpeg"
                } else {
                    output = source
                    name = metadata.name
                    mime = metadata.mime
                }
                withContext(Dispatchers.IO) {
                    if (!output.isFile || output.length() <= 0) throw Mango9AttachmentFailure(Mango9AttachmentFailure.Reason.EMPTY)
                    if (mmsByteLimit != null && output.length() > mmsByteLimit) throw Mango9AttachmentFailure(Mango9AttachmentFailure.Reason.MMS_TOO_LARGE)
                    if (output.length() > byteLimit) throw Mango9AttachmentFailure(Mango9AttachmentFailure.Reason.TOO_LARGE)
                    if (output != source) source.delete()
                    result = Mango9PreparedAttachment.owned(requireNotNull(directory), output, name, mime)
                }
            }
            currentCoroutineContext().ensureActive()
            requireNotNull(result)
        } catch (error: Throwable) {
            // The decoder/copy has stopped before this runs; no file is deleted under a writer.
            result?.close()
            if (result == null) withContext(NonCancellable + Dispatchers.IO) { directory?.close() }
            if (error is TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                throw Mango9AttachmentFailure(Mango9AttachmentFailure.Reason.TIMEOUT, error)
            }
            throw error
        }
    }

    private data class Metadata(val name: String, val mime: String)

    private suspend fun copySource(uri: Uri, output: File, mimeHint: String?): Metadata = coroutineScope {
        require(uri.scheme == "content" || uri.scheme == "file") { "This attachment location is not supported." }
        val cancellation = CancellationSignal()
        val opened = AtomicReference<Closeable?>()
        val worker = async(Dispatchers.IO) {
            try {
            val job = currentCoroutineContext()
            var suppliedName: String? = null
            if (uri.scheme == "content") {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null, cancellation)?.use { cursor ->
                    val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (column >= 0 && cursor.moveToFirst()) suppliedName = cursor.getString(column)
                }
            } else {
                suppliedName = uri.path?.let(::File)?.name
            }
            val name = safeName(suppliedName ?: uri.lastPathSegment.orEmpty())
            val extension = name.substringAfterLast('.', "").lowercase()
            val reportedMime = if (uri.scheme == "content") resolver.getType(uri) else null
            val mime = Mango9MediaType.explicit(reportedMime) ?: Mango9MediaType.explicit(mimeHint)
                ?: Mango9MediaType.explicit(Mango9MediaType.fromExtension(extension))
                ?: Mango9MediaType.normalized(MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension))
                ?: "application/octet-stream"
            job.ensureActive()
            val stream = if (uri.scheme == "file") {
                File(requireNotNull(uri.path)).inputStream()
            } else {
                val descriptor = resolver.openAssetFileDescriptor(uri, "r", cancellation)
                    ?: throw Mango9AttachmentFailure(Mango9AttachmentFailure.Reason.UNAVAILABLE)
                try { descriptor.createInputStream() } catch (error: Throwable) { descriptor.close(); throw error }
            }
            opened.set(stream)
            try {
                stream.use { input ->
                    output.outputStream().use { destination ->
                    val buffer = ByteArray(32 * 1024)
                    var count = 0L
                    while (true) {
                        job.ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        count += read
                        if (count > byteLimit) throw Mango9AttachmentFailure(Mango9AttachmentFailure.Reason.TOO_LARGE)
                        destination.write(buffer, 0, read)
                    }
                    if (count == 0L) throw Mango9AttachmentFailure(Mango9AttachmentFailure.Reason.EMPTY)
                }
                }
            } finally { opened.compareAndSet(stream, null) }
                Metadata(name, mime)
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                throw error
            }
        }
        try { worker.await() } finally {
            // CancellationSignal interrupts provider query/open; closing the stream wakes pipe reads.
            // Await the actual worker before deleting its partial snapshot.
            withContext(NonCancellable + Dispatchers.IO) {
                cancellation.cancel()
                runCatching { opened.getAndSet(null)?.close() }
                worker.cancelAndJoin()
            }
        }
    }

    companion object {
        const val MAX_BYTES = 50L * 1024 * 1024
        private val slots = Semaphore(2)
        private val standaloneAudioTypes = setOf(
            "audio/mpeg", "audio/wav", "audio/x-wav", "audio/ogg", "audio/flac", "audio/aac", "audio/amr",
        )

        internal fun safeName(value: String): String = value.substringAfterLast('/').substringAfterLast('\\')
            .filter { !it.isISOControl() }.trim().take(230).ifBlank { "attachment" }
    }
}
