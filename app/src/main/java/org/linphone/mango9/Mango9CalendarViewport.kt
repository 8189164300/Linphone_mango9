/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import java.time.LocalDate
import java.time.ZoneId

/** Memory-only presentation state: month rows or timeline hours, never pixels or appointments. */
data class Mango9CalendarViewport(
    val mode: Mango9CalendarMode,
    val period: LocalDate,
    val zone: ZoneId,
    val position: Float,
    val dayPosition: Float = 0f,
) {
    fun matches(state: Mango9CalendarScreenState): Boolean =
        position.isFinite() && position >= 0 && dayPosition.isFinite() && dayPosition >= 0 &&
            mode == state.mode && zone == state.displayZone && period == period(state)

    companion object {
        fun period(state: Mango9CalendarScreenState): LocalDate = when (state.mode) {
            Mango9CalendarMode.Day -> state.date
            Mango9CalendarMode.Month -> state.date.withDayOfMonth(1)
            Mango9CalendarMode.Week -> Mango9CalendarPresentation.days(state.date, state.mode, state.firstWeekday).first()
        }

        fun at(state: Mango9CalendarScreenState, position: Float, dayPosition: Float = 0f) =
            Mango9CalendarViewport(state.mode, period(state), state.displayZone, position, dayPosition)
    }
}
