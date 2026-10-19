/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
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
class Mango9CalendarViewportInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val date = LocalDate.of(2026, 9, 12)
    private val zone = ZoneId.of("America/Los_Angeles")

    private fun state(mode: Mango9CalendarMode) = Mango9CalendarScreenState(date, zone, DayOfWeek.MONDAY, calendar = true, mode = mode)

    private fun make(scope: CoroutineScope, font: Float = 1f, width: Int = 350, height: Int = 230) = Mango9CalendarView(
        ContextThemeWrapper(
            instrumentation.targetContext.createConfigurationContext(
            Configuration(instrumentation.targetContext.resources.configuration).apply { fontScale = font }
        ),
            R.style.Theme_Linphone
        )
    ).apply {
        bind(scope)
        clock = Clock.fixed(date.atTime(10, 0).atZone(zone).toInstant(), zone)
        size(this, width, height)
    }

    @Test fun monthRefreshHoursAndReflowKeepTheVisibleWeekAndNewMonthResets() {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        lateinit var view: Mango9CalendarView
        lateinit var restored: Mango9CalendarView
        lateinit var retained: Mango9CalendarViewport
        lateinit var firstDay: String
        try {
            instrumentation.runOnMainSync { view = make(scope); view.submit(state(Mango9CalendarMode.Month)) }
            ready(view)
            instrumentation.runOnMainSync {
                scroll(view, 135f)
                retained = view.captureViewport()!!
                assertTrue(retained.position > 1f)
                firstDay = visibleDays(view).first()
                val days = (0..6).map { Mango9BusinessDay(it, null, null) }
                val hours = Mango9BusinessHours(true, zone.id, true, true, days, days, "new-hours")
                val metadata =
                    Mango9CalendarMetadata(zone.id, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), Mango9CalendarCapabilities(true, false, false, false), hours)
                view.submit(state(Mango9CalendarMode.Month).copy(query = "filter", metadata = metadata))
            }
            ready(view)
            instrumentation.runOnMainSync {
                assertEquals(retained.position, view.captureViewport()!!.position, .001f)
                assertEquals(firstDay, visibleDays(view).first())
                size(view, 440, 190)
            }
            ready(view)
            instrumentation.runOnMainSync {
                assertEquals(retained.position, view.captureViewport()!!.position, .001f)
                assertEquals(firstDay, visibleDays(view).first())
                restored = make(scope, font = 1.8f, width = 320, height = 200)
                restored.restoreViewport(view.captureViewport())
                restored.submit(state(Mango9CalendarMode.Month))
            }
            ready(restored)
            instrumentation.runOnMainSync {
                assertEquals(retained.position, restored.captureViewport()!!.position, .001f)
                assertEquals(firstDay, visibleDays(restored).first())
                restored.submit(state(Mango9CalendarMode.Month).copy(date = date.plusMonths(1)))
            }
            ready(restored)
            instrumentation.runOnMainSync { assertEquals(0f, restored.captureViewport()!!.position, .001f) }
        } finally { scope.cancel() }
    }

    @Test fun timelineRetainsWallClockPositionAndZoomFocusAcrossReplacementLayouts() {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        lateinit var view: Mango9CalendarView
        lateinit var restored: Mango9CalendarView
        try {
            instrumentation.runOnMainSync { view = make(scope); view.submit(state(Mango9CalendarMode.Day)) }
            ready(view)
            var expected = 0f
            instrumentation.runOnMainSync {
                scroll(view, 115f)
                val old = view.captureViewport()!!.position
                // Focus100dp below the fixed55dp header: preserve exactly that hour.
                val focus = 155 * view.resources.displayMetrics.density
                expected = old + 100f / 68f - 100f / 111.5f
                view.setZoom(111.5f, focus)
                view.submit(state(Mango9CalendarMode.Day).copy(query = "filtered"))
                size(view, 400, 210) // cancels the pending worker, not the semantic anchor
            }
            ready(view)
            instrumentation.runOnMainSync {
                assertEquals(expected, view.captureViewport()!!.position, .01f)
                restored = make(scope, font = 1.8f)
                restored.setZoom(111.5f)
                restored.restoreViewport(view.captureViewport())
                restored.submit(state(Mango9CalendarMode.Day))
            }
            ready(restored)
            instrumentation.runOnMainSync {
                assertEquals(expected, restored.captureViewport()!!.position, .01f)
                restored.resetViewport()
                restored.submit(state(Mango9CalendarMode.Day))
            }
            ready(restored)
            instrumentation.runOnMainSync { assertEquals(9f, restored.captureViewport()!!.position, .01f) }
        } finally { scope.cancel() }
    }

    @Test fun lastMinuteTitlesAreFullyReachableInDayAndWeekAtSmallFractionalZoomAndLargeType() {
        for (mode in listOf(Mango9CalendarMode.Day, Mango9CalendarMode.Week)) for (font in listOf(1f, 1.8f)) for (zoom in listOf(44f, 68.5f)) {
            val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
            lateinit var view: Mango9CalendarView
            val start = date.atTime(23, 59).atZone(zone).toInstant()
            val event =
                Mango9Appointment(
                    91, 42, "Midnight consultation", "", start, start.plusSeconds(60), zone.id, "appointment", "medium", null, null,
                Mango9AppointmentRecurrence("none", emptyList()), emptyList(), "appointment", emptyList(),
                Mango9AppointmentPermissions(true, true, false, false, true, null), "late-fixture"
                )
            var selected: Mango9Appointment? = null
            try {
                instrumentation.runOnMainSync {
                    view = make(scope, font)
                    view.setZoom(zoom); view.onEvent = { selected = it }
                    view.submit(state(mode).copy(events = listOf(event)))
                }
                ready(view)
                instrumentation.runOnMainSync {
                    repeat(30) { scroll(view, 140f) }
                    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    view.draw(Canvas(bitmap))
                    val provider = view.accessibilityNodeProvider!!
                    val count = provider.createAccessibilityNodeInfo(View.NO_ID)!!.childCount
                    val id = (0 until count).single {
                        provider.createAccessibilityNodeInfo(it)?.contentDescription?.startsWith(event.title) == true
                    }
                    val bounds = Rect().also { provider.createAccessibilityNodeInfo(id)!!.getBoundsInParent(it) }
                    val density = view.resources.displayMetrics.density
                    val minimum =
                        maxOf(22 * density, android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 14f, view.resources.displayMetrics) + 5 * density)
                    assertTrue("Full minimum label height $mode/$font/$zoom", bounds.height() >= minimum - 2)
                    assertTrue("Padding below the final title $mode/$font/$zoom", bounds.bottom <= view.height - 8 * density)
                    var ink = 0
                    for (y in bounds.top until bounds.bottom) for (x in bounds.left + (5 * density).toInt() until bounds.right) {
                        if (bitmap.getPixel(x, y) == 0xff232c3a.toInt()) ink++
                    }
                    assertTrue("Title pixels, not just accessibility $mode/$font/$zoom", ink > 10)
                    assertTrue(provider.performAction(id, AccessibilityNodeInfo.ACTION_CLICK, null))
                    assertEquals(event, selected)
                    assertEquals(start, selected!!.startAt)
                    assertEquals(start.plusSeconds(60), selected!!.endAt)
                    bitmap.recycle()
                }
            } finally { scope.cancel() }
        }
    }

    private fun visibleDays(view: Mango9CalendarView): List<String> {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap)); bitmap.recycle()
        val provider = view.accessibilityNodeProvider!!
        val count = provider.createAccessibilityNodeInfo(View.NO_ID)!!.childCount
        return (0 until count).mapNotNull { provider.createAccessibilityNodeInfo(it)?.contentDescription?.toString() }
            .filter { it.contains("2026,") }
    }

    private fun size(view: View, width: Int, height: Int) {
        val density = view.resources.displayMetrics.density
        val w = (width * density).toInt(); val h = (height * density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
    }

    private fun scroll(view: View, distance: Float) {
        val at = SystemClock.uptimeMillis()
        val x = view.width / 2f; val y = view.height - 15 * view.resources.displayMetrics.density
        for ((action, endY) in listOf(MotionEvent.ACTION_DOWN to y, MotionEvent.ACTION_MOVE to y - distance * view.resources.displayMetrics.density, MotionEvent.ACTION_CANCEL to y - distance * view.resources.displayMetrics.density)) {
            val event = MotionEvent.obtain(at, at + if (action == MotionEvent.ACTION_DOWN) 0 else 20, action, x, endY, 0)
            try { view.onTouchEvent(event) } finally { event.recycle() }
        }
    }

    private fun ready(view: Mango9CalendarView) {
        val end = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < end) {
            var ready = false
            instrumentation.runOnMainSync { ready = view.isLayoutReady }
            if (ready) return
            SystemClock.sleep(20)
        }
        fail("Calendar layout did not finish")
    }
}
