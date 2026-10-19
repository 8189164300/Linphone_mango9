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
import android.os.SystemClock
import android.view.View
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.allOf
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
import org.linphone.ui.main.crm.fragment.Mango9ReminderBanner
import org.linphone.ui.main.crm.fragment.Mango9ReminderHost

/** Actual MainActivity, banner and detail dialogs, with account-scoped synthetic API responses. */
@RunWith(AndroidJUnit4::class)
class Mango9ReminderHostInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private class History : Mango9ReminderHistory {
        val cadence = Mango9ReminderCadence()
        var records = 0

        override suspend fun ready() {}

        override fun allows(key: String, now: Instant, repeatMinutes: Int) = cadence.allows(key, now, repeatMinutes)

        override fun record(key: String, now: Instant) { records++; cadence.record(key, now) }
    }

    private class Calls(private val changed: () -> Unit) : Mango9ReminderCalls {
        override var blocked = false
        var closed = false

        fun block(value: Boolean) { blocked = value; changed() }

        override fun close() { closed = true; blocked = true }
    }

    private class Fixture {
        val api = Mango9AppointmentReminderInstrumentedTest.Fixture().apply {
            reminder.put("enabled", true).put("minutes_before", 15).put("due_at", "2030-01-10T08:45:00Z")
        }
        val accountChanges = MutableStateFlow(0L)
        val history = History()
        var feedReads = 0
        var metadataReads = 0
        var metadataWait: CompletableDeferred<Unit>? = null
        lateinit var host: Mango9ReminderHost
        lateinit var banner: Mango9ReminderBanner
        lateinit var calls: Calls
        lateinit var scenario: ActivityScenario<MainActivity>
    }

    @Test fun bannerOpensFreshAppointmentAndServerSaveDoesNotCloseTheDetail() = fixture { f ->
        await { f.banner.visibility == View.VISIBLE }
        assertEquals("Synthetic consultation", (f.banner.findViewWithTag<View>("reminder.banner.title") as TextView).text.toString())
        assertEquals(1, f.history.records)
        onView(allOf(withTagValue(`is`("reminder.banner.open")), isDisplayed())).perform(click())
        await { dialogView("appointment.personalReminder").isEnabled }
        assertEquals(1, f.metadataReads)
        assertTrue(f.api.calls.any { it.url.encodedPath.endsWith("/events/91") })
        val original = dialogView("appointment.personalReminder")
        onView(withTagValue(`is`("appointment.reminderDismiss"))).inRoot(isDialog()).perform(scrollTo(), click())
        await { f.api.writes.size == 1 && original.isEnabled }
        assertSame(original, dialogView("appointment.personalReminder"))
        assertTrue(f.api.writes.single().url.encodedPath.endsWith("/reminder"))
        await { f.banner.visibility == View.GONE }
        assertSame("Feed invalidation must not rebuild the open detail", original, dialogView("appointment.personalReminder"))
    }

    @Test fun backgroundAndHostRecreationHideBannerWithoutReplayingItsCadence() = fixture { f ->
        await { f.banner.visibility == View.VISIBLE }
        capture(f.banner, "foreground-reminder-banner.png")
        f.scenario.moveToState(Lifecycle.State.STARTED)
        assertEquals(View.GONE, f.banner.visibility)
        val before = f.feedReads
        SystemClock.sleep(100)
        assertEquals(before, f.feedReads)
        f.scenario.moveToState(Lifecycle.State.RESUMED)
        await { f.feedReads > before }
        assertEquals(View.GONE, f.banner.visibility)
        val oldGate = f.calls
        f.scenario.recreate()
        f.scenario.onActivity { attach(it, f) }
        assertTrue(oldGate.closed)
        await { f.feedReads > before + 1 }
        assertEquals(View.GONE, f.banner.visibility)
        assertEquals(1, f.history.records)
        instrumentation.runOnMainSync {
            f.api.reminder.put("revision", "reminder-new"); Mango9CalendarChanges.saved()
        }
        await { f.banner.visibility == View.VISIBLE }
        assertEquals(2, f.history.records)
    }

    @Test fun callOrAwayAndBackAccountChangeRejectsLateOpeningAndHidesBanner() = fixture { f ->
        await { f.banner.visibility == View.VISIBLE }
        f.metadataWait = CompletableDeferred()
        onView(allOf(withTagValue(`is`("reminder.banner.open")), isDisplayed())).perform(click())
        await { f.metadataReads == 1 }
        instrumentation.runOnMainSync { f.calls.block(true) }
        assertEquals(View.GONE, f.banner.visibility)
        f.metadataWait!!.complete(Unit)
        instrumentation.waitForIdleSync()
        assertNull(f.host.reminders.state.value.selection)
        instrumentation.runOnMainSync {
            f.metadataWait = null
            f.api.reminder.put("revision", "reminder-next"); f.calls.block(false)
        }
        await { f.banner.visibility == View.VISIBLE }
        onView(allOf(withTagValue(`is`("reminder.banner.open")), isDisplayed())).perform(click())
        await { dialogView("appointment.personalReminder").isEnabled }
        instrumentation.runOnMainSync {
            f.api.generations.selectionChanged(); f.api.generations.selectionChanged(); f.accountChanges.value++
        }
        await { f.host.reminders.state.value.selection == null && f.banner.visibility == View.GONE }
        onView(withTagValue(`is`("appointment.personalReminder"))).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
        assertTrue(f.api.writes.isEmpty())
    }

    private fun attach(activity: MainActivity, f: Fixture) {
        f.banner = Mango9ReminderBanner(activity)
        activity.findViewById<ViewGroup>(R.id.notifications_area).addView(f.banner)
        val repository = Mango9CalendarRepository(f.api, { error("No refresh") }) { request ->
            when {
                request.url.encodedPath.endsWith("/reminders") -> {
                    f.feedReads++
                    response(
                        JSONObject().put("server_time", "2030-01-10T08:50:00Z").put("settings", f.api.settings)
                        .put("items", JSONArray().put(JSONObject().put("event", f.api.event).put("reminder", f.api.reminder)))
                    )
                }
                request.url.encodedPath.endsWith("/metadata") -> {
                    f.metadataReads++
                    f.metadataWait?.let { withContext(NonCancellable) { it.await() } }
                    response(
                        JSONObject(
                            """{"timezone":"UTC","statuses":[{"id":3,"name":"Confirmed","color":"#008080"}],
                    "activities":["appointment"],"priorities":["medium"],"reminder_minutes":[15],"reminder_channels":["email","sms"],
                    "share_recipients":[],"assignees":[],"capabilities":{"create":true,"assign":true,"push":false,"in_app_reminders":true}}"""
                        )
                    )
                }
                else -> f.api.respond(request)
            }
        }
        f.host = Mango9ReminderHost(activity, f.banner, repository, f.history, { changed ->
            Calls(changed).also {
            f.calls = it
        }
        }, f.accountChanges)
    }

    @Test fun narrowBannerKeepsTextAndViewActionInsideNaturalHeightAtLargeFont() {
        val f = Fixture()
        for (scale in listOf(1f, 1.8f)) instrumentation.runOnMainSync {
            val configured = context.createConfigurationContext(
                Configuration(context.resources.configuration).apply {
                fontScale = scale
            }
            )
            val banner = Mango9ReminderBanner(ContextThemeWrapper(configured, R.style.Theme_Linphone))
            val item =
                Mango9ReminderItem(Mango9CalendarJson.appointment(f.api.event), Mango9CalendarJson.personalReminder(f.api.reminder))
            banner.render(Mango9ForegroundReminderState(item = item))
            val width = (320 * configured.resources.displayMetrics.density).toInt()
            banner.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            banner.layout(0, 0, width, banner.measuredHeight)
            for (tag in listOf("reminder.banner.title", "reminder.banner.subtitle", "reminder.banner.open")) {
                val text = banner.findViewWithTag<TextView>(tag)
                assertTrue("Text has usable width at $scale", text.width > text.totalPaddingLeft + text.totalPaddingRight)
                assertTrue("Text height is not clipped at $scale", text.layout.height <= text.height - text.totalPaddingTop - text.totalPaddingBottom)
            }
            val button = banner.findViewWithTag<View>("reminder.banner.open")
            assertTrue(button.right <= width - banner.paddingRight)
            assertTrue(button.height >= (48 * configured.resources.displayMetrics.density).toInt())
            val bitmap = Bitmap.createBitmap(width, banner.height, Bitmap.Config.ARGB_8888)
            banner.draw(Canvas(bitmap))
            File(context.getExternalFilesDir(null), "foreground-reminder-banner-$scale.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    private fun fixture(test: (Fixture) -> Unit) {
        for (permission in listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CAMERA)) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        val identity = Mango9SessionStore(context).activeIdentity
        var previous: Account? = null; var account: Account? = null
        val f = Fixture()
        try {
            core { sdk ->
                previous = sdk.defaultAccount
                val params = sdk.createAccountParams().apply {
                    identityAddress = Factory.instance().createAddress("sip:reminder-host@fixture.example.invalid")
                    serverAddress = Factory.instance().createAddress("sip:fixture.example.invalid")
                    isRegisterEnabled = false; pushNotificationAllowed = false
                }
                account = sdk.createAccount(params); sdk.addAccount(account!!); sdk.defaultAccount = account
            }
            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                f.scenario = scenario
                try { scenario.onActivity { attach(it, f) }; test(f) } finally {
                    f.metadataWait?.complete(Unit)
                    scenario.onActivity { f.host.close(); (f.banner.parent as? ViewGroup)?.removeView(f.banner) }
                }
            }
        } finally {
            f.metadataWait?.complete(Unit)
            core { sdk ->
                sdk.defaultAccount = previous
                account?.let { if (sdk.accountList.any { item -> item === it }) sdk.removeAccount(it) }
                sdk.config.sync()
            }
            Mango9SessionStore(context).let { if (it.activeIdentity != identity) it.activate(identity) }
            Mango9SdkTestCleanup.flush()
        }
    }

    private fun response(data: JSONObject) = Mango9CalendarResponse(200, JSONObject().put("success", true).put("data", data).toString())

    private fun dialogView(tag: String): View {
        var found: View? = null
        onView(withTagValue(`is`(tag))).inRoot(isDialog()).check { view, error -> if (error != null) throw error; found = view }
        return requireNotNull(found)
    }

    private fun capture(view: View, name: String) = instrumentation.runOnMainSync {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun core(action: (Core) -> Unit) {
        val done = CountDownLatch(1); var result: Result<Unit>? = null
        coreContext.postOnCoreThread { sdk -> result = runCatching { action(sdk) }; done.countDown() }
        assertTrue(done.await(15, TimeUnit.SECONDS)); result!!.getOrThrow()
    }

    private fun await(condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 15_000
        var last: Throwable? = null
        while (SystemClock.elapsedRealtime() < until) {
            try {
                if (condition()) {
                    instrumentation.waitForIdleSync(); return
                }
            } catch (error: androidx.test.espresso.NoMatchingViewException) { last = error }
            SystemClock.sleep(20)
        }
        throw AssertionError("Reminder host did not settle", last)
    }
}
