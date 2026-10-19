/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class Mango9CalendarPresentationTest {
    private val presentation = Mango9CalendarPresentation
    private val zone = ZoneId.of("America/Los_Angeles")
    private val day = LocalDate.of(2026, 9, 11)

    private fun event(id: Int, hour: Int, minute: Int = 0, duration: Long = 30): Mango9Appointment {
        val start = day.atTime(hour, minute).atZone(zone).toInstant()
        return Mango9CalendarTestFixtures.event().copy(id = id, startAt = start, endAt = start.plusSeconds(duration * 60))
    }

    @Test fun monthGridIncludesAdjacentDaysAndOnlyCompleteWeeks() {
        for (first in DayOfWeek.entries) for (month in 1..12) {
            val date = LocalDate.of(2026, month, 18)
            val days = presentation.days(date, Mango9CalendarMode.Month, first)
            assertEquals(first, days.first().dayOfWeek)
            assertTrue(days.size in 28..42); assertEquals(0, days.size % 7)
            assertEquals(date.lengthOfMonth(), days.count { it.month == date.month })
            assertTrue(days.zipWithNext().all { it.first.plusDays(1) == it.second })
            val range = presentation.visibleRange(date, Mango9CalendarMode.Month, zone, first)
            assertEquals(days.first(), range.start.atZone(zone).toLocalDate())
            assertEquals(days.last().plusDays(1), range.end.atZone(zone).toLocalDate())
        }
    }

    @Test fun dayWeekHeadingsIncludeRealDatesAndYearBoundary() {
        assertEquals(listOf(day), presentation.days(day, Mango9CalendarMode.Day, DayOfWeek.SUNDAY))
        val days = presentation.days(day, Mango9CalendarMode.Week, DayOfWeek.MONDAY)
        assertEquals(LocalDate.of(2026, 9, 7), days.first()); assertEquals(7, days.size)
        val heading = presentation.heading(LocalDate.of(2027, 1, 1), Mango9CalendarMode.Week, DayOfWeek.MONDAY, Locale.US)
        assertTrue(heading.contains("2026")); assertTrue(heading.contains("2027"))
        assertTrue(presentation.heading(day, Mango9CalendarMode.Day, DayOfWeek.MONDAY, Locale.US).contains("Sep 11"))
    }

    @Test fun crowdedDayAgendaRetainsOvernightEventsAndCombinesFilters() {
        val overnight = event(1, 23, 30, 90).copy(title = "Overnight consultation", status = Mango9AppointmentStatus(7, "Confirmed", "#008080"))
        val endsAtMidnight = event(2, 23, duration = 60)
        val otherStatus = overnight.copy(id = 3, status = Mango9AppointmentStatus(8, "Pending", null))
        val events = listOf(overnight, endsAtMidnight, otherStatus)
        assertSame(events, presentation.filteredEvents(events, "", null, null, zone))
        assertEquals(listOf(1, 3), presentation.filteredEvents(events, "", null, day.plusDays(1), zone).map { it.id })
        assertEquals(listOf(1), presentation.filteredEvents(events, "  CONSULTATION  ", 7, day.plusDays(1), zone).map { it.id })
        assertTrue(presentation.filteredEvents(events, "unknown", 7, day.plusDays(1), zone).isEmpty())
    }

    @Test fun tinyMonthCellsNeverMakeNegativeRangesAndAlwaysLeaveAnOverflowCount() {
        assertEquals(0, presentation.monthPreviewCount(20, 1.0, 17.0))
        assertEquals(1, presentation.monthPreviewCount(20, 46.0, 17.0))
        assertEquals(2, presentation.monthPreviewCount(2, 46.0, 17.0))
        assertEquals(Int.MAX_VALUE, presentation.monthPreviewCount(Int.MAX_VALUE, Double.MAX_VALUE, 17.0))
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0)) {
            assertEquals(0, presentation.monthPreviewCount(10, invalid, 17.0))
        }
    }

    @Test fun adjacentAppointmentsShareLaneButShortVisualLabelsDoNotOverlap() {
        val events = listOf(event(1, 9, duration = 1), event(2, 9, 2, 1), event(3, 10))
        val values = presentation.placements(events, day, zone, 300.0, 40.0, 18.0)
        assertEquals(3, values.size)
        assertNotEquals(values[0].x, values[1].x)
        assertEquals(values[0].width, values[1].width, 0.001)
        assertTrue(values[2].width > values[0].width)
        assertEquals(1 * 60L, java.time.Duration.between(values[0].event.startAt, values[0].event.endAt).seconds)
        assertTrue(values.all { it.height >= 18 && it.width >= 0 && it.x >= 0 })
    }

    @Test fun overlapLanesReuseSpaceWithoutQuadraticDenseScanning() {
        val dense = (1..10_000).map { event(it, 9) }
        val result = presentation.placements(dense, day, zone, 300.0, 60.0, 18.0)
        assertEquals(10_000, result.size)
        assertEquals(10_000, result.map { it.x }.toSet().size)
        assertTrue(result.all { it.width.isFinite() && it.width >= 0 })
        val adjacent = presentation.placements(listOf(event(1, 9), event(2, 9, 30)), day, zone, 300.0, 60.0, 18.0)
        assertEquals(adjacent[0].x, adjacent[1].x, 0.001)
        assertEquals(adjacent[0].width, adjacent[1].width, 0.001)
    }

    @Test fun invalidLayoutProposalsAreEmptyAndOvernightEventsClipToVisibleDay() {
        for (width in listOf(-1.0, 0.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertTrue(presentation.placements(listOf(event(1, 9)), day, zone, width, 60.0, 18.0).isEmpty())
        }
        val overnight = event(1, 23, 30, 90)
        val first = presentation.placements(listOf(overnight), day, zone, 300.0, 60.0, 18.0).single()
        val next = presentation.placements(listOf(overnight), day.plusDays(1), zone, 300.0, 60.0, 18.0).single()
        assertEquals(1410.0, first.y, 0.01); assertEquals(28.0, first.height, 0.01)
        assertEquals(0.0, next.y, 0.01); assertEquals(58.0, next.height, 0.01)
        assertTrue(presentation.eventsOn(listOf(overnight), day.plusDays(2), zone).isEmpty())
    }

    @Test fun timeSlotsUseShownZoneAcrossGapsAndLongPressDatesUseBusinessOpenings() {
        val spring = LocalDate.of(2026, 3, 8)
        val shifted = presentation.timeSlot(spring, 10, zone)!!.atZone(zone)
        assertEquals(3, shifted.hour); assertEquals(30, shifted.minute)
        assertNull(presentation.timeSlot(day, -1, zone)); assertNull(presentation.timeSlot(day, 96, zone))
        val hours = Mango9CalendarTestFixtures.hours()
        assertNull(presentation.startOnDate(day.plusDays(1), zone, hours))
        val armenia = ZoneId.of("Asia/Yerevan")
        val monday = LocalDate.of(2026, 9, 14)
        assertEquals(20, presentation.startOnDate(monday, armenia, hours)!!.atZone(armenia).hour)
        assertEquals(9, presentation.startOnDate(monday, zone, null)!!.atZone(zone).hour)
    }

    @Test fun fallBackEventEndingEarlierOnTheClockStillHasPositiveVisualHeight() {
        val date = LocalDate.of(2026, 11, 1)
        val event = event(1, 9).copy(startAt = Instant.parse("2026-11-01T08:45:00Z"), endAt = Instant.parse("2026-11-01T09:15:00Z"))
        val placement = presentation.placements(listOf(event), date, zone, 200.0, 60.0, 18.0).single()
        assertEquals(105.0, placement.y, 0.01); assertEquals(28.0, placement.height, 0.01)
        assertEquals(event.startAt, placement.event.startAt)
    }

    @Test fun zoomFitsRealLineMetricsAndDropsStatusBeforeTitle() {
        for (height in listOf(18.0, 18.25, 25.7, 40.0, 90.0)) for (scale in listOf(1.0, 1.5, 2.0)) {
            val layout = presentation.textLayout(160.0, height, 11 * scale, 9.0, true, true) { it * 1.2 }
            assertTrue("Title must survive a measured minimum slot", layout.titleLines >= 1)
            assertTrue(layout.lineHeight * layout.titleLines <= floorForTest(height - 2))
            if (height < 40) assertFalse(layout.showStatus)
        }
        assertFalse(presentation.textLayout(35.0, 40.0, 11.0, 9.0, true, true) { it * 1.2 }.showRecurrence)
        assertEquals(0, presentation.textLayout(100.0, 1.0, 11.0, 9.0, true, true) { it * 1.2 }.titleLines)
        assertEquals(0, presentation.textLayout(100.0, 30.0, 11.0, 9.0, true, true) { Double.NaN }.titleLines)
    }

    @Test fun agendaRanksDueThenUpcomingChronologicallyNotByPriorityAndPreservesCustomStatus() {
        val now = day.atTime(9, 15).atZone(zone).toInstant()
        val due = event(1, 9)
        val earlier = event(2, 10).copy(priority = "low")
        val later = event(3, 11).copy(priority = "high")
        val past = event(4, 8)
        val closed = event(5, 9).copy(status = Mango9AppointmentStatus(9, "Completed", "green"))
        val values = Mango9AppointmentAgenda.sections(listOf(later, closed, past, earlier, due), now, zone)
        assertEquals(listOf(1, 2, 3, 4, 5), values.flatMap { it.events }.map { it.id })
        assertEquals(
            Mango9AppointmentAgenda.Bucket.Upcoming,
            Mango9AppointmentAgenda.bucket(later.copy(status = Mango9AppointmentStatus(10, "Reschedule needed", "orange")), now)
        )
        assertEquals(4, values.map { it.id }.toSet().size)
    }

    @Test fun recurrenceMastersDoNotDuplicateExpandedOccurrencesInCalendar() {
        val master = event(1, 9).copy(recurrence = Mango9AppointmentRecurrence("daily", emptyList()))
        val occurrence = master.copy(occurrenceKey = "1|2026-09-11")
        assertEquals(listOf(occurrence), presentation.eventsOn(listOf(master, occurrence), day, zone))
    }

    @Test fun restoredReminderCadenceIsBoundedBeforeAnyNewReminder() {
        val values = (1..1000).associate { it.toString() to it.toLong() }
        val restored = Mango9ReminderCadence(values).snapshot()
        assertEquals(256, restored.size); assertTrue(restored.containsKey("1000")); assertFalse(restored.containsKey("1"))
    }

    @Test fun legendIncludesOnlyRealStatusColorsAndAllPriorityFallbacks() {
        val states =
            listOf(Mango9AppointmentStatus(1, "White", "white"), Mango9AppointmentStatus(2, "Uncolored", "not-a-color"), Mango9AppointmentStatus(1, "Duplicate", "red"))
        val key = Mango9AppointmentAppearance.legend(states, true)
        assertEquals(listOf("White"), key.filter { it.kind == Mango9AppointmentAppearance.LegendKind.Status }.map { it.value })
        assertEquals(
            listOf(0xff0000, 0xffa500, 0x008000),
            key.filter {
            it.kind == Mango9AppointmentAppearance.LegendKind.Priority
        }.map { it.rgb }
        )
        assertEquals(Mango9AppointmentAppearance.LegendKind.OutsideHours, key.last().kind)
        assertEquals(5, Mango9AppointmentAppearance.legend(emptyList(), true).size)
        assertEquals(4, Mango9AppointmentAppearance.legend(emptyList(), false).size)
    }

    @Test fun priorityIndicatorsDoNotInheritTheStatusColor() {
        val high = event(1, 9).copy(priority = "high", status = Mango9AppointmentStatus(1, "Confirmed", "green"))
        assertEquals(0x008000, Mango9AppointmentAppearance.rgb(high))
        assertEquals(0xff0000, Mango9AppointmentAppearance.indicatorPriorityRgb(high.priority))
        assertEquals(0xff0000, Mango9AppointmentAppearance.indicatorPriorityRgb("urgent"))
        assertEquals(0xffa500, Mango9AppointmentAppearance.indicatorPriorityRgb("normal"))
    }

    private fun floorForTest(value: Double) = kotlin.math.floor(value)
}
