/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.Core
import org.linphone.core.CoreFileTransferService
import org.linphone.core.CoreListenerStub
import org.linphone.core.CoreServiceSubscription
import org.linphone.ui.assistant.AssistantActivity

@RunWith(AndroidJUnit4::class)
class Mango9FileTransferServiceInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun sdkListenerIsAttachedAndRemovedOnlyOnTheCoreWorker() {
        val attached = CountDownLatch(1); val detached = CountDownLatch(1)
        val listener = object : CoreListenerStub() {}
        var attachLooper: Looper? = null; var detachLooper: Looper? = null
        var adds = 0; var removes = 0
        val subscription = CoreServiceSubscription<Core>(
            enqueue = { action -> coreContext.postOnCoreThread { action(it) } },
            attach = { core ->
                attachLooper = Looper.myLooper(); core.addListener(listener); adds++; attached.countDown()
            },
            detach = { core ->
                detachLooper = Looper.myLooper(); core.removeListener(listener); removes++; detached.countDown()
            },
        )
        try {
            instrumentation.runOnMainSync { subscription.start(); subscription.start() }
            assertTrue(attached.await(10, TimeUnit.SECONDS))
        } finally { instrumentation.runOnMainSync { subscription.close(); subscription.close() } }
        assertTrue(detached.await(10, TimeUnit.SECONDS)); coreBarrier()
        assertEquals(1, adds); assertEquals(1, removes)
        assertNotNull(attachLooper); assertNotSame(Looper.getMainLooper(), attachLooper)
        assertSame(attachLooper, detachLooper)
    }

    @Test fun realServiceCanStartAndStopWhileCoreIsBusyWithoutLateNotification() {
        val hadPermission = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= 33 && !hadPermission) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val service = Intent(context, CoreFileTransferService::class.java)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val releasedInTime = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            ActivityScenario.launch<AssistantActivity>(Intent(context, AssistantActivity::class.java)).use {
                coreBarrier()
                coreContext.postOnCoreThread {
                    entered.countDown(); releasedInTime.set(release.await(15, TimeUnit.SECONDS))
                }
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                try {
                    assertNotNull(context.startService(service))
                    await { foreground() }
                    val heartbeat = CountDownLatch(1)
                    Handler(Looper.getMainLooper()).post { heartbeat.countDown() }
                    assertTrue("Service start blocked main on the busy Core", heartbeat.await(2, TimeUnit.SECONDS))
                    assertTrue(context.stopService(service))
                    await { !running() }
                } finally { release.countDown() }
                coreBarrier(); instrumentation.waitForIdleSync()
                assertTrue("Busy-Core fixture expired before service teardown", releasedInTime.get())
                await {
                    context.getSystemService(NotificationManager::class.java).activeNotifications.none {
                        it.notification.channelId == "org_linphone_core_file_transfer_service_notification_channel"
                    }
                }
            }
        } finally {
            release.countDown(); context.stopService(service); coreBarrier()
            if (Build.VERSION.SDK_INT >= 33 && !hadPermission) {
                instrumentation.uiAutomation.revokeRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun running(): Boolean = context.getSystemService(ActivityManager::class.java)
        .getRunningServices(100).any { it.service.className == CoreFileTransferService::class.java.name }

    @Suppress("DEPRECATION")
    private fun foreground(): Boolean = context.getSystemService(ActivityManager::class.java)
        .getRunningServices(100).any { it.service.className == CoreFileTransferService::class.java.name && it.foreground }

    private fun coreBarrier() {
        val done = CountDownLatch(1); coreContext.postOnCoreThread { done.countDown() }
        assertTrue("Core did not settle", done.await(10, TimeUnit.SECONDS))
    }

    private fun await(check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (check()) return
            SystemClock.sleep(20)
        }
        assertTrue("Service lifecycle did not settle", check())
    }
}
