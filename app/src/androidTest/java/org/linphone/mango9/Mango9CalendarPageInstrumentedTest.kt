/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.widget.ImageButton
import android.widget.TextView
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.chip.Chip
import java.io.File
import java.time.LocalDate
import java.time.Clock
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Request
import org.hamcrest.Matchers.`is`
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
import org.linphone.ui.main.crm.fragment.Mango9AppointmentAgendaAdapter
import org.linphone.ui.main.crm.fragment.Mango9AgendaRow
import org.linphone.ui.main.crm.fragment.Mango9CalendarView
import org.linphone.ui.main.crm.viewmodel.Mango9CalendarViewModel

/** Real navigation, Fragment, widgets and retained ViewModel; synthetic HTTP only. */
@RunWith(AndroidJUnit4::class)
class Mango9CalendarPageInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun realCalendarLoadsBeforeSettingsAndRetainsFiltersOnRotationButNotAccountSwitch() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_CONTACTS)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val active = Mango9SessionStore(context).activeIdentity
        var previous: Account? = null; var fixtureAccount: Account? = null
        val fixture = Mango9AppointmentReminderInstrumentedTest.Fixture()
        val generations = Mango9SessionGenerations()
        var session = fixture.session
        val accountChanges = MutableStateFlow(0L)
        val requests = CopyOnWriteArrayList<Request>()
        val releaseSettings = CompletableDeferred<Unit>()
        val access = object : Mango9SessionAccess {
            override fun captureSession() = generations.capture(session)

            override fun currentSession(lease: Mango9SessionLease) = session.takeIf {
                generations.matches(lease, session.sipIdentity)
            }

            override fun replaceTokens(lease: Mango9SessionLease, expected: Mango9Session, refreshed: Mango9Session) = false
        }
        val start = LocalDate.now().atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant()
        val repository = Mango9CalendarRepository(access, { error("No token refresh") }) { request ->
            requests.add(request)
            val data = when (request.url.pathSegments.last()) {
                "settings" -> {
                    releaseSettings.await()
                    JSONObject(fixture.settings.toString()).put("new_appointment_timezone", "Europe/Paris")
                }
                "metadata" -> JSONObject(
                    """{"timezone":"UTC","statuses":[{"id":3,"name":"Confirmed","color":"#008080"}],
                    "activities":["appointment"],"priorities":["medium"],"reminder_minutes":[15],"reminder_channels":["email","sms"],
                    "share_recipients":[],"assignees":[],"capabilities":{"create":true,"assign":true,"push":false}}"""
                )
                "events" -> {
                    val items = JSONArray()
                    val other = session.userId != "42"
                    repeat(if (other) 1 else 2) { index ->
                        items.put(
                            JSONObject(fixture.event.toString()).put("id", 91 + index)
                            .put("title", if (other) "Fresh other account" else "Synthetic ${if (index == 0) "confirmed" else "unassigned"}")
                            .put("start_at", start.toString()).put("end_at", start.plusSeconds(1800).toString())
                            .put("status", if (index == 0) JSONObject("""{"id":3,"name":"Confirmed","color":"#008080"}""") else JSONObject.NULL)
                        )
                    }
                    JSONObject().put("events", items).put("snapshot", "calendar-page-${session.userId}")
                        .put("pagination", JSONObject().put("page", 1).put("limit", 100).put("total", items.length()).put("has_more", false))
                }
                else -> error("Unexpected calendar request: ${request.url.encodedPath}")
            }
            Mango9CalendarResponse(200, JSONObject().put("success", true).put("data", data).toString())
        }
        lateinit var model: Mango9CalendarViewModel
        try {
            core { core ->
                previous = core.defaultAccount
                val params = core.createAccountParams().apply {
                    identityAddress = Factory.instance().createAddress("sip:calendar-page@fixture.example.invalid")
                    serverAddress = Factory.instance().createAddress("sip:fixture.example.invalid")
                    isRegisterEnabled = false; pushNotificationAllowed = false
                }
                fixtureAccount = core.createAccount(params)
                core.addAccount(fixtureAccount); core.defaultAccount = fixtureAccount
            }
            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                scenario.onActivity { activity ->
                    activity.supportFragmentManager.registerFragmentLifecycleCallbacks(
                        object : FragmentManager.FragmentLifecycleCallbacks() {
                        override fun onFragmentPreCreated(fm: FragmentManager, f: Fragment, savedInstanceState: Bundle?) {
                            if (f is Mango9CalendarFragment) {
                                model = ViewModelProvider(
                                    f,
                                    object : ViewModelProvider.Factory {
                                    @Suppress("UNCHECKED_CAST")
                                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                                        Mango9CalendarViewModel(activity.application, repository, accountChanges, MutableStateFlow(0L)) as T
                                }
                                )[Mango9CalendarViewModel::class.java]
                            }
                        }
                    },
                        true
                    )
                    (activity.supportFragmentManager.findFragmentById(R.id.main_nav_container) as NavHostFragment)
                        .navController.navigate(R.id.action_global_mango9CalendarFragment)
                }
                await { !model.screen.state.value.loading && model.screen.state.value.events.size == 2 }
                assertNull(model.screen.state.value.preferences)
                assertFalse(releaseSettings.isCompleted)
                onView(withTagValue(`is`("calendar.search" as Any))).perform(replaceText("Synthetic"), closeSoftKeyboard())
                onView(withTagValue(`is`("calendar.status" as Any))).perform(click())
                onView(withText(R.string.mango9_calendar_no_status)).perform(click())
                await { model.screen.state.value.selectedStatus == 0 }
                awaitRows(scenario, listOf("Synthetic unassigned"))
                val retained = model
                scenario.recreate(); instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val fragment = currentFragment(activity)
                    assertSame(retained, ViewModelProvider(fragment)[Mango9CalendarViewModel::class.java])
                }
                awaitRows(scenario, listOf("Synthetic unassigned"))
                releaseSettings.complete(Unit)
                await { model.screen.state.value.preferences?.newAppointmentTimezone == "Europe/Paris" }
                awaitRows(scenario, listOf("Synthetic unassigned"))
                scenario.onActivity { activity ->
                    val view = currentFragment(activity).requireView()
                    assertEquals("Synthetic", view.findViewWithTag<TextInputEditText>("calendar.search").text.toString())
                    assertEquals(context.getString(R.string.mango9_calendar_no_status), view.findViewWithTag<ImageButton>("calendar.status").contentDescription)
                    val chip = view.findViewWithTag<Chip>("calendar.status.active")
                    assertEquals(View.VISIBLE, chip.visibility)
                    assertEquals("Status: No status", chip.text.toString())
                    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    view.jumpDrawablesToCurrentState(); view.draw(Canvas(bitmap))
                    File(context.getExternalFilesDir(null), "calendar-filters-page.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                    chip.performCloseIconClick()
                }
                awaitRows(scenario, listOf("Synthetic confirmed", "Synthetic unassigned"))
                scenario.onActivity { activity ->
                    assertEquals(View.GONE, currentFragment(activity).requireView().findViewWithTag<Chip>("calendar.status.active").visibility)
                }
                onView(withTagValue(`is`("calendar.status" as Any))).perform(click())
                onView(withText(R.string.mango9_calendar_no_status)).perform(click())
                awaitRows(scenario, listOf("Synthetic unassigned"))
                // Exercise the real retained Fragment/ViewModel, not just an
                // isolated calendar restoring its own snapshot.
                scenario.onActivity { model.screen.chooseMode(Mango9CalendarMode.Day) }
                awaitGrid(scenario)
                lateinit var rendered: Mango9CalendarScreenState
                lateinit var displayed: Mango9CalendarView
                scenario.onActivity { activity ->
                    displayed = currentFragment(activity).requireView().findViewWithTag("calendar.grid")
                    val state = model.screen.state.value
                    rendered = state.copy(events = Mango9CalendarPresentation.filteredEvents(state.events, state.query, state.selectedStatus, state.agendaDay, state.displayZone))
                    displayed.clock = Clock.fixed(state.date.atTime(10, 0).atZone(state.displayZone).toInstant(), state.displayZone)
                    displayed.resetViewport(); displayed.submit(rendered)
                }
                awaitGrid(scenario)
                await { !displayed.isDirty }
                scenario.onActivity {
                    val before = Bitmap.createBitmap(displayed.width, displayed.height, Bitmap.Config.ARGB_8888)
                    displayed.draw(Canvas(before))
                    val navy = 0xff444dcd.toInt()
                    val beforeLine = (0 until before.height).filter { before.getPixel(before.width - 1, it) == navy }.average()
                    assertTrue("Current-time line is actually visible", beforeLine.isFinite())
                    displayed.clock = Clock.offset(displayed.clock, java.time.Duration.ofMinutes(10))
                    displayed.submit(rendered.copy(events = rendered.events.toList()))
                    assertTrue("Identical data must schedule a real repaint", displayed.isDirty)
                    assertTrue("Clock tick must not rebuild placements", displayed.isLayoutReady)
                    val after = Bitmap.createBitmap(displayed.width, displayed.height, Bitmap.Config.ARGB_8888)
                    displayed.draw(Canvas(after))
                    val afterLine = (0 until after.height).filter { after.getPixel(after.width - 1, it) == navy }.average()
                    assertTrue("Current-time pixels moved without new events", afterLine > beforeLine + 1)
                    before.recycle(); after.recycle()
                    scrollCalendar(displayed, 110f)
                }
                val originalLease = repository.capture()!!
                lateinit var savedViewport: Mango9CalendarViewport
                lateinit var oldAction: (Mango9Appointment) -> Unit
                lateinit var oldEvent: Mango9Appointment
                scenario.onActivity {
                    savedViewport = displayed.captureViewport()!!
                    assertEquals(savedViewport, model.retainedViewport(originalLease))
                    oldEvent = model.screen.state.value.events.first()
                }
                scenario.recreate(); instrumentation.waitForIdleSync()
                awaitGrid(scenario)
                scenario.onActivity { activity ->
                    val restoredGrid = currentFragment(activity).requireView().findViewWithTag<Mango9CalendarView>("calendar.grid")
                    assertEquals(savedViewport.position, restoredGrid.captureViewport()!!.position, .01f)
                    assertEquals(savedViewport.period, restoredGrid.captureViewport()!!.period)
                    oldAction = restoredGrid.onEvent
                    model.screen.chooseCalendar(false)
                }
                awaitRows(scenario, listOf("Synthetic unassigned"))
                scenario.onActivity {
                    // A selected-account generation is not a token refresh or a
                    // view recreation. It must clear private search/filter state.
                    generations.selectionChanged()
                    session = session.copy(userId = "44", sipIdentity = "sip:44@crm.example.invalid", accessToken = "test-other-access")
                    val before = requests.size
                    oldAction(oldEvent)
                    assertEquals("An old calendar target cannot read the new account", before, requests.size)
                    assertNull(model.edits.active)
                    assertNull(model.retainedViewport(originalLease))
                    assertNull(model.retainedViewport(repository.capture()!!))
                    accountChanges.value++
                }
                await { model.screen.state.value.events.singleOrNull()?.title == "Fresh other account" }
                awaitRows(scenario, listOf("Fresh other account"))
                scenario.onActivity {
                    // Also reject the old callback after the screen itself has
                    // acquired the new login (the dangerous recapture window).
                    val before = requests.size
                    oldAction(oldEvent)
                    assertNull("No new-account detail may open for an old row", model.edits.active)
                    assertEquals(before, requests.size)
                }
                scenario.onActivity { activity ->
                    val view = currentFragment(activity).requireView()
                    assertEquals("", view.findViewWithTag<TextInputEditText>("calendar.search").text.toString())
                    assertEquals(context.getString(R.string.mango9_calendar_all_statuses), view.findViewWithTag<ImageButton>("calendar.status").contentDescription)
                    assertEquals(View.GONE, view.findViewWithTag<Chip>("calendar.status.active").visibility)
                }
                assertNull(model.screen.state.value.selectedStatus)
                verifyAdaptivePage(scenario, model)
                assertTrue(requests.all { it.method == "GET" })
                assertTrue(requests.any { it.header("Authorization") == "Bearer test-other-access" })
            }
        } finally {
            releaseSettings.complete(Unit)
            core { core ->
                core.defaultAccount = previous
                fixtureAccount?.let { if (core.accountList.any { account -> account === it }) core.removeAccount(it) }
                core.config.sync()
            }
            Mango9SessionStore(context).let { if (it.activeIdentity != active) it.activate(active) }
            Mango9SdkTestCleanup.flush()
        }
    }

    private fun currentFragment(activity: MainActivity): Fragment =
        (activity.supportFragmentManager.findFragmentById(R.id.main_nav_container) as NavHostFragment).childFragmentManager.primaryNavigationFragment!!

    private fun verifyAdaptivePage(scenario: ActivityScenario<MainActivity>, model: Mango9CalendarViewModel) {
        val originalFont = Settings.System.getString(context.contentResolver, Settings.System.FONT_SCALE)
        val originalDark = org.linphone.LinphoneApplication.corePreferences.darkMode
        var oldActivity: MainActivity? = null
        try {
            org.linphone.LinphoneApplication.corePreferences.darkMode = -1
            scenario.onActivity { oldActivity = it; model.screen.chooseMode(Mango9CalendarMode.Week) }
            fontScale("1.8")
            await {
                var changed = false
                scenario.onActivity { changed = it !== oldActivity && it.resources.configuration.fontScale > 1.7f }
                changed
            }
            awaitGrid(scenario)
            for (night in listOf(false, true)) {
                scenario.onActivity {
                    it.delegate.localNightMode = if (night) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
                }
                await {
                    var matches = false
                    scenario.onActivity {
                        matches = (it.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                    }
                    matches
                }
                awaitGrid(scenario)
                for (width in listOf(320, 360)) {
                    scenario.onActivity { activity ->
                        val root = currentFragment(activity).requireView()
                        root.layoutParams = root.layoutParams.apply { this.width = (width * root.resources.displayMetrics.density).toInt() }
                    }
                    instrumentation.waitForIdleSync(); awaitGrid(scenario)
                    await {
                        var laidOut = false
                        scenario.onActivity { activity ->
                            val root = currentFragment(activity).requireView()
                            laidOut = root.width == (width * root.resources.displayMetrics.density).toInt() && !root.isLayoutRequested
                        }
                        laidOut
                    }
                    scenario.onActivity { activity ->
                        val root = currentFragment(activity).requireView() as ScrollView

                        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                        root.draw(Canvas(bitmap))
                        File(context.getExternalFilesDir(null), "calendar-adaptive-page-$width-$night.png").outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        bitmap.recycle()
                        val navigation = root.findViewWithTag<ViewGroup>("calendar.navigation")
                        assertTrue("Controls stay within page width", navigation.width <= root.width)
                        val content = root.getChildAt(0) as ViewGroup
                        assertTrue("The toolbar spacer must not consume the page", content.getChildAt(0).height <= 60 * root.resources.displayMetrics.density)

                        fun verify(view: View) {
                            if (view is TextView && view.visibility == View.VISIBLE && view.text.isNotEmpty()) {
                                val bounds = Rect(); view.getDrawingRect(bounds); root.offsetDescendantRectToMyCoords(view, bounds)
                                assertTrue("Control text within width: ${view.text}", bounds.left >= 0 && bounds.right <= root.width)
                                val layout = view.layout!!
                                assertEquals("Complete navigation text: ${view.text}", view.text.length, layout.getLineEnd(layout.lineCount - 1))
                                for (line in 0 until layout.lineCount) assertEquals("No ellipsis: ${view.text}", 0, layout.getEllipsisCount(line))
                                val textHeight = layout.getLineBottom(layout.lineCount - 1)
                                val availableHeight = view.height - view.compoundPaddingTop - view.compoundPaddingBottom
                                assertTrue("No clipped lines: ${view.text}, $width/night=$night, text=$textHeight available=$availableHeight view=${view.width}x${view.height} measured=${view.measuredWidth}x${view.measuredHeight}", textHeight <= availableHeight)
                            }
                            if (view is ViewGroup) for (i in 0 until view.childCount) verify(view.getChildAt(i))
                        }
                        verify(navigation)
                        val title = root.findViewWithTag<TextView>("calendar.title")
                        verify(title)
                        assertEquals("The heading must not break inside Appointments", 1, title.layout.lineCount)
                        verify(root.findViewWithTag("calendar.tabs"))
                        verify(root.findViewWithTag("calendar.zoom"))
                        val grid = root.findViewWithTag<Mango9CalendarView>("calendar.grid")
                        assertTrue("Usable calendar height at $width/night=$night", grid.height >= 240 * root.resources.displayMetrics.density - 1)
                        assertEquals(model, ViewModelProvider(currentFragment(activity))[Mango9CalendarViewModel::class.java])
                    }
                }
            }
        } finally {
            org.linphone.LinphoneApplication.corePreferences.darkMode = originalDark
            fontScale(originalFont)
            scenario.onActivity { it.delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_UNSPECIFIED }
        }
    }

    private fun fontScale(value: String?) {
        require(value == null || value.toFloatOrNull()?.let { it in .5f..3f } == true)
        val command = if (value == null) "settings delete system font_scale" else "settings put system font_scale $value"
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use {
            it.readText()
        }
    }

    private fun awaitGrid(scenario: ActivityScenario<MainActivity>) = await {
        var ready = false
        scenario.onActivity { activity ->
            val grid = currentFragment(activity).requireView().findViewWithTag<Mango9CalendarView>("calendar.grid")
            ready = !modelLoading(activity) && grid.visibility == View.VISIBLE && grid.isLayoutReady
        }
        ready
    }

    private fun modelLoading(activity: MainActivity) = ViewModelProvider(currentFragment(activity))[Mango9CalendarViewModel::class.java].screen.state.value.loading

    private fun scrollCalendar(view: View, distance: Float) {
        val now = SystemClock.uptimeMillis()
        val x = view.width / 2f; val y = view.height - 20 * view.resources.displayMetrics.density
        for ((action, endY) in listOf(MotionEvent.ACTION_DOWN to y, MotionEvent.ACTION_MOVE to y - distance * view.resources.displayMetrics.density, MotionEvent.ACTION_CANCEL to y - distance * view.resources.displayMetrics.density)) {
            val event = MotionEvent.obtain(now, now + if (action == MotionEvent.ACTION_DOWN) 0 else 20, action, x, endY, 0)
            try { view.onTouchEvent(event) } finally { event.recycle() }
        }
    }

    private fun awaitRows(scenario: ActivityScenario<MainActivity>, titles: List<String>) = await {
        var matches = false
        scenario.onActivity { activity ->
            val list = currentFragment(activity).requireView().findViewWithTag<RecyclerView>("calendar.agenda")
            val rows = (list.adapter as Mango9AppointmentAgendaAdapter).currentList.filterIsInstance<Mango9AgendaRow.Event>()
            matches = list.visibility == View.VISIBLE && rows.map { it.event.title } == titles
        }
        matches
    }

    private fun await(condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < until) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) { instrumentation.waitForIdleSync(); return }
            SystemClock.sleep(20)
        }
        fail("Calendar page did not reach its expected state")
    }

    private fun core(action: (Core) -> Unit) {
        val done = CountDownLatch(1); var result: Result<Unit>? = null
        coreContext.postOnCoreThread { core -> result = runCatching { action(core) }; done.countDown() }
        assertTrue(done.await(10, TimeUnit.SECONDS)); result!!.getOrThrow()
    }
}
