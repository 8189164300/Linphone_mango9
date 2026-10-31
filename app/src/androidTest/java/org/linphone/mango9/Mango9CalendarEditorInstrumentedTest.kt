/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Request
import okio.Buffer
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.not
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.core.Account
import org.linphone.core.Core
import org.linphone.core.Factory
import org.linphone.ui.main.MainActivity
import org.linphone.ui.main.crm.fragment.Mango9CalendarFragment
import org.linphone.ui.main.crm.viewmodel.Mango9CalendarViewModel
import org.linphone.ui.main.crm.viewmodel.Mango9ReminderEditsViewModel
import org.linphone.ui.main.crm.fragment.Mango9ReminderHost
import org.linphone.ui.main.crm.fragment.Mango9ReminderBanner

/** Actual CalendarFragment navigation and dialogs, retained production ViewModel, synthetic HTTP. */
@RunWith(AndroidJUnit4::class)
class Mango9CalendarEditorInstrumentedTest {
    // Event forms are full pages now; native date/time/confirmation dialogs still use their own roots.
    private fun onView(matcher: org.hamcrest.Matcher<View>): androidx.test.espresso.ViewInteraction =
        androidx.test.espresso.Espresso.onView(
            org.hamcrest.Matchers.allOf(
                matcher,
                object : org.hamcrest.TypeSafeMatcher<View>() {
            override fun describeTo(description: org.hamcrest.Description) {
                description.appendText("the topmost event page or native dialog")
            }

            override fun matchesSafely(view: View): Boolean {
                var ancestor: View? = view
                while (ancestor != null && ancestor.tag != "mango9.fullpage") ancestor = ancestor.parent as? View
                val page = ancestor ?: return true
                val host = page.parent as? android.view.ViewGroup ?: return false
                return (0 until host.childCount).map(host::getChildAt).lastOrNull { it.tag == "mango9.fullpage" } === page
            }
        }
            )
        )

