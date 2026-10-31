// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobInfo
import androidx.core.app.NotificationCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Mango9NotificationSafetyInstrumentedTest {
    @org.junit.Before fun allowSyntheticNotificationsOnTheTestDevice() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.uiAutomation.grantRuntimePermission(
                instrumentation.targetContext.packageName, android.Manifest.permission.POST_NOTIFICATIONS,
            )
        }
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val sessions = Mango9SessionStore(context) { }

    @Test fun oldNotificationCannotBindToIdenticalRelogin() = withSession { session ->
        val push = bound(session)
        val reconstructed = Mango9MessagePush.fromJson(push.toJson())!!
        assertNotNull(Mango9SessionStore(context).resolveNotificationLogin(reconstructed))
        sessions.remove(session.sipIdentity!!)
        assertNull(sessions.resolveNotificationLogin(reconstructed))
        sessions.save(session, persist = false)
        assertNull(sessions.resolveNotificationLogin(reconstructed))
        assertNotEquals(push.notificationLogin, bound(session).notificationLogin)
    }

    @Test fun tokenRefreshRetainsNotificationButUnboundOrForgedNonceDoesNotOpen() = withSession { session ->
        val push = bound(session)
        val lease = sessions.captureLogin(session.sipIdentity!!)!!
        assertTrue(sessions.replaceLoginTokens(lease, session, session.copy(accessToken = "new-access", refreshToken = "new-refresh")))
        assertNotNull(sessions.resolveNotificationLogin(push))
        assertNull(sessions.resolveNotificationLogin(push.copy(notificationLogin = null)))
        assertNull(sessions.resolveNotificationLogin(push.copy(notificationLogin = "wrong")))
        assertNull(sessions.resolveNotificationLogin(push.copy(crmId = "other-tenant")))
    }

    @Test fun remotePayloadCannotSupplyLocalOwnership() = withSession { session ->
        val local = bound(session)
        val remote = Mango9MessagePush.parse(
            mapOf(
                "event" to "sms.received", "phone" to "12025550123",
            "sip_identity" to session.sipIdentity!!, "notification_login" to local.notificationLogin!!
            )
        )!!
        assertNull(remote.notificationLogin)
        assertNull(sessions.resolveNotificationLogin(remote))
    }

    @Test fun publicationIsRejectedAfterRemovalAndDoesNotTouchReplacement() = withSession { session ->
        val push = bound(session)
        var calls = 0
        assertTrue(sessions.withNotificationLogin(push) { calls++ })
        sessions.save(session, persist = false)
        assertFalse(sessions.withNotificationLogin(push) { calls++ })
        assertEquals(1, calls)
    }

    @Test fun logoutCancelsOnlyTheRemovedLoginNotification() = withSession { session ->
        val manager = context.getSystemService(NotificationManager::class.java)
        assertTrue("Grant POST_NOTIFICATIONS to the owned emulator before running this test", manager.areNotificationsEnabled())
        val channel = "notification-safety-fixture"
        val otherTag = "mango9.message.other-synthetic-login"
        val push = bound(session)
        val tag = Mango9MessageNotifications.tag(push.notificationLogin!!)
        manager.createNotificationChannel(NotificationChannel(channel, "Synthetic fixture", NotificationManager.IMPORTANCE_LOW))
        try {
            val notification = NotificationCompat.Builder(context, channel)
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Synthetic fixture").build()
            manager.notify(tag, 42, notification); manager.notify(otherTag, 42, notification)
            awaitNotification { manager.activeNotifications.any { it.tag == tag } }
            sessions.remove(session.sipIdentity!!)
            awaitNotification { manager.activeNotifications.none { it.tag == tag } }
            assertTrue(manager.activeNotifications.any { it.tag == otherTag })
        } finally {
            manager.cancel(tag, 42); manager.cancel(otherTag, 42); manager.deleteNotificationChannel(channel)
        }
    }

    @Test fun delayedSentDismissalCannotRemoveANewerIncomingMessage() = withSession { session ->
        val manager = context.getSystemService(NotificationManager::class.java)
        assertTrue(manager.areNotificationsEnabled())
        val channel = "notification-safety-fixture"
        val push = bound(session); val login = push.notificationLogin!!; val tag = Mango9MessageNotifications.tag(login)
        manager.createNotificationChannel(NotificationChannel(channel, "Synthetic fixture", NotificationManager.IMPORTANCE_LOW))

        fun notification(title: String) = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title)
            .addExtras(Mango9MessageNotifications.instanceExtras()).build()
        val sent = notification("Sent fixture"); val incoming = notification("New fixture")
        try {
            sessions.withNotificationLogin(push) { Mango9MessageNotifications.publish(context, login, 43, sent) }
            awaitNotification { manager.activeNotifications.any { it.tag == tag && it.id == 43 } }
            sessions.withNotificationLogin(push) { Mango9MessageNotifications.publish(context, login, 43, incoming) }
            // Race the old timer against the enqueued replacement, before waiting for NMS.
            sessions.withNotificationLogin(push) { Mango9MessageNotifications.cancelIfUnchanged(context, login, 43, sent) }
            awaitNotification {
                manager.activeNotifications.any {
                    it.tag == tag && it.notification.extras.getString(android.app.Notification.EXTRA_TITLE) == "New fixture"
                }
            }
            sessions.withNotificationLogin(push) { Mango9MessageNotifications.cancelIfUnchanged(context, login, 43, sent) }
            assertTrue(manager.activeNotifications.any { it.tag == tag && it.id == 43 })
            sessions.withNotificationLogin(push) { Mango9MessageNotifications.cancelIfUnchanged(context, login, 43, incoming) }
            awaitNotification { manager.activeNotifications.none { it.tag == tag && it.id == 43 } }
        } finally { manager.cancel(tag, 43); manager.deleteNotificationChannel(channel) }
    }

    @Test fun cleanupJobIsPersistentNetworkConstrainedAndHasNoCredentialsInExtras() {
        val job = Mango9PushCleanupJob.jobInfo(context)
        assertTrue(job.isPersisted); assertEquals(JobInfo.NETWORK_TYPE_ANY, job.networkType)
        assertEquals(30_000L, job.initialBackoffMillis)
        assertEquals(JobInfo.BACKOFF_POLICY_EXPONENTIAL, job.backoffPolicy)
        assertTrue(job.extras.isEmpty); assertTrue(job.transientExtras.isEmpty)
        val service = context.packageManager.getServiceInfo(job.service, 0)
        assertEquals("android.permission.BIND_JOB_SERVICE", service.permission)
    }

    @Test fun schedulerFailureDoesNotLoseTheDurableCleanupOrKeepTheAccountLoggedIn() = withSession { session ->
        val before = sessions.pendingPushRemovals().map { it.id }.toSet()
        val unavailable = Mango9SessionStore(context) { error("fixture scheduler unavailable") }
        try {
            assertTrue(unavailable.removeIfCurrent(unavailable.captureLogin(session.sipIdentity!!)!!, journalPushCleanup = true))
            assertNull(sessions.load(session.sipIdentity))
            assertEquals(session, sessions.pendingPushRemovals().single { it.id !in before }.session)
        } finally {
            sessions.pendingPushRemovals().filter { it.id !in before }.forEach { sessions.acknowledgePushRemoval(it.id) }
        }
    }

    @Test fun systemCanBindAndFinishAnEmptyCleanupJob() {
        // Only run against the owned, account-free test app, never a pending real cleanup.
        assertTrue(sessions.captureLogins().isEmpty())
        assertTrue(sessions.pendingPushRemovals().isEmpty())
        val scheduler = context.getSystemService(android.app.job.JobScheduler::class.java)
        val job = Mango9PushCleanupJob.jobInfo(context)
        assertNull(scheduler.getPendingJob(job.id))
        try {
            Mango9PushCleanupJob.schedule(context)
            if (scheduler.getPendingJob(job.id) != null) {
                val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                    "cmd jobscheduler run -f ${context.packageName} ${job.id}",
                )
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
            }
            val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
            while (scheduler.getPendingJob(job.id) != null && android.os.SystemClock.elapsedRealtime() < deadline) {
                android.os.SystemClock.sleep(50)
            }
            assertNull("System did not finish the empty cleanup job", scheduler.getPendingJob(job.id))
        } finally { scheduler.cancel(job.id) }
    }

    @Test fun encryptedRemovalJournalSurvivesNewStoreAndPreservesRotatedDetachedTokens() = withSession { session ->
        val previousIds = sessions.pendingPushRemovals().map { it.id }.toSet()
        try {
            assertTrue(sessions.removeIfCurrent(sessions.captureLogin(session.sipIdentity!!)!!, journalPushCleanup = true))
            assertNull(sessions.load(session.sipIdentity))
            val other = Mango9SessionStore(context) { }
            val entry = other.pendingPushRemovals().single { it.id !in previousIds }
            assertEquals(session, entry.session)
            val renewed = session.copy(accessToken = "rotated-access", refreshToken = "rotated-refresh")
            other.updatePushRemoval(entry.id, session, renewed)
            assertEquals(renewed, sessions.pendingPushRemovals().single { it.id == entry.id }.session)
            assertNull(sessions.captureLogin(session.sipIdentity))
            other.acknowledgePushRemoval(entry.id)
            assertTrue(sessions.pendingPushRemovals().none { it.id == entry.id })
        } finally {
            sessions.pendingPushRemovals().filter { it.id !in previousIds }.forEach { sessions.acknowledgePushRemoval(it.id) }
        }
    }

    private fun awaitNotification(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 3000
        while (!condition() && android.os.SystemClock.elapsedRealtime() < deadline) android.os.SystemClock.sleep(20)
        assertTrue("Expected synthetic notification state", condition())
    }

    private fun bound(session: Mango9Session) = sessions.bindNotification(
        Mango9MessagePush(Mango9MessagePushTarget.Sms("12025550123", "Fixture"), session.sipIdentity, session.crmId),
        sessions.captureLogin(session.sipIdentity!!)!!,
    )!!

    private fun withSession(test: (Mango9Session) -> Unit) {
        val session =
            Mango9Session(
                "notification-safety-fixture", "https://crm.example.invalid", "https://crm.example.invalid/api/v2",
            "fixture-user", "fixture-tenant", "agent", "fixture@example.invalid", "Fixture", "fixture-access", "fixture-refresh",
            "https://chat.example.invalid/api", "wss://chat.example.invalid/socket", 1_900_000_000, "sip:notification-safety@crm.example.invalid"
            )
        sessions.save(session, persist = false)
        try { test(requireNotNull(sessions.load(session.sipIdentity!!))) } finally { sessions.remove(session.sipIdentity!!) }
    }
}
