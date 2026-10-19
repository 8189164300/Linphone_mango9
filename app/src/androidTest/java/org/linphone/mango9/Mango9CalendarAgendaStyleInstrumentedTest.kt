/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.R
import org.linphone.ui.main.crm.fragment.Mango9AgendaRow
import org.linphone.ui.main.crm.fragment.Mango9AppointmentAgendaAdapter

@RunWith(AndroidJUnit4::class)
class Mango9CalendarAgendaStyleInstrumentedTest {
    @Test fun largeTypeCardsExposeNoStatusAndIndependentColoredIndicatorsWithoutLosingText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (scale in listOf(1f, 1.8f)) instrumentation.runOnMainSync {
            val configured = instrumentation.targetContext.createConfigurationContext(
                Configuration(instrumentation.targetContext.resources.configuration).apply {
                fontScale = scale
            }
            )
            val context = ContextThemeWrapper(configured, R.style.Theme_Linphone)
            val event = Mango9CalendarJson.appointment(Mango9AppointmentReminderInstrumentedTest.Fixture().event).copy(
                title = "Follow up with the appointment client", status = null, priority = "high", activity = "follow_up",
                recurrence = Mango9AppointmentRecurrence("daily", emptyList()), reminders = listOf(Mango9AppointmentReminder(15, listOf("email"))),
            )
            var opened: Mango9Appointment? = null
            val adapter = Mango9AppointmentAgendaAdapter { opened = it }
            adapter.submitList(listOf(Mango9AgendaRow.Event(event, ZoneId.of("UTC"), Instant.parse("2030-01-10T08:45:00Z").epochSecond / 60)))
            val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
            adapter.onBindViewHolder(holder, 0)
            val box = holder.box
            val width = (320 * context.resources.displayMetrics.density).toInt()
            box.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            box.layout(0, 0, box.measuredWidth, box.measuredHeight)
            val status = box.findViewWithTag<TextView>("appointment.agenda.status")
            assertEquals(context.getString(R.string.mango9_calendar_no_status), status.text)
            assertTrue(status.width > 0); assertTrue(status.height > 0)
            assertTrue(box.contentDescription.contains("Priority: High"))
            assertTrue(box.contentDescription.contains("Activity: Follow Up"))
            assertTrue(box.contentDescription.contains("Recurring"))
            assertTrue(box.contentDescription.contains("Appointment reminder set"))
            assertTrue(box.contentDescription.contains("Upcoming in 15 minutes"))
            assertTrue(box.performClick()); assertEquals(event, opened)
            val bitmap = Bitmap.createBitmap(box.width, box.height, Bitmap.Config.ARGB_8888)
            box.draw(Canvas(bitmap))
            File(context.getExternalFilesDir(null), "calendar-agenda-$scale.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
