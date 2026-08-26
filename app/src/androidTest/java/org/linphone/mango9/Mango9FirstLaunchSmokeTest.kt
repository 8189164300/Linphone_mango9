/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.mango9

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.RestrictionsManager
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.CoreMatchers.not
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.R
import org.linphone.ui.main.MainActivity

@RunWith(AndroidJUnit4::class)
@LargeTest
class Mango9FirstLaunchSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before
    fun grantFirstLaunchPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            listOf(
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
            ).forEach { permission ->
                instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        if (Build.VERSION.SDK_INT >= 34) {
            instrumentation.uiAutomation.executeShellCommand(
                "appops set ${context.packageName} USE_FULL_SCREEN_INTENT allow",
            ).close()
        }
    }

    @Test
    fun manifestIdentityAndManagedConfigurationMatchMango9Contract() {
        assertEquals("com.mango9.phone", context.packageName)
        val applicationInfo = context.packageManager.getApplicationInfo(context.packageName, 0)
        assertEquals("Mango9", context.packageManager.getApplicationLabel(applicationInfo).toString())
        assertFalse(
            "Cleartext traffic must remain disabled",
            applicationInfo.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC != 0,
        )
        assertTrue(Application::class.java.isAssignableFrom(Class.forName(applicationInfo.className)))

        val restrictions = context.getSystemService(RestrictionsManager::class.java)
            .getManifestRestrictions(context.packageName)
            .map { it.key }
            .toSet()
        assertEquals(setOf("xmlConfig", "rootCa", "configUri"), restrictions)
    }

    @Test
    fun launcherShowsMango9PasswordAndEmailCodeModes() {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        ActivityScenario.launch<MainActivity>(intent).use {
            onView(withText(R.string.app_name)).check(matches(isDisplayed()))
            onView(withText(R.string.mango9_connect_account)).check(matches(isDisplayed()))
            onView(withId(R.id.mango9_username)).check(matches(isDisplayed()))
            onView(withId(R.id.password)).check(matches(isDisplayed()))
            onView(withId(R.id.mango9_primary_action)).check(matches(not(isEnabled())))
            onView(withText(R.string.mango9_keep_signed_in)).check(matches(isDisplayed()))

            onView(withId(R.id.mango9_use_email_code)).perform(click())
            onView(withId(R.id.mango9_email)).check(matches(isDisplayed()))
            onView(withId(R.id.mango9_use_password)).check(matches(isDisplayed())).perform(click())
            onView(withId(R.id.mango9_username)).check(matches(isDisplayed()))
            onView(withId(R.id.password)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun helpUsesMango9SupportAndExactVersionLicensing() {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        ActivityScenario.launch<MainActivity>(intent).use {
            onView(withContentDescription(R.string.help_title)).perform(click())
            onView(withText(R.string.mango9_help_support_title)).check(matches(isDisplayed()))
            onView(withText(R.string.mango9_help_safety_title)).check(matches(isDisplayed()))
            onView(withText(R.string.help_troubleshooting_title)).check(doesNotExist())

            onView(withId(R.id.licensing_row)).perform(scrollTo(), click())
            onView(withText(R.string.mango9_licensing_title)).check(matches(isDisplayed()))
            onView(withText(R.string.mango9_licensing_source_missing))
                .perform(scrollTo())
                .check(matches(isDisplayed()))
            onView(withText(R.string.mango9_licensing_sdk_license_subtitle))
                .perform(scrollTo())
                .check(matches(isDisplayed()))
        }
    }

    @Test
    fun manualSipSetupDoesNotCreateAMango9Session() {
        assertEquals(null, Mango9SessionStore(context).activeIdentity)
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        ActivityScenario.launch<MainActivity>(intent).use {
            onView(withContentDescription(R.string.mango9_manual_sip_accessibility)).perform(click())
            onView(withText(R.string.assistant_login_third_party_sip_account_title))
                .check(matches(isDisplayed()))
            onView(withId(R.id.username)).check(matches(isDisplayed()))
            onView(withId(R.id.password)).check(matches(isDisplayed()))
            onView(withId(R.id.domain)).check(matches(isDisplayed()))
            onView(withId(R.id.transport)).check(matches(isDisplayed()))
            onView(withText(R.string.mango9_crm_title)).check(doesNotExist())
        }
        assertEquals(null, Mango9SessionStore(context).activeIdentity)
    }
}
