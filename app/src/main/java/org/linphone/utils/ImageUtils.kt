/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import java.io.FileNotFoundException
import java.io.File
import org.linphone.core.tools.Log
import androidx.core.net.toUri
import androidx.core.graphics.createBitmap

class ImageUtils {
    companion object {
        private const val TAG = "[Image Utils]"

        @WorkerThread
        fun getBitmap(
            context: Context,
            path: String?,
            round: Boolean = false
        ): Bitmap? {
            if (!path.isNullOrBlank()) {
                try {
                    val fromPictureUri = path.toUri()
                    val source = if (fromPictureUri.scheme == null) {
                        ImageDecoder.createSource(File(path))
                    } else {
                        ImageDecoder.createSource(context.contentResolver, fromPictureUri)
                    }
                    // Only notification/contact icons use this helper. Decode
                    // small software pixels directly, not a full photo plus a copy.
                    val bitmap = ImageDecoder.decodeBitmap(
                        source
                    ) { decoder, info, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val longest = maxOf(info.size.width, info.size.height)
                        if (longest > 256) {
                            decoder.setTargetSize(
                                (info.size.width * (256.0 / longest)).toInt().coerceAtLeast(1),
                                (info.size.height * (256.0 / longest)).toInt().coerceAtLeast(1),
                            )
                        }
                    }
                    return if (round) {
                        try { getRoundBitmap(bitmap) } finally { bitmap.recycle() }
                    } else {
                        bitmap
                    }
                } catch (fnfe: FileNotFoundException) {
                    Log.w("$TAG Contact photo is no longer available")
                    return null
                } catch (e: Exception) {
                    Log.w("$TAG Could not decode contact photo: ${e.javaClass.simpleName}")
                    return null
                }
            }

            return null
        }

        @AnyThread
        private fun getRoundBitmap(bitmap: Bitmap): Bitmap {
            val edge = minOf(bitmap.width, bitmap.height)
            val output = createBitmap(edge, edge)
            val canvas = Canvas(output)
            val color = -0xbdbdbe
            val paint = Paint()
            val rect = Rect(0, 0, edge, edge)
            val left = (bitmap.width - edge) / 2
            val top = (bitmap.height - edge) / 2
            val source = Rect(left, top, left + edge, top + edge)
            paint.isAntiAlias = true
            canvas.drawARGB(0, 0, 0, 0)
            paint.color = color
            canvas.drawCircle(
                edge / 2f,
                edge / 2f,
                edge / 2f,
                paint
            )
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(bitmap, source, rect, paint)
            return output
        }
    }
}
