/*
 * Copyright (c) 2010-2025 Belledonne Communications SARL.
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
package org.linphone.ui.call.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import org.linphone.R

class VuMeterView : View {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val normalMeterColor = ContextCompat.getColor(context, R.color.mango9_call_meter)
    private val selectedMeterColor = ContextCompat.getColor(context, R.color.mango9_call_meter_selected)

    private var vuMeterPercentage: Float = 0f

    constructor(context: Context?) : super(context)

    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context?, attrs: AttributeSet?, defStyle: Int) : super(
        context,
        attrs,
        defStyle
    )

    fun setVuMeterPercentage(percentage: Float) {
        val level = if (percentage.isFinite()) percentage.coerceIn(0f, 1f) else 0f
        if (vuMeterPercentage == level) return
        vuMeterPercentage = level
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isEnabled || width <= 0 || height <= 0 || vuMeterPercentage <= 0f) return
        paint.color = if (isSelected) selectedMeterColor else normalMeterColor
        // The view's background remains the control's stateful drawable. Draw
        // only the level overlay, without allocating a bitmap/shader per frame.
        val radius = minOf(width, height) / 2f
        val centerY = height / 2f
        val save = canvas.save()
        try {
            canvas.clipRect(0f, centerY + radius - 2f * radius * vuMeterPercentage, width.toFloat(), height.toFloat())
            canvas.drawCircle(width / 2f, centerY, radius, paint)
        } finally { canvas.restoreToCount(save) }
    }
}
