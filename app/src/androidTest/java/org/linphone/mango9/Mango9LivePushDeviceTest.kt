// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.SystemClock
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.R
import org.linphone.ui.main.MainActivity
import org.linphone.ui.main.crm.adapter.Mango9MessagingTab
import org.linphone.ui.main.crm.fragment.Mango9LiveChatFragment
import org.linphone.ui.main.crm.viewmodel.Mango9LiveChatViewModel

/** Physical-device notification/tap check using existing history. Never sends a visitor message. */
@RunWith(AndroidJUnit4::class)
class Mango9LivePushDeviceTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context = instrument.targetContext

    private fun waitFor(check: () -> Boolean) {
        val start = SystemClock.elapsedRealtime()
        while (!check()) {
            if (SystemClock.elapsedRealtime() - start > 30000) fail("Live Chat notification did not reach its expected state")
            SystemClock.sleep(100)
        }
    }

    private fun leaf(fragment: Fragment): Fragment = fragment.childFragmentManager.primaryNavigationFragment?.let(::leaf) ?: fragment

    private fun activity(check: (MainActivity) -> Boolean): Boolean {
        var result = false
        instrument.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().firstOrNull()?.let { result = check(it) }
        }
        return result
    }

    @Test fun remoteLiveNotificationArrivesAndOpensConversation() {
        val id = InstrumentationRegistry.getArguments().getString("livePushId")
        org.junit.Assume.assumeTrue("Requires an explicitly requested push transport check", id != null)
        java.util.UUID.fromString(id)
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { }
        val manager = context.getSystemService(NotificationManager::class.java)
        val title = "Mango9 Live Chat delivery check"
        android.util.Log.i("Mango9LivePushCheck", "Ready for the remote notification")
        waitFor { manager.activeNotifications.any { it.notification.extras.getString(Notification.EXTRA_TITLE) == title } }
        val notification = manager.activeNotifications.last { it.notification.extras.getString(Notification.EXTRA_TITLE) == title }
        try {
            notification.notification.contentIntent.send()
            waitFor { activity { main ->
                val host = main.supportFragmentManager.findFragmentById(R.id.main_nav_container) as? NavHostFragment
                val screen = host?.let(::leaf)
                if (screen !is Mango9LiveChatFragment) return@activity false
                val state = ViewModelProvider(screen)[Mango9LiveChatViewModel::class.java].current()
                state.authorized && state.error == null && state.conversation?.optString("id") == id
            } }
        } finally { manager.cancel(notification.tag, notification.id) }
    }

    @Test fun liveNotificationOpensExactConversationWithNewAndExistingActivity() {
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { }
        val owner = requireNotNull(Mango9SessionStore(context).captureSession())
        val rows = runBlocking { Mango9LiveChatApi(context).request(owner, "list") }.getJSONArray("conversations")
        assertTrue("A physical-phone check requires existing Live Chat history", rows.length() > 0)
        val id = rows.getJSONObject(0).getString("id")
        val manager = context.getSystemService(NotificationManager::class.java)
        val testTitle = "Live Chat notification check"
        repeat(2) { iteration ->
            val push = requireNotNull(
                Mango9MessagePush.parse(
                    mapOf(
                "event" to "livechat.message", "conversation_id" to id,
                "crm_id" to owner.session.crmId, "sip_identity" to requireNotNull(owner.session.sipIdentity),
                "name" to testTitle, "body" to "Device routing check $iteration",
            )
                )
            )
            Mango9MessagePushCoordinator.receive(context, push)
            waitFor { manager.activeNotifications.any { it.notification.extras.getString(Notification.EXTRA_TITLE) == testTitle } }
            val notification = manager.activeNotifications.last {
                it.notification.extras.getString(Notification.EXTRA_TITLE) == testTitle
            }
            try {
                assertEquals("Device routing check $iteration", notification.notification.extras.getString(Notification.EXTRA_TEXT))
                notification.notification.contentIntent.send()
                waitFor {
                    activity { main ->
                    val host = main.supportFragmentManager.findFragmentById(R.id.main_nav_container) as? NavHostFragment
                    val screen = host?.let(::leaf)
                    if (screen !is Mango9LiveChatFragment) return@activity false
                    val state = ViewModelProvider(screen)[Mango9LiveChatViewModel::class.java].current()
                    state.authorized && state.error == null && state.conversation?.optString("id") == id
                }
                }
                assertEquals(owner.session.sipIdentity, Mango9SessionStore(context).load()?.sipIdentity)
                activity { main -> main.onBackPressedDispatcher.onBackPressed(); true }
                waitFor {
                    activity { main ->
                    val tabs = main.findViewById<TabLayout>(R.id.mango9_messages_tabs)
                    tabs?.getTabAt(tabs.selectedTabPosition)?.tag == Mango9MessagingTab.Live
                }
                }
            } finally { manager.cancel(notification.tag, notification.id) }
        }
    }
}
