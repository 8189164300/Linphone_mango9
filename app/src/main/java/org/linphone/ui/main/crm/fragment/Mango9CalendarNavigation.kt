/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.ui.main.crm.fragment

import android.content.Context
import android.view.View
import android.widget.LinearLayout

/** Keep every action reachable instead of compressing the mode label to zero width. */
internal class Mango9CalendarNavigation
    @JvmOverloads
    constructor(context: Context, private val choices: LinearLayout = LinearLayout(context), private val actions: View = LinearLayout(context)) : LinearLayout(context) {
    init {
        tag = "calendar.navigation"
        addView(choices, LayoutParams(0, -2, 1f))
        addView(actions, LayoutParams(-2, -2))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val natural = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        choices.orientation = HORIZONTAL
        choices.measure(natural, natural); actions.measure(natural, natural)
        val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val split = choices.measuredWidth + actions.measuredWidth > available
        if (choices.measuredWidth > available) choices.orientation = VERTICAL
        orientation = if (split) VERTICAL else HORIZONTAL
        (choices.layoutParams as LayoutParams).apply { width = if (split) -1 else 0; weight = if (split) 0f else 1f }
        (actions.layoutParams as LayoutParams).width = if (split) -1 else -2
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
