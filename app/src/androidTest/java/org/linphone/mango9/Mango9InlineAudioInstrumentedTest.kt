/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.appcompat.view.ContextThemeWrapper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.R
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.Account
import org.linphone.core.Core
import org.linphone.core.Factory
import org.linphone.ui.main.MainActivity
import org.linphone.ui.main.crm.adapter.Mango9AudioMessageView
import org.linphone.ui.main.crm.adapter.Mango9MessageAdapter
import org.linphone.ui.main.crm.adapter.Mango9MessageListItem
import org.linphone.ui.main.crm.viewmodel.Mango9InlineAudioController
import org.linphone.ui.main.crm.viewmodel.Mango9InlineAudioFocus
import org.linphone.ui.main.crm.viewmodel.Mango9PlatformInlineAudioFocus

/** Production inline controls/adapter/controller with native AAC, no microphone or remote account. */
@RunWith(AndroidJUnit4::class)
class Mango9InlineAudioInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun selectingAnAudioBubbleDoesNotPlayAndIncomingInkRemainsReadable() = fixture { f ->
        f.submit(listOf(sms("selection")))
        var selected = 0
        main {
            val row = f.row(0)
            row.setInk(android.graphics.Color.DKGRAY)
            assertEquals(android.graphics.Color.DKGRAY, row.name.currentTextColor)
            assertEquals(android.graphics.Color.DKGRAY, row.play.imageTintList?.defaultColor)
            row.selection { selected++ }
            val now = SystemClock.uptimeMillis()
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                val event = android.view.MotionEvent.obtain(now, now + 20, action, 24f, 24f, 0)
                row.dispatchTouchEvent(event)
                event.recycle()
            }
        }
        // View dispatches its click on the UI queue after ACTION_UP.
        await { selected == 1 }
        main {
            assertEquals(0, f.loads)
            val row = f.row(0)
            row.selection(null)
            row.play.performClick()
        }
        await { f.audio.state.prepared && f.loads == 1 }
    }

    @Test fun longSmsAndTeamHistoryOnlyLoadsTheTappedClipAndOwnsOnePlayer() = fixture { f ->
        f.submit((0 until 1_000).map { if (it % 2 == 0) sms("$it") else team("$it") })
        main {
            assertEquals(0, f.loads)
            assertFalse(f.audio.player.isPrepared.value == true)
            assertTrue(f.recycler.childCount < 20)
            f.row(0).play.performClick()
        }
        await { f.audio.state.requested && f.audio.state.position > 0 }
        val first = f.assets.single()
        main { f.row(1).play.performClick() }
        await { f.audio.state.clip?.messageId == "team:1" && f.audio.state.position > 0 }
        main {
            assertEquals(2, f.loads)
            assertFalse(first.file.exists())
            assertEquals(1, f.assets.count { it.file.exists() })
            assertEquals("0:00", f.row(0).elapsed.text.toString())
            assertEquals(context.getString(R.string.mango9_audio_play), f.row(0).play.contentDescription)
        }
    }

    @Test fun playPauseAccessibleSeekAndReplayWorkInBothChatTypes() = fixture { f ->
        listOf(sms("voice"), team("voice")).forEach { item ->
            f.submit(listOf(item))
            main { f.row(0).play.performClick() }
            await { f.audio.state.prepared && f.audio.state.requested && f.audio.state.position > 0 }
            main {
                val row = f.row(0)
                row.play.performClick()
                assertFalse(f.audio.state.requested)
                assertTrue(row.seek.isEnabled)
                assertTrue(
                    row.seek.performAccessibilityAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                    Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 2_000f) }
                    )
                )
            }
            await { !f.audio.player.isSeeking.value!! && f.audio.state.position in 1_700..2_300 }
            main { assertFalse(f.audio.state.requested); f.row(0).play.performClick() }
            await { f.audio.state.position > 2_300 }
            main {
                f.row(0).seek.performAccessibilityAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, f.audio.state.duration.toFloat()) }
                )
            }
            await { !f.audio.state.requested && f.audio.state.position == 0 }
            main { f.row(0).play.performClick() }
            await { f.audio.state.requested && f.audio.state.position > 0 }
            main { f.audio.clear() }
        }
        assertEquals(2, f.loads)
    }

    @Test fun scrollingAwayStopsAudioAndRebindingResumesWithoutAnotherDownload() = fixture(realCache = true) { f ->
        f.submit((0 until 100).map { sms("$it") })
        main { f.row(0).play.performClick() }
        await { f.audio.state.position >= 500 }
        main { f.recycler.scrollToPosition(99) }
        await { f.recycler.findViewHolderForAdapterPosition(99) != null && !f.audio.state.requested && !f.audio.state.prepared }
        val paused = f.audio.state.position
        main { assertTrue(f.assets.none { it.file.exists() }) }
        main { f.recycler.scrollToPosition(0) }
        await { f.recycler.findViewHolderForAdapterPosition(0)?.itemView?.isAttachedToWindow == true }
        main { assertFalse(f.audio.state.requested); f.row(0).play.performClick() }
        await { f.audio.state.position > paused && f.audio.state.requested }
        assertEquals(2, f.loads) // A fresh pin, not another network request.
        assertEquals(1, f.networkRequests.get())
    }

    @Test fun statusRefreshKeepsAudioButRemovingOrReplacingItsMessageStopsIt() = fixture { f ->
        val item = sms("stable")
        f.submit(listOf(item))
        main { f.row(0).play.performClick() }
        await { f.audio.state.position > 0 }
        val file = f.assets.single().file
        f.submit(listOf(item.copy(message = item.message.copy(status = 3))))
        main { assertTrue(f.audio.state.requested); assertTrue(file.exists()); assertEquals(1, f.loads) }
        f.submit(listOf(sms("stable", "replacement")))
        await { f.audio.state.clip == null && !file.exists() }
        main { f.row(0).play.performClick() }
        await { f.audio.state.position > 0 }
        f.submit(emptyList())
        await { f.audio.state.clip == null && f.assets.none { it.file.exists() } }
    }

    @Test fun backgroundAndInvalidAccountRejectLateDownloadCompletion() = fixture { f ->
        f.loader = {
            withContext(NonCancellable) { delay(150) }
            f.asset()
        }
        f.submit(listOf(sms("slow")))
        main { f.row(0).play.performClick(); f.audio.setForeground(false) }
        await { f.assets.isNotEmpty() }
        await { f.assets.none { it.file.exists() } }
        main { assertFalse(f.audio.state.requested); assertFalse(f.audio.state.loading); f.audio.setForeground(true) }
        main { f.row(0).play.performClick(); f.current = false; f.audio.clear() }
        await { f.assets.size >= 2 && f.assets.none { it.file.exists() } }
        main { assertFalse(f.audio.state.requested); assertFalse(f.audio.state.prepared) }
    }

    @Test fun focusLossPausesAndFocusDenialNeverStartsTheDecoder() = fixture { f ->
        f.submit(listOf(team("focus")))
        main { f.focus.granted = false; f.row(0).play.performClick() }
        await { f.audio.state.focusDenied }
        main { assertFalse(f.audio.state.requested); f.focus.granted = true; f.row(0).play.performClick() }
        await { f.audio.state.position > 0 }
        main { f.focus.lost?.invoke() }
        await { !f.audio.state.requested && !f.audio.state.prepared }
        main { assertTrue(f.focus.abandoned > 0); f.row(0).play.performClick() }
        await { f.audio.state.requested }
    }

    @Test fun corruptAudioOffersRetryAndUsesFreshBytes() = fixture { f ->
        f.loader = { f.asset(corrupt = f.loads == 1) }
        f.submit(listOf(sms("retry")))
        main { f.row(0).play.performClick() }
        await { f.audio.state.error }
        main {
            assertEquals(1, f.invalidations)
            assertFalse(f.assets.first().file.exists())
            assertEquals(context.getString(R.string.mango9_audio_retry), f.row(0).play.contentDescription)
            f.row(0).play.performClick()
        }
        await { f.audio.state.requested && f.audio.state.position > 0 && !f.audio.state.error }
        assertEquals(2, f.loads)
    }

    @Test fun largeTextControlsFitAndShowReadablePlaybackTimes() = fixture(fontScale = 1.8f) { f ->
        f.submit(listOf(sms("A long voice recording title for a team appointment")))
        main { f.row(0).play.performClick() }
        await { f.audio.state.prepared && f.audio.state.position > 0 }
        main {
            val row = f.row(0)
            row.play.performClick()
            assertTrue(row.play.width >= dp(48))
            assertTrue(row.seek.height >= dp(48))
            listOf(row.name, row.elapsed, row.duration).forEach {
                assertTrue(it.layout.getLineBottom(it.lineCount - 1) <= it.height - it.paddingTop - it.paddingBottom)
                assertTrue(it.right <= (it.parent as View).width)
            }
            assertTrue(row.width <= f.recycler.width - dp(24))
            snapshot(f.recycler, "mango9-inline-audio-large-text.png")
        }
    }

    @Test fun accessibleSeekReacquiresFocusBeforeResumingAndEndReleasesIt() = fixture { f ->
        f.submit(listOf(sms("seek-focus")))
        main { f.row(0).play.performClick() }
        await { f.audio.state.position > 0 }
        main {
            assertTrue(f.focus.held)
            assertTrue(
                f.row(0).seek.performAccessibilityAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 1_000f) }
            )
            )
            assertTrue(f.audio.state.requested)
            assertTrue(f.focus.held)
        }
        await { f.audio.state.position > 1_200 }
        main {
            assertTrue(f.focus.held)
            f.row(0).seek.performAccessibilityAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, f.audio.state.duration.toFloat()) }
            )
        }
        await { !f.audio.state.requested && f.audio.state.position == 0 }
        main { assertFalse(f.focus.held) }
    }

    @Test fun twoMessagesWithTheSameAudioUrlDoNotSharePlaybackControls() = fixture { f ->
        f.submit(listOf(sms("first", "shared"), sms("second", "shared")))
        main { f.row(0).play.performClick() }
        await { f.audio.state.position > 0 }
        main {
            assertEquals(context.getString(R.string.mango9_audio_play), f.row(1).play.contentDescription)
            f.row(1).play.performClick()
        }
        await { f.audio.state.clip?.messageId == "sms:second" && f.audio.state.position > 0 }
        main {
            assertEquals("0:00", f.row(0).elapsed.text.toString())
            assertEquals(1, f.assets.count { it.file.exists() })
        }
    }

    @Test fun backgroundClosesCachePinsAcrossMoreThanFourClips() = fixture(realCache = true) { f ->
        repeat(6) { index ->
            f.submit(listOf(sms("background-$index")))
            main { f.audio.setForeground(true); f.row(0).play.performClick() }
            await { f.audio.state.requested && f.audio.state.position > 0 }
            main {
                f.audio.setForeground(false)
                assertFalse(f.audio.state.prepared)
                assertTrue(f.assets.none { it.file.exists() })
            }
        }
        assertEquals(6, f.networkRequests.get())
    }

    @Test fun actualPlatformFocusCanPlayAndReleaseFromTheVisibleConversation() = fixture(realFocus = true) { f ->
        f.submit(listOf(team("platform")))
        main { f.row(0).play.performClick() }
        await { f.audio.state.requested && f.audio.state.position > 0 }
        main { f.audio.setForeground(false) }
        await { !f.audio.state.requested && !f.audio.state.prepared }
    }

    private class Focus : Mango9InlineAudioFocus {
        var granted = true
        var held = false
        var abandoned = 0
        var lost: (() -> Unit)? = null

        override fun request(onLost: () -> Unit): Boolean { lost = onLost; held = granted; return granted }

        override fun abandon() { abandoned++; held = false; lost = null }
    }

    private inner class Fixture(activity: MainActivity, bytes: ByteArray, fontScale: Float, realFocus: Boolean, realCache: Boolean) {
        @Volatile var current = true
        var loads = 0
        var invalidations = 0
        val assets = mutableListOf<Mango9MediaAsset>()
        private val data = bytes
        val networkRequests = AtomicInteger()
        private val cacheRoot = if (realCache) Files.createTempDirectory(context.cacheDir.toPath(), "inline-audio-cache-test-").toFile() else null
        private val cache = cacheRoot?.let { root ->
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                // Stop before DNS/network; exercise real cache pins with native AAC bytes.
                networkRequests.incrementAndGet()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(data.toResponseBody()).build()
            }.build()
            Mango9MediaDiskCache(File(root, "disk"), File(root, "open"), client)
        }
        private var closed = false
        val focus = Focus()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var loader: suspend (Mango9ChatMedia) -> Mango9MediaAsset = { media ->
            cache?.acquire("inline-fixture", Request.Builder().url(media.url).build(), "m4a") { current }
                ?.also(assets::add) ?: asset()
        }
        val audio = Mango9InlineAudioController(
            scope, { current }, { loads++; loader(it) },
            if (realFocus) Mango9PlatformInlineAudioFocus(activity) else focus
        )
        private val themed =
            ContextThemeWrapper(
                activity.createConfigurationContext(
                    Configuration(activity.resources.configuration).apply {
                this.fontScale = fontScale
            }
                ),
                R.style.Theme_Linphone
            )
        val recycler = RecyclerView(themed).apply {
            layoutManager = LinearLayoutManager(themed)
            setBackgroundColor(android.graphics.Color.WHITE)
        }
        private val adapter = Mango9MessageAdapter({}, {}).apply { setAudioController(audio) }
        private val dialog = Dialog(activity).apply {
            setContentView(recycler)
            show()
            window?.setLayout(dp(360).coerceAtMost(context.resources.displayMetrics.widthPixels - dp(16)), dp(600))
        }

        init { recycler.adapter = adapter; audio.setForeground(true) }

        fun submit(items: List<Mango9MessageListItem>) {
            main { adapter.submitList(items) }
            await { adapter.currentList == items && (items.isEmpty() || recycler.childCount > 0) }
            instrumentation.waitForIdleSync()
        }

        fun row(position: Int): Mango9AudioMessageView {
            fun find(view: View): Mango9AudioMessageView? {
                if (view is Mango9AudioMessageView) return view
                if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
                return null
            }
            return requireNotNull(find(requireNotNull(recycler.findViewHolderForAdapterPosition(position)).itemView))
        }

        fun asset(corrupt: Boolean = false): Mango9MediaAsset {
            val file = File.createTempFile("inline-audio-test-", ".m4a", context.cacheDir)
            file.writeBytes(if (corrupt) "not audio".toByteArray() else data)
            return Mango9MediaAsset(file, discard = { invalidations++ }) { file.delete() }.also(assets::add)
        }

        fun close() {
            if (closed) return
            closed = true
            audio.dispose(); dialog.dismiss(); recycler.adapter = null; scope.cancel(); assets.forEach(Mango9MediaAsset::close)
            cache?.shutdown()
            cacheRoot?.deleteRecursively()
        }
    }

    private fun fixture(fontScale: Float = 1f, realFocus: Boolean = false, realCache: Boolean = false, block: (Fixture) -> Unit) {
        listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CAMERA).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
        }
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val audio = File.createTempFile("inline-audio-source-", ".m4a", context.cacheDir)
        var oldDefault: Account? = null
        var account: Account? = null
        var fixture: Fixture? = null
        try {
            Mango9MediaTestFixtures.createAudio(audio)
            val bytes = audio.readBytes()
            core { c ->
                oldDefault = c.defaultAccount
                account = c.createAccount(
                    c.createAccountParams().apply {
                    identityAddress = Factory.instance().createAddress("sip:inline-audio@fixture.example.invalid")
                    serverAddress = Factory.instance().createAddress("sip:fixture.example.invalid")
                    isRegisterEnabled = false; pushNotificationAllowed = false
                }
                )
                c.addAccount(account); c.defaultAccount = account
            }
            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                scenario.onActivity { fixture = Fixture(it, bytes, fontScale, realFocus, realCache) }
                try { block(requireNotNull(fixture)) } finally { main { fixture?.close() } }
            }
        } finally {
            main { fixture?.close() }
            core { c ->
                c.defaultAccount = oldDefault
                account?.let { if (c.accountList.any { found -> found === it }) c.removeAccount(it) }
                c.config.sync()
            }
            audio.delete()
        }
    }

    private fun sms(id: String, source: String = id) = Mango9MessageListItem.Sms(Mango9SmsMessage(id, "2025550100", "", "2026-09-13T10:30:00Z", "", 2, true, "https://media.example.invalid/${android.net.Uri.encode(source)}.m4a"))

    private fun team(id: String) = Mango9MessageListItem.Team(Mango9ChatMessage(id, 1, "fixture", "", "2026-09-13T10:30:00Z", 2, "https://media.example.invalid/$id.m4a"), false, "Teammate")

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        do {
            var ready = false
            main { ready = condition() }
            if (ready) return
            SystemClock.sleep(25)
        } while (SystemClock.elapsedRealtime() < deadline)
        fail("Inline audio condition did not settle")
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private fun snapshot(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(context.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun core(action: (Core) -> Unit) {
        val done = CountDownLatch(1)
        var error: Throwable? = null
        coreContext.postOnCoreThread { c ->
            try {
            action(c)
        } catch (failure: Throwable) { error = failure } finally { done.countDown() }
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        error?.let { throw it }
    }
}
