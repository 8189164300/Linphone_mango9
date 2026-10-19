/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.contacts

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.TypedValue
import androidx.core.content.ContextCompat
import org.linphone.R

/** One small, view-owned drawable: no PNG encoding, file IO, bitmap or shared mutable cache. */
class ContactAvatarDrawable(
    context: Context,
    initials: String,
    private val size: Int = context.resources.getDimensionPixelSize(R.dimen.avatar_list_cell_size),
    private val textSize: Float = context.resources.getDimension(R.dimen.avatar_initials_text_size),
    private val transparentBackground: Boolean = false,
) : Drawable() {
    private val label = initials.take(32)
    private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(context, R.attr.color_avatar_background, R.color.gray_main2_200)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(context, R.attr.color_avatar_text, R.color.gray_main2_600)
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val person = if (label.isBlank()) {
        ContextCompat.getDrawable(context, R.drawable.user_circle)?.mutate()?.apply {
        setTint(text.color)
    }
    } else {
        null
    }
    private val ink = Rect()
    private var baseline = 0f
    private var radius = 0f

    override fun getIntrinsicWidth(): Int = size.coerceAtLeast(1)

    override fun getIntrinsicHeight(): Int = intrinsicWidth

    override fun onBoundsChange(bounds: Rect) {
        val edge = minOf(bounds.width(), bounds.height()).coerceAtLeast(0).toFloat()
        radius = edge / 2f
        val nominal = textSize.takeIf { it.isFinite() && it > 0 } ?: size * .35f
        text.textSize = minOf(nominal * edge / intrinsicWidth, edge * .5f)
        val measured = text.measureText(label)
        if (measured > edge * .72f) text.textSize *= edge * .72f / measured
        text.getTextBounds(label, 0, label.length, ink)
        if (ink.height() > edge * .65f) {
            text.textSize *= edge * .65f / ink.height()
            text.getTextBounds(label, 0, label.length, ink)
        }
        baseline = bounds.exactCenterY() - (ink.top + ink.bottom) / 2f
        val inset = edge * .28f
        person?.setBounds(
            (bounds.exactCenterX() - inset).toInt(), (bounds.exactCenterY() - inset).toInt(),
            (bounds.exactCenterX() + inset).toInt(), (bounds.exactCenterY() + inset).toInt(),
        )
    }

    override fun draw(canvas: Canvas) {
        if (radius <= 0f) return
        if (!transparentBackground) canvas.drawCircle(bounds.exactCenterX(), bounds.exactCenterY(), radius, background)
        if (person != null) {
            person.draw(canvas)
        } else {
            canvas.drawText(label, bounds.exactCenterX(), baseline, text)
        }
    }

    override fun setAlpha(alpha: Int) {
        text.alpha = alpha; background.alpha = alpha; person?.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        text.colorFilter = colorFilter; background.colorFilter = colorFilter; person?.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Drawable opacity is no longer used by Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun themeColor(context: Context, attr: Int, fallback: Int): Int {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(attr, value, true)) {
            if (value.resourceId != 0) ContextCompat.getColor(context, value.resourceId) else value.data
        } else {
            ContextCompat.getColor(context, fallback)
        }
    }
}
