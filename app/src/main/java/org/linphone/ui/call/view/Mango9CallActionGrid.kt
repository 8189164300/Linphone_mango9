/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.ui.call.view

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.max

/** Fits call actions to the actual translated label metrics, including enlarged system text. */
class Mango9CallActionGrid
    @JvmOverloads
    constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GridLayout(context, attrs) {
    private var configuredChildren = -1

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(1)
        val density = resources.displayMetrics.density
        var minimumCellWidth = 88f * density
        for (index in 0 until childCount) {
            val cell = getChildAt(index) as? ViewGroup ?: continue
            for (item in 0 until cell.childCount) {
                val label = cell.getChildAt(item) as? TextView ?: continue
                if (label.labelFor == View.NO_ID) continue
                // Keep whole words together where possible without shrinking accessible text.
                for (word in label.text.split(WHITESPACE)) {
                    minimumCellWidth = max(minimumCellWidth, label.paint.measureText(word) + 24f * density)
                }
            }
        }
        val columns = (available / ceil(minimumCellWidth).toInt().coerceAtLeast(1)).coerceIn(1, childCount.coerceAtLeast(1))
        if (columnCount != columns || configuredChildren != childCount) {
            // Reset auto-assigned specs before reducing columns after rotation or a text change.
            for (index in 0 until childCount) {
                getChildAt(index).layoutParams = LayoutParams().apply {
                    width = 0
                    height = LayoutParams.WRAP_CONTENT
                    columnSpec = spec(UNDEFINED, FILL, 1f)
                    rowSpec = spec(UNDEFINED, TOP)
                }
            }
            columnCount = columns
            configuredChildren = childCount
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
