/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import android.content.Intent
import android.graphics.Color
import android.media.MediaPlayer
import android.os.SystemClock
import android.view.TextureView
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager2.widget.ViewPager2
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.hamcrest.Matchers.allOf
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.R
import org.linphone.databinding.FileMediaViewerChildFragmentBinding
import org.linphone.ui.fileviewer.MediaViewerActivity
import org.linphone.ui.fileviewer.adapter.MediaListAdapter
import org.linphone.ui.fileviewer.fragment.MediaViewerFragment
import org.linphone.ui.fileviewer.viewmodel.MediaListViewModel
import org.linphone.ui.fileviewer.viewmodel.MediaViewModel
import org.linphone.ui.main.chat.model.FileModel

@RunWith(AndroidJUnit4::class)
class Mango9MediaPlaybackInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun corruptMediaFailsSafelyAndTheNextFileCanPlay() = withFixtures { valid, corrupt ->
        withPlayer { model, _ ->
            main { model.loadFile(corrupt.path); model.play() }
            await { model.hasPlaybackError.value == true }
            main {
                assertFalse(model.isPrepared.value == true)
                repeat(5) { model.play(); model.pause(); model.seekTo(300, true) }
                assertFalse(model.isMediaPlaying.value == true)
                model.loadFile(valid.path)
                model.play()
                // Deliberately pause before the asynchronous prepared callback.
                model.pause()
            }
            await { model.isPrepared.value == true }
            main {
                assertFalse(model.isMediaPlaying.value == true)
                assertFalse(model.hasPlaybackError.value == true)
                assertTrue((model.duration.value ?: 0) in 7_900..8_100)
                model.play()
            }
            await { (model.position.value ?: 0) > 0 }
        }
    }

    @Test fun preparationCannotResurrectReleasedOrReplacedPlayer() = withFixtures { valid, corrupt ->
        withPlayer { model, store ->
            main {
                model.loadFile(valid.path)
                model.play()
                model.releasePlayback()
                assertFalse(model.isPreparing.value == true)
                assertFalse(model.isPrepared.value == true)
                model.loadFile(corrupt.path)
                // The discarded player's error must not fail the new source.
                model.loadFile(valid.path)
            }
            await { model.isPrepared.value == true }
            main {
                assertFalse(model.hasPlaybackError.value == true)
                assertFalse(model.isMediaPlaying.value == true)
                model.play()
            }
            await { (model.position.value ?: 0) > 0 }
            main {
                store.clear()
                model.play()
                model.loadFile(corrupt.path)
            }
            assertStoppedFor(model, 600)
        }
    }

    @Test fun playbackSeekCompletionAndStopResumeKeepOneOwnedPlayer() = withFixtures { valid, _ ->
        withPlayer { model, _ ->
            main { model.loadFile(valid.path); model.play() }
            await { (model.position.value ?: 0) > 0 }
            main {
                repeat(10) { model.play(); model.loadFile(valid.path) }
                assertTrue(model.isPrepared.value == true)
                model.pause()
                model.seekTo(3_000, false)
            }
            assertStoppedFor(model, 600)
            main {
                assertEquals(3_000, model.position.value)
                model.releasePlayback()
                assertFalse(model.isPrepared.value == true)
                model.play()
            }
            await { model.isPrepared.value == true && (model.position.value ?: 0) >= 3_000 }
            main { model.pause(); model.seekTo(7_800, true) }
            await { model.isPrepared.value == true && model.isMediaPlaying.value == false && model.position.value == 0 }
            main { model.play() }
            await { model.isMediaPlaying.value == true && (model.position.value ?: 0) > 0 }
        }
    }

    @Test fun restoredPlaybackAndImmediateNearEndSeekStayLiveAcrossRepeatedPlayers() = withFixtures { valid, _ ->
        repeat(16) { iteration ->
            withPlayer { model, _ ->
                main { model.loadFile(valid.path) }
                await { model.isPrepared.value == true }
                main { model.seekTo(3_000, false) }
                await { model.isSeeking.value == false }
                main { model.releasePlayback(); model.play() }
                await { model.isPrepared.value == true }
                // Vary whether the restored seek is still pending when another seek arrives.
                SystemClock.sleep((iteration % 4) * 10L)
                main { model.pause(); model.seekTo(7_800, true) }
                await { model.isPrepared.value == true && model.isMediaPlaying.value == false && model.position.value == 0 }
                main { model.play() }
                await { model.isMediaPlaying.value == true && (model.position.value ?: 0) > 0 }
            }
        }
    }

    @Test fun rapidSeeksKeepOnlyTheLastPositionAndDoNotAutoplay() = withFixtures { valid, _ ->
        withPlayer { model, _ ->
            main { model.loadFile(valid.path) }
            await { model.isPrepared.value == true }
            main {
                for (target in 1_000..6_000 step 250) model.seekTo(target, false)
                assertTrue(model.isSeeking.value == true)
                assertFalse(model.isMediaPlaying.value == true)
                assertFalse(model.isPlaybackRequested.value == true)
                assertEquals(6_000, model.position.value)
            }
            await { model.isSeeking.value == false && (model.position.value ?: 0) in 5_900..6_100 }
            assertStoppedFor(model, 600)
            main { model.play() }
            await { model.isMediaPlaying.value == true && (model.position.value ?: 0) > 6_100 }
        }
    }

    @Test fun encodedVoiceRecordingsRestoreSeekAndReplayWithoutStalling() {
        val file = File.createTempFile("playback-regression-aac-", ".m4a", context.cacheDir)
        try {
            Mango9MediaTestFixtures.createAudio(file)
            repeat(8) { iteration ->
                withPlayer { model, _ ->
                    main { model.loadFile(file.path) }
                    await { model.isPrepared.value == true }
                    main {
                        assertTrue((model.duration.value ?: 0) in 3_900..4_200)
                        model.seekTo(1_000, false)
                    }
                    await { model.isSeeking.value == false }
                    main { model.releasePlayback(); model.play() }
                    await { model.isPrepared.value == true }
                    SystemClock.sleep((iteration % 4) * 10L)
                    main { model.seekTo(requireNotNull(model.duration.value) - 200, true) }
                    await { model.isSeeking.value == false && model.isMediaPlaying.value == false && model.position.value == 0 }
                    main { model.play() }
                    await { model.isMediaPlaying.value == true && (model.position.value ?: 0) > 0 }
                }
            }
        } finally { file.delete() }
    }

    @Test fun pausingAndReleasingDuringSeekPreserveTargetWithoutResurrectingPlayback() = withFixtures { valid, _ ->
        withPlayer { model, _ ->
            main { model.loadFile(valid.path) }
            await { model.isPrepared.value == true }
            main {
                model.seekTo(4_000, true)
                assertTrue(model.isPlaybackRequested.value == true)
                model.pause()
                assertFalse(model.isPlaybackRequested.value == true)
                model.releasePlayback()
                assertEquals(4_000, model.position.value)
                assertFalse(model.isSeeking.value == true)
                model.play()
                model.pause()
            }
            await { model.isPrepared.value == true && model.isSeeking.value == false }
            main { assertTrue((model.position.value ?: 0) in 3_900..4_100) }
            assertStoppedFor(model, 600)
            main { model.play() }
            await { model.isMediaPlaying.value == true && (model.position.value ?: 0) > 4_100 }
        }
    }

    @Test fun seekingToEndCanReplayAndPauseCancelsThePendingReplay() = withFixtures { valid, _ ->
        withPlayer { model, _ ->
            main { model.loadFile(valid.path) }
            await { model.isPrepared.value == true }
            main { model.seekTo(requireNotNull(model.duration.value), true) }
            await { model.isSeeking.value == false && model.position.value == 0 }
            main {
                assertFalse(model.isMediaPlaying.value == true)
                model.play()
                assertTrue(model.isPreparing.value == true)
                assertTrue(model.isPlaybackRequested.value == true)
                model.togglePlayPause()
                assertFalse(model.isPlaybackRequested.value == true)
            }
            await { model.isPrepared.value == true && model.isSeeking.value == false }
            assertStoppedFor(model, 600)
            main { model.play() }
            await { model.isMediaPlaying.value == true && (model.position.value ?: 0) > 0 }
        }
    }

    @Test fun nearEndPlaybackCompletesAndReplaysRepeatedly() = withFixtures { valid, _ ->
        withPlayer { model, _ ->
            main { model.loadFile(valid.path) }
            await { model.isPrepared.value == true }
            repeat(3) {
                main { model.seekTo(7_800, true) }
                await {
                    model.isSeeking.value == false && model.isMediaPlaying.value == false && model.position.value == 0
                }
                main { model.play() }
                await { model.isMediaPlaying.value == true && (model.position.value ?: 0) > 0 }
            }
        }
    }

    @Test fun queuedSeekAwayFromEndWinsOverCompletion() = withFixtures { valid, _ ->
        withPlayer { model, _ ->
            main { model.loadFile(valid.path) }
            await { model.isPrepared.value == true }
            main {
                model.seekTo(requireNotNull(model.duration.value), true)
                model.seekTo(2_000, false)
                assertFalse(model.isPlaybackRequested.value == true)
            }
            await { model.isSeeking.value == false && (model.position.value ?: 0) in 1_900..2_100 }
            assertStoppedFor(model, 600)
            main { assertTrue((model.position.value ?: 0) in 1_900..2_100); model.play() }
            await { model.isMediaPlaying.value == true && (model.position.value ?: 0) > 2_100 }
        }
    }

    @Test fun naturalCompletionReplayCanBeCancelledAndStartedAgain() = withFixtures { valid, _ ->
        withPlayer { model, _ ->
            main { model.loadFile(valid.path) }
            await { model.isPrepared.value == true }
            main { model.seekTo(7_800, true) }
            await { model.isSeeking.value == false && model.isMediaPlaying.value == false && model.position.value == 0 }
            main {
                model.play()
                // Replay prepares a fresh decoder and remains cancellable.
                assertTrue(model.isPreparing.value == true)
                assertFalse(model.isSeeking.value == true)
                assertTrue(model.isPlaybackRequested.value == true)
                model.togglePlayPause()
                assertFalse(model.isPlaybackRequested.value == true)
            }
            await { model.isPrepared.value == true && model.isSeeking.value == false }
            assertStoppedFor(model, 600)
            main { model.play() }
            await { model.isMediaPlaying.value == true && (model.position.value ?: 0) > 0 }
        }
    }

    @Test fun releasingPendingReplayDiscardsTheCompletedDecoderAndLateCallbacks() = withFixtures { valid, _ ->
        withPlayer { model, _ ->
            val playerField = MediaViewModel::class.java.getDeclaredField("mediaPlayer").apply { isAccessible = true }
            main { model.loadFile(valid.path) }
            await { model.isPrepared.value == true }
            main { model.seekTo(7_800, true) }
            await { model.isMediaPlaying.value == false && model.position.value == 0 && model.isSeeking.value == false }
            main {
                val completed = requireNotNull(playerField.get(model))
                model.play()
                assertNotSame(completed, requireNotNull(playerField.get(model)))
                assertTrue(model.isPreparing.value == true)
                model.releasePlayback()
                assertNull(playerField.get(model))
            }
            assertStoppedFor(model, 600)
            main {
                assertFalse(model.isPrepared.value == true)
                assertFalse(model.isPlaybackRequested.value == true)
                assertEquals(0, model.position.value)
                model.play()
            }
            await { model.isMediaPlaying.value == true && (model.position.value ?: 0) > 0 }
        }
    }

    @Test fun realAudioViewerSurvivesBackgroundAndRecreationWithVisibleControls() = withFixtures { valid, _ ->
        val intent = Intent(context, MediaViewerActivity::class.java).putExtra("path", valid.path)
        ActivityScenario.launch<MediaViewerActivity>(intent).use { scenario ->
            val first = viewerModel(scenario)
            await { first.isPrepared.value == true }
            onView(withId(R.id.play_pause_audio_playback)).check(matches(isDisplayed())).check(matches(isEnabled()))
            scenario.moveToState(Lifecycle.State.CREATED)
            main { assertFalse(first.isPrepared.value == true) }
            assertStoppedFor(first, 300)
            scenario.moveToState(Lifecycle.State.RESUMED)
            await { first.isPrepared.value == true && first.isMediaPlaying.value == true }
            scenario.recreate()
            val restored = viewerModel(scenario)
            await { restored.isPrepared.value == true }
            onView(withId(R.id.play_pause_audio_playback)).check(matches(isDisplayed()))
        }
    }

    @Test fun realPauseButtonCanCancelReplayWhileTheNewDecoderPrepares() = withFixtures { valid, _ ->
        val intent = Intent(context, MediaViewerActivity::class.java).putExtra("path", valid.path)
        ActivityScenario.launch<MediaViewerActivity>(intent).use { scenario ->
            val model = viewerModel(scenario)
            await { model.isPrepared.value == true }
            main { model.seekTo(requireNotNull(model.duration.value), true) }
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager.fragments.filterIsInstance<MediaViewerFragment>().single()
                val binding =
                    requireNotNull(DataBindingUtil.getBinding<FileMediaViewerChildFragmentBinding>(fragment.requireView()))
                binding.executePendingBindings()
                assertTrue(binding.playPauseAudioPlayback.isEnabled)
                binding.playPauseAudioPlayback.performClick()
                binding.executePendingBindings()
                assertTrue(model.isPreparing.value == true)
                assertTrue(model.isPlaybackRequested.value == true)
                assertTrue(binding.playPauseAudioPlayback.isEnabled)
                binding.playPauseAudioPlayback.performClick()
                assertFalse(model.isPlaybackRequested.value == true)
            }
            await { model.isPrepared.value == true }
            assertStoppedFor(model, 600)
            onView(withId(R.id.play_pause_audio_playback)).check(matches(isDisplayed())).check(matches(isEnabled()))
        }
    }

    @Test fun realCorruptViewerShowsAnErrorInsteadOfCrashing() = withFixtures { _, corrupt ->
        val intent = Intent(context, MediaViewerActivity::class.java).putExtra("path", corrupt.path)
        ActivityScenario.launch<MediaViewerActivity>(intent).use { scenario ->
            val model = viewerModel(scenario)
            await { model.hasPlaybackError.value == true }
            onView(withId(R.id.media_playback_error)).check(matches(isDisplayed()))
            onView(withId(R.id.media_playback_retry)).check(matches(isDisplayed()))
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            main { assertFalse(model.isMediaPlaying.value == true) }
        }
    }

    @Test fun realVideoRendersFramesAfterBackgroundAndRecreation() {
        val video = File.createTempFile("playback-regression-video-", ".mp4", context.cacheDir)
        try {
            Mango9MediaTestFixtures.createVideo(video)
            val intent = Intent(context, MediaViewerActivity::class.java).putExtra("path", video.path)
            ActivityScenario.launch<MediaViewerActivity>(intent).use { scenario ->
                val first = viewerModel(scenario)
                await { first.isPrepared.value == true && (first.position.value ?: 0) > 0 }
                assertVideoFrame(scenario)
                scenario.moveToState(Lifecycle.State.CREATED)
                main { assertFalse(first.isPrepared.value == true) }
                assertStoppedFor(first, 300)
                scenario.moveToState(Lifecycle.State.RESUMED)
                await { first.isPrepared.value == true && first.isMediaPlaying.value == true }
                assertVideoFrame(scenario)
                scenario.recreate()
                val restored = viewerModel(scenario)
                await { restored.isPrepared.value == true && restored.isMediaPlaying.value == true }
                assertVideoFrame(scenario)
            }
        } finally {
            video.delete()
        }
    }

    private fun assertVideoFrame(scenario: ActivityScenario<MediaViewerActivity>) {
        var texture: TextureView? = null
        scenario.onActivity { texture = it.findViewById(R.id.video_player) }
        await {
            val bitmap = texture?.bitmap
            if (bitmap == null) {
                false
            } else {
                try {
                    val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                    // Fixture is a red-tinted frame; a black/unrendered texture must not pass.
                    Color.red(pixel) > 100 && Color.red(pixel) > Color.blue(pixel)
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    @Test fun mediaPagerRefreshesDelayedFilesAndKeepsDistinctStableIds() = withFixtures { valid, corrupt ->
        val intent = Intent(context, MediaViewerActivity::class.java).putExtra("path", valid.path)
        ActivityScenario.launch<MediaViewerActivity>(intent).use { scenario ->
            val first = viewerModel(scenario)
            await { first.isPrepared.value == true }
            scenario.onActivity { activity ->
                val adapter = MediaListAdapter(activity)
                val a = fileModel(valid.path, "Aa")
                val b = fileModel(corrupt.path, "BB")
                val c = fileModel(valid.path, "")
                val d = fileModel(corrupt.path, "")
                // Old originalPath.hashCode IDs collide for both pairs.
                assertEquals("Aa".hashCode(), "BB".hashCode())
                adapter.submitList(listOf(a, b, c, d, c))
                val ids = (0 until adapter.itemCount).map(adapter::getItemId)
                assertEquals(5, ids.toSet().size)
                adapter.submitList(listOf(d, c, b, a, c))
                assertEquals(listOf(ids[3], ids[2], ids[1], ids[0], ids[4]), (0 until adapter.itemCount).map(adapter::getItemId))
                adapter.submitList(listOf(d))
                assertFalse(adapter.containsItem(ids[0]))
                val restoredAdapter = MediaListAdapter(activity)
                restoredAdapter.submitList(listOf(d))
                assertEquals(adapter.getItemId(0), restoredAdapter.getItemId(0))
                ViewModelProvider(activity)[MediaListViewModel::class.java].mediaList.value = listOf(d)
                listOf(a, b, c).forEach(FileModel::destroy)
            }
            val replacement = viewerModel(scenario, corrupt.path)
            await { replacement.hasPlaybackError.value == true }
            assertStoppedFor(first, 300)
            onView(withId(R.id.media_playback_error)).check(matches(isDisplayed()))
        }
    }

    private fun fileModel(path: String, originalPath: String) =
        FileModel(path, File(path).name, 0, 0, false, originalPath, false)

    @Test fun mediaPagerKeepsTheSelectedAttachmentAfterRecreation() = withFixtures { valid, corrupt ->
        val intent = Intent(context, MediaViewerActivity::class.java).putExtra("path", valid.path)
        ActivityScenario.launch<MediaViewerActivity>(intent).use { scenario ->
            val initial = viewerModel(scenario)
            await { initial.isPrepared.value == true }
            scenario.onActivity { activity ->
                ViewModelProvider(activity)[MediaListViewModel::class.java].mediaList.value = listOf(
                    fileModel(valid.path, valid.path),
                    fileModel(corrupt.path, corrupt.path)
                )
                activity.findViewById<ViewPager2>(R.id.media_view_pager).setCurrentItem(1, false)
            }
            val selected = viewerModel(scenario, corrupt.path)
            await { selected.hasPlaybackError.value == true }
            // ViewPager also retains the adjacent page's hidden error layout.
            onView(allOf(withId(R.id.media_playback_error), isDisplayed())).check(matches(isDisplayed()))
            scenario.recreate()
            val restored = viewerModel(scenario, corrupt.path)
            await { restored.hasPlaybackError.value == true }
            scenario.onActivity { activity ->
                assertEquals(1, activity.findViewById<ViewPager2>(R.id.media_view_pager).currentItem)
            }
            onView(allOf(withId(R.id.media_playback_error), isDisplayed())).check(matches(isDisplayed()))
        }
    }

    @Test fun rotationKeepsSelectionWhenTheInitialAttachmentHasLeftTheWindow() = withFixtures { valid, corrupt ->
        ActivityScenario.launch<MediaViewerActivity>(Intent(context, MediaViewerActivity::class.java).putExtra("path", valid.path)).use { scenario ->
            await { viewerModel(scenario).isPrepared.value == true }
            scenario.onActivity { activity ->
                val list = ViewModelProvider(activity)[MediaListViewModel::class.java]
                val first = fileModel(valid.path, valid.path)
                val second = fileModel(corrupt.path, corrupt.path)
                list.mediaList.value = listOf(first, second)
                activity.findViewById<ViewPager2>(R.id.media_view_pager).setCurrentItem(1, false)
                list.mediaList.value = listOf(second)
                first.destroy()
                assertEquals(corrupt.path, list.selectedFilePath())
            }
            await { viewerModel(scenario, corrupt.path).hasPlaybackError.value == true }
            scenario.recreate()
            await { viewerModel(scenario, corrupt.path).hasPlaybackError.value == true }
            scenario.onActivity { activity ->
                assertEquals(corrupt.path, ViewModelProvider(activity)[MediaListViewModel::class.java].selectedFilePath())
                assertEquals(0, activity.findViewById<android.view.ViewGroup>(R.id.toasts_area).childCount)
            }
        }
    }

    private fun viewerModel(scenario: ActivityScenario<MediaViewerActivity>, path: String? = null): MediaViewModel {
        var model: MediaViewModel? = null
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (model == null && SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { activity ->
                activity.supportFragmentManager.fragments.filterIsInstance<MediaViewerFragment>()
                    .firstOrNull { path == null || it.arguments?.getString("path") == path }?.let {
                    model = ViewModelProvider(it)[MediaViewModel::class.java]
                }
            }
            if (model == null) SystemClock.sleep(25)
        }
        return requireNotNull(model) { "The real media viewer did not open its fragment" }
    }

    private fun withPlayer(block: (MediaViewModel, ViewModelStore) -> Unit) {
        val store = ViewModelStore()
        lateinit var model: MediaViewModel
        main { model = MediaViewModel(); store.put("player", model) }
        try {
            block(model, store)
        } catch (failure: Throwable) {
            var state = ""
            main { state = playbackState(model) }
            throw AssertionError("${failure.message}\nSynthetic player state: $state", failure)
        } finally { main { store.clear() } }
    }

    /** Test-owned primitive state only: never capture a path, URL, credential or user media. */
    private fun playbackState(model: MediaViewModel): String {
        fun field(name: String): Any? = MediaViewModel::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }.get(model)
        val player = field("mediaPlayer") as? MediaPlayer
        val native = if (player != null && model.isPrepared.value == true) {
            runCatching {
            "position=${player.currentPosition},duration=${player.duration},playing=${player.isPlaying}"
        }.getOrElse { it.javaClass.simpleName }
        } else {
            "unprepared"
        }
        return "prepared=${model.isPrepared.value},preparing=${model.isPreparing.value},seeking=${model.isSeeking.value}," +
            "position=${model.position.value},duration=${model.duration.value},playing=${model.isMediaPlaying.value}," +
            "requested=${model.isPlaybackRequested.value},error=${model.hasPlaybackError.value}," +
            "activeSeek=${field("activeSeek")},queuedSeek=${field("queuedSeek")},pendingPosition=${field("pendingPosition")}," +
            "replay=${field("replayFromStart")},native=[$native]"
    }

    private fun withFixtures(block: (File, File) -> Unit) {
        val valid = File.createTempFile("playback-regression-", ".wav", context.cacheDir)
        val corrupt = File.createTempFile("playback-regression-corrupt-", ".wav", context.cacheDir)
        try {
            // Eight seconds of silent, mono PCM: no microphone, customer media or network.
            val pcmBytes = 8 * 8_000 * 2
            val data = ByteBuffer.allocate(44 + pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
            data.put("RIFF".toByteArray()).putInt(36 + pcmBytes).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000)
                .putShort(2).putShort(16).put("data".toByteArray()).putInt(pcmBytes)
            valid.writeBytes(data.array())
            corrupt.writeText("not a media file")
            block(valid, corrupt)
        } finally {
            valid.delete()
            corrupt.delete()
        }
    }

    private fun assertStoppedFor(model: MediaViewModel, millis: Long) {
        val until = SystemClock.elapsedRealtime() + millis
        do {
            main { assertFalse(model.isMediaPlaying.value == true) }
            SystemClock.sleep(25)
        } while (SystemClock.elapsedRealtime() < until)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        do {
            var ready = false
            main { ready = condition() }
            if (ready) return
            SystemClock.sleep(25)
        } while (SystemClock.elapsedRealtime() < deadline)
        fail("Timed out waiting for native media state")
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
