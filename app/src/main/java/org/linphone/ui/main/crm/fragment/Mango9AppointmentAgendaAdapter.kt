/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.ui.main.crm.fragment

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.view.View
import android.widget.ImageView
import android.content.res.ColorStateList
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.linphone.R
import org.linphone.mango9.Mango9Appointment
import org.linphone.mango9.Mango9AppointmentAgenda
import org.linphone.mango9.Mango9AppointmentAppearance

internal sealed interface Mango9AgendaRow {
    val key: String

    data class Heading(override val key: String, val text: String) : Mango9AgendaRow

    data class Task(val task: org.linphone.mango9.Mango9ManagedTask) : Mango9AgendaRow { override val key get() = "task:${task.id}" }

    data class More(override val key: String, val text: String) : Mango9AgendaRow

    data class Event(val event: Mango9Appointment, val zone: ZoneId, val minute: Long) : Mango9AgendaRow {
        override val key get() = "event:${event.displayId}"
    }
}

/** RecyclerView binds only visible cards, including days containing thousands of events. */
internal class Mango9AppointmentAgendaAdapter(private val open: (Mango9Appointment) -> Unit) : ListAdapter<Mango9AgendaRow, Mango9AppointmentAgendaAdapter.Holder>(object : DiffUtil.ItemCallback<Mango9AgendaRow>() {
    override fun areItemsTheSame(a: Mango9AgendaRow, b: Mango9AgendaRow) = a.key == b.key

    override fun areContentsTheSame(a: Mango9AgendaRow, b: Mango9AgendaRow) = a == b
}) {
    var onTask: (org.linphone.mango9.Mango9ManagedTask) -> Unit = {}
    var onMore: () -> Unit = {}

    class Holder(val box: LinearLayout) : RecyclerView.ViewHolder(box)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        LinearLayout(parent.context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = dp(context, 10) }
    }
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val box = holder.box; val ctx = box.context
        box.removeAllViews(); box.setOnClickListener(null); box.isClickable = false; box.contentDescription = null
        box.setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
        when (val row = getItem(position)) {
            is Mango9AgendaRow.Task -> {
                box.background = GradientDrawable().apply {
                    setColor(ctx.getColor(R.color.mango9_calendar_surface)); cornerRadius = dp(ctx, 14).toFloat()
                }
                box.addView(text(ctx, row.task.title, 16f, ctx.getColor(R.color.mango9_calendar_ink), true))
                box.addView(text(ctx, row.task.status.replace('_', ' '), 13f, ctx.getColor(R.color.mango9_calendar_accent)))
                box.addView(text(ctx, row.task.dueAt ?: "No due date", 13f, ctx.getColor(R.color.mango9_calendar_secondary)))
                box.addView(text(ctx, row.task.assignees.joinToString(", "), 13f, ctx.getColor(R.color.mango9_calendar_secondary)))
                box.setOnClickListener { onTask(row.task) }
            }
            is Mango9AgendaRow.More -> {
                box.background = null; box.addView(text(ctx, row.text, 15f, ctx.getColor(R.color.mango9_calendar_accent)))
                box.setOnClickListener { onMore() }
            }
            is Mango9AgendaRow.Heading -> {
                box.background = null
                box.addView(text(ctx, row.text, 14f, ctx.getColor(R.color.mango9_calendar_secondary), true))
            }
            is Mango9AgendaRow.Event -> {
                val event = row.event
                val color = Mango9AppointmentAppearance.rgb(event) or (0xff shl 24)
                box.background = GradientDrawable().apply {
                    setColor(ctx.getColor(R.color.mango9_calendar_surface)); cornerRadius = dp(ctx, 14).toFloat(); setStroke(dp(ctx, 1), ctx.getColor(R.color.mango9_calendar_separator))
                }
                val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                header.addView(text(ctx, event.title, 16f, ctx.getColor(R.color.mango9_calendar_ink), true), LinearLayout.LayoutParams(0, -2, 1f))
                val indicators = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(ctx, 4), 0, 0, 0) }

                fun indicator(resource: Int, tint: Int) {
                    indicators.addView(
                        ImageView(ctx).apply {
                        setImageResource(resource); imageTintList = ColorStateList.valueOf(tint or (0xff shl 24))
                        setPadding(dp(ctx, 3), dp(ctx, 3), dp(ctx, 3), dp(ctx, 3))
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                        LinearLayout.LayoutParams(dp(ctx, 24), dp(ctx, 24))
                    )
                }
                if (event.isRecurring) indicator(R.drawable.arrow_clockwise, color)
                indicator(
                    when (event.activity) {
                    "call" -> R.drawable.phone
                    "meeting" -> R.drawable.video_camera
                    "task" -> R.drawable.check_square_offset
                    "follow_up" -> R.drawable.arrow_right
                    else -> R.drawable.calendar
                },
                    0x8055b8
                )
                if (event.priority.isNotBlank()) indicator(R.drawable.mango9_priority_flag, Mango9AppointmentAppearance.indicatorPriorityRgb(event.priority))
                if (event.reminders.isNotEmpty()) indicator(R.drawable.bell, 0xdc8500)
                if (ctx.resources.configuration.fontScale < 1.4f) header.addView(indicators)
                box.addView(header)
                if (indicators.parent == null) box.addView(indicators)
                val timeRange = Mango9CalendarDateLabels(ctx).timeRange(event.startAt, event.endAt, row.zone)
                box.addView(
                    text(ctx, timeRange, 13f, ctx.getColor(R.color.mango9_calendar_secondary)).apply {
                    tag = "appointment.agenda.times"
                }
                )
                event.contact?.let { box.addView(text(ctx, it.displayName, 13f, ctx.getColor(R.color.mango9_calendar_secondary))) }
                val statusName = event.status?.name?.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.mango9_calendar_no_status)
                box.addView(
                    text(ctx, statusName, 12f, ctx.getColor(R.color.mango9_calendar_ink), true).apply {
                    tag = "appointment.agenda.status"
                    setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 8), dp(ctx, 4))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(ctx, 6).toFloat()
                        setColor(Color.argb(28, Color.red(color), Color.green(color), Color.blue(color)))
                        setStroke(dp(ctx, 1), ctx.getColor(R.color.mango9_calendar_separator))
                    }
                },
                    LinearLayout.LayoutParams(-2, -2)
                )
                val now = Instant.ofEpochSecond(row.minute * 60)
                val minutes = ((event.startAt.epochSecond - now.epochSecond + 59) / 60).toInt()
                val due = when {
                    Mango9AppointmentAgenda.bucket(event, now) == Mango9AppointmentAgenda.Bucket.DueNow -> ctx.getString(R.string.mango9_calendar_now)
                    Mango9AppointmentAgenda.bucket(event, now) == Mango9AppointmentAgenda.Bucket.Upcoming && minutes in 1..60 -> ctx.resources.getQuantityString(R.plurals.mango9_calendar_in_minutes, minutes, minutes)
                    else -> null
                }
                due?.let { box.addView(text(ctx, it, 12f, ctx.getColor(R.color.mango9_calendar_accent), true)) }
                box.contentDescription = listOfNotNull(
                    event.title, ctx.getString(R.string.mango9_calendar_status_filter, statusName),
                    ctx.getString(R.string.mango9_calendar_field_value, ctx.getString(R.string.mango9_calendar_activity), Mango9CalendarLegend.humanize(event.activity)),
                    ctx.getString(R.string.mango9_calendar_priority, Mango9CalendarLegend.humanize(event.priority)),
                    event.startAt.atZone(row.zone).format(DateTimeFormatter.ofPattern("EEE, MMM d")) + ", " + timeRange,
                    event.contact?.displayName, due,
                    ctx.getString(R.string.mango9_calendar_recurring).takeIf { event.isRecurring },
                    ctx.getString(R.string.mango9_calendar_reminder_set).takeIf { event.reminders.isNotEmpty() },
                ).joinToString(", ")

                fun decorative(view: View) {
                    view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    if (view is ViewGroup) for (i in 0 until view.childCount) decorative(view.getChildAt(i))
                }
                for (i in 0 until box.childCount) decorative(box.getChildAt(i))
                box.setOnClickListener { open(event) }
            }
        }
    }

    companion object {
        fun dp(ctx: Context, value: Int) = (value * ctx.resources.displayMetrics.density).toInt()

        fun text(ctx: Context, value: String, size: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
            text = value; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(ctx, 2), 0, dp(ctx, 2))
        }
    }
}
