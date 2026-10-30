// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.ui.main.MainActivity
import org.linphone.ui.main.crm.fragment.Mango9SupervisorFragment
import org.linphone.ui.main.crm.viewmodel.Mango9SupervisorViewModel

/** Physical-device checks: no AI consent, AI prompt, voice transmission, or approval is performed. */
@RunWith(AndroidJUnit4::class)
class Mango9SupervisorDeviceTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context = instrument.targetContext

    private fun requested() = assumeTrue("Explicit physical-device check only", InstrumentationRegistry.getArguments().getString("supervisorDeviceCheck") == "1")

    private fun leaf(f: Fragment): Fragment = f.childFragmentManager.primaryNavigationFragment?.let(::leaf) ?: f

    private fun screen(block: (MainActivity, Fragment) -> Boolean): Boolean {
        var result = false
        instrument.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()?.let { main ->
                (main.supportFragmentManager.findFragmentById(R.id.main_nav_container) as? NavHostFragment)?.let {
                    result = block(main, leaf(it))
                }
            }
        }
        return result
    }

    private fun waitFor(check: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 45000
        while (!check()) {
            if (SystemClock.elapsedRealtime() > until) fail("Supervisor device check timed out"); SystemClock.sleep(100)
        }
    }

    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) {
        (0 until v.childCount).flatMap {
        views(v.getChildAt(it))
    }
    } else {
        emptyList()
    }

    private fun capture(name: String) {
        var secured = false
        screen { main, _ -> secured = main.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0; main.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE); true }
        try {
            SystemClock.sleep(500)
            instrument.uiAutomation.takeScreenshot()?.let { image ->
                File(context.cacheDir, "supervisor-$name.png").outputStream().use {
                    image.compress(Bitmap.CompressFormat.PNG, 100, it)
                }; image.recycle()
            }
        } finally { if (secured) screen { main, _ -> main.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE); true } }
    }

    @Test fun privacyLayoutAndOpeningNeverStartsVoice() {
        requested()
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            waitFor { screen { _, _ -> true } }
            val lease =
                requireNotNull(Mango9SessionStore(context).captureSession()) { "A signed-in test account is required" }
            assumeTrue("Does not change an existing user's consent", !Mango9SupervisorStore(context).accepted(lease.session))
            screen { main, _ ->
                val host = main.supportFragmentManager.findFragmentById(R.id.main_nav_container) as NavHostFragment
                host.navController.navigate(
                    R.id.action_global_mango9SupervisorFragment,
                    Bundle().apply {
                    Mango9NavigationSession.bind(this, lease)
                }
                ); true
            }
            waitFor {
                screen { _, fragment -> fragment is Mango9SupervisorFragment && !ViewModelProvider(fragment)[Mango9SupervisorViewModel::class.java].loading }
            }
            screen { _, fragment ->
                val model = ViewModelProvider(fragment)[Mango9SupervisorViewModel::class.java]
                assertTrue("Supervisor owner authorization failed: ${model.error}", model.authorized)
                assertFalse(model.accepted); assertFalse(model.voice.running); assertFalse(model.canSend()); assertFalse(model.canVoice()); assertNull(model.voice.error); true
            }
            capture("privacy-portrait")
            screen { main, _ -> main.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE; true }
            SystemClock.sleep(1400); capture("privacy-landscape")
            onView(withText("Not now")).perform(click())
            screen { main, fragment ->
                val mode = views(fragment.requireView()).first { it.contentDescription == "Supervisor voice or chat" }
                assertTrue(mode.performClick()); assertFalse(ViewModelProvider(fragment)[Mango9SupervisorViewModel::class.java].voice.running)
                main.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT; true
            }
            SystemClock.sleep(1200); onView(withText("Not now")).perform(click())
            capture("chat-portrait")
            assertFalse(Mango9SupervisorStore(context).accepted(lease.session))
            screen { _, fragment ->
                val model = ViewModelProvider(fragment)[Mango9SupervisorViewModel::class.java]
                assertFalse(model.voice.running); assertFalse(model.accepted); assertNull(model.voice.error); true
            }
        }
    }

    @Test fun mutedNativeAudioCyclesRestoreThePreviousRoute() {
        requested()
        assumeTrue(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            waitFor { screen { _, _ -> coreContext.isReady() } }
            var calls = -1; val ready = CountDownLatch(1)
            coreContext.postOnCoreThreadCancellable { core -> calls = core.callsNb; ready.countDown() }
            assertTrue(ready.await(3, TimeUnit.SECONDS)); assumeTrue("Never interrupt a phone call", calls == 0)
            val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val previousMode = manager.mode
            val sent = AtomicInteger(); val failure = AtomicReference<String?>()
            repeat(2) {
                val engine =
                    SupervisorAudio(context, { true }, { sent.incrementAndGet() }, { _, _ -> }, { failure.set("Native audio interrupted") })
                engine.muted = true
                try {
                    engine.start(); SystemClock.sleep(1100)
                    assertTrue("Native capture should start only in this explicit muted check", engine.capturing)
                    assertNull(failure.get()); assertEquals(0, sent.get())
                    instrument.sendStatus(
                        0,
                        Bundle().apply {
                        putString("nativeEchoCancellation", engine.echoCancellationEnabled.toString())
                    }
                    )
                } finally { engine.close() }
                assertFalse(engine.capturing); assertEquals(previousMode, manager.mode)
            }
        }
    }
}
