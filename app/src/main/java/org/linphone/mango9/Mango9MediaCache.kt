package org.linphone.mango9

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Download on demand, off the UI thread, for the existing native media viewer. */
internal object Mango9MediaCache {
    private const val MAX_BYTES = 50L * 1024 * 1024
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(false)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    suspend fun localFile(context: Context, media: Mango9ChatMedia): File = withContext(Dispatchers.IO) {
        val url = Mango9Configuration.verifiedHttpsUrl(media.url)
            ?: error("Invalid media address")
        val directory = File(context.cacheDir, "mango9-media").apply { mkdirs() }
        val hash = MessageDigest.getInstance("SHA-256").digest(media.url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val extension = extensionFor(media)
        val destination = File(directory, "$hash.$extension")
        if (destination.isFile && destination.length() in 1..MAX_BYTES) return@withContext destination
        val temporary = File.createTempFile("download-", ".part", directory)
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                check(response.isSuccessful) { "Media download failed" }
                val body = response.body ?: error("Empty attachment")
                check(body.contentLength() <= MAX_BYTES) { "Attachment is too large" }
                body.byteStream().use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var total = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            check(total <= MAX_BYTES) { "Attachment is too large" }
                            output.write(buffer, 0, count)
                        }
                        check(total > 0) { "Empty attachment" }
                    }
                }
            }
            check(temporary.renameTo(destination)) { "Cannot cache attachment" }
            destination
        } finally {
            temporary.delete()
        }
    }

    internal fun extensionFor(media: Mango9ChatMedia): String = when (media.mimeType.lowercase()) {
        "audio/mp4", "audio/x-m4a" -> "m4a"
        "audio/mpeg" -> "mp3"
        "audio/wav", "audio/x-wav" -> "wav"
        "audio/ogg" -> "ogg"
        "video/mp4" -> "mp4"
        "video/quicktime" -> "mov"
        "video/webm" -> "webm"
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "application/pdf" -> "pdf"
        else -> media.name.substringAfterLast('.', "").lowercase()
            .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "bin"
    }
}
