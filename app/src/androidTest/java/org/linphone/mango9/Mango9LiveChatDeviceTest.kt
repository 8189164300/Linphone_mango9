// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.tabs.TabLayout
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.R
import org.linphone.ui.main.MainActivity

/** Physical-phone smoke using the existing account. Opens history only; never sends or mutates server data. */
@RunWith(AndroidJUnit4::class)
class Mango9LiveChatDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
    private fun leaf(fragment: Fragment): Fragment = fragment.childFragmentManager.primaryNavigationFragment?.let(::leaf) ?: fragment
    private fun waitFor(timeout: Long = 15000, check: () -> Boolean) {
        val start = SystemClock.elapsedRealtime()
        while (!check()) { if (SystemClock.elapsedRealtime() - start > timeout) fail("Screen did not reach the expected state"); SystemClock.sleep(100) }
    }
    private fun image(name: String, scenario: ActivityScenario<MainActivity>) {
        var secure = false
        scenario.onActivity { secure = it.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0; it.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
        SystemClock.sleep(150)
        try {
        val output = File(context.getExternalFilesDir(null), "live-chat-verification").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap -> File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
        } finally { if (secure) scenario.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) } }
    }
    private fun keyboard(scenario: ActivityScenario<MainActivity>, name: String) {
        scenario.onActivity { activity ->
            val editor = descendants(activity.findViewById(android.R.id.content)).filterIsInstance<EditText>().first { it.isShown && (it.id == R.id.message || it.hint == "Message") }
            editor.requestFocus()
            (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(editor, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        SystemClock.sleep(900)
        scenario.onActivity { activity ->
            val editor = descendants(activity.findViewById(android.R.id.content)).filterIsInstance<EditText>().first { it.isShown && (it.id == R.id.message || it.hint == "Message") }
            val rect = Rect(); assertTrue(editor.getGlobalVisibleRect(rect)); assertEquals("Composer stays visible above native keyboard", editor.height, rect.height())
        }
        image(name, scenario)
        scenario.onActivity { activity ->
            (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
            activity.currentFocus?.clearFocus()
        }
        SystemClock.sleep(300)
    }
    @Test fun chatTabsSurviveRotationAndReturningFromNativeLiveHistory() {
        val owner = Mango9SessionStore(context).captureSession()
        assertNotNull("Sign in on the physical phone before this test", owner)
        File(context.getExternalFilesDir(null), "live-chat-verification").apply { mkdirs() }.resolve("account-scope.json").writeText(org.json.JSONObject().put("api", owner!!.session.crmApiBaseUrl).put("userId", owner.session.userId).put("parentClientId", owner.session.parentClientId).toString())
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            var ready = false
            waitFor { scenario.onActivity { activity -> ready = activity.supportFragmentManager.fragments.any { it is NavHostFragment } }; ready }
            scenario.onActivity { activity ->
                val host = activity.supportFragmentManager.fragments.filterIsInstance<NavHostFragment>().first()
                host.navController.navigate(R.id.conversationsListFragment)
            }
            for ((name, orientation) in listOf("portrait" to ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "landscape" to ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, "reverse" to ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE)) {
                scenario.onActivity { it.requestedOrientation = orientation }
                SystemClock.sleep(1400)
                waitFor {
                    scenario.onActivity { activity -> ready = activity.findViewById<TabLayout>(R.id.mango9_messages_tabs)?.tabCount == 4 }; ready
                }
                for (index in 0..3) {
                    scenario.onActivity { activity -> activity.findViewById<TabLayout>(R.id.mango9_messages_tabs).getTabAt(index)!!.select() }
                    SystemClock.sleep(600)
                    scenario.onActivity { activity ->
                        val tabs = activity.findViewById<TabLayout>(R.id.mango9_messages_tabs)
                        assertEquals(index, tabs.selectedTabPosition)
                        val labels = descendants(tabs).filterIsInstance<TextView>().filter { it.isShown && it.text == "Social" }.toList()
                        assertEquals("Social label is rendered once", 1, labels.size)
                        assertTrue(tabs.isShown); assertTrue(tabs.height > 0)
                        val bounds = Rect(); assertTrue(tabs.getGlobalVisibleRect(bounds)); assertEquals(tabs.height, bounds.height())
                        val panel = activity.findViewById<View>(R.id.mango9_messages_panel)
                        assertTrue("Inbox uses full screen width", panel.width >= activity.findViewById<View>(android.R.id.content).width * .9)
                        val compose = activity.findViewById<View>(R.id.new_conversation)
                        if (compose.isShown) assertTrue("New chat stays above bottom navigation", compose.bottom <= activity.findViewById<View>(R.id.bottom_nav_bar).top)
                    }
                    image("$name-tab-$index", scenario)
                    if (index < 3) {
                        var opened = false
                        scenario.onActivity { activity ->
                            val rows = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.mango9_messages_list)
                            if ((rows.adapter?.itemCount ?: 0) > 0 && rows.childCount > 0) opened = rows.getChildAt(0).performClick()
                        }
                        if (opened) {
                            waitFor { scenario.onActivity { activity -> ready = leaf(activity.supportFragmentManager.fragments.filterIsInstance<NavHostFragment>().first()) is org.linphone.ui.main.crm.fragment.Mango9MessagingConversationFragment }; ready }
                            SystemClock.sleep(700)
                            scenario.onActivity { activity ->
                                val editor = descendants(activity.findViewById(android.R.id.content)).filterIsInstance<EditText>().first { it.isShown && it.id == R.id.message }
                                val rect = Rect(); assertTrue(editor.getGlobalVisibleRect(rect)); assertEquals(editor.height, rect.height()); assertFalse(editor.hasFocus())
                                assertTrue(editor.imeOptions and android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI != 0)
                            }
                            image("$name-detail-$index", scenario)
                            if (name == "landscape") keyboard(scenario, "$name-keyboard-$index")
                            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                            waitFor { scenario.onActivity { ready = it.findViewById<TabLayout>(R.id.mango9_messages_tabs)?.selectedTabPosition == index }; ready }
                            image("$name-return-$index", scenario)
                        }
                    }
                    if (index == 3) {
                        var first = ""
                        waitFor(30000) {
                            scenario.onActivity { activity ->
                                val host = activity.supportFragmentManager.fragments.filterIsInstance<NavHostFragment>().first()
                                val fragment = leaf(host)
                                val state = androidx.lifecycle.ViewModelProvider(fragment)[org.linphone.ui.main.crm.viewmodel.Mango9LiveChatViewModel::class.java].current()
                                ready = !state.loading && (state.authorized || state.error != null)
                                first = state.conversations.firstOrNull()?.name.orEmpty()
                                if (ready) assertNull("Live inbox API should load", state.error)
                            }; ready
                        }
                        if (first.isNotBlank()) {
                            scenario.onActivity { activity ->
                                descendants(activity.findViewById(R.id.mango9_live_host)).filterIsInstance<TextView>().first { it.text.toString().removePrefix("● ") == first }.let { (it.parent as View).performClick() }
                            }
                            waitFor(30000) {
                                scenario.onActivity { activity ->
                                    val f = leaf(activity.supportFragmentManager.fragments.filterIsInstance<NavHostFragment>().first())
                                    ready = f is org.linphone.ui.main.crm.fragment.Mango9LiveChatFragment && androidx.lifecycle.ViewModelProvider(f)[org.linphone.ui.main.crm.viewmodel.Mango9LiveChatViewModel::class.java].current().authorized
                                }; ready
                            }
                            scenario.onActivity { activity ->
                                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                                val composer = descendants(content).filterIsInstance<EditText>().single { it.hint == "Message" }
                                val rect = Rect(); assertTrue(composer.getGlobalVisibleRect(rect)); assertEquals(composer.height, rect.height()); assertFalse(composer.hasFocus())
                            }
                            image("$name-live-detail", scenario)
                            if (name == "landscape") keyboard(scenario, "$name-live-keyboard")
                            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                            waitFor { scenario.onActivity { ready = it.findViewById<TabLayout>(R.id.mango9_messages_tabs)?.selectedTabPosition == 3 }; ready }
                            SystemClock.sleep(500)
                            image("$name-live-return", scenario)
                        }
                    }
                }
            }
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            SystemClock.sleep(900)
            image("live-inbox-final", scenario)
        }
    }
    @Test fun conversationSettingsHideTabsWithoutChangingServerData() {
        val preferences = Mango9ChatTabPreferences(context)
        val keys = listOf("team", "social", "live")
        val original = keys.associateWith(preferences::visible)
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
                fun settings(enabled: Boolean) {
                    scenario.onActivity { activity -> activity.supportFragmentManager.fragments.filterIsInstance<NavHostFragment>().first().navController.navigate(R.id.action_global_settingsFragment) }
                    SystemClock.sleep(500)
                    scenario.onActivity { activity ->
                        val root = leaf(activity.supportFragmentManager.fragments.filterIsInstance<NavHostFragment>().first()).requireView()
                        if (!root.findViewById<View>(R.id.conversation_settings).isShown) root.findViewById<View>(R.id.chat).performClick()
                    }
                    SystemClock.sleep(200)
                    scenario.onActivity { activity ->
                        for (id in listOf(R.id.mango9_team_enabled, R.id.mango9_social_enabled, R.id.mango9_live_enabled)) {
                            val control = activity.findViewById<android.widget.CompoundButton>(id)
                            assertTrue(control.isShown)
                            if (control.isChecked != enabled) control.performClick()
                        }
                        activity.supportFragmentManager.fragments.filterIsInstance<NavHostFragment>().first().navController.navigate(R.id.conversationsListFragment)
                    }
                    var count = 0
                    waitFor { scenario.onActivity { count = it.findViewById<TabLayout>(R.id.mango9_messages_tabs)?.tabCount ?: 0 }; count == if (enabled) 4 else 1 }
                    assertEquals(enabled, preferences.visible("live"))
                }
                settings(false)
                settings(true)
            }
        } finally { original.forEach { (key, value) -> preferences.set(key, value) } }
    }
    @Test fun liveControlsAndMetadataRemainBoundedWithNativeEmoji() {
        instrumentation.runOnMainSync {
            val themed = androidx.appcompat.view.ContextThemeWrapper(context, R.style.Theme_Linphone)
            val ui = org.linphone.ui.main.crm.fragment.Mango9LiveViews(themed)
            val column = ui.column()
            val emoji = ui.text("👩🏽‍💻 🇺🇸 ❤️ Hello", 16f); column.addView(emoji)
            val row = ui.row()
            val attach = ui.icon(R.drawable.paperclip, "Attach") {}; val mic = ui.icon(R.drawable.microphone, "Record") {}; val send = ui.icon(R.drawable.paper_plane_right, "Send") {}
            row.addView(attach); row.addView(EditText(themed), android.widget.LinearLayout.LayoutParams(0, -2, 1f)); row.addView(mic); row.addView(send); column.addView(row)
            for (widthDp in listOf(320, 640)) {
                val width = ui.dp(widthDp); column.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)); column.layout(0, 0, width, column.measuredHeight)
                assertTrue(emoji.height >= emoji.layout.height)
                for (control in listOf(attach, mic, send)) { assertTrue(control.width >= ui.dp(44)); assertTrue(control.height >= ui.dp(44)); assertTrue(control.right <= width) }
                assertTrue(mic.left > attach.right); assertEquals(attach.top, mic.top)
            }
        }
    }
}
