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
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.switchmaterial.SwitchMaterial
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.Request
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.R
import org.linphone.ui.main.crm.fragment.Mango9TimezonePicker
import org.linphone.ui.main.settings.view.Mango9CrmSettingsView

/** Production controls + guarded repository, synthetic transport; never writes CRM data. */
@RunWith(AndroidJUnit4::class)
class Mango9CrmSettingsInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val json = """{"in_app_reminders":true,"reminder_minutes":15,"repeat_minutes":0,"snooze_minutes":15,
        "new_appointment_timezone":"account","first_weekday":0,"default_calendar_view":"appointments",
        "sms_push":true,"team_chat_push":true,"account_timezone":"UTC","revision":"native-settings-v1"}"""

    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }

    private class Sessions : Mango9SessionAccess {
        val generations = Mango9SessionGenerations()
        val session =
            Mango9Session(
                "fixture", "https://crm.example.invalid", "https://crm.example.invalid/api/v2", "42", "1", "client",
            "settings@example.invalid", "CRM Settings Test", "test-access", "test-refresh", "", "", 1_900_000_000, "sip:42@crm.example.invalid"
            )

        override fun captureSession() = generations.capture(session)

        override fun currentSession(lease: Mango9SessionLease) = session.takeIf {
            generations.matches(lease, session.sipIdentity)
        }

        override fun replaceTokens(lease: Mango9SessionLease, expected: Mango9Session, refreshed: Mango9Session) = false
    }

    @Test fun actualSwitchesSaveOriginalRevisionAndServerResponseControlsSavedState() = fixture { screen, view, calls ->
        ui { view.findViewWithTag<Button>("crm.expand").performClick() }; await(screen)
        ui {
            assertFalse(view.findViewWithTag<Button>("crm.save").isEnabled)
            // An old settings API must not present unsavable new controls as enabled.
            for (key in listOf("crm.social", "crm.leads", "crm.events")) assertFalse(view.findViewWithTag<SwitchMaterial>(key).isEnabled)
            view.findViewWithTag<SwitchMaterial>("crm.sms").performClick()
            view.findViewWithTag<SwitchMaterial>("crm.team").performClick()
        }
        instrumentation.waitForIdleSync()
        ui {
            assertTrue(view.findViewWithTag<Button>("crm.save").isEnabled); view.findViewWithTag<Button>("crm.save").performClick()
        }
        await(screen)
        assertEquals(listOf("GET", "PATCH"), calls.map { it.method })
        val request = calls.last(); assertEquals("\"native-settings-v1\"", request.header("If-Match"))
        val body = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
        assertFalse(body.getBoolean("sms_push")); assertFalse(body.getBoolean("team_chat_push"))
        ui {
            assertTrue(view.findViewWithTag<SwitchMaterial>("crm.team").isChecked)
            assertFalse(view.findViewWithTag<SwitchMaterial>("crm.sms").isChecked)
            assertEquals(View.VISIBLE, view.findViewWithTag<TextView>("crm.saved").visibility)
            assertFalse(view.findViewWithTag<Button>("crm.save").isEnabled)
        }
    }

    @Test fun collapseReopenAndViewRecreationKeepDirtyDraftWithoutAnotherRead() = fixture { screen, view, calls ->
        ui { screen.setExpanded(true) }; await(screen)
        ui {
            view.findViewWithTag<SwitchMaterial>("crm.reminders").performClick()
            screen.setExpanded(false); screen.setExpanded(true)
        }
        instrumentation.waitForIdleSync()
        ui {
            assertFalse(view.findViewWithTag<Button>("crm.before").isEnabled)
            val replacement = Mango9CrmSettingsView(view.context)
            replacement.render(screen.state.value)
            assertFalse(replacement.findViewWithTag<SwitchMaterial>("crm.reminders").isChecked)
            assertTrue(replacement.findViewWithTag<Button>("crm.save").isEnabled)
        }
        assertEquals(1, calls.size)
    }

    @Test fun fullSettingsLayoutContainsSectionAndNaturalHeightAtLargeFonts() {
        for (scale in listOf(1f, 1.8f)) fixture(scale) { screen, view, _ ->
            ui { screen.setExpanded(true) }; await(screen)
            ui {
                val width = (350 * view.resources.displayMetrics.density).toInt()
                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                view.layout(0, 0, width, view.measuredHeight)
                val option = view.findViewWithTag<Button>("crm.before")
                assertTrue(option.lineCount >= 2)
                assertTrue("Two-line setting clipped at font $scale", option.height >= option.layout.height + option.compoundPaddingTop + option.compoundPaddingBottom)
                assertTrue(view.findViewWithTag<SwitchMaterial>("crm.reminders").height >= (48 * view.resources.displayMetrics.density).toInt())
                val bitmap = Bitmap.createBitmap(width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                val directory = File(context.getExternalFilesDir(null), "crm-settings-native-fixtures").apply { mkdirs() }
                File(directory, "settings-$scale.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                val binding = org.linphone.databinding.SettingsFragmentBinding.inflate(android.view.LayoutInflater.from(view.context))
                assertNotNull(binding.mango9CrmSettings)
                assertTrue(binding.mango9CrmSettings.findViewWithTag<Button>("crm.expand").text.toString().contains("CRM"))
            }
        }
    }

    @Test fun timezoneSearchNormalizesSpacesCaseAndIncludesUtcWithoutFreeformValues() {
        assertTrue(Mango9TimezonePicker.matchingZones(" utc ").contains("UTC"))
        assertTrue(Mango9TimezonePicker.matchingZones("LOS ANGELES").contains("America/Los_Angeles"))
        assertEquals(Mango9TimezonePicker.matchingZones("los angeles"), Mango9TimezonePicker.matchingZones("los_angeles"))
        assertTrue(Mango9TimezonePicker.matchingZones("unrecognized zone").isEmpty())
    }

    private fun fixture(scale: Float = 1f, run: (Mango9CrmSettingsScreen, Mango9CrmSettingsView, List<Request>) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val calls = CopyOnWriteArrayList<Request>()
        lateinit var screen: Mango9CrmSettingsScreen
        lateinit var view: Mango9CrmSettingsView
        lateinit var owner: Owner
        try {
            ui {
                val repository = Mango9CalendarRepository(Sessions(), { error("No refresh") }) { request ->
                    calls.add(request)
                    val result = JSONObject(json).apply {
                        if (request.method == "PATCH") put("sms_push", false).put("revision", "native-settings-v2")
                    }
                    Mango9CalendarResponse(200, JSONObject().put("success", true).put("data", result).toString())
                }
                screen = Mango9CrmSettingsScreen(repository, scope)
                val configured = context.createConfigurationContext(
                    Configuration(context.resources.configuration).apply {
                    fontScale = scale
                }
                )
                view = Mango9CrmSettingsView(ContextThemeWrapper(configured, R.style.Theme_Linphone))
                owner = Owner(); owner.lifecycle.currentState = Lifecycle.State.RESUMED
                view.bind(screen, owner)
            }
            run(screen, view, calls)
        } finally {
            ui { owner.lifecycle.currentState = Lifecycle.State.DESTROYED; scope.cancel() }
        }
    }

    private fun await(screen: Mango9CrmSettingsScreen) {
        val finished = CountDownLatch(1)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val poll = object : Runnable {
            override fun run() { if (!screen.state.value.busy) finished.countDown() else handler.postDelayed(this, 10) }
        }
        handler.post(poll)
        try {
            assertTrue("Settings request did not finish", finished.await(10, TimeUnit.SECONDS))
        } finally { handler.removeCallbacks(poll) }
        instrumentation.waitForIdleSync()
    }

    private fun ui(action: () -> Unit) = instrumentation.runOnMainSync(action)
}
