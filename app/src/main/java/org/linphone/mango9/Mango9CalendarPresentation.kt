/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import java.util.PriorityQueue
import kotlin.math.ceil
import kotlin.math.floor

/** Calendar layout is display-only. Never persist a visual height, lane, or occurrence ID. */
object Mango9CalendarPresentation {
    fun days(date: LocalDate, mode: Mango9CalendarMode, firstWeekday: DayOfWeek): List<LocalDate> {
        if (mode == Mango9CalendarMode.Day) return listOf(date)
        val first = if (mode == Mango9CalendarMode.Month) date.withDayOfMonth(1) else date
        val start = first.with(TemporalAdjusters.previousOrSame(firstWeekday))
        val count = if (mode == Mango9CalendarMode.Week) {
            7
        } else {
            val padding = java.time.temporal.ChronoUnit.DAYS.between(start, first).toInt()
            ((padding + first.lengthOfMonth() + 6) / 7) * 7
        }
        return List(count) { start.plusDays(it.toLong()) }
    }

    fun visibleRange(date: LocalDate, mode: Mango9CalendarMode, zone: ZoneId, firstWeekday: DayOfWeek): Mango9CalendarRange {
        val days = days(date, mode, firstWeekday)
        return Mango9CalendarRange(days.first().atStartOfDay(zone).toInstant(), days.last().plusDays(1).atStartOfDay(zone).toInstant())
    }

    fun heading(date: LocalDate, mode: Mango9CalendarMode, firstWeekday: DayOfWeek, locale: Locale): String {
        fun LocalDate.format(pattern: String) = format(DateTimeFormatter.ofPattern(pattern, locale))
        return when (mode) {
            Mango9CalendarMode.Day -> date.format("EEE, MMM d, yyyy")
            Mango9CalendarMode.Month -> date.format("MMMM yyyy")
            Mango9CalendarMode.Week -> {
                val days = days(date, mode, firstWeekday)
                val first = days.first(); val last = days.last()
                first.format(if (first.year == last.year) "MMM d" else "MMM d, yyyy") + " – " + last.format("MMM d, yyyy")
            }
        }
    }

    fun eventsOn(events: List<Mango9Appointment>, day: LocalDate, zone: ZoneId): List<Mango9Appointment> {
        val range = Mango9CalendarMode.Day.range(day, zone, DayOfWeek.MONDAY)
        return events.filter { (!it.isRecurring || it.occurrenceKey != null) && range.overlaps(it.startAt, it.endAt) }
            .sortedWith(compareBy({ it.startAt }, { it.id }, { it.displayId }))
    }

    fun filteredEvents(
        events: List<Mango9Appointment>, query: String, status: Int?, day: LocalDate?, zone: ZoneId,
    ): List<Mango9Appointment> {
        if (query.isBlank() && status == null && day == null) return events
        val normalized = query.trim().lowercase(Locale.ROOT)
        return (day?.let { eventsOn(events, it, zone) } ?: events).filter { event ->
            (status == null || (event.status?.id ?: 0) == status) &&
                (
                    normalized.isEmpty() || event.title.lowercase(Locale.ROOT).contains(normalized) ||
                    event.contact?.displayName?.lowercase(Locale.ROOT)?.contains(normalized) == true
                )
        }
    }

    /** Month cells reserve a count row instead of hiding the existence of overflow events. */
    fun monthPreviewCount(total: Int, availableHeight: Double, rowHeight: Double): Int {
        if (total <= 0 || !availableHeight.isFinite() || !rowHeight.isFinite() || availableHeight <= 0 || rowHeight <= 0) return 0
        val capacity = floor(availableHeight / (rowHeight + 6))
        return if (capacity >= total) total else (capacity.toInt() - 1).coerceAtLeast(0)
    }

    data class Placement(val event: Mango9Appointment, val x: Double, val y: Double, val width: Double, val height: Double)

    private data class Interval(val event: Mango9Appointment, val start: Double, val end: Double, val visualEnd: Double)

    private data class Lane(val end: Double, val index: Int)

    /** O(n log n), including dense overlaps; independent of the size of the address book. */
    fun placements(
        events: List<Mango9Appointment>, day: LocalDate, zone: ZoneId, width: Double,
        hourHeight: Double, minimumHeight: Double,
    ): List<Placement> {
        if (!width.isFinite() || width <= 4 || !hourHeight.isFinite() || hourHeight <= 0 ||
            !minimumHeight.isFinite() || minimumHeight < 0
        ) {
            return emptyList()
        }
        val range = Mango9CalendarMode.Day.range(day, zone, DayOfWeek.MONDAY)
        val values = eventsOn(events, day, zone).map { event ->
            val startAt = maxOf(event.startAt, range.start)
            val first = minute(startAt, zone)
            val last = if (event.endAt >= range.end) 1440.0 else minute(event.endAt, zone)
            // A fall-back fold can end at an earlier wall-clock time. Keep positive
            // visible duration without changing the authoritative Instant values.
            val length = if (last > first) last - first else maxOf(1.0, Duration.between(startAt, event.endAt).seconds / 60.0)
            val end = minOf(1440.0, first + length)
            Interval(event, first, end, maxOf(end, first + (minimumHeight + 2) * 60 / hourHeight))
        }.sortedWith(compareBy({ it.start }, { it.event.startAt }, { it.event.id }, { it.event.displayId }))
        val result = ArrayList<Placement>(values.size)
        var index = 0
        while (index < values.size) {
            var groupEnd = values[index].visualEnd
            var last = index
            val occupied = PriorityQueue<Lane>(compareBy({ it.end }, { it.index }))
            val available = PriorityQueue<Int>()
            val lanes = ArrayList<Int>()
            var laneCount = 0
            while (last < values.size && (last == index || values[last].start < groupEnd - 0.000001)) {
                val value = values[last]
                while (occupied.peek()?.let { it.end <= value.start + 0.000001 } == true) {
                    available.add(occupied.remove().index)
                }
                val lane = if (available.isEmpty()) laneCount++ else available.remove()
                lanes.add(lane); occupied.add(Lane(value.visualEnd, lane))
                groupEnd = maxOf(groupEnd, value.visualEnd); last++
            }
            val laneWidth = (width - 4) / laneCount.coerceAtLeast(1)
            for (i in index until last) {
                val value = values[i]
                result.add(
                    Placement(
                        value.event, 2 + lanes[i - index] * laneWidth, value.start * hourHeight / 60,
                    (laneWidth - 2).coerceAtLeast(0.0), maxOf(2.0, minimumHeight, (value.end - value.start) * hourHeight / 60 - 2)
                    )
                )
            }
            index = last
        }
        return result
    }

