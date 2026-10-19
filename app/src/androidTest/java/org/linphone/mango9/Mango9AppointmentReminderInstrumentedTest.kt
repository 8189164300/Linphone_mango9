/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.io.File
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.Request
import okio.Buffer
import org.hamcrest.Matchers.`is`
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
import org.linphone.ui.main.crm.fragment.Mango9AppointmentDialogs

/** Actual appointment dialogs and repository round trips; synthetic transport, no CRM writes. */
@RunWith(AndroidJUnit4::class)
class Mango9AppointmentReminderInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    internal class Fixture : Mango9SessionAccess {
        val generations = Mango9SessionGenerations()
        val session =
            Mango9Session(
                "fixture", "https://crm.example.invalid", "https://crm.example.invalid/api/v2", "42", "1", "client",
            "reminders@example.invalid", "Reminder Test", "test-access", "test-refresh", "", "", 1_900_000_000, "sip:42@crm.example.invalid"
            )
        var now = Instant.parse("2030-01-10T08:00:00Z")
        var event =
            JSONObject(
                """{"id":91,"owner_id":42,"title":"Synthetic consultation","description":"Reminder fixture",
            "start_at":"2030-01-10T09:00:00Z","end_at":"2030-01-10T09:30:00Z","timezone":"UTC","activity":"appointment","priority":"medium",
            "status":{"id":3,"name":"Confirmed","color":"#008080"},"contact":null,"recurrence":{"frequency":"none","weekdays":[]},
            "reminders":[],"origin":"appointment","shared_by_me_user_ids":[],"permissions":{"can_edit":true,"can_delete":true,
            "can_assign":true,"can_share":true,"can_change_contact":true,"read_only_reason":null},"revision":"event-v1"}"""
            )
        var reminder =
            JSONObject("""{"enabled":false,"minutes_before":null,"due_at":null,"dismissed":false,"needs_reenable":false,"revision":"reminder-v1"}""")
        val settings =
            JSONObject(
                """{"in_app_reminders":true,"reminder_minutes":15,"repeat_minutes":0,"snooze_minutes":15,
            "new_appointment_timezone":"device","first_weekday":0,"default_calendar_view":"appointments","sms_push":true,"team_chat_push":true,
            "account_timezone":"UTC","revision":"settings-v1"}"""
            )
        val metadata = Mango9CalendarJson.metadata(
            JSONObject(
                """{"timezone":"UTC","statuses":[{"id":3,"name":"Confirmed","color":"#008080"}],
            "activities":["appointment"],"priorities":["medium"],"reminder_minutes":[15],"reminder_channels":["email","sms"],
            "share_recipients":[],"assignees":[],"capabilities":{"create":true,"assign":true,"push":false,"in_app_reminders":true}}"""
            )
        )
        val calls = CopyOnWriteArrayList<Request>()
        val writes get() = calls.filter { it.method != "GET" }
        var failure: Mango9CalendarResponse? = null
        var throwWrite = false
        var acceptMinutes: Int? = null
        var revokeEditOnStatus = false
        var serial = 1
        var settingsWait: CompletableDeferred<Unit>? = null
        var settingsFailure = false
        lateinit var dialogs: Mango9AppointmentDialogs

        override fun captureSession() = generations.capture(session)

        override fun currentSession(lease: Mango9SessionLease) = session.takeIf {
            generations.matches(lease, session.sipIdentity)
        }

        override fun replaceTokens(lease: Mango9SessionLease, expected: Mango9Session, refreshed: Mango9Session) = false

