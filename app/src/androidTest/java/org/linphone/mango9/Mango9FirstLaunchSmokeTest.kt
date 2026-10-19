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
import android.content.Context
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
import org.junit.Assert.assertNotNull
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.Account
import org.linphone.core.Factory
import org.linphone.ui.main.chat.adapter.ConversationsListAdapter
import org.linphone.ui.main.history.adapter.HistoryListAdapter
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.R
import org.linphone.BuildConfig
import org.linphone.ui.main.MainActivity

@RunWith(AndroidJUnit4::class)
@LargeTest
class Mango9FirstLaunchSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var originalIdentity: String? = null

    @Before
    fun grantFirstLaunchPermissions() {
        val sessions = Mango9SessionStore(context)
        originalIdentity = sessions.activeIdentity
        sessions.activate(null)
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

    @After
    fun restoreSelectedAccount() {
        val sessions = Mango9SessionStore(context)
        sessions.activate(originalIdentity?.takeIf { sessions.hasSession(it) })
        Mango9SdkTestCleanup.flush()
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
        listOf(
            "org.linphone.ui.assistant.fragment.QrCodeScannerFragment",
            "org.linphone.ui.assistant.viewmodel.QrCodeViewModel",
        ).forEach { removedClass ->
            assertTrue(
                "QR setup must not be packaged: $removedClass",
                runCatching { Class.forName(removedClass) }.exceptionOrNull() is ClassNotFoundException,
            )
        }

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
            onView(withContentDescription("Scan QR code")).check(doesNotExist())
            onView(withContentDescription(R.string.mango9_manual_sip_accessibility)).check(matches(isDisplayed()))
            onView(withContentDescription(R.string.help_title)).check(matches(isDisplayed()))

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
            val sourceConfigured = BuildConfig.MANGO9_SOURCE_CODE_URL.isNotBlank()
            val sourceSubtitle = if (sourceConfigured) {
                context.getString(
                    R.string.mango9_licensing_source_available,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE,
                )
            } else {
                context.getString(R.string.mango9_licensing_source_missing)
            }
            onView(withId(R.id.corresponding_source_subtitle))
                .perform(scrollTo())
                .check(matches(withText(sourceSubtitle)))
                .check(matches(isDisplayed()))
            onView(withId(R.id.corresponding_source))
                .check(matches(if (sourceConfigured) isEnabled() else not(isEnabled())))
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

    @Test
    fun historyAndConversationsInitializeAndRebindPaginationAfterRecreation() {
        val preferences = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
        val originalDestination = preferences.all["default_fragment"] as? Int
        var originalAccount: Account? = null
        var fixtureAccount: Account? = null
        val identity = "sip:screen-lifecycle-fixture@example.invalid"
        try {
            onCore {
                val core = coreContext.core
                originalAccount = core.defaultAccount
                assertTrue(core.accountList.none { it.params.identityAddress?.asStringUriOnly() == identity })
                val params = core.createAccountParams().apply {
                    identityAddress = Factory.instance().createAddress(identity)
                    serverAddress = Factory.instance().createAddress("sip:example.invalid")
                    isRegisterEnabled = false
                    pushNotificationAllowed = false
                }
                fixtureAccount = core.createAccount(params)
                core.addAccount(fixtureAccount)
                core.defaultAccount = fixtureAccount
            }
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                listOf(
                    R.id.historyListFragment,
                    R.id.conversationsListFragment,
                    R.id.contactsListFragment,
                    R.id.historyListFragment,
                    R.id.conversationsListFragment,
                ).forEach { destination ->
                    scenario.onActivity { it.findNavController().navigate(destination) }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { assertEquals(destination, it.findNavController().currentDestination?.id) }
                    if (destination != R.id.contactsListFragment) {
                        val listId = if (destination == R.id.historyListFragment) R.id.history_list else R.id.conversations_list
                        onView(withId(listId)).check(matches(isDisplayed()))
                        scenario.onActivity { activity ->
                            val adapter = activity.findViewById<RecyclerView>(listId).adapter
                            when (adapter) {
                                is HistoryListAdapter -> assertNotNull(adapter.onNearEnd)
                                is ConversationsListAdapter -> assertNotNull(adapter.onNearEnd)
                                else -> throw AssertionError("Expected the contact-aware list adapter")
                            }
                        }
                    }
                }
                scenario.recreate()
                onView(withId(R.id.conversations_list)).check(matches(isDisplayed()))
                scenario.onActivity { activity ->
                    val adapter = activity.findViewById<RecyclerView>(R.id.conversations_list).adapter as ConversationsListAdapter
                    assertNotNull(adapter.onNearEnd)
                }
            }
        } finally {
            onCore {
                coreContext.core.defaultAccount = originalAccount
                fixtureAccount?.let { coreContext.core.removeAccount(it) }
            }
            val editor = preferences.edit()
            if (originalDestination == null) editor.remove("default_fragment") else editor.putInt("default_fragment", originalDestination)
            editor.commit()
        }
    }

    private fun onCore(block: () -> Unit) {
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        coreContext.postOnCoreThread {
            try { block() } catch (error: Throwable) { failure = error } finally { done.countDown() }
        }
        assertTrue("Core queue timed out", done.await(15, TimeUnit.SECONDS))
        failure?.let { throw AssertionError(it) }
    }
}
