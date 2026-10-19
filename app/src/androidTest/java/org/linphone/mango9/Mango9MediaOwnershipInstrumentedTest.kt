/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.ui.fileviewer.MediaViewerActivity
import org.linphone.ui.fileviewer.fragment.MediaViewerFragment
import org.linphone.ui.fileviewer.viewmodel.MediaViewModel

/** Real cache files, Activity, native AAC decoder and login generations. No network or microphone. */
@RunWith(AndroidJUnit4::class)
class Mango9MediaOwnershipInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun heldAudioSurvivesEvictionAndRotationThenClosesOnLogout() = fixture { f ->
        val asset = f.acquire()
        val intent = Intent(context, MediaViewerActivity::class.java)
        Mango9MediaCache.bindViewer(intent, asset, f.lease())
        ActivityScenario.launch<MediaViewerActivity>(intent).use { scenario ->
            val first = player(scenario)
            await { first.isPrepared.value == true && (first.position.value ?: 0) > 0 }
            f.cache.evictOwner("fixture")
            assertTrue(asset.file.exists())
            scenario.recreate()
            val restored = player(scenario)
            await { restored.isPrepared.value == true && (restored.position.value ?: 0) > 0 }
            assertTrue(asset.file.exists())
            main { f.sessions.remove(requireNotNull(f.a.sipIdentity)) }
            awaitDestroyed(scenario)
            assertFalse(asset.file.exists())
            main {
                assertFalse(restored.isPrepared.value == true)
                assertFalse(restored.isPlaybackRequested.value == true)
            }
        }
    }

    @Test fun rapidAccountRoundTripCannotKeepTheOldViewerOrReuseItsTicket() = fixture { f ->
        val asset = f.acquire()
        val intent = Intent(context, MediaViewerActivity::class.java)
        Mango9MediaCache.bindViewer(intent, asset, f.lease())
        ActivityScenario.launch<MediaViewerActivity>(intent).use { scenario ->
            val original = player(scenario)
            await { original.isPrepared.value == true }
            main {
                f.sessions.activate(f.b.sipIdentity)
                f.sessions.activate(f.a.sipIdentity)
            }
            awaitDestroyed(scenario)
            assertFalse(asset.file.exists())
            assertNull(Mango9MediaCache.takeViewer(requireNotNull(intent.extras), f.lease()))
            main { assertFalse(original.isPrepared.value == true) }
        }
    }

    @Test fun aRemovedAndReaddedLoginCannotOpenAnOldPendingTicket() = fixture { f ->
        val asset = f.acquire()
        val intent = Intent(context, MediaViewerActivity::class.java)
        Mango9MediaCache.bindViewer(intent, asset, f.lease())
        main {
            f.sessions.remove(requireNotNull(f.a.sipIdentity))
            f.sessions.save(f.a, persist = false, makeActive = true)
        }
        ActivityScenario.launch<MediaViewerActivity>(intent).use { scenario ->
            awaitDestroyed(scenario)
            assertFalse(asset.file.exists())
        }
    }

    @Test fun changedPathAndDuplicateClaimsAreRejectedWithoutLeakingFiles() = fixture { f ->
        val first = f.acquire()
        val intent = Intent(context, MediaViewerActivity::class.java)
        Mango9MediaCache.bindViewer(intent, first, f.lease())
        val changed = requireNotNull(intent.extras).apply { putString("path", "unrelated") }
        assertNull(Mango9MediaCache.takeViewer(changed, f.lease()))
        assertFalse(first.file.exists())
        val second = f.acquire()
        val valid = Intent(context, MediaViewerActivity::class.java)
        Mango9MediaCache.bindViewer(valid, second, f.lease())
        val claimed = Mango9MediaCache.takeViewer(requireNotNull(valid.extras), f.lease())
        try {
            assertSame(second, claimed)
            assertNull(Mango9MediaCache.takeViewer(requireNotNull(valid.extras), f.lease()))
            assertTrue(second.file.exists())
        } finally { claimed?.close() }
        assertFalse(second.file.exists())
    }

    @Test fun failedLaunchDiscardsItsPinAndRegularCloseDeletesTheViewerFile() = fixture { f ->
        val abandoned = f.acquire()
        val ignored = Intent(context, MediaViewerActivity::class.java)
        val ticket = Mango9MediaCache.bindViewer(ignored, abandoned, f.lease())
        Mango9MediaCache.discardViewer(ticket)
        assertFalse(abandoned.file.exists())
        val opened = f.acquire()
        val intent = Intent(context, MediaViewerActivity::class.java)
        Mango9MediaCache.bindViewer(intent, opened, f.lease())
        ActivityScenario.launch<MediaViewerActivity>(intent).use { scenario ->
            val model = player(scenario)
            await { model.isPrepared.value == true }
        }
        await { !opened.file.exists() }
    }

    @Test fun actualRequestWrapperRejectsLoginRemovalWhileDownloadingAndAllowsFreshLogin() = fixture { f ->
        val media =
            Mango9ChatMedia("fixture", "https://media.example.invalid/owned", "voice.m4a", "audio/mp4", Mango9ChatMedia.Kind.Audio)
        val oldLease = f.lease()
        f.onRequest = { main { f.sessions.remove(requireNotNull(f.a.sipIdentity)) } }
        val failed = runBlocking { runCatching { Mango9MediaCache.acquire(f.cache, f.sessions, media, oldLease) } }
        assertTrue(failed.isFailure)
        assertTrue(File(f.root, "open").listFiles().orEmpty().isEmpty())
        assertEquals(0, f.cache.trackedEntries)
        f.onRequest = null
        main { f.sessions.save(f.a, persist = false, makeActive = true) }
        runBlocking {
            Mango9MediaCache.acquire(f.cache, f.sessions, media, f.lease()).use {
                assertEquals("m4a", it.file.extension)
                assertTrue(it.file.length() > 0)
            }
        }
        assertTrue(File(f.root, "open").listFiles().orEmpty().isEmpty())
    }

    private inner class Fixture : AutoCloseable {
        val sessions = Mango9SessionStore(context)
        private val oldIdentity = sessions.activeIdentity
        val a = session("media-cache-a")
        val b = session("media-cache-b")
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "media-ownership-test-").toFile()
        val cache: Mango9MediaDiskCache
        var onRequest: (() -> Unit)? = null
        private val held = mutableListOf<Mango9MediaAsset>()

        init {
            val recording = File(root, "fixture.m4a")
            Mango9MediaTestFixtures.createAudio(recording)
            val data = recording.readBytes()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                // Intercept before DNS/network; the body is a generated native AAC fixture.
                onRequest?.invoke()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(data.toResponseBody()).build()
            }.build()
            cache = Mango9MediaDiskCache(File(root, "disk"), File(root, "open"), client)
            main {
                sessions.save(b, persist = false)
                sessions.save(a, persist = false, makeActive = true)
            }
        }

        fun lease() = requireNotNull(sessions.captureSession())

        fun acquire() = runBlocking {
            cache.acquire("fixture", Request.Builder().url("https://media.example.invalid/fixture").build(), "m4a") { true }
        }.also(held::add)

        override fun close() {
            main {
                sessions.remove(requireNotNull(a.sipIdentity))
                sessions.remove(requireNotNull(b.sipIdentity))
                sessions.activate(oldIdentity)
            }
            held.forEach(Mango9MediaAsset::close)
            cache.shutdown()
            root.deleteRecursively()
        }
    }

    private fun session(name: String) = Mango9Session(
        "fixture", "https://crm.example.invalid", "https://crm.example.invalid/api/v2", "fixture", "fixture", "agent",
        "$name@example.invalid", "Fixture", "fixture-access", "fixture-refresh", "", "", 1_900_000_000,
        "sip:$name@crm.example.invalid",
    )

    private fun fixture(block: (Fixture) -> Unit) { Fixture().use(block) }

    private fun player(scenario: ActivityScenario<MediaViewerActivity>): MediaViewModel {
        var model: MediaViewModel? = null
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (model == null && SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { activity ->
                activity.supportFragmentManager.fragments.filterIsInstance<MediaViewerFragment>().firstOrNull()?.let {
                    model = ViewModelProvider(it)[MediaViewModel::class.java]
                }
            }
            if (model == null) SystemClock.sleep(25)
        }
        return requireNotNull(model)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        do {
            var ready = false
            main { ready = condition() }
            if (ready) return
            SystemClock.sleep(25)
        } while (SystemClock.elapsedRealtime() < deadline)
        fail("Owned media state did not settle")
    }

    private fun awaitDestroyed(scenario: ActivityScenario<MediaViewerActivity>) {
        // ActivityScenario synchronizes with the main thread internally.
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (scenario.state != Lifecycle.State.DESTROYED && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(25)
        }
        assertEquals(Lifecycle.State.DESTROYED, scenario.state)
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
