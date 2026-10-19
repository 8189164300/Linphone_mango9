// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.content.Context
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Response
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.BufferedSink

/** Cancellation closes the socket and awaits the writer before its file pin may be released. */
internal suspend fun <T> Call.mango9UploadResult(read: (Response) -> T): T = coroutineScope {
    val request = this@mango9UploadResult
    val worker = async(Dispatchers.IO) {
        try { request.execute().use(read) } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            throw error
        }
    }
    try { worker.await() } finally {
        withContext(NonCancellable + Dispatchers.IO) { request.cancel(); worker.cancelAndJoin() }
    }
}

internal fun Response.mango9UploadBytes(limit: Int): ByteArray {
    require(limit > 0)
    val source = body.source()
    source.request(limit.toLong() + 1)
    if (source.buffer.size > limit) throw IOException("The attachment server returned an invalid response.")
    return source.buffer.readByteArray()
}

internal class Mango9AttachmentRequestBody(
    private val context: Context,
    private val attachment: Mango9PendingAttachment,
    private val checkOwner: () -> Unit,
    private val limit: Long = Mango9AttachmentPreparer.MAX_BYTES,
) : RequestBody() {
    override fun contentType() = attachment.mimeType.toMediaTypeOrNull()

    override fun contentLength(): Long = attachment.size

    override fun writeTo(sink: BufferedSink) {
        checkOwner()
        if (attachment.size <= 0 || attachment.size > limit) throw IOException("The attachment must be prepared before sending.")
        val stream = when (attachment.uri.scheme) {
            "file" -> attachment.uri.path?.let(::File)?.inputStream()
            "content" -> context.contentResolver.openInputStream(attachment.uri)
            else -> null
        } ?: throw IOException("The attachment is no longer available.")
        stream.use { input ->
            val buffer = ByteArray(32 * 1024)
            var count = 0L
            while (true) {
                checkOwner()
                val read = input.read(buffer)
                if (read == -1) break
                count += read
                if (count > limit || count > attachment.size) throw IOException("The attachment changed after preparation.")
                checkOwner()
                sink.write(buffer, 0, read)
            }
            if (count != attachment.size) throw IOException("The attachment changed after preparation.")
        }
    }
}
