// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.ui.main.crm.fragment

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import org.linphone.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Shared, native sizing keeps controls compact without shrinking their touch targets. */
internal class Mango9LiveViews(val context: Context) {
    val ink = color(android.R.attr.textColorPrimary)
    val secondary = color(android.R.attr.textColorSecondary)
    val surface = color(android.R.attr.colorBackground)
    val accent = ContextCompat.getColor(context, R.color.orange_main_500)
    val tinted get() = (accent and 0x00ffffff) or 0x14000000
    fun dp(value: Int) = (context.resources.displayMetrics.density * value).toInt()
    fun color(attribute: Int): Int {
        val values = context.obtainStyledAttributes(intArrayOf(attribute))
        return try { values.getColor(0, 0xff333333.toInt()) } finally { values.recycle() }
    }
    fun round(color: Int, radius: Int = 12) = GradientDrawable().apply { cornerRadius = dp(radius).toFloat(); setColor(color) }
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL }
    fun text(value: String = "", size: Float = 14f, bold: Boolean = false) = AppCompatTextView(context).apply {
        text = value; textSize = size; setTextColor(ink)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    fun button(value: String, action: () -> Unit) = text(value).apply {
        gravity = Gravity.CENTER; minHeight = dp(44); setTextColor(accent)
        setPadding(dp(8), 0, dp(8), 0)
        val typed = TypedValue(); context.theme.resolveAttribute(android.R.attr.selectableItemBackground, typed, true)
        setBackgroundResource(typed.resourceId); setOnClickListener { action() }
    }
    fun icon(resource: Int, label: String, action: () -> Unit) = ImageButton(context).apply {
        contentDescription = label; setImageResource(resource); imageTintList = ColorStateList.valueOf(accent)
        val typed = TypedValue(); context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, typed, true)
        setBackgroundResource(typed.resourceId); setPadding(dp(11), dp(11), dp(11), dp(11))
        setOnClickListener { action() }; layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
    }
    fun oneLine(view: TextView) { view.maxLines = 1; view.ellipsize = TextUtils.TruncateAt.END }
    fun date(value: String): String = runCatching { date(Instant.parse(value).epochSecond) }.getOrDefault("")
    fun date(seconds: Long): String = if (seconds <= 0) "" else DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(seconds))
    fun padded(view: View, horizontal: Int = 12, vertical: Int = 6) = view.apply { setPadding(dp(horizontal), dp(vertical), dp(horizontal), dp(vertical)) }
    fun line() = View(context).apply { setBackgroundColor((secondary and 0xffffff) or 0x20000000); layoutParams = ViewGroup.LayoutParams(-1, dp(1)) }
}
