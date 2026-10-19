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
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class Mango9CalendarRange(val start: Instant, val end: Instant) {
    val duration: Duration get() = Duration.between(start, end)

    fun overlaps(start: Instant, end: Instant): Boolean = this.start < end && this.end > start
}

enum class Mango9CalendarMode {
    Day,
    Week,
    Month;

    fun range(date: LocalDate, zone: ZoneId, firstWeekday: DayOfWeek): Mango9CalendarRange {
        val start = when (this) {
            Day -> date
            Week -> date.with(TemporalAdjusters.previousOrSame(firstWeekday))
            Month -> date.withDayOfMonth(1)
        }
        val end = when (this) { Day -> start.plusDays(1); Week -> start.plusWeeks(1); Month -> start.plusMonths(1) }
        return Mango9CalendarRange(start.atStartOfDay(zone).toInstant(), end.atStartOfDay(zone).toInstant())
    }

    fun move(date: LocalDate, count: Long): LocalDate = when (this) {
        Day -> date.plusDays(count); Week -> date.plusWeeks(count); Month -> date.withDayOfMonth(1).plusMonths(count)
    }
}

object Mango9CalendarOccurrences {
    const val MAXIMUM_OCCURRENCES = 10_000

    fun expand(
        masters: List<Mango9Appointment>, range: Mango9CalendarRange,
        limit: Int = MAXIMUM_OCCURRENCES, checkCancelled: () -> Unit = {},
    ): List<Mango9Appointment> {
        if (range.end <= range.start || range.duration > Duration.ofDays(366)) throw Mango9CalendarFailure("invalid_range", 422)
        require(limit in 1..MAXIMUM_OCCURRENCES)
        val result = ArrayList<Mango9Appointment>()
        val seen = HashSet<Int>()

        fun append(master: Mango9Appointment, start: Instant, end: Instant) {
            if (!range.overlaps(start, end)) return
            if (result.size >= limit) throw Mango9CalendarFailure("too_many_occurrences")
            result += master.copy(
                startAt = start, endAt = end,
                occurrenceKey = if (master.isRecurring) "${master.id}|$start" else null
            )
        }
        for (master in masters) {
            checkCancelled()
            if (!seen.add(master.id)) continue
            if (master.endAt <= master.startAt) throw Mango9CalendarFailure("invalid_stored_event", 409)
            append(master, master.startAt, master.endAt)
            if (!master.isRecurring) continue
            val frequency = master.recurrence.frequency
            if (frequency !in setOf("daily", "weekly", "custom", "monthly")) throw Mango9CalendarFailure("unsupported_recurrence")
            val zone = try {
                ZoneId.of(master.timezone)
            } catch (_: Exception) { throw Mango9CalendarFailure("invalid_stored_event", 409) }
            val original = master.startAt.atZone(zone)
            val originalEnd = master.endAt.atZone(zone)
            val daySpan = ChronoUnit.DAYS.between(original.toLocalDate(), originalEnd.toLocalDate())
            val horizon = addingMonths(12, original).toInstant()
            val until = minOf(range.end, horizon.plusSeconds(1))
            if (until <= master.startAt) continue

            fun addOccurrence(date: ZonedDateTime) {
                val start = date.toInstant()
                if (start <= master.startAt || start > horizon) return
                // Java time shifts gaps forward and chooses the earlier overlap offset,
                // preserving local clock times rather than adding 24-hour durations.
                val proposedEnd = date.toLocalDate().plusDays(daySpan).atTime(originalEnd.toLocalTime()).atZone(zone).toInstant()
                val end = if (proposedEnd > start) proposedEnd else start.plus(Duration.between(master.startAt, master.endAt))
                append(master, start, end)
            }
            if (frequency == "monthly") {
                var date = addingMonths(1, original)
                while (date.toInstant() < until) {
                    checkCancelled(); addOccurrence(date)
                    date = addingMonths(1, date)
                }
            } else {
                val weekdays = master.recurrence.weekdays.filter { it in 1..7 }.toSet()
                if (frequency != "daily" && weekdays.isEmpty()) continue
                var day = maxOf(original.toLocalDate(), range.start.atZone(zone).toLocalDate().minusDays(daySpan + 1))
                while (day.atStartOfDay(zone).toInstant() < until) {
                    checkCancelled()
                    if (frequency == "daily" || day.dayOfWeek.value in weekdays) addOccurrence(day.atTime(original.toLocalTime()).atZone(zone))
                    day = day.plusDays(1)
                }
            }
        }
        checkCancelled()
        return result.sortedWith(compareBy({ it.startAt }, { it.displayId }))
    }

