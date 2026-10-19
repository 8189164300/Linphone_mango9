/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.ContextThemeWrapper
import android.view.View
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.R
import org.linphone.ui.main.crm.fragment.Mango9CalendarView

@RunWith(AndroidJUnit4::class)
class Mango9CalendarViewInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val zone = ZoneId.of("America/Los_Angeles")
    private val date = LocalDate.of(2026, 9, 12)

    private fun appointment(id: Int = 1) = Mango9Appointment(
        id, 42, "Consultation with Taylor", "", date.atTime(10, 0).atZone(zone).toInstant(), date.atTime(10, 30).atZone(zone).toInstant(),
        zone.id, "appointment", "medium", Mango9AppointmentStatus(3, "Confirmed", "#008080"), null,
        Mango9AppointmentRecurrence("none", emptyList()), emptyList(), "appointment", emptyList(),
        Mango9AppointmentPermissions(true, true, false, false, true, null), "fixture-$id",
    )

    private fun state(mode: Mango9CalendarMode, events: List<Mango9Appointment>) = Mango9CalendarScreenState(date, zone, DayOfWeek.MONDAY, calendar = true, mode = mode, events = events)

    // Select the exact slot using the OS clock/locale contract, not an English
    // fixed-24-hour substring. The gesture assertions below independently check
    // that this node still creates precisely September 12 at 10:15 in Los Angeles.
    private fun slotLabel(view: View): String {
        val locale = view.resources.configuration.locales[0]
        val skeleton = "yMMMEd" + if (DateFormat.is24HourFormat(view.context)) "Hm" else "hm"
        return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale).apply {
            timeZone = TimeZone.getTimeZone(zone)
        }.format(Date.from(date.atTime(10, 15).atZone(zone).toInstant()))
    }

    @Test fun shortEventTitlesRemainVisibleAtFractionalZoomAndLargeText() {
        for (fontScale in listOf(1f, 1.8f)) for (zoom in listOf(44f, 68f, 175.5f)) {
            val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
            lateinit var view: Mango9CalendarView
            try {
                instrumentation.runOnMainSync {
                    val configured = instrumentation.targetContext.createConfigurationContext(
                        Configuration(instrumentation.targetContext.resources.configuration).apply {
                        this.fontScale = fontScale
                    }
                    )
                    val ctx = ContextThemeWrapper(configured, R.style.Theme_Linphone)
                    view = Mango9CalendarView(ctx)
                    view.bind(scope); view.setZoom(zoom)
                    val density = ctx.resources.displayMetrics.density
                    measure(view, (350 * density).toInt(), (650 * density).toInt())
                    view.submit(state(Mango9CalendarMode.Day, listOf(appointment())))
                }
                awaitReady(view)
                instrumentation.runOnMainSync {
                    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    view.draw(Canvas(bitmap))
                    val provider = view.accessibilityNodeProvider!!
                    val host = requireNotNull(provider.createAccessibilityNodeInfo(View.NO_ID))
                    assertNotNull(host)
                    val nodes = (0 until host.childCount).mapNotNull { provider.createAccessibilityNodeInfo(it) }
                    val title = nodes.firstOrNull { it.contentDescription?.contains("Consultation with Taylor") == true }
                    assertNotNull("Full title must remain accessible at font $fontScale / zoom $zoom", title)
                    val bounds = Rect().also { title!!.getBoundsInParent(it) }
                    assertTrue(bounds.height() > 0)
                    var ink = 0
                    for (y in bounds.top.coerceAtLeast(0) until bounds.bottom.coerceAtMost(bitmap.height)) {
                        for (x in (bounds.left + 8).coerceAtLeast(0) until bounds.right.coerceAtMost(bitmap.width)) {
                            if (bitmap.getPixel(x, y) == 0xff232c3a.toInt()) ink++
                        }
                    }
                    assertTrue("Title must actually draw, not just have an accessibility label: $fontScale/$zoom", ink > 10)
                    if (zoom == 44f) {
                        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "calendar-native-fixtures").apply {
                            mkdirs()
                        }
                        File(directory, "calendar-day-$fontScale.png").outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    }
                    bitmap.recycle()
                }
            } finally { scope.cancel() }
        }
    }

    @Test fun crowdedDayAggregatesWithoutLosingCountAndMonthOffersEveryDate() {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        lateinit var view: Mango9CalendarView
        var openedDay: LocalDate? = null
        try {
            instrumentation.runOnMainSync {
                val ctx = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_Linphone)
                view = Mango9CalendarView(ctx); view.bind(scope)
                val density = ctx.resources.displayMetrics.density
                measure(view, (350 * density).toInt(), (650 * density).toInt())
                view.onCrowdedDay = { openedDay = it }
                view.submit(state(Mango9CalendarMode.Day, (1..10_000).map(::appointment)))
            }
            awaitReady(view)
            instrumentation.runOnMainSync {
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                val provider = view.accessibilityNodeProvider!!
                val count = requireNotNull(provider.createAccessibilityNodeInfo(View.NO_ID)).childCount
                assertTrue("Dense day must not create 10k accessibility children", count < 150)
                val aggregation = (0 until count).firstOrNull {
                    provider.createAccessibilityNodeInfo(it)?.contentDescription?.contains("10000 appointments") == true
                }
                assertNotNull("All 10k events must be reachable, not silently clipped", aggregation)
                assertTrue(provider.performAction(aggregation!!, AccessibilityNodeInfo.ACTION_CLICK, null))
                assertEquals(date, openedDay)
                bitmap.recycle()
                view.submit(state(Mango9CalendarMode.Month, listOf(appointment())))
            }
            awaitReady(view)
            instrumentation.runOnMainSync {
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                val provider = view.accessibilityNodeProvider!!
                val count = requireNotNull(provider.createAccessibilityNodeInfo(View.NO_ID)).childCount
                val dayNames = (0 until count).mapNotNull { provider.createAccessibilityNodeInfo(it)?.contentDescription?.toString() }
                assertTrue(dayNames.any { it.contains("August 31, 2026") })
                assertTrue(dayNames.any { it.contains("October 4, 2026") })
                assertFalse(
                    "Month cells must show counts, not tiny event title targets",
                    dayNames.any {
                    it.contains("Consultation with Taylor")
                }
                )
                val eventDay = (0 until count).first {
                    provider.createAccessibilityNodeInfo(it)?.contentDescription?.contains("September 12, 2026") == true
                }
                assertTrue(provider.createAccessibilityNodeInfo(eventDay)!!.contentDescription.contains("1 appointment"))
                var selected: LocalDate? = null
                view.onDay = { selected = it }
                assertTrue(provider.performAction(eventDay, AccessibilityNodeInfo.ACTION_CLICK, null))
                assertEquals(date, selected)
                bitmap.recycle()
            }
        } finally { scope.cancel() }
    }

    @Test fun heldTimelineSlotUsesDisplayedDateAndTimeWithoutFallingBackToNow() {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        lateinit var view: Mango9CalendarView
        var selected: Instant? = null
        try {
            instrumentation.runOnMainSync {
                view = Mango9CalendarView(ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_Linphone))
                view.bind(scope); view.onCreate = { selected = it }
                val density = view.resources.displayMetrics.density
                measure(view, (350 * density).toInt(), (650 * density).toInt())
                view.submit(state(Mango9CalendarMode.Day, emptyList()))
            }
            awaitReady(view)
            instrumentation.runOnMainSync {
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                val provider = view.accessibilityNodeProvider!!
                val count = requireNotNull(provider.createAccessibilityNodeInfo(View.NO_ID)).childCount
                val expected = slotLabel(view)
                val target = (0 until count).single {
                    provider.createAccessibilityNodeInfo(it)?.contentDescription == expected
                }
                assertTrue(provider.performAction(target, AccessibilityNodeInfo.ACTION_LONG_CLICK, null))
                assertEquals(date.atTime(10, 15).atZone(zone).toInstant(), selected)
                bitmap.recycle()
            }
        } finally { scope.cancel() }
    }

    @Test fun emptySlotRequiresTouchHoldAndKeepsItsTimeAfterZoom() {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        lateinit var view: Mango9CalendarView
        val created = mutableListOf<Instant>()

        fun slotBounds(): Rect {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap)); bitmap.recycle()
            val provider = view.accessibilityNodeProvider!!
            val count = requireNotNull(provider.createAccessibilityNodeInfo(View.NO_ID)).childCount
            val expected = slotLabel(view)
            val node = (0 until count).mapNotNull { provider.createAccessibilityNodeInfo(it) }.single {
                it.contentDescription == expected
            }
            return Rect().also(node::getBoundsInParent)
        }

        fun touch(action: Int, downAt: Long, bounds: Rect) {
            val event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            try { view.onTouchEvent(event) } finally { event.recycle() }
        }
        try {
            instrumentation.runOnMainSync {
                view = Mango9CalendarView(ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_Linphone))
                view.bind(scope); view.onCreate = { created.add(it) }
                val density = view.resources.displayMetrics.density
                measure(view, (350 * density).toInt(), (650 * density).toInt())
                view.submit(state(Mango9CalendarMode.Day, emptyList()))
            }
            awaitReady(view)
            instrumentation.runOnMainSync {
                val bounds = slotBounds()
                val now = SystemClock.uptimeMillis()
                touch(MotionEvent.ACTION_DOWN, now, bounds); touch(MotionEvent.ACTION_UP, now, bounds)
                assertTrue("A tap in empty space must not create an appointment", created.isEmpty())
                view.setZoom(175.5f, bounds.exactCenterY())
            }
            awaitReady(view)
            lateinit var held: Rect
            val downAt = SystemClock.uptimeMillis()
            instrumentation.runOnMainSync {
                held = slotBounds(); touch(MotionEvent.ACTION_DOWN, downAt, held)
            }
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 150)
            instrumentation.runOnMainSync {
                touch(MotionEvent.ACTION_UP, downAt, held)
                assertEquals(listOf(date.atTime(10, 15).atZone(zone).toInstant()), created)
            }
        } finally { scope.cancel() }
    }

    @Test fun closedDatesHeadersAndTimelineSlotsExplainInsteadOfSilentlyDoingNothing() {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        val days = (0..6).map { Mango9BusinessDay(it, null, null) }
        val hours = Mango9BusinessHours(true, zone.id, true, true, days, days, "closed-hours")
        val metadata =
            Mango9CalendarMetadata(zone.id, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), Mango9CalendarCapabilities(true, false, false, false), hours)
        lateinit var view: Mango9CalendarView
        var explanations = 0; var created = 0
        try {
            instrumentation.runOnMainSync {
                view = Mango9CalendarView(ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_Linphone))
                view.bind(scope); view.onOutsideHours = { explanations++ }; view.onCreate = { created++ }
                measure(view, (350 * view.resources.displayMetrics.density).toInt(), (650 * view.resources.displayMetrics.density).toInt())
            }
            for (mode in listOf(Mango9CalendarMode.Month, Mango9CalendarMode.Day, Mango9CalendarMode.Week)) {
                instrumentation.runOnMainSync { view.submit(state(mode, emptyList()).copy(metadata = metadata)) }
                awaitReady(view)
                instrumentation.runOnMainSync {
                    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    view.draw(Canvas(bitmap)); bitmap.recycle()
                    val provider = view.accessibilityNodeProvider!!
                    val count = provider.createAccessibilityNodeInfo(View.NO_ID)!!.childCount
                    val expected = slotLabel(view) + ", " + view.context.getString(R.string.mango9_calendar_outside_hours)
                    val target = (0 until count).single {
                        val description = provider.createAccessibilityNodeInfo(it)?.contentDescription
                        if (mode == Mango9CalendarMode.Month) {
                            description?.startsWith("Saturday, September 12, 2026,") == true
                        } else {
                            description == expected
                        }
                    }
                    val before = explanations
                    assertTrue(provider.performAction(target, AccessibilityNodeInfo.ACTION_LONG_CLICK, null))
                    assertEquals(before + 1, explanations); assertEquals(0, created)
                    if (mode != Mango9CalendarMode.Month) {
                        val header = (0 until count).first { provider.createAccessibilityNodeInfo(it)?.contentDescription == date.toString() }
                        assertTrue(provider.performAction(header, AccessibilityNodeInfo.ACTION_LONG_CLICK, null))
                        assertEquals(before + 2, explanations); assertEquals(0, created)
                        assertTrue(provider.createAccessibilityNodeInfo(target)!!.contentDescription.contains("Outside business hours"))
                    }
                }
            }
        } finally { scope.cancel() }
    }

    private fun measure(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)); view.layout(0, 0, width, height)
    }

    private fun awaitReady(view: Mango9CalendarView) {
        val ready = CountDownLatch(1)
        val handler = Handler(Looper.getMainLooper())
        val check = object : Runnable { override fun run() {
            if (view.isLayoutReady) ready.countDown() else handler.postDelayed(this, 20)
        } }
        handler.post(check)
        try {
            assertTrue("Native calendar layout did not complete", ready.await(15, TimeUnit.SECONDS))
        } finally { handler.removeCallbacks(check) }
    }
}
