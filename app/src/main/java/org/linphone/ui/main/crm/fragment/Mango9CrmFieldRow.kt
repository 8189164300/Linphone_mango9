/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.ui.main.crm.fragment

import android.content.Context
import android.view.ViewGroup
import org.linphone.mango9.Mango9CrmFieldLayout

/** At most three fields. Uses the actual dialog width and never rebuilds editors while typing. */
internal class Mango9CrmFieldRow(context: Context) : ViewGroup(context) {
    private var columns = 1
    private var fieldWidth = 0
    private var rowHeights = emptyList<Int>()
    private val gap = (12 * resources.displayMetrics.density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val available = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        columns = Mango9CrmFieldLayout.columns(
            available / resources.displayMetrics.density, resources.configuration.fontScale, childCount,
        )
        fieldWidth = ((available - gap * (columns - 1)) / columns).coerceAtLeast(0)
        rowHeights = (0 until childCount).chunked(columns).map { indexes ->
            indexes.maxOf { index ->
                getChildAt(index).apply {
                    measure(MeasureSpec.makeMeasureSpec(fieldWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                }.measuredHeight
            }
        }
        setMeasuredDimension(width, resolveSize(paddingTop + paddingBottom + rowHeights.sum() + gap * (rowHeights.size - 1).coerceAtLeast(0), heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        var y = paddingTop
        rowHeights.forEachIndexed { row, height ->
            for (column in 0 until columns) {
                val child = getChildAt(row * columns + column) ?: break
                val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) {
                    width - paddingRight - (column + 1) * fieldWidth - column * gap
                } else {
                    paddingLeft + column * (fieldWidth + gap)
                }
                child.layout(x, y, x + fieldWidth, y + child.measuredHeight)
            }
            y += height + gap
        }
    }

    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
}