    /** CRM/PHP rolls an overflowing month forward, e.g. Jan 31 -> Mar 3, not Feb 28. */
    internal fun addingMonths(months: Long, date: ZonedDateTime): ZonedDateTime =
        date.toLocalDate().withDayOfMonth(1).plusMonths(months).plusDays(date.dayOfMonth.toLong() - 1)
            .atTime(date.toLocalTime()).atZone(date.zone)
}

data class Mango9BusinessDay(val weekday: Int, val open: String?, val close: String?) {
    val isOpen: Boolean get() = open != null && close != null
}

data class Mango9BusinessHours(
    val configured: Boolean, val timezone: String, val timezoneEditable: Boolean, val quickEnable: Boolean,
    val days: List<Mango9BusinessDay>, val defaults: List<Mango9BusinessDay>, val revision: String,
) {
    val validationMessage: String? get() {
        try { ZoneId.of(timezone) } catch (_: Exception) { return "Choose a valid time zone." }
        if (days.size != 7 || days.map { it.weekday }.toSet() != (0..6).toSet()) return "Reload all seven business days."
        for (day in days) {
            if (day.open == null && day.close == null) continue
            val start = seconds(day.open); val end = seconds(day.close)
            if (start == null || end == null || end < start) return "Set opening and closing times. Closing cannot be earlier than opening."
        }
        return null
    }

    fun useDefaults(): Mango9BusinessHours = copy(days = defaults.toList(), configured = true, quickEnable = true)

    fun closeAll(): Mango9BusinessHours = copy(
        days = (0..6).map {
        Mango9BusinessDay(it, null, null)
    },
        configured = true, quickEnable = false
    )

    fun changes(original: Mango9BusinessHours): JSONObject {
        require(validationMessage == null)
        require(timezone == original.timezone || original.timezoneEditable)
        val hoursChanged = days != original.days || quickEnable != original.quickEnable || configured != original.configured
        return JSONObject().apply {
            put("action", if (hoursChanged) "replace" else "timezone")
            if (hoursChanged) {
                put("quick_enable", quickEnable)
                put(
                    "days",
                    JSONArray(
                        days.map {
                            JSONObject().put("weekday", it.weekday)
                    .put("open", it.open ?: JSONObject.NULL).put("close", it.close ?: JSONObject.NULL)
                        }
                    )
                )
            }
            if (timezone != original.timezone || !hoursChanged) put("timezone", timezone)
        }
    }

    fun openIntervals(date: LocalDate, displayZone: ZoneId): List<Mango9CalendarRange> {
        val visible = Mango9CalendarMode.Day.range(date, displayZone, DayOfWeek.MONDAY)
        if (!configured) return listOf(visible)
        if (validationMessage != null) throw Mango9CalendarFailure("invalid_stored_event")
        val businessZone = ZoneId.of(timezone)
        var businessDay = visible.start.atZone(businessZone).toLocalDate()
        val result = ArrayList<Mango9CalendarRange>()
        repeat(4) {
            if (businessDay.atStartOfDay(businessZone).toInstant() >= visible.end) return result
            val hours = days.first { it.weekday == businessDay.dayOfWeek.value % 7 }
            val open = seconds(hours.open); val close = seconds(hours.close)
            if (open != null && close != null && close > open) {
                val opening = businessDay.atTime(LocalTime.ofSecondOfDay(open.toLong())).atZone(businessZone).toInstant()
                val closing = businessDay.atTime(LocalTime.ofSecondOfDay(close.toLong())).atZone(businessZone).toInstant()
                val start = maxOf(opening, visible.start); val end = minOf(closing, visible.end)
                if (end > start) result += Mango9CalendarRange(start, end)
            }
            businessDay = businessDay.plusDays(1)
        }
        return result
    }

    fun allows(start: Instant, end: Instant): Boolean {
        if (end <= start) return false
        if (!configured) return true
        val zone = ZoneId.of(timezone)
        return openIntervals(start.atZone(zone).toLocalDate(), zone).any { start >= it.start && end <= it.end }
    }

    fun firstStart(date: LocalDate, displayZone: ZoneId, duration: Duration = Duration.ofMinutes(30)): Instant? =
        openIntervals(date, displayZone).firstOrNull { it.duration >= duration }?.start

    fun closedMinutes(date: LocalDate, displayZone: ZoneId): List<Pair<Double, Double>> {
        if (!configured) return emptyList()
        val visibleEnd = date.plusDays(1).atStartOfDay(displayZone).toInstant()

        fun minute(instant: Instant): Double = if (instant >= visibleEnd) {
            1440.0
        } else {
            instant.atZone(displayZone).toLocalTime().toSecondOfDay() / 60.0
        }
        var cursor = 0.0
        val result = ArrayList<Pair<Double, Double>>()
        openIntervals(date, displayZone).map {
            minute(it.start) to minute(it.end)
        }.sortedBy { it.first }.forEach { (start, end) ->
            if (start > cursor) result += cursor to start
            cursor = maxOf(cursor, end)
        }
        if (cursor < 1440) result += cursor to 1440.0
        return result
    }

    companion object {
        fun seconds(value: String?): Int? {
            if (value == null || !value.matches(Regex("[0-9]{2}:[0-9]{2}(:[0-9]{2})?"))) return null
            val parts = value.split(':').map { it.toInt() }
            if (parts[0] !in 0..23 || parts[1] !in 0..59 || parts.getOrElse(2) { 0 } !in 0..59) return null
            return parts[0] * 3600 + parts[1] * 60 + parts.getOrElse(2) { 0 }
        }
    }
}

