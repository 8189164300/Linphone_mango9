/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CancellationException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Mango9CalendarTimeTest {
    private val la = ZoneId.of("America/Los_Angeles")

    private fun at(value: String) = Instant.parse(value)

    private fun range(start: String, end: String) = Mango9CalendarRange(at(start), at(end))

    @Test fun dayWeekMonthRespectLocalDatesAndFirstWeekdayAcrossDst() {
        assertEquals(listOf("Day", "Week", "Month"), Mango9CalendarMode.entries.map { it.name })
        val day = LocalDate.of(2026, 3, 8)
        assertEquals(Duration.ofHours(23), Mango9CalendarMode.Day.range(day, la, DayOfWeek.SUNDAY).duration)
        assertEquals(LocalDate.of(2026, 3, 2), Mango9CalendarMode.Week.range(day, la, DayOfWeek.MONDAY).start.atZone(la).toLocalDate())
        assertEquals(day, Mango9CalendarMode.Week.range(day, la, DayOfWeek.SUNDAY).start.atZone(la).toLocalDate())
        assertEquals(LocalDate.of(2026, 4, 1), Mango9CalendarMode.Month.range(day, la, DayOfWeek.SUNDAY).end.atZone(la).toLocalDate())
        assertEquals(LocalDate.of(2026, 2, 1), Mango9CalendarMode.Month.move(LocalDate.of(2026, 1, 31), 1))
    }

    @Test fun recurringIsoWeekdaysKeepServerIdentityAndIndependentDisplayIds() {
        val master = Mango9CalendarTestFixtures.event().copy(recurrence = Mango9AppointmentRecurrence("custom", listOf(1, 3, 7, 3)))
        val values = Mango9CalendarOccurrences.expand(listOf(master, master), range("2026-09-11T07:00:00Z", "2026-09-18T07:00:00Z"))
        assertEquals(listOf(11, 13, 14, 16), values.map { it.startAt.atZone(la).dayOfMonth })
        assertEquals(4, values.map { it.displayId }.toSet().size)
        assertTrue(values.all { it.id == master.id && it.revision == master.revision && it.permissions == master.permissions })
        assertEquals(values[2], master.retainingOccurrence(values[2]))
        assertEquals(master.startAt, master.copy(revision = "changed").retainingOccurrence(values[2]).startAt)
    }

    @Test fun dailyOvernightOccurrencesKeepWallClockThroughFallDst() {
        val master = Mango9CalendarTestFixtures.event().copy(
            startAt = at("2026-11-01T06:30:00Z"),
            endAt = at("2026-11-01T07:30:00Z"), recurrence = Mango9AppointmentRecurrence("daily", emptyList())
        )
        val values = Mango9CalendarOccurrences.expand(listOf(master), range("2026-11-01T07:00:00Z", "2026-11-03T08:00:00Z"))
        assertEquals(3, values.size)
        assertTrue(values.all { it.startAt.atZone(la).hour == 23 && it.endAt.atZone(la).hour == 0 && it.endAt > it.startAt })
        assertEquals(Duration.ofHours(25), Duration.between(values[0].startAt, values[1].startAt))
    }

    @Test fun springGapNeverProducesNegativeDuration() {
        val master = Mango9CalendarTestFixtures.event().copy(
            startAt = at("2026-03-07T10:30:00Z"),
            endAt = at("2026-03-07T11:00:00Z"), recurrence = Mango9AppointmentRecurrence("daily", emptyList())
        )
        val values = Mango9CalendarOccurrences.expand(listOf(master), range("2026-03-08T08:00:00Z", "2026-03-09T07:00:00Z"))
        assertEquals(1, values.size)
        assertEquals(3, values.single().startAt.atZone(la).hour)
        assertEquals(30, values.single().startAt.atZone(la).minute)
        assertTrue(values.single().endAt > values.single().startAt)
    }

    @Test fun monthlyFollowsPhpOverflowNotEndOfMonthClamping() {
        val master = Mango9CalendarTestFixtures.event().copy(
            startAt = at("2026-01-31T17:00:00Z"),
            endAt = at("2026-01-31T17:30:00Z"), recurrence = Mango9AppointmentRecurrence("monthly", emptyList())
        )
        val values = Mango9CalendarOccurrences.expand(listOf(master), Mango9CalendarRange(master.startAt, master.startAt.plus(Duration.ofDays(365))))
        assertEquals(LocalDate.of(2026, 3, 3), values[1].startAt.atZone(la).toLocalDate())
        assertEquals(LocalDate.of(2026, 4, 3), values[2].startAt.atZone(la).toLocalDate())
        assertTrue(Mango9CalendarOccurrences.expand(listOf(master), range("2027-03-01T00:00:00Z", "2027-04-01T00:00:00Z")).isEmpty())
    }

    @Test fun recurrenceIsBoundedAndNarrowRangesRemainUsable() {
        val master = Mango9CalendarTestFixtures.event().copy(recurrence = Mango9AppointmentRecurrence("daily", emptyList()))
        val masters = (1..500).map { master.copy(id = it) }
        val result =
            runCatching {
                Mango9CalendarOccurrences.expand(masters, Mango9CalendarRange(master.startAt, master.startAt.plus(Duration.ofDays(31))))
            }
        assertEquals("too_many_occurrences", (result.exceptionOrNull() as Mango9CalendarFailure).code)
        assertEquals(500, Mango9CalendarOccurrences.expand(masters, Mango9CalendarRange(master.startAt, master.startAt.plus(Duration.ofDays(1)))).size)
        assertThrows(CancellationException::class.java) {
            Mango9CalendarOccurrences.expand(masters, Mango9CalendarRange(master.startAt, master.endAt)) {
                throw CancellationException()
            }
        }
    }

    @Test fun invalidStoredEventsAndUnknownRulesFailInsteadOfFabricatingData() {
        val event = Mango9CalendarTestFixtures.event()
        for (invalid in listOf(event.copy(endAt = event.startAt), event.copy(timezone = "Invalid/Zone", recurrence = Mango9AppointmentRecurrence("daily", emptyList())))) {
            assertEquals(
                "invalid_stored_event",
                (
                    runCatching {
                Mango9CalendarOccurrences.expand(listOf(invalid), Mango9CalendarRange(event.startAt, event.endAt))
            }.exceptionOrNull() as Mango9CalendarFailure
                ).code
            )
        }
        assertEquals(
            "unsupported_recurrence",
            (
                runCatching {
            Mango9CalendarOccurrences.expand(listOf(event.copy(recurrence = Mango9AppointmentRecurrence("yearly", emptyList()))), Mango9CalendarRange(event.startAt, event.endAt))
        }.exceptionOrNull() as Mango9CalendarFailure
            ).code
        )
    }

    @Test fun unconfiguredHoursAreDistinctFromExplicitlyClosedHours() {
        val hours = Mango9CalendarTestFixtures.hours(false)
        val saturday = LocalDate.of(2026, 9, 12)
        val start = saturday.atTime(12, 0).atZone(la).toInstant()
        assertTrue(hours.allows(start, start.plusSeconds(1800)))
        assertTrue(hours.closedMinutes(saturday, la).isEmpty())
        assertFalse(hours.closeAll().allows(start, start.plusSeconds(1800)))
        assertEquals(listOf(0.0 to 1440.0), hours.closeAll().closedMinutes(saturday, la))
        assertNull(hours.useDefaults().firstStart(saturday, la))
        assertTrue(hours.useDefaults().configured)
    }

    @Test fun remoteDisplayDayProjectsHoursWithoutShiftingAppointmentInstants() {
        val hours = Mango9CalendarTestFixtures.hours()
        val yerevan = ZoneId.of("Asia/Yerevan")
        val saturday = LocalDate.of(2026, 9, 12)
        assertEquals(listOf(300.0 to 1440.0), hours.closedMinutes(saturday, yerevan))
        assertEquals(saturday.atStartOfDay(yerevan).toInstant(), hours.firstStart(saturday, yerevan))
        assertEquals(listOf(0.0 to 1200.0), hours.closedMinutes(LocalDate.of(2026, 9, 14), yerevan))
        val opening = at("2026-09-11T16:00:00Z"); val closing = at("2026-09-12T01:00:00Z")
        assertTrue(hours.allows(opening, closing))
        assertFalse(hours.allows(opening.minusSeconds(1), closing))
        assertFalse(hours.allows(opening, closing.plusSeconds(1)))
    }

    @Test fun hoursValidationAndTimezoneOnlyPayloadPreserveUnconfiguredSchedule() {
        for (invalid in listOf("9:00", "09:70", "09:00:99", "09:00x", "", "09:00:00:00", "25:00")) assertNull(Mango9BusinessHours.seconds(invalid))
        assertEquals(0, Mango9BusinessHours.seconds("00:00"))
        val original = Mango9CalendarTestFixtures.hours(false)
        val draft = original.copy(timezone = "Asia/Yerevan")
        assertEquals(setOf("action", "timezone"), draft.changes(original).keySet())
        assertEquals("timezone", draft.changes(original).getString("action"))
        assertFalse(draft.configured)
        assertEquals("replace", draft.useDefaults().changes(original).getString("action"))
        assertFalse(draft.useDefaults().changes(original).has("user_id"))
        assertNotNull(original.copy(timezone = "Invalid/Zone").validationMessage)
        assertNotNull(original.copy(days = original.days.drop(1)).validationMessage)
        assertNull(
            original.copy(
                days = original.days.map {
            if (it.weekday == 1) it.copy(close = it.open) else it
        }
            ).validationMessage
        )
    }

    @Test fun businessHoursRetainLocalOpeningTimeAcrossBothDstChanges() {
        val hours = Mango9CalendarTestFixtures.hours()
        for (date in listOf("2026-03-06", "2026-03-09", "2026-10-30", "2026-11-02")) {
            val day = LocalDate.parse(date)
            val range = hours.openIntervals(day, la).single()
            assertEquals(day.atTime(9, 0).atZone(la).toInstant(), range.start)
            assertEquals(Duration.ofHours(9), range.duration)
            assertEquals(listOf(0.0 to 540.0, 1080.0 to 1440.0), hours.closedMinutes(day, la))
        }
    }

    @Test fun serverStatusColorsWinWithWebPriorityFallback() {
        val event = Mango9CalendarTestFixtures.event()
        assertEquals(0x008080, Mango9AppointmentAppearance.rgb(event))
        assertEquals(0xff0000, Mango9AppointmentAppearance.rgb(event.copy(status = null, priority = "high")))
        assertEquals(0xffa500, Mango9AppointmentAppearance.rgb(event.copy(status = event.status?.copy(color = "invalid"))))
        assertEquals(0xaabbcc, Mango9AppointmentAppearance.statusRgb(" #AbC "))
        assertEquals(0x008000, Mango9AppointmentAppearance.statusRgb("green"))
        assertNull(Mango9AppointmentAppearance.statusRgb("#12345678"))
    }

    @Test fun preferencesUseAccountDeviceOrExplicitZoneWithoutChangingStoredEvents() {
        val settings = Mango9CalendarJson.preferences(JSONObject(Mango9CalendarTestFixtures.settingsJson))
        assertEquals(ZoneId.of("Asia/Yerevan"), settings.appointmentZone(la))
        assertEquals(DayOfWeek.MONDAY, settings.weekStartsOn(DayOfWeek.SUNDAY))
        assertEquals(ZoneId.of("Europe/Paris"), settings.copy(newAppointmentTimezone = "account").appointmentZone(la, "Europe/Paris"))
        assertEquals(la, settings.copy(newAppointmentTimezone = "device").appointmentZone(la))
        assertFalse(settings.body().has("revision")); assertFalse(settings.body().has("user_id")); assertFalse(settings.body().has("account_timezone"))
    }

    @Test fun reminderCadenceIsBoundedAccountScopedAndUsesServerTime() {
        val event = Mango9CalendarTestFixtures.event()
        val reminder = Mango9PersonalReminder(true, 15, event.startAt, false, false, "reminder-fixture")
        val item = Mango9ReminderItem(event, reminder)
        val session = Mango9CalendarTestFixtures.session()
        val key = Mango9ReminderCadence.key(session, item)
        assertEquals(64, key.length)
        assertNotEquals(key, Mango9ReminderCadence.key(session.copy(crmApiBaseUrl = "https://second.example.invalid/api/v2"), item))
        val cadence = Mango9ReminderCadence()
        assertTrue(cadence.allows(key, event.startAt, 0)); cadence.record(key, event.startAt)
        assertFalse(cadence.allows(key, event.startAt.plusSeconds(3600), 0))
        assertFalse(cadence.allows(key, event.startAt.plusSeconds(299), 5))
        assertTrue(cadence.allows(key, event.startAt.plusSeconds(300), 5))
        repeat(300) { cadence.record("fixture-$it", event.startAt.plusSeconds(it.toLong())) }
        assertEquals(256, cadence.snapshot().size)
        val items = listOf(
            item, item.copy(reminder = reminder.copy(dueAt = event.startAt.plusSeconds(1))),
            item.copy(reminder = reminder.copy(dismissed = true)), item.copy(event = event.copy(recurrence = Mango9AppointmentRecurrence("daily", emptyList())))
        )
        assertEquals(listOf(item), Mango9ReminderFeed(items, event.startAt, null).due())
        assertTrue(Mango9ReminderFeed(items, event.endAt, null).due().isEmpty())
    }

    private fun JSONObject.keySet(): Set<String> = keys().asSequence().toSet()
}
