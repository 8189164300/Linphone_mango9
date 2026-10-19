/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.ui.main.crm.fragment

import android.content.Context
import android.text.format.DateFormat
import androidx.annotation.UiThread
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.linphone.mango9.Mango9BusinessHours

/** UI-only, per-presentation formatters. Never parse these labels for API writes. */
@UiThread
internal class Mango9CalendarDateLabels(context: Context) {
    private val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
    private val is24Hour = DateFormat.is24HourFormat(context)
    private val clock = if (is24Hour) "Hm" else "hm"
    // SimpleDateFormat is mutable: instances belong to this UI presentation,
    // not a global cache or a background calendar layout worker.
    private val formats = mutableMapOf<Pair<String, ZoneId>, SimpleDateFormat>()

    fun updated(context: Context): Mango9CalendarDateLabels = if (
        locale == context.resources.configuration.locales[0] && is24Hour == DateFormat.is24HourFormat(context)
    ) {
        this
    } else {
        Mango9CalendarDateLabels(context)
    }

    private fun format(instant: Instant, zone: ZoneId, skeleton: String): String {
        val formatter = formats.getOrPut(skeleton to zone) {
            SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale).apply {
                timeZone = TimeZone.getTimeZone(zone)
            }
        }
        return formatter.format(Date.from(instant))
    }

    fun time(instant: Instant, zone: ZoneId): String = format(instant, zone, clock)

    fun dateTime(instant: Instant, zone: ZoneId, showZone: Boolean = false): String =
        format(instant, zone, "yMMMEd" + clock + if (showZone) "z" else "")

    fun timeRange(start: Instant, end: Instant, zone: ZoneId): String {
        val first = start.atZone(zone)
        val last = end.atZone(zone)
        val offsetChanges = first.offset != last.offset
        val startLabel = format(start, zone, clock + if (offsetChanges) "z" else "")
        val endLabel = if (first.toLocalDate() != last.toLocalDate()) {
            dateTime(end, zone, offsetChanges)
        } else {
            format(end, zone, clock + if (offsetChanges) "z" else "")
        }
        return "$startLabel – $endLabel"
    }

    // Business hours and hour-axis labels are wall-clock values, not instants
    // in the device/server zone. A fixed UTC anchor avoids DST conversion.
    fun wallTime(time: LocalTime, compactHour: Boolean = false): String = format(
        LocalDate.of(2000, 1, 1).atTime(time).toInstant(ZoneOffset.UTC), ZoneOffset.UTC,
        if (compactHour && !is24Hour) "ha" else clock + if (time.second != 0) "s" else ""
    )

    fun businessTime(raw: String?): String = Mango9BusinessHours.seconds(raw)?.let {
        wallTime(LocalTime.ofSecondOfDay(it.toLong()))
    } ?: raw.orEmpty()
}