    data class TextLayout(val fontSize: Double, val lineHeight: Double, val titleLines: Int, val showStatus: Boolean, val showRecurrence: Boolean)

    /** The renderer supplies actual platform font metrics, including accessibility scaling. */
    fun textLayout(
        width: Double, height: Double, preferredFontSize: Double, minimumFontSize: Double,
        hasStatus: Boolean, recurring: Boolean, lineHeight: (Double) -> Double,
    ): TextLayout {
        val empty = TextLayout(0.0, 0.0, 0, false, false)
        if (listOf(width, height, preferredFontSize, minimumFontSize).any { !it.isFinite() } ||
            width <= 12 || height <= 2 || minimumFontSize <= 0 || preferredFontSize < minimumFontSize
        ) {
            return empty
        }
        val budget = floor(height - 2)
        val preferredLine = ceil(lineHeight(preferredFontSize))
        if (!preferredLine.isFinite() || preferredLine <= 0) return empty
        var font = minOf(preferredFontSize, preferredFontSize * budget / preferredLine)
        if (font < minimumFontSize) return empty
        // Pixel rounding and non-linear platform font metrics can leave a fraction
        // over budget. Fit a whole line, never clip the last baseline after zoom.
        var line = ceil(lineHeight(font))
        while (line > budget && font > minimumFontSize) {
            font = maxOf(minimumFontSize, font - 0.25); line = ceil(lineHeight(font))
        }
        if (!line.isFinite() || line <= 0 || line > budget) return empty
        val titleLines = if (budget >= line * 2 + 4) 2 else 1
        return TextLayout(
            font, line, titleLines, hasStatus && width > 80 && budget >= (titleLines + 1) * line + 6,
            recurring && width >= font * 4 + 12
        )
    }

    /** A held quarter-hour uses the calendar's displayed date AND time zone. */
    fun timeSlot(day: LocalDate, slot: Int, displayZone: ZoneId): Instant? =
        if (slot in 0..95) day.atTime(slot / 4, slot % 4 * 15).atZone(displayZone).toInstant() else null

    fun startOnDate(day: LocalDate, displayZone: ZoneId, hours: Mango9BusinessHours?): Instant? {
        val start = day.atTime(LocalTime.of(9, 0)).atZone(displayZone).toInstant()
        if (hours == null || hours.allows(start, start.plusSeconds(1800))) return start
        return hours.firstStart(day, displayZone)
    }

    private fun minute(instant: Instant, zone: ZoneId): Double = instant.atZone(zone).toLocalTime().toSecondOfDay() / 60.0
}

object Mango9AppointmentAgenda {
    enum class Bucket { DueNow, Upcoming, Past, Closed }

    data class Section(val bucket: Bucket, val day: LocalDate, val events: List<Mango9Appointment>) {
        val id: String get() = "$bucket|$day"
    }

    fun bucket(event: Mango9Appointment, now: Instant): Bucket = when {
        event.status?.name?.trim()?.lowercase(Locale.ROOT) in closedStatuses -> Bucket.Closed
        event.endAt <= now -> Bucket.Past
        event.startAt <= now -> Bucket.DueNow
        else -> Bucket.Upcoming
    }

    fun sections(events: List<Mango9Appointment>, now: Instant, zone: ZoneId): List<Section> {
        val sorted = events.sortedWith { a, b ->
            val category = bucket(a, now); val other = bucket(b, now)
            if (category != other) {
                category.compareTo(other)
            } else {
                val dateOrder = if (category == Bucket.Past || category == Bucket.Closed) b.startAt.compareTo(a.startAt) else a.startAt.compareTo(b.startAt)
                if (dateOrder != 0) dateOrder else compareValuesBy(a, b, { it.id }, { it.displayId })
            }
        }
        return sorted.groupBy { bucket(it, now) to it.startAt.atZone(zone).toLocalDate() }
            .map { (key, values) -> Section(key.first, key.second, values) }
    }

    private val closedStatuses = setOf("completed", "complete", "done", "cancelled", "canceled", "no show", "no-show")
}
