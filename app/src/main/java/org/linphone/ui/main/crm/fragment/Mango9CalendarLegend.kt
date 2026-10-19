/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.ui.main.crm.fragment

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale
import org.linphone.R
import org.linphone.mango9.Mango9AppointmentAppearance

/** Compact footer; the native recycled list exposes every key without clipping labels. */
internal object Mango9CalendarLegend {
    fun humanize(value: String): String = value.replace('_', ' ').split(' ').joinToString(" ") { word ->
        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }

    private fun name(context: Context, entry: Mango9AppointmentAppearance.LegendEntry): String = when (entry.kind) {
        Mango9AppointmentAppearance.LegendKind.Recurring -> context.getString(R.string.mango9_calendar_recurring)
        Mango9AppointmentAppearance.LegendKind.Status -> entry.value
        Mango9AppointmentAppearance.LegendKind.Priority -> context.getString(R.string.mango9_calendar_priority_legend, humanize(entry.value))
        Mango9AppointmentAppearance.LegendKind.OutsideHours -> context.getString(R.string.mango9_calendar_outside_hours)
    }

    fun summary(context: Context, entries: List<Mango9AppointmentAppearance.LegendEntry>): CharSequence =
        SpannableStringBuilder(context.getString(R.string.mango9_calendar_legend) + " ▸\n").apply {
            entries.take(8).forEachIndexed { index, entry ->
                if (index > 0) append("   ")
                val start = length
                append(if (entry.rgb == null) "↻ " else "● ")
                entry.rgb?.let { setSpan(ForegroundColorSpan(it or (0xff shl 24)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                append(name(context, entry))
            }
            if (entries.size > 8) append(" …")
        }

    fun dialog(context: Context, entries: List<Mango9AppointmentAppearance.LegendEntry>): AlertDialog {
        val adapter = object : ArrayAdapter<Mango9AppointmentAppearance.LegendEntry>(context, android.R.layout.simple_list_item_1, entries) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val dp: (Int) -> Int = { Mango9AppointmentAgendaAdapter.dp(context, it) }
                val item = getItem(position)!!
                return ((convertView as? TextView) ?: TextView(context)).apply {
                    text = name(context, item); textSize = 14f; setTextColor(context.getColor(R.color.mango9_calendar_ink))
                    minHeight = dp(48); gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(dp(18), dp(10), dp(18), dp(10)); compoundDrawablePadding = dp(14)
                    val swatch = if (item.kind == Mango9AppointmentAppearance.LegendKind.Recurring) {
                        androidx.appcompat.content.res.AppCompatResources.getDrawable(context, org.linphone.R.drawable.arrow_clockwise)?.mutate()?.apply {
                            setTint(context.getColor(R.color.mango9_calendar_secondary))
                        }
                    } else {
                        GradientDrawable().apply {
                            shape = GradientDrawable.OVAL; setColor(requireNotNull(item.rgb) or (0xff shl 24))
                            setStroke(dp(1), context.getColor(R.color.mango9_calendar_secondary))
                        }
                    }
                    swatch?.setBounds(0, 0, dp(16), dp(16))
                    setCompoundDrawablesRelative(swatch, null, null, null)
                    contentDescription = text
                    tag = "calendar.legend.${item.kind}.${item.value}"
                }
            }

            override fun isEnabled(position: Int) = false
        }
        return MaterialAlertDialogBuilder(context).setTitle(R.string.mango9_calendar_legend)
            .setAdapter(adapter, null).setPositiveButton(R.string.mango9_calendar_done, null).create()
    }
}
