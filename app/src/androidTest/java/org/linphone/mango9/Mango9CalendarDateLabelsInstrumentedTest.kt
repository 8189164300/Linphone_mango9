/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import android.content.res.Configuration
import android.text.format.DateFormat
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.R
import org.linphone.ui.main.crm.fragment.Mango9AgendaRow
import org.linphone.ui.main.crm.fragment.Mango9AppointmentAgendaAdapter
import org.linphone.ui.main.crm.fragment.Mango9CalendarDateLabels
import org.linphone.ui.main.crm.fragment.Mango9ReminderBanner
import org.linphone.ui.main.crm.fragment.Mango9CalendarView

@RunWith(AndroidJUnit4::class)
class Mango9CalendarDateLabelsInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val utc = ZoneId.of("UTC")

    private fun configured(locale: Locale) = ContextThemeWrapper(
        context.createConfigurationContext(
            Configuration(context.resources.configuration).apply {
            setLocale(locale)
        }
        ),
            R.style.Theme_Linphone
    )

    private fun spaces(value: String) = value.replace('\u202f', ' ').replace('\u00a0', ' ')

    @Test fun explicitClockPreferencesMatchNativeTimeInEnglishFrenchAndArabic() {
        for (clock in listOf("12", "24")) Mango9ClockPreferenceFixture.withPreference(clock) {
            instrumentation.runOnMainSync {
                for (locale in listOf(Locale.US, Locale.FRANCE, Locale.forLanguageTag("ar-EG"))) {
                    val ctx = configured(locale)
                    val labels = Mango9CalendarDateLabels(ctx)
                    for (zone in listOf(utc, ZoneId.of("America/Los_Angeles"))) {
                        val native = DateFormat.getTimeFormat(ctx).apply { timeZone = TimeZone.getTimeZone(zone) }
                        for (hour in listOf(0, 12, 13, 23)) {
                            val instant = java.time.LocalDate.of(2030, 1, 10).atTime(hour, 45).atZone(zone).toInstant()
                            val expected = spaces(native.format(Date.from(instant)))
                            assertEquals("$locale/$clock/$zone/$hour", expected, spaces(labels.time(instant, zone)))
                            assertTrue(spaces(labels.dateTime(instant, zone)).contains(expected))
                        }
                    }
                }
            }
        }
    }

    @Test fun clockUpdatesPreserveRawBusinessHoursAndDistinguishDstOffsetsAndYearBoundaries() {
        Mango9ClockPreferenceFixture.withPreference("12") {
            val ctx = configured(Locale.US)
            lateinit var labels: Mango9CalendarDateLabels
            instrumentation.runOnMainSync {
                labels = Mango9CalendarDateLabels(ctx)
                assertEquals("1:45 PM", spaces(labels.businessTime("13:45")))
                assertEquals("1:45:30 PM", spaces(labels.businessTime("13:45:30")))
                assertEquals("12:00 AM", spaces(labels.wallTime(LocalTime.MIDNIGHT)))
                assertEquals("12:00 PM", spaces(labels.wallTime(LocalTime.NOON)))
                val zone = ZoneId.of("America/Los_Angeles")
                val range = labels.timeRange(Instant.parse("2026-11-01T08:30:00Z"), Instant.parse("2026-11-01T09:45:00Z"), zone)
                assertTrue(range.contains("PDT")); assertTrue(range.contains("PST"))
                assertTrue(labels.timeRange(Instant.parse("2029-12-31T23:30:00Z"), Instant.parse("2030-01-01T00:30:00Z"), utc).contains("2030"))
                assertEquals("invalid", labels.businessTime("invalid"))
            }
            Mango9ClockPreferenceFixture.set("24")
            instrumentation.runOnMainSync {
                val replacement = labels.updated(ctx)
                assertNotSame(labels, replacement)
                assertEquals("13:45", replacement.businessTime("13:45"))
                assertEquals("13:45:30", replacement.businessTime("13:45:30"))
                assertSame(replacement, replacement.updated(ctx))
                assertNotSame(replacement, replacement.updated(configured(Locale.FRANCE)))
            }
        }
    }

    @Test fun agendaAndReminderBannerUseTheSameClockAsTheNativePicker() {
        for (clock in listOf("12", "24")) Mango9ClockPreferenceFixture.withPreference(clock) {
            instrumentation.runOnMainSync {
                val ctx = configured(Locale.US)
                val event = Mango9CalendarJson.appointment(Mango9AppointmentReminderInstrumentedTest.Fixture().event).copy(
                    startAt = Instant.parse("2030-01-10T13:45:00Z"), endAt = Instant.parse("2030-01-10T14:15:00Z")
                )
                val adapter = Mango9AppointmentAgendaAdapter {}
                adapter.submitList(listOf(Mango9AgendaRow.Event(event, utc, event.startAt.epochSecond / 60)))
                val holder = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
                adapter.onBindViewHolder(holder, 0)
                val text = holder.box.findViewWithTag<TextView>("appointment.agenda.times").text.toString()
                val native = DateFormat.getTimeFormat(ctx).apply { timeZone = TimeZone.getTimeZone(utc) }
                assertEquals(spaces(native.format(Date.from(event.startAt)) + " – " + native.format(Date.from(event.endAt))), spaces(text))
                assertTrue(holder.box.contentDescription.contains(text))
                val banner = Mango9ReminderBanner(ctx)
                val item =
                    Mango9ReminderItem(event, Mango9CalendarJson.personalReminder(Mango9AppointmentReminderInstrumentedTest.Fixture().reminder))
                banner.render(Mango9ForegroundReminderState(item = item))
                val deviceNative = DateFormat.getTimeFormat(ctx).apply { timeZone = TimeZone.getDefault() }
                assertTrue(spaces(banner.findViewWithTag<TextView>("reminder.banner.subtitle").text.toString()).contains(spaces(deviceNative.format(Date.from(event.startAt)))))
            }
        }
    }

    @Test fun calendarEventAndEmptySlotAccessibilityRefreshWhenClockPreferenceChanges() {
        Mango9ClockPreferenceFixture.withPreference("12") {
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())
            val ctx = configured(Locale.US)
            val event = Mango9CalendarJson.appointment(Mango9AppointmentReminderInstrumentedTest.Fixture().event).copy(
                startAt = Instant.parse("2030-01-10T13:45:00Z"), endAt = Instant.parse("2030-01-10T14:15:00Z")
            )
            lateinit var view: Mango9CalendarView
            try {
                instrumentation.runOnMainSync {
                    view = Mango9CalendarView(ctx)
                    view.bind(scope)
                    view.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(1800, android.view.View.MeasureSpec.EXACTLY))
                    view.layout(0, 0, 1080, 1800)
                }
                for (mode in Mango9CalendarMode.entries) {
                    instrumentation.runOnMainSync {
                        view.submit(Mango9CalendarScreenState(java.time.LocalDate.of(2030, 1, 10), utc, java.time.DayOfWeek.MONDAY, calendar = true, mode = mode, events = listOf(event)))
                    }
                    val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
                    var ready = false
                    while (!ready && android.os.SystemClock.elapsedRealtime() < deadline) {
                        instrumentation.runOnMainSync { ready = view.isLayoutReady }
                        if (!ready) android.os.SystemClock.sleep(20)
                    }
                    assertTrue("Calendar layout must finish", ready)
                    for (clock in listOf("12", "24")) {
                        Mango9ClockPreferenceFixture.set(clock)
                        instrumentation.runOnMainSync {
                            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                            try {
                                view.draw(android.graphics.Canvas(bitmap))
                                val provider = view.accessibilityNodeProvider!!
                                val host = requireNotNull(provider.createAccessibilityNodeInfo(android.view.View.NO_ID))
                                val nodes = (0 until host.childCount).mapNotNull { provider.createAccessibilityNodeInfo(it) }
                                if (mode == Mango9CalendarMode.Month) {
                                    val count = ctx.resources.getQuantityString(org.linphone.R.plurals.mango9_calendar_count, 1, 1)
                                    assertTrue(
                                        "Month exposes the event count instead of titles",
                                        nodes.any {
                                        it.contentDescription?.contains(count) == true
                                    }
                                    )
                                    assertFalse(nodes.any { it.contentDescription?.contains(event.title) == true })
                                } else {
                                val title = nodes.single { it.contentDescription?.contains(event.title) == true }.contentDescription.toString()
                                val native = DateFormat.getTimeFormat(ctx).apply { timeZone = TimeZone.getTimeZone(utc) }
                                assertTrue("$mode/$clock event time", spaces(title).contains(spaces(native.format(Date.from(event.startAt)))))
                                assertTrue("$mode/$clock event end", spaces(title).contains(spaces(native.format(Date.from(event.endAt)))))
                                }
                                if (mode != Mango9CalendarMode.Month) {
                                    val slot = nodes.firstOrNull {
                                        it.actionList.any { action -> action.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK }
                                    }
                                    assertNotNull("Empty time slots must remain reachable", slot)
                                    val text = spaces(slot!!.contentDescription.toString())
                                    if (clock == "12") {
                                        assertTrue(text.contains("AM") || text.contains("PM"))
                                    } else {
                                        assertFalse(text.contains("AM") || text.contains("PM"))
                                    }
                                }
                            } finally { bitmap.recycle() }
                        }
                    }
                }
            } finally { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
        }
    }
}