object Mango9AppointmentAppearance {
    fun statusRgb(value: String?): Int? {
        var hex = value?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        val named = mapOf(
            "red" to 0xff0000, "orange" to 0xffa500, "green" to 0x008000, "blue" to 0x0000ff,
            "purple" to 0x800080, "gray" to 0x808080, "grey" to 0x808080, "black" to 0x000000, "white" to 0xffffff, "yellow" to 0xffff00
        )
        named[hex]?.let { return it }
        hex = hex.removePrefix("#")
        if (hex.length == 3) hex = hex.flatMap { listOf(it, it) }.joinToString("")
        return if (hex.matches(Regex("[0-9a-f]{6}"))) hex.toInt(16) else null
    }

    fun priorityRgb(priority: String): Int = when (priority.lowercase(Locale.ROOT)) { "high" -> 0xff0000; "medium" -> 0xffa500; else -> 0x008000 }

    fun rgb(event: Mango9Appointment): Int = statusRgb(event.status?.color) ?: priorityRgb(event.priority)

    // A priority badge describes priority itself, not the appointment status.
    fun indicatorPriorityRgb(priority: String): Int = when (priority.lowercase(Locale.ROOT)) {
        "urgent", "high" -> 0xff0000
        "normal", "medium" -> 0xffa500
        "low" -> 0x008000
        else -> 0x666666
    }

    enum class LegendKind { Recurring, Status, Priority, OutsideHours }

    data class LegendEntry(val kind: LegendKind, val value: String, val rgb: Int?)

    fun legend(statuses: List<Mango9AppointmentStatus>, configuredHours: Boolean): List<LegendEntry> = buildList {
        add(LegendEntry(LegendKind.Recurring, "", null))
        statuses.distinctBy { it.id }.forEach { status ->
            statusRgb(status.color)?.let { add(LegendEntry(LegendKind.Status, status.name, it)) }
        }
        listOf("high", "medium", "low").forEach { add(LegendEntry(LegendKind.Priority, it, priorityRgb(it))) }
        if (configuredHours) add(LegendEntry(LegendKind.OutsideHours, "", 0xe6e9ef))
    }
}
