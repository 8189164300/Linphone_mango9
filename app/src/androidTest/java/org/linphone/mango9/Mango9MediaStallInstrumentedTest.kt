// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.media.MediaPlayer
import android.os.SystemClock
import android.view.Surface
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.ui.fileviewer.viewmodel.MediaViewModel

/** Script the observed native playing-at-zero failure; never decode or upload user media. */
@RunWith(AndroidJUnit4::class)
class Mango9MediaStallInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun stalledRestoredSeekReopensOnceAtTheOriginalTarget() = fixture { f ->
        startStalled(f)
        main { f.now += 3_001 }
        await { f.players.size == 2 }
        main {
            assertTrue(f.players[0].released)
            assertEquals(3_000, f.model.position.value)
            val next = f.players[1]
            next.prepared()
            assertEquals(listOf(3_000L), next.seeks)
            next.seekComplete(3_000)
            assertTrue(next.playing)
            next.nativePosition = 3_500
        }
        await { f.model.position.value == 3_500 }
        main { assertEquals(2, f.players.size); assertFalse(f.model.hasPlaybackError.value == true) }
    }

    @Test fun pauseDuringRecoveryCancelsAutoplayButRetainsRequestedPosition() = fixture { f ->
        recover(f)
        main {
            f.model.pause()
            val next = f.players.last()
            next.prepared()
            next.seekComplete(3_000)
            assertFalse(next.playing)
            assertEquals(3_000, f.model.position.value)
            f.now += 20_000
        }
        SystemClock.sleep(350)
        main {
            assertEquals(2, f.players.size)
            assertFalse(f.model.isPlaybackRequested.value == true)
            f.model.play()
            assertTrue(f.players.last().playing)
        }
    }

    @Test fun releasingRecoveryDiscardsCallbacksAndCannotAutoplay() = fixture { f ->
        recover(f)
        main {
            val retired = f.players.last()
            f.model.releasePlayback()
            retired.prepared()
            retired.seekComplete(3_000)
            retired.completed()
            assertTrue(retired.released)
            assertFalse(f.model.isPrepared.value == true)
            assertFalse(f.model.isPlaybackRequested.value == true)
            f.now += 20_000
        }
        SystemClock.sleep(350)
        main { assertEquals(2, f.players.size) }
    }

    @Test fun newerSeekAndPauseWinOverTheRecoverySeek() = fixture { f ->
        recover(f)
        main {
            val next = f.players.last()
            next.prepared()
            f.model.seekTo(1_000, false)
            next.seekComplete(3_000)
            assertEquals(listOf(3_000L, 1_000L), next.seeks)
            next.seekComplete(1_000)
            assertEquals(1_000, f.model.position.value)
            assertFalse(next.playing)
            f.model.play()
            assertTrue(next.playing)
        }
    }

    @Test fun repeatedStallStopsWithRetryInsteadOfAnInfiniteDecoderLoop() = fixture { f ->
        recover(f)
        main {
            f.players.last().prepared()
            f.players.last().seekComplete(0)
            f.now += 3_001
        }
        await { f.model.hasPlaybackError.value == true }
        main {
            assertEquals(2, f.players.size)
            assertTrue(f.players.all { it.released })
            assertFalse(f.model.isMediaPlaying.value == true)
            assertFalse(f.model.isPlaybackRequested.value == true)
            f.now += 30_000
        }
        SystemClock.sleep(350)
        main {
            assertEquals(2, f.players.size)
            f.model.retryPlayback()
            val next = f.players.last()
            next.prepared()
            assertTrue(next.playing)
            assertFalse(f.model.hasPlaybackError.value == true)
        }
    }

    @Test fun recoveryUsesLastProgressAndDoesNotRepeatedlyRestartAfterProgress() = fixture { f ->
        main {
            f.model.loadFile("synthetic.m4a")
            f.model.play()
            f.players.single().prepared()
            f.players.single().nativePosition = 4_000
        }
        await { f.model.position.value == 4_000 }
        main { f.now += 3_001 }
        await { f.players.size == 2 }
        main {
            val next = f.players.last()
            next.prepared()
            assertEquals(listOf(4_000L), next.seeks)
            next.seekComplete(4_000)
            next.nativePosition = 4_500
        }
        await { f.model.position.value == 4_500 }
        main { f.now += 3_001 }
        await { f.model.hasPlaybackError.value == true }
        main { assertEquals(2, f.players.size) }
    }

    @Test fun normalProgressAndNewFileDoNotTriggerOldRecovery() = fixture { f ->
        recover(f)
        main {
            val old = f.players.last()
            f.model.loadFile("replacement.m4a")
            f.model.play()
            old.prepared()
            old.seekComplete(0)
            old.completed()
            val next = f.players.last()
            next.prepared()
            next.nativePosition = 1_000
            f.now += 2_500
        }
        await { f.model.position.value == 1_000 }
        main { f.players.last().nativePosition = 2_000; f.now += 2_500 }
        await { f.model.position.value == 2_000 }
        main { assertEquals(3, f.players.size); assertFalse(f.model.hasPlaybackError.value == true) }
    }

    private fun startStalled(f: Fixture) = main {
        f.model.loadFile("synthetic.m4a", 3_000)
        f.model.play()
        f.players.single().prepared()
        f.players.single().seekComplete(0)
        assertTrue(f.model.isMediaPlaying.value == true)
    }

    private fun recover(f: Fixture) {
        startStalled(f)
        main { f.now += 3_001 }
        await { f.players.size == 2 }
    }

    private fun fixture(block: (Fixture) -> Unit) {
        lateinit var f: Fixture
        main { f = Fixture() }
        try { block(f) } finally { main { f.store.clear() } }
    }

    private class Fixture {
        var now = 100L
        val store = ViewModelStore()
        val players = mutableListOf<ScriptedPlayer>()
        val model = MediaViewModel({ now }) { ScriptedPlayer().also { players.add(it) } }

        init { store.put("fixture", model) }
    }

    private class ScriptedPlayer : MediaPlayer() {
        var nativePosition = 0
        var playing = false
        var released = false
        val seeks = mutableListOf<Long>()
        private var preparedListener: OnPreparedListener? = null
        private var seekListener: OnSeekCompleteListener? = null
        private var completedListener: OnCompletionListener? = null

        override fun setDataSource(path: String) = Unit

        override fun setSurface(surface: Surface?) = Unit

        override fun prepareAsync() = Unit

        override fun getDuration(): Int = 8_000

        override fun getCurrentPosition(): Int = nativePosition

        override fun isPlaying(): Boolean = playing

        override fun start() { check(!released); playing = true }

        override fun pause() { check(!released); playing = false }

        override fun seekTo(msec: Long, mode: Int) { check(!released); seeks.add(msec) }

        override fun release() { check(!released); released = true; playing = false; super.release() }

        override fun setOnPreparedListener(listener: OnPreparedListener?) { preparedListener = listener }

        override fun setOnSeekCompleteListener(listener: OnSeekCompleteListener?) { seekListener = listener }

        override fun setOnCompletionListener(listener: OnCompletionListener?) { completedListener = listener }

        fun prepared() { preparedListener?.onPrepared(this) }

        fun seekComplete(position: Int) { nativePosition = position; seekListener?.onSeekComplete(this) }

        fun completed() { playing = false; completedListener?.onCompletion(this) }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        do {
            var ready = false
            main { ready = condition() }
            if (ready) return
            SystemClock.sleep(25)
        } while (SystemClock.elapsedRealtime() < deadline)
        fail("Timed out waiting for controlled decoder state")
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
