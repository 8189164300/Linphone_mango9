/*
 * Copyright (c) 2010-2022 Belledonne Communications SARL.
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
package org.linphone.contacts

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.graphics.drawable.IconCompat
import org.linphone.R
import androidx.core.graphics.createBitmap

class AvatarGenerator(private val context: Context) {
    private var textSize = context.resources.getDimension(R.dimen.avatar_initials_text_size)
    private var avatarSize = context.resources.getDimensionPixelSize(R.dimen.avatar_list_cell_size)
    private var initials = ""

    fun setTextSize(size: Float) = apply {
        textSize = size
    }

    fun setAvatarSize(size: Int) = apply {
        avatarSize = size
    }

    fun setInitials(label: String) = apply {
        initials = label
    }

    fun buildBitmap(useTransparentBackground: Boolean): Bitmap {
        // Bitmaps are only needed for platform notifications and shortcuts.
        // List/detail/call views use the drawable directly, at their own size.
        val edge = avatarSize.coerceIn(1, 1024)
        val bitmap = createBitmap(edge, edge)
        ContactAvatarDrawable(context, initials, edge, textSize, useTransparentBackground).apply {
            setBounds(0, 0, edge, edge)
            draw(Canvas(bitmap))
        }
        return bitmap
    }

    fun buildIcon(): IconCompat {
        return IconCompat.createWithAdaptiveBitmap(buildBitmap(false))
    }

}