    private fun isDialog(): org.hamcrest.Matcher<androidx.test.espresso.Root> = object : org.hamcrest.TypeSafeMatcher<androidx.test.espresso.Root>() {
        override fun describeTo(description: org.hamcrest.Description) {
            description.appendText("a focused native dialog or full-screen event page")
        }

        override fun matchesSafely(root: androidx.test.espresso.Root): Boolean {
            fun page(view: View): Boolean = view.tag == "mango9.fullpage" || view is android.view.ViewGroup && (0 until view.childCount).any {
                page(view.getChildAt(it))
            }
            return root.decorView.hasWindowFocus() && (androidx.test.espresso.matcher.RootMatchers.isDialog().matches(root) || page(root.decorView))
        }
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private class Fixture {
        val session = Mango9AppointmentReminderInstrumentedTest.Fixture()
        val changes = MutableStateFlow(0L)
        val calls = CopyOnWriteArrayList<Request>()
        val writes get() = calls.filter { it.method != "GET" }
        var release: CompletableDeferred<Unit>? = null
        var failWrite = false
        var includeEvent = false
        var failDetailRead = false
        var hoursRevision = "hours-v1"
        var hoursTimezone = "UTC"
        val contactRequests = CopyOnWriteArrayList<String>()
        var failContactPage = false

        suspend fun contacts(lease: Mango9SessionLease, kind: Mango9RecordKind, query: String, page: Int): Mango9CrmRecordPage {
            check(repository.isCurrent(lease))
            contactRequests.add("$kind|$query|$page")
            if (page == 2 && failContactPage) { failContactPage = false; throw IOException("Synthetic contact page failure") }
            return Mango9CrmRecordPage(
                (1..10).map { index ->
                Mango9CrmRecord(page * 100 + index, 1, "", "$kind $query page $page record $index", "", "", "", "", "")
            },
                Mango9CrmPagination(30, page, 10, 3)
            )
        }

        fun hours(): JSONObject {
            val days =
                JSONArray((0..6).map { JSONObject().put("weekday", it).put("open", "09:00").put("close", "18:00") })
            return JSONObject().put("configured", true).put("quick_enable", true).put("timezone", hoursTimezone).put("timezone_editable", true)
                .put("days", days).put("defaults", days).put("revision", hoursRevision)
        }

        val metadata =
            JSONObject(
                """{"timezone":"UTC","statuses":[{"id":3,"name":"Confirmed","color":"#008080"}],
            "activities":["appointment"],"priorities":["low","medium","high"],"reminder_minutes":[15],"reminder_channels":["email","sms"],
            "share_recipients":[],"assignees":[],"capabilities":{"create":true,"assign":true,"push":false,"in_app_reminders":true}}"""
            ).put("business_hours_settings", hours())
        val repository = Mango9CalendarRepository(session, { error("No token refresh") }) { request ->
            calls.add(request)
            if (request.method != "GET") {
                release?.await()
                if (failWrite) throw IOException("Synthetic uncertain write")
            }
            if (request.url.pathSegments.last() in setOf("91", "reminder")) {
                if (request.method == "GET" && failDetailRead) throw IOException("Synthetic unavailable detail")
                if (request.method == "DELETE") return@Mango9CalendarRepository Mango9CalendarResponse(200, """{"success":true,"data":{}}""")
                return@Mango9CalendarRepository session.respond(request)
            }
            val data = when (request.url.pathSegments.last()) {
                "metadata" -> metadata
                "settings" -> session.settings
                "reminders" -> JSONObject().put("items", JSONArray()).put("server_time", Instant.now().toString()).put("settings", session.settings)
                "business-hours" -> {
                    if (request.method == "PATCH") {
                        val body = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                        hoursTimezone = body.optString("timezone", hoursTimezone); hoursRevision = "hours-v2"
                    }
                    hours()
                }
                "events" -> if (request.method == "POST") {
                    session.event
                } else {
                    JSONObject().put("events", if (includeEvent) JSONArray().put(session.event) else JSONArray()).put("snapshot", "editor-fixture")
                    .put("pagination", JSONObject().put("page", 1).put("limit", 100).put("total", if (includeEvent) 1 else 0).put("has_more", false))
                }
                else -> error("Unexpected editor request ${request.url.encodedPath}")
            }
            Mango9CalendarResponse(200, JSONObject().put("success", true).put("data", data).toString())
        }
        lateinit var model: Mango9CalendarViewModel
    }

    @Test fun appointmentDraftAndOneSaveSurviveBackgroundAndActivityRecreation() = fixture { scenario, f ->
        onView(withContentDescription(R.string.mango9_calendar_add)).perform(click())
        onView(withTagValue(`is`("appointment-title" as Any))).inRoot(isDialog()).perform(replaceText("Retained visit"), closeSoftKeyboard())
        val edit = f.model.edits.active as Mango9AppointmentEdit
        assertNull(edit.draft.status); assertEquals("medium", edit.draft.priority)
        scenario.recreate(); instrumentation.waitForIdleSync()
        onView(withTagValue(`is`("appointment-title" as Any))).inRoot(isDialog()).check(matches(withText("Retained visit")))
        assertSame(edit, f.model.edits.active)
        scenario.moveToState(Lifecycle.State.CREATED); scenario.moveToState(Lifecycle.State.RESUMED)
        onView(withTagValue(`is`("appointment-title" as Any))).inRoot(isDialog()).check(matches(withText("Retained visit")))
        capture("appointment-editor-retained.png", "appointment-title")
        f.release = CompletableDeferred()
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
        await { f.writes.size == 1 && edit.progress.value.writing }
        onView(withId(android.R.id.button2)).inRoot(isDialog()).check(matches(not(isEnabled())))
        scenario.recreate(); instrumentation.waitForIdleSync()
        onView(withId(android.R.id.button1)).inRoot(isDialog()).check(matches(not(isEnabled())))
        onView(withId(android.R.id.button2)).inRoot(isDialog()).check(matches(not(isEnabled())))
        assertSame(edit, f.model.edits.active)
        f.release!!.complete(Unit)
        await { f.model.edits.active == null }
        assertEquals(1, f.writes.size); assertEquals("POST", f.writes.single().method)
        assertTrue(edit.progress.value.completed)
    }

    @Test fun hoursKeepStableRowsConfirmTimezoneAndRetainUncertainWriteUntilReload() = fixture { scenario, f ->
        onView(withContentDescription(R.string.mango9_calendar_hours)).check(matches(isEnabled())).perform(click())
        await { (f.model.edits.active as? Mango9BusinessHoursEdit)?.original != null }
        val edit = f.model.edits.active as Mango9BusinessHoursEdit
        onView(withId(android.R.id.button1)).inRoot(isDialog()).check(matches(not(isEnabled())))
        val originalRow = view("business-hours-day-1")
        onView(withTagValue(`is`("business-hours-day-1" as Any))).inRoot(isDialog()).perform(scrollTo(), click())
        assertSame(originalRow, view("business-hours-day-1")); assertFalse(edit.draft!!.days.first { it.weekday == 1 }.isOpen)
        scenario.recreate(); instrumentation.waitForIdleSync()
        assertSame(edit, f.model.edits.active)
        onView(withTagValue(`is`("business-hours-day-1" as Any))).inRoot(isDialog()).perform(scrollTo()).check(matches(not(isChecked())))
        onView(withTagValue(`is`("business-hours-timezone" as Any))).inRoot(isDialog()).perform(scrollTo(), click())
        onView(withTagValue(`is`("crm.zone.search" as Any))).inRoot(isDialog()).perform(replaceText("Europe/Paris"), closeSoftKeyboard())
        onView(allOf(withText("Europe/Paris"), isAssignableFrom(android.widget.CheckedTextView::class.java))).inRoot(isDialog()).perform(click())
        assertEquals("Europe/Paris", edit.draft!!.timezone)
        capture("business-hours-editor-retained.png", "business-hours-timezone")
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
        onView(withText(R.string.mango9_calendar_timezone_warning)).inRoot(isDialog()).check(matches(isDisplayed()))
        onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click())
        assertTrue(f.writes.isEmpty())
        f.failWrite = true
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
        await { edit.progress.value.blocked }
        scenario.recreate(); instrumentation.waitForIdleSync()
        assertSame(edit, f.model.edits.active)
        onView(withId(android.R.id.button1)).inRoot(isDialog()).check(matches(not(isEnabled())))
        assertEquals(1, f.writes.size)
        f.failWrite = false; f.hoursRevision = "hours-v2"
        onView(withTagValue(`is`("business-hours-reload" as Any))).inRoot(isDialog()).perform(scrollTo(), click())
        await { !edit.progress.value.busy && !edit.progress.value.blocked }
        assertEquals("hours-v2", edit.original!!.revision); assertEquals(edit.original, edit.draft)
        onView(withId(android.R.id.button1)).inRoot(isDialog()).check(matches(not(isEnabled())))
        onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click())
        await { f.model.edits.active == null }
    }

    @Test fun enabledReminderWithoutChannelCannotBeSavedAndAccountReplacementClosesDraft() = fixture { _, f ->
        onView(withContentDescription(R.string.mango9_calendar_add)).perform(click())
        onView(withTagValue(`is`("appointment-title" as Any))).inRoot(isDialog()).perform(replaceText("Reminder visit"), closeSoftKeyboard())
        val edit = f.model.edits.active as Mango9AppointmentEdit
        onView(withText("${context.getString(R.string.mango9_calendar_reminder)}: ${context.getString(R.string.mango9_calendar_reminder_off)}")).inRoot(isDialog()).perform(scrollTo(), click())
        onView(withText(context.getString(R.string.mango9_calendar_minutes_before, 15))).inRoot(isDialog()).perform(click())
        onView(withId(android.R.id.button1)).inRoot(isDialog()).check(matches(not(isEnabled())))
        onView(withText(R.string.mango9_calendar_channels)).inRoot(isDialog()).perform(scrollTo(), click())
        onView(withText("email")).inRoot(isDialog()).perform(click())
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
        onView(withId(android.R.id.button1)).inRoot(isDialog()).check(matches(isEnabled()))
        instrumentation.runOnMainSync { f.session.generations.selectionChanged(); f.changes.value++ }
        await { f.model.edits.active == null }
        assertFalse(edit.canSave); assertTrue(f.writes.isEmpty())
    }

    @Test fun reminderHostRestoresItsOwnActivityScopedEditorWithoutReopeningADuplicate() = fixture { scenario, f ->
        f.session.settings.put("in_app_reminders", false)
        var host: Mango9ReminderHost? = null
        var retained: Mango9ReminderEditsViewModel? = null

        fun attach(activity: MainActivity) {
            val model = ViewModelProvider(
                activity,
                object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = Mango9ReminderEditsViewModel(activity.application, f.repository) as T
            }
            )["editor-reminder-fixture", Mango9ReminderEditsViewModel::class.java]
            if (retained == null) {
                retained = model
                val metadata = Mango9CalendarJson.metadata(f.metadata)
                val creation =
                    Mango9AppointmentCreationContext(f.repository.capture()!!, metadata, null, Instant.now(), java.time.ZoneId.of("UTC"))
                model.edits.appointment(creation.lease, metadata, null, creation)
            } else {
                assertSame(retained, model)
            }
            host = Mango9ReminderHost(
                activity, Mango9ReminderBanner(activity), f.repository,
                history = object : Mango9ReminderHistory {
                    override suspend fun ready() {}

                    override fun allows(key: String, now: Instant, repeatMinutes: Int) = false

                    override fun record(key: String, now: Instant) {}
                },
                    calls = {
                        object : Mango9ReminderCalls { override val blocked = false

                override fun close() {} }
                    },
                accountChanges = f.changes, retainedEdits = model.edits
            )
        }
        try {
            scenario.onActivity(::attach)
            onView(withTagValue(`is`("appointment-title" as Any))).inRoot(isDialog()).perform(replaceText("Reminder-owned draft"), closeSoftKeyboard())
            val edit = retained!!.edits.active
            scenario.moveToState(Lifecycle.State.CREATED); scenario.moveToState(Lifecycle.State.RESUMED)
            onView(withTagValue(`is`("appointment-title" as Any))).inRoot(isDialog()).check(matches(withText("Reminder-owned draft")))
            scenario.recreate(); scenario.onActivity(::attach)
            onView(withTagValue(`is`("appointment-title" as Any))).inRoot(isDialog()).check(matches(withText("Reminder-owned draft")))
            assertSame(edit, retained!!.edits.active); assertNull(f.model.edits.active)
            onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click())
            await { retained!!.edits.active == null }; assertTrue(f.writes.isEmpty())
        } finally { scenario.onActivity { host?.close() } }
    }

    private fun openDetail(f: Fixture) {
        val start = Instant.now().plusSeconds(3600)
        f.session.event.put("start_at", start.toString()).put("end_at", start.plusSeconds(1800).toString())
        f.includeEvent = true
        instrumentation.runOnMainSync { f.model.screen.refresh() }
        await { !f.model.screen.state.value.loading && f.model.screen.state.value.events.size == 1 }
        onView(withText("Synthetic consultation")).perform(click())
        await { (f.model.edits.active as? Mango9AppointmentDetail)?.let { it.canSave && !it.personal.value.loading } == true }
    }

    @Test fun detailStatusPendingWriteSurvivesRotationAndRemainsInline() = fixture { scenario, f ->
        openDetail(f)
        val detail = f.model.edits.active as Mango9AppointmentDetail
        f.release = CompletableDeferred()
        onView(withTagValue(`is`("appointment.status" as Any))).inRoot(isDialog()).perform(scrollTo(), click())
        onView(withText(R.string.mango9_calendar_no_status)).inRoot(isDialog()).perform(click())
        await { f.writes.size == 1 && detail.progress.value.writing }
        scenario.recreate(); instrumentation.waitForIdleSync()
        assertSame(detail, f.model.edits.active)
        onView(withId(android.R.id.button2)).inRoot(isDialog()).check(matches(not(isEnabled())))
        scenario.moveToState(Lifecycle.State.CREATED)
        f.release!!.complete(Unit)
        await { !detail.progress.value.busy }
        scenario.moveToState(Lifecycle.State.RESUMED)
        // Background restoration replaces the dialog. Espresso can otherwise pin its wait to
        // the dismissed root. Require the real focused replacement, never simulate focus.
        onView(withTagValue(`is`("appointment.status" as Any))).inRoot(focusedDialog()).check(matches(withText("Status: No status")))
        assertSame(detail, f.model.edits.active); assertFalse(detail.progress.value.completed)
        assertEquals(1, f.writes.size); assertEquals("\"event-v1\"", f.writes.single().header("If-Match"))
        onView(withTagValue(`is`("appointment.edit" as Any))).inRoot(isDialog()).perform(scrollTo(), click())
        onView(withTagValue(`is`("appointment-title" as Any))).inRoot(isDialog()).check(matches(withText("Synthetic consultation")))
        assertTrue(detail.progress.value.closed)
        val edit = f.model.edits.active as Mango9AppointmentEdit
        assertEquals(java.time.ZoneId.systemDefault(), edit.zone)
        assertEquals(detail.event!!.startAt, edit.draft.start)
        assertEquals(detail.event!!.endAt, edit.draft.end)
        assertEquals(detail.event!!.timezone, edit.event!!.timezone)
        onView(withText(context.getString(R.string.mango9_calendar_zone, edit.zone.id))).inRoot(isDialog()).check(matches(isDisplayed()))
    }

    @Test fun pushBackAndReminderRecoveryRetainServerRevisionsAcrossRotation() = fixture { scenario, f ->
        openDetail(f)
        val detail = f.model.edits.active as Mango9AppointmentDetail
        val original = detail.event!!
        f.release = CompletableDeferred()
        onView(withTagValue(`is`("appointment.pushBack" as Any))).inRoot(isDialog()).perform(scrollTo(), click())
        onView(withText("15 minutes")).inRoot(isDialog()).perform(click())
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
        await { f.writes.size == 1 && detail.progress.value.writing }
        scenario.recreate(); f.release!!.complete(Unit)
        await { detail.canSave && !detail.personal.value.loading }
        assertEquals(original.startAt.plusSeconds(900), detail.event!!.startAt)
        assertEquals(original.endAt.plusSeconds(900), detail.event!!.endAt)
        f.release = CompletableDeferred(); f.failWrite = true
        onView(withTagValue(`is`("appointment.personalReminder" as Any))).inRoot(isDialog()).perform(scrollTo(), click())
        onView(withText("30 minutes before")).inRoot(isDialog()).perform(click())
        await { f.writes.size == 2 && detail.progress.value.writing }
        scenario.recreate(); f.release!!.complete(Unit)
        await { detail.progress.value.blocked }
        onView(withTagValue(`is`("appointment.personalReminder" as Any))).inRoot(isDialog()).check(matches(not(isEnabled())))
        f.failDetailRead = true
        onView(withTagValue(`is`("appointment.reload" as Any))).inRoot(isDialog()).perform(scrollTo(), click())
        await { !detail.progress.value.busy }; assertTrue(detail.progress.value.blocked)
        scenario.recreate(); assertSame(detail, f.model.edits.active)
        assertEquals(2, f.writes.size)
        f.failDetailRead = false; f.failWrite = false
        f.session.event.put("revision", "reconciled-event")
        f.session.reminder.put("revision", "reconciled-reminder")
        onView(withTagValue(`is`("appointment.reload" as Any))).inRoot(isDialog()).perform(scrollTo(), click())
        await { detail.canSave && !detail.personal.value.loading }
        onView(withTagValue(`is`("appointment.personalReminder" as Any))).inRoot(isDialog()).perform(scrollTo(), click())
        onView(withText("30 minutes before")).inRoot(isDialog()).perform(click())
        await { f.writes.size == 3 && detail.canSave }
        assertEquals("\"reconciled-event\"", f.writes.last().header("If-Match"))
        val body = JSONObject(Buffer().also { f.writes.last().body!!.writeTo(it) }.readUtf8())
        assertEquals("reconciled-reminder", body.getString("reminder_revision"))
        capture("appointment-detail-retained.png", "appointment.personalReminder")
    }

    @Test fun deletingWhileBackgroundedCompletesOnceAndDoesNotReopen() = fixture { scenario, f ->
        openDetail(f)
        val detail = f.model.edits.active as Mango9AppointmentDetail
        f.release = CompletableDeferred()
        onView(withTagValue(`is`("appointment.delete" as Any))).inRoot(isDialog()).perform(scrollTo(), click())
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
        await { f.writes.size == 1 && detail.progress.value.writing }
        scenario.moveToState(Lifecycle.State.CREATED)
        f.release!!.complete(Unit); await { detail.progress.value.completed }
        scenario.moveToState(Lifecycle.State.RESUMED)
        await { f.model.edits.active == null }
        assertEquals("DELETE", f.writes.single().method)
        onView(withTagValue(`is`("appointment.status" as Any))).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
    }

    @Test fun detailFieldsAndFullLegendAreVisibleAndClosedHoursExplainTheNextAction() = fixture { scenario, f ->
        f.session.event.put("activity", "follow_up").put("priority", "high")
            .put("contact", JSONObject().put("id", 101).put("name", "Synthetic client").put("kind", "client"))
        openDetail(f)
        onView(withTagValue(`is`("appointment.activity" as Any))).inRoot(isDialog()).perform(scrollTo()).check(matches(withText("Activity: Follow Up")))
        onView(withTagValue(`is`("appointment.priority" as Any))).inRoot(isDialog()).check(matches(withText("Priority: High")))
        onView(withTagValue(`is`("appointment.timezone" as Any))).inRoot(isDialog()).perform(scrollTo()).check(matches(withText(context.getString(R.string.mango9_calendar_zone, java.time.ZoneId.systemDefault().id))))
        onView(withTagValue(`is`("appointment.linkedContact" as Any))).inRoot(isDialog()).perform(scrollTo()).check(matches(withText("Linked client: Synthetic client")))
        onView(withTagValue(`is`("appointment.activity" as Any))).inRoot(isDialog()).perform(scrollTo())
        capture("appointment-detail-fields.png", "appointment.activity")
        onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click())
        onView(withText(R.string.mango9_calendar_tab)).perform(click())
        onView(withTagValue(`is`("calendar.legend" as Any))).perform(click())
        onView(withText("High priority")).inRoot(isDialog()).check(matches(isDisplayed()))
        onView(withText("Medium priority")).inRoot(isDialog()).check(matches(isDisplayed()))
        onView(withText("Low priority")).inRoot(isDialog()).check(matches(isDisplayed()))
        onView(withText(R.string.mango9_calendar_outside_hours)).inRoot(isDialog()).check(matches(isDisplayed()))
        onView(withText(R.string.mango9_calendar_recurring)).inRoot(isDialog()).check { row, error ->
            if (error != null) throw error
            val icon = (row as android.widget.TextView).compoundDrawablesRelative[0]
            assertNotNull(icon)
            assertFalse("Recurrence uses its arrow, not a status-color swatch", icon is android.graphics.drawable.GradientDrawable)
        }
        capture("calendar-legend.png", "calendar.legend.Priority.high")
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
        scenario.onActivity {
            f.model.screen.acceptHours(f.repository.capture()!!, Mango9CalendarJson.businessHours(f.hours()).closeAll())
        }
        await { f.model.screen.state.value.hours?.days?.none { it.isOpen } == true }
        var grid: org.linphone.ui.main.crm.fragment.Mango9CalendarView? = null
        scenario.onActivity { activity -> grid = activity.findViewById<View>(R.id.main_nav_container).findViewWithTag("calendar.grid") }
        await { grid?.isLayoutReady == true }
        scenario.onActivity {
            val v = grid!!
            val bitmap = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); v.draw(Canvas(bitmap)); bitmap.recycle()
            val provider = v.accessibilityNodeProvider!!
            val count = provider.createAccessibilityNodeInfo(View.NO_ID)!!.childCount
            val target = (0 until count).first {
                provider.createAccessibilityNodeInfo(it)?.contentDescription?.contains("appointments") == true && provider.createAccessibilityNodeInfo(it)?.actionList?.any { a -> a.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK } == true
            }
            assertTrue(provider.performAction(target, android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK, null))
        }
        onView(withText(R.string.mango9_calendar_outside_hours_help)).inRoot(isDialog()).check(matches(isDisplayed()))
        assertTrue(f.writes.isEmpty()); assertNull(f.model.edits.active)
        scenario.recreate()
        onView(withText(R.string.mango9_calendar_outside_hours_help)).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
    }

    @Test fun unconfirmedReminderChannelsAndFormScrollSurviveRecreationWithoutSaving() = fixture { scenario, f ->
        onView(withContentDescription(R.string.mango9_calendar_add)).perform(click())
        onView(withTagValue(`is`("appointment-title" as Any))).inRoot(isDialog()).perform(replaceText("Picker restoration"), closeSoftKeyboard())
        // A short form fits on large phones now that the editor is a full page.
        // Force real overflow so this still verifies restoration of a nonzero scroll position.
        onView(withTagValue(`is`("appointment-notes" as Any))).inRoot(isDialog()).perform(scrollTo(), replaceText("QA scroll restoration\n".repeat(20)), closeSoftKeyboard())
        val edit = f.model.edits.active as Mango9AppointmentEdit
        val before = edit.draft.channels
        onView(withText(R.string.mango9_calendar_channels)).inRoot(isDialog()).perform(scrollTo(), click())
        onView(withText("sms")).inRoot(isDialog()).perform(click()).check(matches(isChecked()))
        assertEquals(before, edit.draft.channels)
        scenario.recreate(); instrumentation.waitForIdleSync()
        onView(withText("sms")).inRoot(isDialog()).check(matches(isChecked()))
        assertEquals(before, edit.draft.channels); assertTrue(f.writes.isEmpty())
        scenario.moveToState(Lifecycle.State.CREATED); scenario.moveToState(Lifecycle.State.RESUMED)
        onView(withText("sms")).inRoot(isDialog()).check(matches(isChecked()))
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
        assertTrue("Restored native checked state must be applied, not the old initial array", "sms" in edit.draft.channels)
        val scroll = view("calendar.form.scroll") as android.widget.ScrollView
        assertTrue(scroll.scrollY > 0)
        val position = scroll.scrollY
        scenario.recreate(); instrumentation.waitForIdleSync()
        assertEquals(position, (view("calendar.form.scroll") as android.widget.ScrollView).scrollY)
        assertTrue(f.writes.isEmpty())
        capture("calendar-picker-restored.png", "calendar.form.scroll")
    }

    @Test fun dateAndTimePickerKeepUnconfirmedValuesAndOriginalDateAcrossRecreation() = fixture { scenario, f ->
        onView(withContentDescription(R.string.mango9_calendar_add)).perform(click())
        val edit = f.model.edits.active as Mango9AppointmentEdit
        val original = edit.draft.start
        onView(withText(org.hamcrest.Matchers.startsWith("Starts:"))).inRoot(isDialog()).perform(click())
        val initialDate = nativeView(android.widget.DatePicker::class.java)
        instrumentation.runOnMainSync { initialDate.updateDate(2030, 0, 11) }
        scenario.recreate(); instrumentation.waitForIdleSync()
        val date = nativeView(android.widget.DatePicker::class.java)
        assertEquals(2030, date.year); assertEquals(0, date.month); assertEquals(11, date.dayOfMonth)
        assertEquals(original, edit.draft.start)
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
        val time = nativeView(android.widget.TimePicker::class.java)
        instrumentation.runOnMainSync { time.hour = 14; time.minute = 25 }
        scenario.recreate(); instrumentation.waitForIdleSync()
        val restored = nativeView(android.widget.TimePicker::class.java)
        assertEquals(14, restored.hour); assertEquals(25, restored.minute)
        assertEquals(original, edit.draft.start); assertTrue(f.writes.isEmpty())
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
        assertEquals(java.time.LocalDate.of(2030, 1, 11).atTime(14, 25).atZone(edit.zone).toInstant(), edit.draft.start)
        assertEquals(1800L, java.time.Duration.between(edit.draft.start, edit.draft.end).seconds)
        assertTrue(f.writes.isEmpty())
        onView(withText(org.hamcrest.Matchers.startsWith("Starts:"))).inRoot(isDialog()).perform(click())
        nativeView(android.widget.DatePicker::class.java)
        instrumentation.runOnMainSync { f.session.generations.selectionChanged(); f.changes.value++ }
        await { f.model.edits.active == null }
        assertNull(edit.presentation.child)
        onView(isAssignableFrom(android.widget.DatePicker::class.java)).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
        scenario.recreate()
        onView(isAssignableFrom(android.widget.DatePicker::class.java)).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
        assertTrue(f.writes.isEmpty())
    }

    @Test fun timezoneSearchRestoresButConfirmationNeverRestoresOrWrites() = fixture { scenario, f ->
        onView(withContentDescription(R.string.mango9_calendar_hours)).perform(click())
        await { (f.model.edits.active as? Mango9BusinessHoursEdit)?.draft != null }
        onView(withTagValue(`is`("business-hours-timezone" as Any))).inRoot(isDialog()).perform(click())
        onView(withId(R.id.mango9_calendar_zone_search)).inRoot(isDialog()).perform(replaceText("Paris"), closeSoftKeyboard())
        scenario.recreate(); instrumentation.waitForIdleSync()
        onView(withId(R.id.mango9_calendar_zone_search)).inRoot(isDialog()).check(matches(withText("Paris")))
        assertEquals("UTC", (f.model.edits.active as Mango9BusinessHoursEdit).draft!!.timezone)
        onView(withText("Europe/Paris")).inRoot(isDialog()).perform(click())
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
        onView(withText(R.string.mango9_calendar_timezone_warning)).inRoot(isDialog()).check(matches(isDisplayed()))
        scenario.recreate(); instrumentation.waitForIdleSync()
        onView(withText(R.string.mango9_calendar_timezone_warning)).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
        assertEquals("Europe/Paris", (f.model.edits.active as Mango9BusinessHoursEdit).draft!!.timezone)
        assertTrue(f.writes.isEmpty())
    }

    @Test fun contactQueryKindAndFailedPageRestoreWithoutSkippingOrChangingTheLink() = fixture { scenario, f ->
        onView(withContentDescription(R.string.mango9_calendar_add)).perform(click())
        val edit = f.model.edits.active as Mango9AppointmentEdit
        onView(withText(org.hamcrest.Matchers.startsWith("Linked lead or client:"))).inRoot(isDialog()).perform(scrollTo(), click())
        onView(withTagValue(`is`("calendar.contact.search" as Any))).inRoot(isDialog()).perform(replaceText("Example"), closeSoftKeyboard())
        onView(withTagValue(`is`("calendar.contact.kind" as Any))).inRoot(isDialog()).perform(click())
        await { f.contactRequests.contains("Client|Example|1") }
        onView(withText("Client Example page 1 record 1")).inRoot(isDialog()).check(matches(isDisplayed()))
        f.failContactPage = true
        onView(withTagValue(`is`("calendar.contact.next" as Any))).inRoot(isDialog()).perform(scrollTo(), click())
        await { f.contactRequests.contains("Client|Example|2") && !f.failContactPage }
        onView(withTagValue(`is`("calendar.contact.retry" as Any))).inRoot(isDialog()).perform(scrollTo()).check(matches(isDisplayed()))
        assertEquals(listOf("Client|Example|2"), f.contactRequests.filter { it.endsWith("|2") })
        assertNull(edit.draft.contact)
        scenario.recreate(); instrumentation.waitForIdleSync()
        onView(withTagValue(`is`("calendar.contact.search" as Any))).inRoot(isDialog()).check(matches(withText("Example")))
        onView(withTagValue(`is`("calendar.contact.kind" as Any))).inRoot(isDialog()).check(matches(withText("Clients")))
        await { f.contactRequests.count { it == "Client|Example|2" } == 2 }
        instrumentation.runOnMainSync {
            assertSame(edit, f.model.edits.active)
            assertFalse(edit.progress.value.closed)
            assertTrue(f.repository.isCurrent(edit.lease))
        }
        onView(withText("Client Example page 2 record 1")).inRoot(isDialog()).perform(scrollTo())
            .check(matches(isEnabled()))
        // The asynchronously populated dialog also changes the platform window.
        // Settle its accessibility/window events before injecting a real touch;
        // an idle app looper alone does not establish that the window is ready.
        instrumentation.uiAutomation.waitForIdle(200, 5_000)
        onView(withText("Client Example page 2 record 1")).inRoot(isDialog()).perform(click())
        instrumentation.runOnMainSync {
            assertEquals(201, edit.draft.contact?.id); assertEquals("client", edit.draft.contact?.kind)
        }
        assertFalse(f.contactRequests.any { it.endsWith("|3") }); assertTrue(f.writes.isEmpty())
    }

    private fun <T : View> nativeView(type: Class<T>): T {
        var found: T? = null
        onView(isAssignableFrom(type)).inRoot(isDialog()).check { view, error -> if (error != null) throw error; found = type.cast(view) }
        return found!!
    }

    @Test fun orientationRetainsTheVisibleFieldAfterLongNotesReflowWithoutSaving() = fixture { scenario, f ->
        var orientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        scenario.onActivity {
            orientation = it.requestedOrientation; it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        await { context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
        try {
            onView(withContentDescription(R.string.mango9_calendar_add)).perform(click())
            onView(withTagValue(`is`("appointment-notes" as Any))).inRoot(isDialog()).perform(
                replaceText("A long appointment note that must keep its position during screen reflow. ".repeat(35)), closeSoftKeyboard()
            )
            val edit = f.model.edits.active as Mango9AppointmentEdit
            val originalNotes = edit.draft.notes
            val scroll = view("calendar.form.scroll") as android.widget.ScrollView
            instrumentation.runOnMainSync { scroll.scrollTo(0, (250 * scroll.resources.displayMetrics.density).toInt()) }
            instrumentation.waitForIdleSync()
            val oldWidth = scroll.width
            val oldPosition = scroll.scrollY
            assertTrue(oldPosition > 0)
            scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            await { context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            val restored = view("calendar.form.scroll") as android.widget.ScrollView
            assertNotEquals(oldWidth, restored.width)
            assertSame(edit, f.model.edits.active); assertEquals(originalNotes, edit.draft.notes)
            val anchor = requireNotNull(edit.presentation.anchor)
            val target = view(anchor.tag)
            instrumentation.runOnMainSync {
                val body = restored.getChildAt(0) as android.view.ViewGroup
                val bounds = android.graphics.Rect(0, 0, target.width, target.height)
                body.offsetDescendantRectToMyCoords(target, bounds)
                val expected = (bounds.top + anchor.fraction * bounds.height()).toInt()
                    .coerceIn(0, (body.height - restored.height).coerceAtLeast(0))
                assertTrue("Changed width must reflow this fixture", kotlin.math.abs(expected - oldPosition) > 10)
                assertTrue("Retain the field-relative position, not the old pixel offset", kotlin.math.abs(expected - restored.scrollY) <= 2)
            }
            assertTrue(f.writes.isEmpty())
            capture("calendar-editor-landscape.png", "calendar.form.scroll")
        } finally {
            scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            await { context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
            scenario.onActivity { it.requestedOrientation = orientation }
        }
    }

    @Test fun activityAndPriorityLabelsAreReadableButPreserveServerValues() = fixture { _, f ->
        f.metadata.put("activities", JSONArray(listOf("appointment", "follow_up")))
        instrumentation.runOnMainSync { f.model.screen.refresh() }
        await { f.model.screen.state.value.metadata?.activities?.contains("follow_up") == true }
        onView(withContentDescription(R.string.mango9_calendar_add)).perform(click())
        onView(withTagValue(`is`("appointment-title" as Any))).inRoot(isDialog()).perform(replaceText("Readable labels"), closeSoftKeyboard())
        onView(withText("Activity: Appointment")).inRoot(isDialog()).perform(scrollTo(), click())
        onView(withText("Follow Up")).inRoot(isDialog()).perform(click())
        onView(withText("Activity: Follow Up")).inRoot(isDialog()).check(matches(isDisplayed()))
        onView(withText("Priority: Medium")).inRoot(isDialog()).perform(scrollTo(), click())
        onView(withText("High")).inRoot(isDialog()).perform(click())
        onView(withText("Priority: High")).inRoot(isDialog()).check(matches(isDisplayed()))
        val edit = f.model.edits.active as Mango9AppointmentEdit
        assertEquals("follow_up", edit.draft.activity); assertEquals("high", edit.draft.priority)
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
        await { f.writes.size == 1 && f.model.edits.active == null }
        val body = JSONObject(Buffer().also { f.writes.single().body!!.writeTo(it) }.readUtf8())
        assertEquals("follow_up", body.getString("activity")); assertEquals("high", body.getString("priority"))
    }

    @Test fun businessHourPickerRetainsWallTimeButUsesTheChangedClockAndWritesAsciiHours() {
        Mango9ClockPreferenceFixture.withPreference("12") {
            fixture { scenario, f ->
                onView(withContentDescription(R.string.mango9_calendar_hours)).perform(click())
                await { (f.model.edits.active as? Mango9BusinessHoursEdit)?.draft != null }
                val edit = f.model.edits.active as Mango9BusinessHoursEdit
                val label = view("business-hours-1-open") as android.widget.TextView
                assertTrue(label.text.toString().contains("9:00")); assertTrue(label.text.toString().contains("AM"))
                assertTrue(label.contentDescription.contains("opens at"))
                assertEquals("09:00", edit.draft!!.days.first { it.weekday == 1 }.open)
                onView(withTagValue(`is`("business-hours-1-open" as Any))).inRoot(isDialog()).perform(scrollTo(), click())
                val picker = nativeView(android.widget.TimePicker::class.java)
                assertFalse(picker.is24HourView)
                instrumentation.runOnMainSync { picker.hour = 13; picker.minute = 45 }
                Mango9ClockPreferenceFixture.set("24")
                scenario.recreate(); instrumentation.waitForIdleSync()
                val restored = nativeView(android.widget.TimePicker::class.java)
                assertTrue(restored.is24HourView); assertEquals(13, restored.hour); assertEquals(45, restored.minute)
                assertEquals("09:00", edit.draft!!.days.first { it.weekday == 1 }.open)
                assertTrue(f.writes.isEmpty())
                onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
                assertEquals("13:45", edit.draft!!.days.first { it.weekday == 1 }.open)
                assertEquals("13:45", (view("business-hours-1-open") as android.widget.TextView).text.toString())
                onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
                await { f.writes.size == 1 && f.model.edits.active == null }
                val body = JSONObject(Buffer().also { f.writes.single().body!!.writeTo(it) }.readUtf8())
                val days = body.getJSONArray("days")
                val monday = (0 until days.length()).map { days.getJSONObject(it) }.single { it.getInt("weekday") == 1 }
                assertEquals("13:45", monday.getString("open")); assertEquals("18:00", monday.getString("close"))
                assertFalse("A clock-display change must not write the CRM timezone", body.has("timezone"))
                assertEquals("UTC", f.hoursTimezone)
            }
        }
    }

    @Test fun detailReminderConfirmationAndEditorFollowClockChangesWithoutChangingAppointmentInstants() {
        Mango9ClockPreferenceFixture.withPreference("12") {
            fixture { scenario, f ->
                val due = Instant.now().plusSeconds(1800)
                f.session.reminder.put("enabled", true).put("due_at", due.toString())
                openDetail(f)
                val detail = f.model.edits.active as Mango9AppointmentDetail
                val original = detail.event!!

                fun nativeTime(instant: Instant): String = android.text.format.DateFormat.getTimeFormat(context).apply {
                    timeZone = java.util.TimeZone.getDefault()
                }.format(java.util.Date.from(instant)).replace('\u202f', ' ').replace('\u00a0', ' ')

                fun assertTime(tag: String, instant: Instant) {
                    val text = (view(tag) as android.widget.TextView).text.toString().replace('\u202f', ' ').replace('\u00a0', ' ')
                    assertTrue("$tag must include the native clock value", text.contains(nativeTime(instant)))
                }
                assertTime("appointment.times", original.startAt)
                assertTime("appointment.reminderNext", due)
                Mango9ClockPreferenceFixture.set("24")
                scenario.recreate(); instrumentation.waitForIdleSync()
                await { detail.canSave }
                assertTime("appointment.times", original.startAt)
                assertTime("appointment.reminderNext", due)
                onView(withTagValue(`is`("appointment.pushBack" as Any))).inRoot(isDialog()).perform(scrollTo(), click())
                onView(withText("15 minutes")).inRoot(isDialog()).perform(click())
                onView(withId(android.R.id.message)).inRoot(isDialog()).check { view, error ->
                    if (error != null) throw error
                    val text = (view as android.widget.TextView).text.toString()
                    assertTrue(text.contains(nativeTime(original.startAt)))
                    assertTrue(text.contains(nativeTime(original.startAt.plusSeconds(900))))
                }
                onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click())
                assertTrue(f.writes.isEmpty())
                onView(withTagValue(`is`("appointment.edit" as Any))).inRoot(isDialog()).perform(scrollTo(), click())
                val edit = f.model.edits.active as Mango9AppointmentEdit
                assertTime("appointment.starts", original.startAt); assertTime("appointment.ends", original.endAt)
                assertEquals(original.startAt, edit.draft.start); assertEquals(original.endAt, edit.draft.end)
                onView(withTagValue(`is`("appointment-title" as Any))).inRoot(isDialog()).perform(replaceText("Clock display only"), closeSoftKeyboard())
                onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
                await { f.writes.size == 1 && f.model.edits.active == null }
                val body = JSONObject(Buffer().also { f.writes.single().body!!.writeTo(it) }.readUtf8())
                assertEquals("Clock display only", body.getString("title"))
                assertFalse(body.has("start_at")); assertFalse(body.has("end_at")); assertFalse(body.has("timezone"))
            }
        }
    }

    private fun fixture(test: (ActivityScenario<MainActivity>, Fixture) -> Unit) {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_CONTACTS)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val identity = Mango9SessionStore(context).activeIdentity
        var previous: Account? = null; var account: Account? = null
        val f = Fixture()
        try {
            core { core ->
                previous = core.defaultAccount
                val params = core.createAccountParams().apply {
                    identityAddress = Factory.instance().createAddress("sip:calendar-editor@fixture.example.invalid")
                    serverAddress = Factory.instance().createAddress("sip:fixture.example.invalid")
                    isRegisterEnabled = false; pushNotificationAllowed = false
                }
                account = core.createAccount(params); core.addAccount(account); core.defaultAccount = account
            }
            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                scenario.onActivity { activity ->
                    activity.supportFragmentManager.registerFragmentLifecycleCallbacks(
                        object : FragmentManager.FragmentLifecycleCallbacks() {
                        override fun onFragmentPreCreated(fm: FragmentManager, fragment: Fragment, savedInstanceState: Bundle?) {
                            if (fragment is Mango9CalendarFragment) {
                                f.model = ViewModelProvider(
                                    fragment,
                                    object : ViewModelProvider.Factory {
                                    @Suppress("UNCHECKED_CAST")
                                    override fun <T : ViewModel> create(modelClass: Class<T>): T = Mango9CalendarViewModel(activity.application, f.repository, f.changes, MutableStateFlow(0L), f::contacts) as T
                                }
                                )[Mango9CalendarViewModel::class.java]
                            }
                        }
                    },
                        true
                    )
                    (activity.supportFragmentManager.findFragmentById(R.id.main_nav_container) as NavHostFragment).navController.navigate(R.id.action_global_mango9CalendarFragment)
                }
                await { !f.model.screen.state.value.loading && f.model.screen.state.value.metadata != null }
                try { test(scenario, f) } finally { f.release?.complete(Unit) }
            }
        } finally {
            f.release?.complete(Unit)
            core { core ->
                core.defaultAccount = previous; account?.let {
                if (core.accountList.any { a -> a === it }) core.removeAccount(it)
            }; core.config.sync()
            }
            Mango9SessionStore(context).let { if (it.activeIdentity != identity) it.activate(identity) }
            Mango9SdkTestCleanup.flush()
        }
    }

    private fun focusedDialog(): org.hamcrest.Matcher<androidx.test.espresso.Root> =
        object : org.hamcrest.TypeSafeMatcher<androidx.test.espresso.Root>() {
            override fun matchesSafely(root: androidx.test.espresso.Root): Boolean =
                isDialog().matches(root) && root.decorView.hasWindowFocus()

            override fun describeTo(description: org.hamcrest.Description) {
                description.appendText("the visible appointment dialog with actual window focus")
            }
        }

    private fun view(tag: String): View {
        var result: View? = null
        onView(withTagValue(`is`(tag as Any))).inRoot(isDialog()).check { view, error -> if (error != null) throw error; result = view }
        return result!!
    }

    private fun capture(name: String, tag: String) {
        val root = view(tag).rootView
        instrumentation.runOnMainSync {
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun await(condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < until) {
            var ready = false; instrumentation.runOnMainSync { ready = condition() }
            if (ready) { instrumentation.waitForIdleSync(); return }
            SystemClock.sleep(20)
        }
        fail("Calendar editor did not reach expected state")
    }

    private fun core(action: (Core) -> Unit) {
        val done = CountDownLatch(1); var result: Result<Unit>? = null
        coreContext.postOnCoreThread { core -> result = runCatching { action(core) }; done.countDown() }
        assertTrue(done.await(10, TimeUnit.SECONDS)); result!!.getOrThrow()
    }
}
