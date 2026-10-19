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
import androidx.core.graphics.ColorUtils
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
class Mango9CalendarAdaptiveInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val date = LocalDate.of(2026, 9, 7)
    private val zone = ZoneId.of("America/Los_Angeles")
    private val events = (0L..6L).map { index ->
        val start = date.plusDays(index).atTime(10, 0).atZone(zone).toInstant()
        Mango9Appointment(
            91 + index.toInt(), 42, "Consultation day ${index + 1}", "", start, start.plusSeconds(1800), zone.id, "appointment", "medium", null, null,
            Mango9AppointmentRecurrence("none", emptyList()), emptyList(), "appointment", emptyList(),
            Mango9AppointmentPermissions(true, true, false, false, true, null), "adaptive-fixture"
        )
    }
    private val state =
        Mango9CalendarScreenState(date, zone, DayOfWeek.MONDAY, calendar = true, mode = Mango9CalendarMode.Week, events = events)

    @Test fun enlargedWeekPansEveryDateWithCorrectTargetsAndRetainsBothAxesAcrossRecreation() {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        lateinit var view: Mango9CalendarView
        lateinit var restored: Mango9CalendarView
        var selected: Mango9Appointment? = null
        var created: java.time.Instant? = null
        try {
            instrumentation.runOnMainSync {
                view = make(scope, 1.8f, false)
                view.onEvent = { selected = it }; view.onCreate = { created = it }
                view.submit(state)
            }
            ready(view)
            lateinit var retained: Mango9CalendarViewport
            instrumentation.runOnMainSync {
                val seen = linkedSetOf<String>()
                val originalHour = view.captureViewport()!!.position
                repeat(12) {
                    val nodes = nodes(view)
                    nodes.values.filter { it.contentDescription.toString().matches(Regex("2026-09-\\d\\d")) }.forEach {
                        seen += it.contentDescription.toString()
                        val bounds = Rect().also(it::getBoundsInParent)
                        assertTrue(bounds.left >= 0 && bounds.right <= view.width)
                        assertTrue(bounds.width() > 0)
                    }
                    swipe(view, horizontal = true, distance = 125f)
                }
                assertEquals((0L..6L).map { date.plusDays(it).toString() }.toSet(), seen)
                assertEquals("Horizontal gestures must not move the hour", originalHour, view.captureViewport()!!.position, .01f)
                val visible = nodes(view)
                val lastEvent = visible.entries.single { it.value.contentDescription.toString().startsWith(events.last().title) }
                assertTrue(view.accessibilityNodeProvider!!.performAction(lastEvent.key, AccessibilityNodeInfo.ACTION_CLICK, null))
                assertEquals(events.last(), selected)
                val expectedSlot = org.linphone.ui.main.crm.fragment.Mango9CalendarDateLabels(view.context).dateTime(date.plusDays(6).atTime(9, 30).atZone(zone).toInstant(), zone)
                val slot = visible.entries.single { it.value.contentDescription.toString() == expectedSlot }
                assertTrue(view.accessibilityNodeProvider!!.performAction(slot.key, AccessibilityNodeInfo.ACTION_LONG_CLICK, null))
                assertEquals(date.plusDays(6).atTime(9, 30).atZone(zone).toInstant(), created)
                swipe(view, horizontal = false, distance = 40f)
                retained = view.captureViewport()!!
                assertTrue(retained.dayPosition > 3f)
                assertTrue(retained.position > originalHour)
                restored = make(scope, 2f, true)
                restored.restoreViewport(retained); restored.submit(state)
            }
            ready(restored)
            instrumentation.runOnMainSync {
                assertEquals(retained.dayPosition, restored.captureViewport()!!.dayPosition, .01f)
                assertEquals(retained.position, restored.captureViewport()!!.position, .01f)
                val before = restored.captureViewport()!!.dayPosition
                assertTrue(restored.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id, null))
                assertTrue(restored.captureViewport()!!.dayPosition < before)
                restored.submit(state.copy(date = date.plusWeeks(1), events = emptyList()))
            }
            ready(restored)
            instrumentation.runOnMainSync { assertEquals(0f, restored.captureViewport()!!.dayPosition, .01f) }
        } finally { scope.cancel() }
    }

    @Test fun darkAndLightCalendarDrawReadableTextAndKeepServerColorMeaning() {
        for (night in listOf(false, true)) {
            val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
            lateinit var view: Mango9CalendarView
            try {
                instrumentation.runOnMainSync { view = make(scope, 1.8f, night); view.submit(state) }
                ready(view)
                instrumentation.runOnMainSync {
                    val surface = view.context.getColor(R.color.mango9_calendar_surface)
                    val ink = view.context.getColor(R.color.mango9_calendar_ink)
                    assertTrue(ColorUtils.calculateContrast(ink, surface) >= 7)
                    assertTrue(ColorUtils.calculateContrast(view.context.getColor(R.color.mango9_calendar_accent), surface) >= 4.5)
                    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    view.draw(Canvas(bitmap))
                    assertEquals(surface, bitmap.getPixel(0, 0))
                    val target = nodes(view).values.first { it.contentDescription.toString().startsWith(events.first().title) }
                    val bounds = Rect().also(target::getBoundsInParent)
                    var titlePixels = 0
                    for (y in bounds.top until bounds.bottom) for (x in bounds.left + 12 until bounds.right) if (bitmap.getPixel(x, y) == ink) titlePixels++
                    assertTrue("Real event ink in night=$night", titlePixels > 10)
                    val rgb = Mango9AppointmentAppearance.rgb(events.first()) or (0xff shl 24)
                    assertEquals("Server/priority stripe not recolored", rgb, bitmap.getPixel(bounds.left + 1, bounds.centerY()))
                    bitmap.recycle()
                }
            } finally { scope.cancel() }
        }
    }

    private fun make(scope: CoroutineScope, font: Float, night: Boolean): Mango9CalendarView {
        val ctx =
            ContextThemeWrapper(
                instrumentation.targetContext.createConfigurationContext(
                    Configuration(instrumentation.targetContext.resources.configuration).apply {
            fontScale = font; uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
                ),
            R.style.Theme_Linphone
            )
        return Mango9CalendarView(ctx).apply {
            bind(scope); clock = Clock.fixed(date.atTime(10, 0).atZone(zone).toInstant(), zone)
            val density = resources.displayMetrics.density
            val w = (320 * density).toInt(); val h = (350 * density).toInt()
            measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)); layout(0, 0, w, h)
        }
    }

    private fun nodes(view: Mango9CalendarView): Map<Int, AccessibilityNodeInfo> {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap)); bitmap.recycle()
        val provider = view.accessibilityNodeProvider!!
        return (0 until provider.createAccessibilityNodeInfo(View.NO_ID)!!.childCount).associateWith {
            provider.createAccessibilityNodeInfo(it)!!
        }
    }

    private fun swipe(view: View, horizontal: Boolean, distance: Float) {
        val now = SystemClock.uptimeMillis(); val density = view.resources.displayMetrics.density
        val x = view.width - 20 * density; val y = view.height / 2f
        for ((step, action) in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_CANCEL).withIndex()) {
            val event = MotionEvent.obtain(now, now + step * 20, action, x - if (horizontal && step > 0) distance * density else 0f, y - if (!horizontal && step > 0) distance * density else 0f, 0)
            try { view.onTouchEvent(event) } finally { event.recycle() }
        }
    }

    private fun ready(view: Mango9CalendarView) {
        val until = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < until) {
            var ready = false; instrumentation.runOnMainSync { ready = view.isLayoutReady }
            if (ready) return
            SystemClock.sleep(20)
        }
        fail("Adaptive calendar layout did not become ready")
    }
}
