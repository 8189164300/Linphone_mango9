// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal object Mango9MmsImages {
    // Preserve animated GIF/WebP; never silently replace an animation with its first frame.
    fun needsConversion(mime: String, size: Long, budget: Long): Boolean =
        mime in setOf("image/jpeg", "image/png", "image/bmp", "image/heic", "image/heif") &&
            (size > budget || mime in setOf("image/bmp", "image/heic", "image/heif"))

    suspend fun compress(source: File, output: File, budget: Long) = withContext(Dispatchers.IO) {
        // ImageDecoder applies the source EXIF orientation before we strip metadata in the JPEG.
        val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val scale = minOf(1.0, 1280.0 / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
        }
        var bitmap: Bitmap? = null
        try {
            bitmap = Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply { drawColor(Color.WHITE); drawBitmap(decoded, 0f, 0f, null) }
            repeat(4) {
                for (quality in listOf(85, 70, 55, 40)) {
                    currentCoroutineContext().ensureActive()
                    output.outputStream().use { stream -> check(requireNotNull(bitmap).compress(Bitmap.CompressFormat.JPEG, quality, stream)) }
                    if (output.length() in 1..budget) return@withContext
                }
                val previous = requireNotNull(bitmap)
                bitmap = Bitmap.createScaledBitmap(previous, (previous.width * 3 / 4).coerceAtLeast(1), (previous.height * 3 / 4).coerceAtLeast(1), true)
                if (bitmap !== previous) previous.recycle()
            }
            throw Mango9AttachmentFailure(Mango9AttachmentFailure.Reason.MMS_TOO_LARGE)
        } finally { bitmap?.recycle(); decoded.recycle() }
    }
}
