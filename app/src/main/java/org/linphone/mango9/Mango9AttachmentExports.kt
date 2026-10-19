// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.linphone.R

/** User-requested exports use system providers, scoped storage and read-only URI grants. */
internal object Mango9AttachmentExports {
    fun fileName(media: Mango9ChatMedia): String = Mango9AttachmentPreparer.safeName(media.name).takeUnless {
        it == "." || it == ".."
    }.orEmpty().ifBlank { "attachment" }.let {
        if (it.substringAfterLast('.', "").isBlank()) "$it.${Mango9MediaCache.extensionFor(media)}" else it
    }

    fun createDocumentIntent(media: Mango9ChatMedia) = Intent(Intent.ACTION_CREATE_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE).setType(media.mimeType)
        .putExtra(Intent.EXTRA_TITLE, fileName(media))

    fun openIntent(context: Context, file: File, media: Mango9ChatMedia): Intent {
        val uri = FileProvider.getUriForFile(context, context.getString(R.string.file_provider), file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, media.mimeType)
            .apply { clipData = ClipData.newRawUri(fileName(media), uri) }
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    suspend fun copy(input: InputStream, output: OutputStream, isCurrent: () -> Boolean) {
        val buffer = ByteArray(32 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            check(isCurrent()) { "This account is no longer active." }
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
        }
    }

    suspend fun saveDocument(context: Context, source: File, uri: Uri, isCurrent: () -> Boolean) = withContext(Dispatchers.IO) {
        check(isCurrent())
        source.inputStream().use { input ->
            requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { output -> copy(input, output, isCurrent) }
        }
    }

    suspend fun saveGallery(context: Context, source: File, media: Mango9ChatMedia, isCurrent: () -> Boolean): Uri = withContext(Dispatchers.IO) {
        check(Build.VERSION.SDK_INT >= 29)
        check(media.kind in setOf(Mango9ChatMedia.Kind.Image, Mango9ChatMedia.Kind.Video))
        check(isCurrent())
        val image = media.kind == Mango9ChatMedia.Kind.Image
        val collection = if (image) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName(media))
            put(MediaStore.MediaColumns.MIME_TYPE, media.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${if (image) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_MOVIES}/Mango9")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(collection, values))
        try {
            saveDocument(context, source, uri, isCurrent)
            check(isCurrent())
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
            uri
        } catch (failure: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw failure
        }
    }

    /** Explicit export copies outlive the chooser. The receiver never depends on a cache lease. */
    suspend fun externalCopy(context: Context, source: File, media: Mango9ChatMedia, isCurrent: () -> Boolean): File = withContext(Dispatchers.IO) {
        val root = File(context.cacheDir, "mango9-exports").apply { mkdirs() }
        val expiry = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        root.listFiles().orEmpty().filter {
            !Files.isSymbolicLink(it.toPath()) && it.isDirectory && it.lastModified() < expiry &&
                runCatching { UUID.fromString(it.name).toString() == it.name }.getOrDefault(false)
        }.forEach { it.deleteRecursively() }
        val directory = File(root, UUID.randomUUID().toString()).apply { check(mkdir()) }
        val output = File(directory, fileName(media))
        try {
            source.inputStream().use { input -> output.outputStream().use { copy(input, it, isCurrent) } }
            output
        } catch (failure: Throwable) { directory.deleteRecursively(); throw failure }
    }
}
