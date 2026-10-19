/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class Mango9CalendarViewportTest {
    private val state =
        Mango9CalendarScreenState(LocalDate.of(2026, 9, 12), ZoneId.of("America/Los_Angeles"), DayOfWeek.MONDAY)

    @Test fun sameMonthIsStableButDifferentPeriodsAndZonesDoNotReusePosition() {
        val saved = Mango9CalendarViewport.at(state, 2.25f)
        assertTrue(saved.matches(state.copy(date = state.date.plusDays(8), firstWeekday = DayOfWeek.SUNDAY, query = "new")))
        assertFalse(saved.matches(state.copy(date = state.date.plusMonths(1))))
        assertFalse(saved.matches(state.copy(mode = Mango9CalendarMode.Day)))
        assertFalse(saved.matches(state.copy(displayZone = ZoneId.of("Europe/Paris"))))
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f)) assertFalse(saved.copy(position = invalid).matches(state))
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f)) assertFalse(saved.copy(dayPosition = invalid).matches(state))
    }

    @Test fun dayAndWeekUseTheActualDisplayedPeriod() {
        val day = state.copy(mode = Mango9CalendarMode.Day)
        assertTrue(Mango9CalendarViewport.at(day, 10.25f).matches(day))
        assertFalse(Mango9CalendarViewport.at(day, 10.25f).matches(day.copy(date = day.date.plusDays(1))))
        val week = state.copy(mode = Mango9CalendarMode.Week)
        assertTrue(Mango9CalendarViewport.at(week, 10.25f).matches(week.copy(date = week.date.minusDays(2))))
        assertFalse(Mango9CalendarViewport.at(week, 10.25f).matches(week.copy(date = week.date.plusWeeks(1))))
        assertEquals(LocalDate.of(2026, 9, 7), Mango9CalendarViewport.at(week, 10.25f).period)
        assertEquals(3.25f, Mango9CalendarViewport.at(week, 10.25f, 3.25f).dayPosition)
    }
}