        suspend fun respond(request: Request): Mango9CalendarResponse {
            calls.add(request)
            if (request.url.encodedPath.endsWith("/settings")) {
                settingsWait?.let { withContext(NonCancellable) { it.await() } }
                if (settingsFailure) throw IOException("Synthetic settings failure")
            }
            if (request.method != "GET") {
                if (throwWrite) throw IOException("Synthetic uncertain write")
                failure?.let { failure = null; return it }
                val body = body(request)
                if (request.url.encodedPath.endsWith("/reminder")) {
                    reminder = JSONObject(reminder.toString()).put("revision", "reminder-v${++serial}")
                    when (body.getString("action")) {
                        "configure" -> {
                            val minutes = acceptMinutes ?: body.getInt("minutes_before")
                            reminder.put("enabled", minutes >= 0).put("minutes_before", if (minutes < 0) JSONObject.NULL else minutes)
                                .put("dismissed", false).put("needs_reenable", false)
                                .put("due_at", if (minutes < 0) JSONObject.NULL else Instant.parse(event.getString("start_at")).minusSeconds(minutes * 60L).toString())
                        }
                        "snooze" -> reminder.put("due_at", body.getString("until")).put("dismissed", false)
                        "dismiss" -> reminder.put("dismissed", true).put("due_at", JSONObject.NULL)
                    }
                } else {
                    event = JSONObject(event.toString()).put("revision", "event-v${++serial}")
                    for (key in listOf("start_at", "end_at")) if (body.has(key)) event.put(key, body.get(key))
                    if (body.has("status_id")) {
                        event.put("status", JSONObject.NULL)
                        if (revokeEditOnStatus) event.getJSONObject("permissions").put("can_edit", false)
                        reminder.put("needs_reenable", true).put("enabled", false).put("revision", "reminder-v$serial")
                    }
                }
            }
            val data = when {
                request.url.encodedPath.endsWith("/reminder") -> reminder
                request.url.encodedPath.endsWith("/settings") -> settings
                else -> event
            }
            return Mango9CalendarResponse(200, JSONObject().put("success", true).put("data", data).toString())
        }
    }

    @Test fun configureSnoozeDismissAndOffUseAuthoritativeReminderWithoutMovingEvent() = fixture { f ->
        val original = view("appointment.personalReminder")
        tap("appointment.personalReminder"); onView(withText("30 minutes before")).perform(click())
        await {
            view("appointment.personalReminder").isEnabled && (view("appointment.personalReminder") as TextView).text.contains("30 minutes")
        }
        assertSame(original, view("appointment.personalReminder"))
        instrumentation.runOnMainSync {
            val root = original.rootView
            root.jumpDrawablesToCurrentState()
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            File(context.getExternalFilesDir(null), "personal-reminder-dialog.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        val configure = f.writes.single()
        assertEquals("\"event-v1\"", configure.header("If-Match"))
        assertEquals("reminder-v1", body(configure).getString("reminder_revision"))
        await { view("appointment.reminderSnoozeDefault").isEnabled }
        tap("appointment.reminderSnoozeDefault")
        await { f.writes.size == 2 && view("appointment.personalReminder").isEnabled }
        assertEquals(f.now.plusSeconds(900).toString(), body(f.writes.last()).getString("until"))
        tap("appointment.reminderDismiss")
        await { f.writes.size == 3 && view("appointment.personalReminder").isEnabled }
        assertFalse(view("appointment.reminderDismiss").isEnabled)
        assertTrue((view("appointment.reminderNext") as TextView).text.contains("Dismissed"))
        tap("appointment.personalReminder"); onView(withText("Off")).perform(click())
        await { f.writes.size == 4 && view("appointment.personalReminder").isEnabled }
        assertEquals(-1, body(f.writes.last()).getInt("minutes_before"))
        assertEquals("2030-01-10T09:00:00Z", f.event.getString("start_at"))
        assertTrue(f.writes.all { it.url.encodedPath.endsWith("/reminder") && !body(it).has("start_at") })
    }

    @Test fun returnedValuesWinAndUncertainWriteRequiresFreshEventAndReminderBeforeRetry() = fixture { f ->
        f.acceptMinutes = 60
        tap("appointment.reminderSuggested")
        await {
            (view("appointment.personalReminder") as TextView).text.contains("60 minutes") && view("appointment.personalReminder").isEnabled
        }
        f.throwWrite = true
        tap("appointment.reminderDismiss")
        await { view("appointment.reload").visibility == View.VISIBLE }
        assertFalse(view("appointment.personalReminder").isEnabled)
        val count = f.writes.size
        val disabled = view("appointment.personalReminder")
        instrumentation.runOnMainSync { disabled.performClick() }
        assertEquals(count, f.writes.size)
        f.throwWrite = false; f.event.put("revision", "external-event"); f.reminder.put("revision", "external-reminder")
        tap("appointment.reload")
        await { view("appointment.personalReminder").isEnabled }
        tap("appointment.reminderDismiss")
        await { f.writes.size == count + 1 && view("appointment.personalReminder").isEnabled }
        assertEquals("\"external-event\"", f.writes.last().header("If-Match"))
        assertEquals("external-reminder", body(f.writes.last()).getString("reminder_revision"))
    }

    @Test fun pushBackRequiresConfirmationPreservesDurationAndAllowsOverlapCorrection() = fixture { f ->
        tap("appointment.pushBack"); onView(withText("15 minutes")).perform(click())
        assertTrue(f.writes.isEmpty())
        onView(withText(R.string.mango9_calendar_cancel)).perform(click())
        assertTrue(f.writes.isEmpty())
        f.failure = Mango9CalendarResponse(409, """{"error":{"code":"overlap"}}""")
        tap("appointment.pushBack"); onView(withText("15 minutes")).perform(click())
        onView(withText(R.string.mango9_calendar_save)).perform(click())
        await { f.writes.size == 1 && view("appointment.pushBack").isEnabled }
        tap("appointment.pushBack"); onView(withText("30 minutes")).perform(click())
        onView(withText(R.string.mango9_calendar_save)).perform(click())
        await { f.writes.size == 2 && view("appointment.personalReminder").isEnabled }
        val change = body(f.writes.last())
        assertEquals(2, change.length()); assertEquals("2030-01-10T09:30:00Z", change.getString("start_at"))
        assertEquals("2030-01-10T10:00:00Z", change.getString("end_at"))
        assertEquals("\"event-v1\"", f.writes.last().header("If-Match"))
        assertEquals(3, f.event.getJSONObject("status").getInt("id"))
    }

    @Test fun pastTimeIsRejectedAgainAtConfirmationAndStatusReloadsPersonalReminder() = fixture { f ->
        tap("appointment.pushBack"); onView(withText("5 minutes")).perform(click())
        f.now = Instant.parse("2030-01-10T09:06:00Z")
        onView(withText(R.string.mango9_calendar_save)).perform(click())
        assertTrue(f.writes.isEmpty())
        tap("appointment.pushBack"); onView(withText("5 minutes")).perform(click())
        assertTrue(f.writes.isEmpty())
        f.revokeEditOnStatus = true
        tap("appointment.status"); onView(withText(R.string.mango9_calendar_no_status)).perform(click())
        await { f.writes.size == 1 && view("appointment.personalReminder").isEnabled }
        assertTrue(f.calls.count { it.method == "GET" && it.url.encodedPath.endsWith("/reminder") } >= 2)
        assertTrue(f.reminder.getBoolean("needs_reenable"))
        assertFalse(view("appointment.status").isEnabled)
        assertFalse(view("appointment.pushBack").isEnabled)
    }

    @Test fun staleChoiceCannotWriteAfterAccountChangesAwayAndBack() = fixture { f ->
        tap("appointment.personalReminder")
        instrumentation.runOnMainSync { f.generations.selectionChanged(); f.generations.selectionChanged() }
        onView(withText("15 minutes before")).perform(click())
        assertTrue(f.writes.isEmpty())
        instrumentation.runOnMainSync { f.dialogs.closeExpired() }
    }

    @Test fun readOnlyAppointmentStillAllowsPersonalReminders() = fixture(prepare = {
        it.event.getJSONObject("permissions").put("can_edit", false).put("can_delete", false).put("can_share", false).put("can_assign", false)
    }) { f ->
        assertFalse(view("appointment.status").isEnabled)
        tap("appointment.reminderSuggested")
        await { f.writes.size == 1 && view("appointment.personalReminder").isEnabled }
        assertTrue(f.writes.single().url.encodedPath.endsWith("/reminder"))
    }

    @Test fun recurringAppointmentsDoNotExposePersonalReminderControls() = fixture(prepare = {
        it.event.getJSONObject("recurrence").put("frequency", "daily")
    }, hasPersonal = false) { f ->
        onView(withTagValue(`is`("appointment.personalReminder"))).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
        assertTrue(f.calls.none { it.url.encodedPath.endsWith("/reminder") })
    }

    @Test fun endedAppointmentCanDismissButCannotConfigureOrSnooze() = fixture(prepare = {
        it.now = Instant.parse("2030-01-10T10:00:00Z")
        it.reminder.put("enabled", true).put("minutes_before", 15).put("due_at", "2030-01-10T08:45:00Z")
    }, hasPersonal = false) { f ->
        await { view("appointment.reminderDismiss").isEnabled }
        assertFalse(view("appointment.personalReminder").isEnabled)
        assertFalse(view("appointment.reminderSnooze").isEnabled)
        tap("appointment.reminderDismiss")
        await { f.writes.size == 1 && !view("appointment.reminderDismiss").isEnabled }
        assertEquals("dismiss", body(f.writes.single()).getString("action"))
    }

    @Test fun slowOptionalSettingsDoNotDelayConfigureOrOverwriteSavedReminder() = fixture(prepare = {
        it.settingsWait = CompletableDeferred()
    }) { f ->
        assertFalse(f.settingsWait!!.isCompleted)
        assertEquals(View.GONE, view("appointment.reminderSuggested").visibility)
        tap("appointment.personalReminder"); onView(withText("30 minutes before")).perform(click())
        await { f.writes.size == 1 && view("appointment.personalReminder").isEnabled }
        f.settingsWait!!.complete(Unit)
        await { view("appointment.reminderSnoozeDefault").visibility == View.VISIBLE }
        assertEquals(View.GONE, view("appointment.reminderSuggested").visibility)
        assertTrue((view("appointment.personalReminder") as TextView).text.contains("30 minutes"))
        assertEquals(1, f.writes.size)
    }

    @Test fun failedOptionalSettingsAllowPersonalActionsAndExplicitDefaultsRetry() = fixture(prepare = {
        it.settingsFailure = true
    }) { f ->
        await { view("appointment.reminderReload").isEnabled }
        assertTrue(view("appointment.personalReminder").isEnabled)
        assertTrue((view("appointment.reminderDefaults") as TextView).text.isNotBlank())
        f.settingsFailure = false
        tap("appointment.reminderReload")
        await { view("appointment.reminderSuggested").visibility == View.VISIBLE }
        assertTrue(view("appointment.personalReminder").isEnabled)
        assertTrue(f.writes.isEmpty())
    }

    @Test fun cancellationIgnoringOptionalSettingsCannotReopenExpiredAccountDialog() = fixture(prepare = {
        it.settingsWait = CompletableDeferred()
    }) { f ->
        instrumentation.runOnMainSync {
            f.generations.selectionChanged(); f.generations.selectionChanged(); f.dialogs.closeExpired()
        }
        f.settingsWait!!.complete(Unit)
        instrumentation.waitForIdleSync()
        onView(withTagValue(`is`("appointment.personalReminder"))).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
        assertTrue(f.writes.isEmpty())
    }

    private fun fixture(prepare: (Fixture) -> Unit = {}, hasPersonal: Boolean = true, test: (Fixture) -> Unit) {
        for (permission in listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CAMERA)) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        val originalIdentity = Mango9SessionStore(context).activeIdentity
        var previous: Account? = null; var account: Account? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val f = Fixture().also(prepare)
        try {
            core { core ->
                previous = core.defaultAccount
                val params = core.createAccountParams().apply {
                    identityAddress = Factory.instance().createAddress("sip:reminder-ui@fixture.example.invalid")
                    serverAddress = Factory.instance().createAddress("sip:fixture.example.invalid")
                    isRegisterEnabled = false; pushNotificationAllowed = false
                }
                account = core.createAccount(params); core.addAccount(account); core.defaultAccount = account
            }
            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                scenario.onActivity { activity ->
                    val repository = Mango9CalendarRepository(f, { error("No refresh") }, f::respond)
                    f.dialogs = Mango9AppointmentDialogs(activity, scope, repository, {}, { _, _ -> }, { f.now })
                    f.dialogs.detail(f.captureSession(), Mango9CalendarJson.appointment(f.event), f.metadata)
                }
                try {
                    await {
                        if (hasPersonal) view("appointment.personalReminder").isEnabled else view("appointment.status").visibility == View.VISIBLE
                    }
                    test(f)
                } finally { scenario.onActivity { f.dialogs.close() } }
            }
        } finally {
            f.settingsWait?.complete(Unit)
            instrumentation.runOnMainSync { scope.cancel() }
            core { core ->
                core.defaultAccount = previous; account?.let {
                if (core.accountList.any { a -> a === it }) core.removeAccount(it)
            }; core.config.sync()
            }
            Mango9SessionStore(context).let { if (it.activeIdentity != originalIdentity) it.activate(originalIdentity) }
            Mango9SdkTestCleanup.flush()
        }
    }

    private fun tap(tag: String) = onView(withTagValue(`is`(tag))).inRoot(isDialog()).perform(scrollTo(), click())

    private fun view(tag: String): View {
        var result: View? = null
        onView(withTagValue(`is`(tag))).inRoot(isDialog()).check { view, error -> if (error != null) throw error; result = view }
        return requireNotNull(result)
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
        throw AssertionError("Reminder UI did not reach expected state", last)
    }

    private fun core(action: (Core) -> Unit) {
        val done = CountDownLatch(1); var result: Result<Unit>? = null
        coreContext.postOnCoreThread { core -> result = runCatching { action(core) }; done.countDown() }
        assertTrue(done.await(10, TimeUnit.SECONDS)); result!!.getOrThrow()
    }

    companion object {
        private fun body(request: Request) = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
    }
}
