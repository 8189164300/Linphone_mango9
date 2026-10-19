/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.notifications.CallNotificationIntent
import org.linphone.notifications.NotificationBroadcastReceiver
import org.linphone.notifications.NotificationsManager
import org.linphone.ui.call.CallActivity

/** Synthetic, unregistered call targets. No real calls, accounts, or production messages. */
@RunWith(AndroidJUnit4::class)
class Mango9CallNotificationInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun notification(id: Int) = NotificationsManager.Notifiable(id, UUID.randomUUID().toString()).apply {
        remoteAddress = "sip:same-peer@example.invalid"
        localIdentity = "sip:account-$id@example.invalid"
    }

    @Test fun answerIntentsRemainDistinctForTwoAccountsCallingTheSamePeer() {
        val manager = coreContext.notificationsManager
        val first = notification(500)
        val second = notification(501)
        val one = manager.getCallAnswerPendingIntent(first)
        val two = manager.getCallAnswerPendingIntent(second)
        try {
            assertNotEquals(one, two)
            assertEquals(one, manager.getCallAnswerPendingIntent(first))
            if (Build.VERSION.SDK_INT >= 31) assertTrue(one.isImmutable)
            assertTrue(one.isActivity)
        } finally { one.cancel(); two.cancel() }
    }

    @Test fun hangupActionsDoNotOverwriteAnotherCallsExtras() {
        val manager = coreContext.notificationsManager
        val first = notification(500)
        val second = notification(501)
        val one = manager.getCallDeclinePendingIntent(first)
        val two = manager.getCallDeclinePendingIntent(second)
        try {
            assertNotEquals(one, two)
            val delivered = sendInertBroadcast(one)
            assertEquals(500, delivered.getIntExtra(NotificationsManager.INTENT_NOTIF_ID, -1))
            assertEquals(first.callTarget, CallNotificationIntent.read(delivered)?.token)
            assertFalse(delivered.hasExtra(NotificationsManager.INTENT_REMOTE_SIP_URI))
        } finally { one.cancel(); two.cancel() }
    }

    @Test fun speakerActionsHavePerCallIdentityAndCannotCollideWithHangup() {
        val manager = coreContext.notificationsManager
        val first = notification(500)
        val second = notification(501)
        val one = manager.getCallToggleSpeakerPendingIntent(first)
        val two = manager.getCallToggleSpeakerPendingIntent(second)
        val hangup = manager.getCallDeclinePendingIntent(first)
        try {
            assertNotEquals(one, two)
            assertNotEquals(one, hangup)
            val delivered = sendInertBroadcast(one)
            assertEquals(first.callTarget, CallNotificationIntent.read(delivered)?.token)
            assertEquals(CallNotificationIntent.SPEAKER, CallNotificationIntent.read(delivered)?.operation)
        } finally { one.cancel(); two.cancel(); hangup.cancel() }
    }

    @Test fun openNotificationsUseDistinctIntentFilterIdentityNotJustExtras() {
        val one = CallNotificationIntent.target(Intent(context, CallActivity::class.java), "first", CallNotificationIntent.OPEN)
        val two = CallNotificationIntent.target(Intent(context, CallActivity::class.java), "second", CallNotificationIntent.OPEN)
        assertFalse(one.filterEquals(two))
        assertTrue(one.filterEquals(Intent(one).putExtra("ActiveCall", true)))
        val answer = CallNotificationIntent.target(Intent(one), "first", CallNotificationIntent.ANSWER)
        assertFalse(one.filterEquals(answer))
    }

    @Test fun answerTargetIsConsumedAndNewIntentCanProvideAnotherTarget() {
        val intent = CallNotificationIntent.target(Intent(), "first", CallNotificationIntent.ANSWER)
        val first = CallNotificationIntent.consumeActivityTarget(intent)
        assertEquals("first", first?.token)
        assertEquals(CallNotificationIntent.ANSWER, first?.operation)
        assertNull(CallNotificationIntent.consumeActivityTarget(intent))
        val newIntent = CallNotificationIntent.target(Intent(), "second", CallNotificationIntent.ANSWER)
        assertEquals("second", CallNotificationIntent.consumeActivityTarget(newIntent)?.token)
    }

    @Test fun restoringOldActionDoesNotReplayButNewCallAndNewOperationStillWork() {
        val original = CallNotificationIntent.target(Intent(), "first", CallNotificationIntent.ANSWER)
        val savedIdentity = requireNotNull(CallNotificationIntent.read(original)).identity
        val restored = Intent(original)
        assertNull(CallNotificationIntent.consumeActivityTarget(restored, savedIdentity))
        assertNull(restored.data)
        val newCall = CallNotificationIntent.target(Intent(), "second", CallNotificationIntent.ANSWER)
        assertEquals("second", CallNotificationIntent.consumeActivityTarget(newCall, savedIdentity)?.token)
        val newOperation = CallNotificationIntent.target(Intent(), "first", CallNotificationIntent.OPEN)
        assertEquals(CallNotificationIntent.OPEN, CallNotificationIntent.consumeActivityTarget(newOperation, savedIdentity)?.operation)
    }

    @Test fun activityCannotConsumeBroadcastActionsOrLegacyAddressOnlyAnswer() {
        val broadcast = CallNotificationIntent.target(Intent(), "first", CallNotificationIntent.HANGUP)
        assertNull(CallNotificationIntent.consumeActivityTarget(broadcast))
        assertNotNull(CallNotificationIntent.read(broadcast))
        assertNull(
            CallNotificationIntent.consumeActivityTarget(
                Intent().apply {
            putExtra("AnswerIncomingCall", true)
            putExtra("Caller", "sip:same-peer@example.invalid")
        }
            )
        )
    }

    @Test fun malformedOrForeignTargetsAreRejected() {
        val invalid = listOf(
            "https://notification/answer/token", "mango9-call://other/answer/token",
            "mango9-call://notification/answer", "mango9-call://notification/answer/token/extra",
            "mango9-call://notification/other/token", "mango9-call://notification/answer/token?q=x",
            "mango9-call://notification/answer/token#fragment"
        )
        for (uri in invalid) assertNull(uri, CallNotificationIntent.read(Intent().setData(Uri.parse(uri))))
    }

    @Test fun opaqueTokensRoundTripWithoutPuttingCallDetailsIntoTheUri() {
        val token = UUID.randomUUID().toString()
        val intent = CallNotificationIntent.target(Intent(), token, CallNotificationIntent.OPEN)
        assertEquals(token, CallNotificationIntent.read(intent)?.token)
        assertEquals("mango9-call://notification/open/$token", intent.dataString)
        assertThrows(IllegalArgumentException::class.java) {
            CallNotificationIntent.target(Intent(), null, CallNotificationIntent.OPEN)
        }
    }

    private fun sendInertBroadcast(pendingIntent: PendingIntent): Intent {
        val done = CountDownLatch(1)
        var received: Intent? = null
        pendingIntent.send(context, 0, null, { _, intent, _, _, _ ->
            received = intent
            done.countDown()
        }, Handler(Looper.getMainLooper()))
        assertTrue("Inert call action completed", done.await(15, TimeUnit.SECONDS))
        assertEquals(NotificationBroadcastReceiver::class.java.name, received?.component?.className)
        return requireNotNull(received)
    }
}
