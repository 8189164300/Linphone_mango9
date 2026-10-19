/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.core.ConfigAssetInstaller
import org.linphone.core.CorePreferences
import org.linphone.core.CoreTaskDispatcher
import org.linphone.LinphoneApplication.Companion.coreContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import org.linphone.notifications.NotificationBroadcastReceiver
import org.linphone.notifications.NotificationsManager

/** Synthetic files and inert notifications only; never modifies accounts or real configurations. */
@RunWith(AndroidJUnit4::class)
class Mango9StartupSafetyInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun unchangedInstallDoesNotReopenPackagedAssets() = files { directory ->
        var opens = 0
        val installer = ConfigAssetInstaller(directory) { opens++; "asset:$it".byteInputStream() }
        installer.install("install-1")
        assertEquals(4, opens)
        installer.install("install-1")
        assertEquals(4, opens)
    }

    @Test fun newInstallRefreshesFactoryDefaultsButNeverUserConfig() = files { directory ->
        var generation = "old"
        val installer = ConfigAssetInstaller(directory) { "$generation:$it".byteInputStream() }
        installer.install("install-1")
        val user = File(directory, CorePreferences.CONFIG_FILE_NAME)
        user.writeText("user-owned account configuration")
        generation = "new"
        installer.install("install-2")
        assertEquals("new:linphonerc_factory", File(directory, "linphonerc").readText())
        assertEquals("user-owned account configuration", user.readText())
    }

    @Test fun deletedFactoryFileIsRepairedWithoutAUserConfigReset() = files { directory ->
        var opens = 0
        val installer = ConfigAssetInstaller(directory) { opens++; it.byteInputStream() }
        installer.install("install-1")
        val user = File(directory, CorePreferences.CONFIG_FILE_NAME).readText()
        assertTrue(File(directory, "assistant_linphone_default_values").delete())
        installer.install("install-1")
        assertEquals(7, opens)
        assertEquals(user, File(directory, CorePreferences.CONFIG_FILE_NAME).readText())
        assertEquals("assistant_linphone_default_values", File(directory, "assistant_linphone_default_values").readText())
    }

    @Test fun missingAssetCannotTruncateExistingFactoryOrPublishUpgradeMarker() = files { directory ->
        val installer = ConfigAssetInstaller(directory) { "old:$it".byteInputStream() }
        installer.install("install-1")
        val marker = File(directory, ".mango9-packaged-configs").readText()
        assertThrows(IOException::class.java) {
            ConfigAssetInstaller(directory) { throw IOException("fixture missing asset") }.install("install-2")
        }
        assertEquals("old:linphonerc_factory", File(directory, "linphonerc").readText())
        assertEquals(marker, File(directory, ".mango9-packaged-configs").readText())
    }

    @Test fun partialReadRollsBackAndNextStartupRetriesTheUpgrade() = files { directory ->
        ConfigAssetInstaller(directory) { "old:$it".byteInputStream() }.install("install-1")
        var closed = false
        assertThrows(IOException::class.java) {
            ConfigAssetInstaller(directory) {
                object : InputStream() {
                    var first = true

                    override fun read(): Int = throw IOException("fixture read failure")

                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        if (!first) throw IOException("fixture read failure")
                        first = false; bytes[offset] = 65; return 1
                    }

                    override fun close() { closed = true }
                }
            }.install("install-2")
        }
        assertTrue(closed)
        assertEquals("old:linphonerc_factory", File(directory, "linphonerc").readText())
        ConfigAssetInstaller(directory) { "new:$it".byteInputStream() }.install("install-2")
        assertEquals("new:linphonerc_factory", File(directory, "linphonerc").readText())
        assertEquals("old:linphonerc_default", File(directory, CorePreferences.CONFIG_FILE_NAME).readText())
    }

    @Test fun interruptedAtomicWriteRecoversUserConfiguration() = files { directory ->
        val installer = ConfigAssetInstaller(directory) { it.byteInputStream() }
        installer.install("install-1")
        val file = File(directory, CorePreferences.CONFIG_FILE_NAME)
        file.writeText("original user config")
        // Simulate the previous process dying before finishWrite/failWrite.
        AtomicFile(file).startWrite().use { it.write("partial replacement".toByteArray()) }
        installer.install("install-1")
        assertEquals("original user config", file.readText())
    }

    @Test fun failedFirstInstallNeverPublishesACompleteMarker() = files { directory ->
        assertThrows(IOException::class.java) {
            ConfigAssetInstaller(directory) {
                if (it == "assistant_linphone_default_values") throw IOException("fixture install failure")
                it.byteInputStream()
            }.install("install-1")
        }
        assertFalse(File(directory, ".mango9-packaged-configs").exists())
        ConfigAssetInstaller(directory) { it.byteInputStream() }.install("install-1")
        assertTrue(File(directory, ".mango9-packaged-configs").exists())
        assertEquals("assistant_third_party_default_values", File(directory, "assistant_third_party_default_values").readText())
    }

    @Test fun pendingCoreTasksUseTheWorkerLooperAndRemainCancellable() {
        val worker = HandlerThread("startup-fixture").apply { start() }
        val dispatcher = CoreTaskDispatcher(SystemClock::uptimeMillis)
        val ran = CountDownLatch(1)
        var observed: Looper? = null
        val cancelled = dispatcher.post { fail("Cancelled action ran") }
        dispatcher.post { observed = Looper.myLooper(); ran.countDown() }
        cancelled.cancel()
        try {
            assertEquals(1L, ran.count)
            val handler = Handler(worker.looper)
            dispatcher.start { task, time -> handler.postAtTime(task, time) }
            assertTrue(ran.await(5, TimeUnit.SECONDS))
            assertSame(worker.looper, observed)
            assertNotSame(Looper.getMainLooper(), observed)
        } finally { worker.quitSafely(); worker.join(5_000) }
    }

    @Test fun notificationReceiverFinishesItsAsyncBroadcastOnAnInvalidTarget() {
        val completed = CountDownLatch(1)
        // Recognized action but deliberately no remote address: exercises the
        // receiver's worker/finally path without touching a call or chat.
        val intent = Intent(context, NotificationBroadcastReceiver::class.java).apply {
            action = NotificationsManager.INTENT_HANGUP_CALL_NOTIF_ACTION
        }
        context.sendOrderedBroadcast(
            intent, null,
            object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { completed.countDown() }
        },
            Handler(Looper.getMainLooper()), 0, null, null
        )
        assertTrue("Broadcast was not completed", completed.await(5, TimeUnit.SECONDS))
    }

    @Test fun waitingForCoreDoesNotBlockMainAndCancellationPreventsLateUiWork() = runBlocking {
        val workerEntered = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val refreshed = AtomicBoolean(false)
        val workerTimedOut = AtomicBoolean(false)
        coreContext.postOnCoreThread {
            workerEntered.countDown()
            workerTimedOut.set(!releaseWorker.await(10, TimeUnit.SECONDS))
        }
        assertTrue(workerEntered.await(10, TimeUnit.SECONDS))
        val waiting = launch(Dispatchers.Main, start = CoroutineStart.UNDISPATCHED) {
            coreContext.awaitReady()
            refreshed.set(true)
        }
        try {
            // Main must stay responsive while the Core worker is unavailable.
            val heartbeat = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post { heartbeat.countDown() }
            assertTrue(heartbeat.await(3, TimeUnit.SECONDS))
            assertTrue(waiting.isActive)
            waiting.cancelAndJoin()
        } finally { releaseWorker.countDown(); waiting.cancelAndJoin() }
        coreContext.awaitReady()
        instrumentation.waitForIdleSync()
        assertFalse("Fixture released Core before cancellation could be checked", workerTimedOut.get())
        assertFalse("Cancelled screen must not refresh after Core resumes", refreshed.get())
    }

    private fun files(block: (File) -> Unit) {
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "startup-fixture-").toFile()
        try { block(directory) } finally { check(directory.deleteRecursively()) }
    }
}
