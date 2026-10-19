/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.ui.main.crm.fragment

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.color.MaterialColors
import java.time.ZoneId
import org.linphone.R
import org.linphone.mango9.Mango9ForegroundReminderState

class Mango9ReminderBanner
    @JvmOverloads
    constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    private val title = TextView(context).apply {
        textSize = 14f; setTypeface(typeface, android.graphics.Typeface.BOLD); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        tag = "reminder.banner.title"
    }
    private val subtitle = TextView(context).apply {
        textSize = 12f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; tag = "reminder.banner.subtitle"
    }
    private val open = Button(context).apply {
        isAllCaps = false; textSize = 14f; minHeight = dp(48); tag = "reminder.banner.open"
    }
    internal var onOpen: () -> Unit = {}

    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(4), dp(8), dp(4))
        setBackgroundColor(MaterialColors.getColor(this, R.attr.color_grey_100, Color.WHITE))
        val primary = MaterialColors.getColor(this, R.attr.color_grey_600, Color.DKGRAY)
        title.setTextColor(primary); subtitle.setTextColor(primary)
        addView(
            ImageView(context).apply {
            setImageResource(R.drawable.bell_ringing); imageTintList = ColorStateList.valueOf(0xffc27713.toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        },
            LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(10) }
        )
        addView(
            LinearLayout(context).apply {
            orientation = VERTICAL; addView(title); addView(subtitle)
        },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )
        open.setTextColor(0xff444dcd.toInt()); open.setBackgroundColor(Color.TRANSPARENT)
        open.setOnClickListener { onOpen() }; addView(open, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        visibility = View.GONE
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    internal fun render(state: Mango9ForegroundReminderState) {
        val item = state.item
        visibility = if (item == null) View.GONE else View.VISIBLE
        title.text = item?.event?.title.orEmpty()
        subtitle.text = if (state.error) {
            context.getString(R.string.mango9_reminder_open_error)
        } else {
            item?.let {
            context.getString(
                R.string.mango9_reminder_banner_caption,
                Mango9CalendarDateLabels(context).time(it.event.startAt, ZoneId.systemDefault())
            )
        }.orEmpty()
        }
        open.setText(if (state.opening) R.string.mango9_reminder_opening else R.string.mango9_reminder_view)
        open.isEnabled = item != null && !state.opening
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
