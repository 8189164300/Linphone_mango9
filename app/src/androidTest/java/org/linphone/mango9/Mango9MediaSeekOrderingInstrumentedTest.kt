/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import android.media.MediaPlayer
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.ui.fileviewer.viewmodel.MediaViewModel

@RunWith(AndroidJUnit4::class)
class Mango9MediaSeekOrderingInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun nativeRestoreAndNearEndSeekPreserveCompletionOrdering() {
        val file = File.createTempFile("seek-order-fixture-", ".wav", instrumentation.targetContext.cacheDir)
        val bytes = 8 * 8_000 * 2
        val data = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + bytes).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(bytes)
        file.writeBytes(data.array())
        val trace = ArrayDeque<String>()

        fun record(event: String) {
            if (trace.size >= 256) trace.removeFirst()
            trace.addLast("${SystemClock.elapsedRealtime()}: $event")
        }
        var playerId = 0
        try {
            repeat(40) { iteration ->
                val store = ViewModelStore()
                lateinit var model: MediaViewModel
                main {
                    record("ITERATION $iteration")
                    model = MediaViewModel { TracedPlayer(++playerId, ::record) }
                    store.put("fixture", model)
                }
                try {
                    main { model.loadFile(file.path); model.play() }
                    await { (model.position.value ?: 0) > 0 }
                    main { model.pause(); model.seekTo(3_000, false) }
                    await { model.isSeeking.value == false }
                    main { model.releasePlayback(); model.play() }
                    await { model.isPrepared.value == true }
                    SystemClock.sleep((iteration % 4) * 10L)
                    main { model.pause(); model.seekTo(7_800, true) }
                    await { model.isPrepared.value == true && model.isMediaPlaying.value == false && model.position.value == 0 }
                    main { model.play() }
                    await { model.isMediaPlaying.value == true && (model.position.value ?: 0) > 0 }
                } catch (failure: Throwable) {
                    var evidence = ""
                    main {
                        record(
                            "MODEL prepared=${model.isPrepared.value},seeking=${model.isSeeking.value}," +
                            "position=${model.position.value},playing=${model.isMediaPlaying.value}," +
                            "requested=${model.isPlaybackRequested.value},error=${model.hasPlaybackError.value}"
                        )
                        evidence = trace.joinToString("\n")
                    }
                    throw AssertionError("${failure.message}\nOwned synthetic player trace:\n$evidence", failure)
                } finally { main { store.clear() } }
            }
        } finally { file.delete() }
    }

    private class TracedPlayer(private val id: Int, private val record: (String) -> Unit) : MediaPlayer() {
        private var ready = false

        private fun trace(event: String) {
            val state = if (ready) {
                runCatching {
                "p=$currentPosition,playing=$isPlaying"
            }.getOrElse { "released" }
            } else {
                "unprepared"
            }
            record("player$id $event [$state]")
        }

        override fun start() { trace("start"); super.start(); trace("started") }

        override fun pause() { trace("pause"); super.pause(); trace("paused") }

        override fun seekTo(msec: Long, mode: Int) { trace("seek $msec"); super.seekTo(msec, mode) }

        override fun release() { trace("release"); ready = false; super.release() }

        override fun setOnPreparedListener(listener: OnPreparedListener?) {
            super.setOnPreparedListener { source ->
                ready = true
                trace("prepared callback")
                listener?.onPrepared(source)
            }
        }

        override fun setOnSeekCompleteListener(listener: OnSeekCompleteListener?) {
            super.setOnSeekCompleteListener { source ->
                trace("seek callback")
                listener?.onSeekComplete(source)
                trace("seek callback returned")
            }
        }

        override fun setOnCompletionListener(listener: OnCompletionListener?) {
            super.setOnCompletionListener { source ->
                trace("completion callback")
                listener?.onCompletion(source)
                trace("completion callback returned")
            }
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        do {
            var ready = false
            main { ready = condition() }
            if (ready) return
            SystemClock.sleep(25)
        } while (SystemClock.elapsedRealtime() < deadline)
        fail("Timed out waiting for owned player")
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
