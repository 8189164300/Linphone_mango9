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
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.RootMatchers.isDialog
import org.hamcrest.Matchers.allOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.switchmaterial.SwitchMaterial
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Request
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
import org.linphone.ui.main.settings.fragment.SettingsFragment
import org.linphone.ui.main.settings.view.Mango9CrmSettingsView
import org.linphone.ui.main.settings.viewmodel.Mango9CrmSettingsViewModel

@RunWith(AndroidJUnit4::class)
class Mango9CrmSettingsPageInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun realSettingsPageOpensPickerAndRetainsDraftAcrossActivityRecreation() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_CONTACTS)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val active = Mango9SessionStore(context).activeIdentity
        var previous: Account? = null
        var fixture: Account? = null
        val requests = CopyOnWriteArrayList<Request>()
        val generations = Mango9SessionGenerations()
        val session =
            Mango9Session(
                "fixture", "https://crm.example.invalid", "https://crm.example.invalid/api/v2", "42", "1", "client",
            "settings@example.invalid", "CRM Settings Test", "test-access", "test-refresh", "", "", 1_900_000_000, "sip:42@crm.example.invalid"
            )
        val access = object : Mango9SessionAccess {
            override fun captureSession() = generations.capture(session)

            override fun currentSession(lease: Mango9SessionLease) = session.takeIf {
                generations.matches(lease, session.sipIdentity)
            }

            override fun replaceTokens(lease: Mango9SessionLease, expected: Mango9Session, refreshed: Mango9Session) = false
        }
        val repository = Mango9CalendarRepository(access, { error("No refresh") }) {
            requests.add(it)
            val data =
                JSONObject(
                    """{"in_app_reminders":true,"reminder_minutes":15,"repeat_minutes":0,"snooze_minutes":15,
                "new_appointment_timezone":"account","first_weekday":0,"default_calendar_view":"appointments",
                "sms_push":true,"team_chat_push":true,"account_timezone":"UTC","revision":"page-settings-v1"}"""
                )
            Mango9CalendarResponse(200, JSONObject().put("success", true).put("data", data).toString())
        }
        lateinit var model: Mango9CrmSettingsViewModel
        try {
            core { core ->
                previous = core.defaultAccount
                val params = core.createAccountParams().apply {
                    identityAddress = Factory.instance().createAddress("sip:crm-settings-page@fixture.example.invalid")
                    serverAddress = Factory.instance().createAddress("sip:fixture.example.invalid")
                    isRegisterEnabled = false; pushNotificationAllowed = false
                }
                fixture = core.createAccount(params)
                core.addAccount(fixture); core.defaultAccount = fixture
            }
            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                scenario.onActivity { activity ->
                    activity.supportFragmentManager.registerFragmentLifecycleCallbacks(
                        object : FragmentManager.FragmentLifecycleCallbacks() {
                        override fun onFragmentPreCreated(fm: FragmentManager, f: Fragment, savedInstanceState: Bundle?) {
                            if (f is SettingsFragment) {
                                model = ViewModelProvider(
                                    f,
                                    object : ViewModelProvider.Factory {
                                    @Suppress("UNCHECKED_CAST")
                                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                                        Mango9CrmSettingsViewModel(activity.application, repository, MutableStateFlow(0L)) as T
                                }
                                )[Mango9CrmSettingsViewModel::class.java]
                            }
                        }
                    },
                        true
                    )
                    (activity.supportFragmentManager.findFragmentById(R.id.main_nav_container) as NavHostFragment)
                        .navController.navigate(R.id.action_global_settingsFragment)
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    it.findViewById<Mango9CrmSettingsView>(R.id.mango9_crm_settings).findViewWithTag<Button>("crm.expand").performClick()
                }
                await { model.screen.state.value.draft != null && !model.screen.state.value.busy }
                scenario.onActivity { activity ->
                    val section = activity.findViewById<Mango9CrmSettingsView>(R.id.mango9_crm_settings)
                    section.findViewWithTag<SwitchMaterial>("crm.sms").performClick()
                    section.findViewWithTag<Button>("crm.zone").performClick()
                }
                onView(withHint(R.string.mango9_crm_zone_search)).inRoot(isDialog()).perform(replaceText("Europe/Paris"), closeSoftKeyboard())
                onView(allOf(withText("Europe/Paris"), withId(android.R.id.text1))).inRoot(isDialog()).perform(click())
                await { model.screen.state.value.draft?.newAppointmentTimezone == "Europe/Paris" }
                assertFalse(model.screen.state.value.draft!!.smsPush)
                val retained = model
                scenario.recreate()
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val host = activity.supportFragmentManager.findFragmentById(R.id.main_nav_container) as NavHostFragment
                    val fragment = host.childFragmentManager.primaryNavigationFragment!!
                    assertTrue("Recreation must keep the current screen for non-launcher intents", fragment is SettingsFragment)
                    assertSame(retained, ViewModelProvider(fragment)[Mango9CrmSettingsViewModel::class.java])
                    val section = activity.findViewById<Mango9CrmSettingsView>(R.id.mango9_crm_settings)
                    assertFalse(section.findViewWithTag<SwitchMaterial>("crm.sms").isChecked)
                    assertTrue(section.findViewWithTag<Button>("crm.zone").text.contains("Europe/Paris"))
                    assertTrue(section.findViewWithTag<Button>("crm.save").isEnabled)
                    val header = section.findViewWithTag<TextView>("crm.expand")
                    assertNotNull(header.compoundDrawablesRelative[0]); assertNotNull(header.compoundDrawablesRelative[2])
                    // This page contains only synthetic fixture data. Draw its real,
                    // attached hierarchy without disabling the app's secure-window policy.
                    val bitmap = Bitmap.createBitmap(section.width, section.height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(com.google.android.material.color.MaterialColors.getColor(section, R.attr.color_grey_100, android.graphics.Color.WHITE))
                    section.jumpDrawablesToCurrentState()
                    section.draw(canvas)
                    File(context.getExternalFilesDir(null), "crm-settings-page-rendered.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
                assertEquals("Rotation must not reload a dirty draft", 1, requests.size)
            }
        } finally {
            core { core ->
                core.defaultAccount = previous
                fixture?.let { if (core.accountList.any { account -> account === it }) core.removeAccount(it) }
                core.config.sync()
            }
            Mango9SessionStore(context).let { if (it.activeIdentity != active) it.activate(active) }
            Mango9SdkTestCleanup.flush()
        }
    }

    private fun await(condition: () -> Boolean) {
        val until = android.os.SystemClock.elapsedRealtime() + 15_000
        while (android.os.SystemClock.elapsedRealtime() < until) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) { instrumentation.waitForIdleSync(); return }
            android.os.SystemClock.sleep(20)
        }
        fail("Settings UI did not reach the expected state")
    }

    private fun core(action: (Core) -> Unit) {
        val done = CountDownLatch(1); var result: Result<Unit>? = null
        coreContext.postOnCoreThread { core -> result = runCatching { action(core) }; done.countDown() }
        assertTrue(done.await(10, TimeUnit.SECONDS)); result!!.getOrThrow()
    }
}
