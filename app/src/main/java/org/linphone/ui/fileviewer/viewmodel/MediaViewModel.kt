/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.ui.fileviewer.viewmodel

import org.linphone.mango9.Mango9NativeAttachment

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.SystemClock
import android.view.Surface
import androidx.annotation.UiThread
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.linphone.core.tools.Log
import org.linphone.mango9.Mango9PlaybackProgress
import org.linphone.ui.GenericViewModel
import org.linphone.utils.Event
import org.linphone.utils.FileUtils
import org.linphone.utils.TimestampUtils
import org.linphone.R

class MediaViewModel
    @UiThread
    internal constructor(
        private val elapsedRealtime: () -> Long,
        private val routePlayer: (MediaPlayer) -> AutoCloseable,
        private val createPlayer: () -> MediaPlayer
    ) : GenericViewModel() {
    @UiThread
    internal constructor(createPlayer: () -> MediaPlayer) : this(SystemClock::elapsedRealtime, {
        AutoCloseable {}
    }, createPlayer)

    @UiThread
    internal constructor(clock: () -> Long, createPlayer: () -> MediaPlayer) : this(clock, {
        AutoCloseable {}
    }, createPlayer)

    @UiThread
    constructor() : this(SystemClock::elapsedRealtime, {
        org.linphone.mango9.Mango9MediaPlaybackRoute(org.linphone.LinphoneApplication.coreContext.context, it)
    }, ::MediaPlayer)

    companion object {
        private const val TAG = "[Media ViewModel]"
        private const val PREPARE_TIMEOUT_MS = 15_000L
    }

    val path = MutableLiveData<String?>()

    val fileName = MutableLiveData<String>()

    val fullScreenMode = MutableLiveData<Boolean>()

    val isImage = MutableLiveData<Boolean>()

    val isVideo = MutableLiveData<Boolean>()

    val isAudio = MutableLiveData<Boolean>()

    val isMediaPlaying = MutableLiveData<Boolean>()

    // While a seek is pending, the pause control must still cancel requested playback.
    val isPlaybackRequested = MutableLiveData(false)

    val duration = MutableLiveData<Int>()

    val formattedDuration = MutableLiveData<String>()

    val position = MutableLiveData<Int>()

    val isPreparing = MutableLiveData(false)

    val isPrepared = MutableLiveData(false)

    val isSeeking = MutableLiveData(false)

    val hasPlaybackError = MutableLiveData(false)

    val videoSizeChangedEvent: MutableLiveData<Event<Pair<Int, Int>>> by lazy {
        MutableLiveData()
    }

    val changeFullScreenModeEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    // MediaPlayer is not thread-safe. Creation, callbacks and position reads all
    // stay on the main looper; preparation itself is asynchronous.
    private var mediaPlayer: MediaPlayer? = null
    private var mediaRoute: AutoCloseable? = null
    private var filePath: String? = null
    private var surface: Surface? = null
    private var playWhenReady = false
        set(value) {
            field = value
            isPlaybackRequested.value = value
        }
    private var pendingPosition = 0
    private var activeSeek: Int? = null
    private var queuedSeek: Int? = null
    private var replayFromStart = false
    private var cleared = false
    private var prepareTimeoutJob: Job? = null
    private var seekTimeoutJob: Job? = null
    private var loadedMimeType: String? = null
    private var updatePositionJob: Job? = null
    private val progress = Mango9PlaybackProgress()

    private var nativeFile: org.linphone.mango9.Mango9NativeFileLease? = null

    fun retainNativeFile(lease: org.linphone.mango9.Mango9NativeFileLease?) {
        if (nativeFile == null) nativeFile = lease else lease?.close()
    }

    override fun onCleared() {
        cleared = true
        releasePlayback()
        nativeFile?.close()
        nativeFile = null
        surface = null
        super.onCleared()
    }

    @UiThread
    fun loadFile(file: String, initialPosition: Int = 0, mimeType: String? = null, displayName: String? = null) {
        val name = displayName?.takeIf { it.isNotBlank() } ?: FileUtils.getNameFromFilePath(file)
        val mime = Mango9NativeAttachment.mimeType(file, name, mimeType)
        if (cleared || (file == filePath && loadedMimeType == mime)) return
        releasePlayback()
        filePath = file
        loadedMimeType = mime
        pendingPosition = initialPosition.coerceAtLeast(0)
        progress.resetForUserAction(pendingPosition)
        replayFromStart = false
        position.value = pendingPosition
        duration.value = 0
        formattedDuration.value = TimestampUtils.durationToString(0)
        hasPlaybackError.value = false
        isImage.value = false
        isVideo.value = false
        isAudio.value = false
        path.value = null
        fileName.value = name
        when (FileUtils.getMimeType(mime)) {
            FileUtils.MimeType.Image -> {
                isImage.value = true
                path.value = file
            }
            FileUtils.MimeType.Video -> {
                isVideo.value = true
                initMediaPlayer()
            }
            FileUtils.MimeType.Audio -> {
                isAudio.value = true
                initMediaPlayer()
            }
            else -> {
                reportPlaybackError()
            }
        }
    }

    @UiThread
    fun toggleFullScreen(): Boolean {
        val newValue = fullScreenMode.value != true
        fullScreenMode.value = newValue
        return newValue
    }

    @UiThread
    fun togglePlayPause() {
        if (playWhenReady || isMediaPlaying.value == true) pause() else play()
    }

    @UiThread
    fun play() {
        if (cleared || hasPlaybackError.value == true || (isAudio.value != true && isVideo.value != true)) return
        if (!playWhenReady && isMediaPlaying.value != true) progress.resetForUserAction(pendingPosition)
        playWhenReady = true
        if (mediaPlayer == null) initMediaPlayer()
        startPreparedPlayer()
    }

    @UiThread
    fun pause() {
        // Also cancel a pending autoplay when onPause precedes onPrepared.
        playWhenReady = false
        stopUpdatePlaybackPosition()
        val player = mediaPlayer
        if (player != null && isPrepared.value == true && isMediaPlaying.value == true) {
            try {
                player.pause()
                if (activeSeek == null) {
                    pendingPosition = progress.sample(elapsedRealtime(), player.currentPosition)
                    position.value = pendingPosition
                }
            } catch (_: IllegalStateException) {
                failPlayer(player)
            }
        }
        isMediaPlaying.value = false
    }

    /** Called when the viewer stops; retain only a local position, not a decoder. */
    @UiThread
    fun releasePlayback() {
        pause()
        releasePlayer()
    }

    @UiThread
    fun retryPlayback() {
        if (cleared || hasPlaybackError.value != true) return
        hasPlaybackError.value = false
        pendingPosition = 0
        position.value = 0
        play()
    }

    private fun startPreparedPlayer() {
        val player = mediaPlayer ?: return
        if (!playWhenReady || isPrepared.value != true || isMediaPlaying.value == true || activeSeek != null) return
        // Do not play the audio track of a video before its view has a surface.
        if (isVideo.value == true && surface?.isValid != true) return
        try {
            if (replayFromStart) {
                // Reopen asynchronously instead of reusing an EOS audio sink.
                // Some native decoders report playing at zero indefinitely after
                // a near-end seek and replay. Invalidate the old callbacks first;
                // the new prepared callback still honors pause/release/surface.
                releasePlayer()
                initMediaPlayer()
                return
            }
            player.start()
            isMediaPlaying.value = true
            progress.start(elapsedRealtime(), player.currentPosition)
            startUpdatePlaybackPosition(player)
        } catch (_: IllegalStateException) {
            failPlayer(player)
        }
    }

    private fun releasePlayer() {
        prepareTimeoutJob?.cancel()
        prepareTimeoutJob = null
        seekTimeoutJob?.cancel()
        seekTimeoutJob = null
        activeSeek = null
        queuedSeek = null
        isSeeking.value = false
        stopUpdatePlaybackPosition()
        val previous = mediaPlayer
        // Invalidate queued callbacks before releasing the native instance.
        mediaPlayer = null
        isPreparing.value = false
        isPrepared.value = false
        isMediaPlaying.value = false
        mediaRoute?.close(); mediaRoute = null
        previous?.release()
    }

    private fun failPlayer(player: MediaPlayer) {
        if (mediaPlayer !== player || cleared) return
        playWhenReady = false
        releasePlayer()
        reportPlaybackError()
    }

    private fun reportPlaybackError() {
        hasPlaybackError.value = true
        fullScreenMode.value = false
        changeFullScreenModeEvent.value = Event(false)
        showRedToast(R.string.media_player_generic_error_toast, R.drawable.warning_circle)
    }

    @UiThread
    fun seekTo(position: Int, resumePlay: Boolean) {
        val player = mediaPlayer ?: return
        if (isPrepared.value != true) return
        try {
            // A seek is asynchronous. Keep the desired position and playback intent
            // separate from the native player's still-old position until it finishes.
            pause()
            if (mediaPlayer !== player) return
            pendingPosition = position.coerceIn(0, duration.value ?: 0)
            progress.resetForUserAction(pendingPosition)
            this.position.value = pendingPosition
            playWhenReady = resumePlay
            replayFromStart = false
            if (activeSeek != null) queuedSeek = pendingPosition else beginSeek(player, pendingPosition)
        } catch (_: IllegalStateException) {
            failPlayer(player)
        }
    }

    private fun beginSeek(player: MediaPlayer, target: Int) {
        if (target >= (duration.value ?: 0)) {
            // The last slider position means finished, with no frame left to play.
            // Keep the paused decoder out of an unnecessary EOS seek; replay opens
            // a fresh decoder. A queued request reaches here only after the
            // previous native seek has completed, so it cannot finish a newer seek.
            isSeeking.value = false
            finishPlayback()
            return
        }
        activeSeek = target
        isSeeking.value = true
        // Only one native seek is issued at a time. Intermediate slider requests
        // are coalesced locally, so callbacks always identify the operation we own.
        player.seekTo(target.toLong(), MediaPlayer.SEEK_CLOSEST)
        seekTimeoutJob?.cancel()
        seekTimeoutJob = viewModelScope.launch {
            delay(PREPARE_TIMEOUT_MS)
            if (mediaPlayer === player && activeSeek != null) failPlayer(player)
        }
    }

    private fun finishPlayback() {
        playWhenReady = false
        isMediaPlaying.value = false
        pendingPosition = 0
        progress.resetForUserAction(0)
        position.value = 0
        replayFromStart = true
        stopUpdatePlaybackPosition()
        fullScreenMode.value = false
        changeFullScreenModeEvent.value = Event(false)
    }

    @UiThread
    private fun initMediaPlayer() {
        val file = filePath ?: return
        if (cleared || mediaPlayer != null) return
        var candidate: MediaPlayer? = null
        try {
            val player = createPlayer()
            candidate = player
            mediaPlayer = player
            replayFromStart = false
            isMediaPlaying.value = false
            isPreparing.value = true
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            mediaRoute = routePlayer(player)
            player.setOnErrorListener { failed, _, _ ->
                failPlayer(failed)
                true
            }
            player.setOnPreparedListener { prepared ->
                if (mediaPlayer !== prepared || cleared) return@setOnPreparedListener
                prepareTimeoutJob?.cancel()
                prepareTimeoutJob = null
                isPreparing.value = false
                try {
                    val durationInMillis = prepared.duration.coerceAtLeast(0)
                    duration.value = durationInMillis
                    formattedDuration.value = TimestampUtils.durationToString(durationInMillis / 1000)
                    isPrepared.value = true
                    pendingPosition = pendingPosition.coerceIn(0, durationInMillis)
                    position.value = pendingPosition
                    prepared.setSurface(surface?.takeIf { it.isValid })
                    if (pendingPosition > 0) beginSeek(prepared, pendingPosition) else startPreparedPlayer()
                } catch (_: IllegalStateException) {
                    failPlayer(prepared)
                }
            }
            player.setOnSeekCompleteListener { source ->
                if (mediaPlayer !== source || cleared) return@setOnSeekCompleteListener
                val completedTarget = activeSeek ?: return@setOnSeekCompleteListener
                seekTimeoutJob?.cancel()
                seekTimeoutJob = null
                activeSeek = null
                val latest = queuedSeek
                queuedSeek = null
                try {
                    if (latest != null && latest != completedTarget) {
                        beginSeek(source, latest)
                    } else {
                        isSeeking.value = false
                        // Keep the user's target until real frames advance. Some
                        // native sinks report zero after seeking, then never move.
                        // This also preserves the target if playback is paused here.
                        pendingPosition = completedTarget.coerceIn(0, duration.value ?: 0)
                        position.value = pendingPosition
                        startPreparedPlayer()
                    }
                } catch (_: IllegalStateException) {
                    failPlayer(source)
                }
            }
            player.setOnCompletionListener { completed ->
                if (mediaPlayer !== completed || cleared) return@setOnCompletionListener
                // A queued seek may have moved away from a just-completed position.
                // Its callback decides whether to resume or remain paused. A newer
                // pause or replay must also win over an already queued completion.
                if (activeSeek != null || !playWhenReady || isMediaPlaying.value != true) return@setOnCompletionListener
                try {
                    if (!completed.isPlaying) finishPlayback()
                } catch (_: IllegalStateException) {
                    failPlayer(completed)
                }
            }
            player.setOnVideoSizeChangedListener { source, width, height ->
                if (mediaPlayer === source && !cleared && width > 0 && height > 0) {
                    videoSizeChangedEvent.value = Event(Pair(width, height))
                }
            }
            player.setDataSource(file)
            player.setSurface(surface?.takeIf { it.isValid })
            player.prepareAsync()
            prepareTimeoutJob = viewModelScope.launch {
                delay(PREPARE_TIMEOUT_MS)
                if (mediaPlayer === player && isPreparing.value == true) failPlayer(player)
            }
        } catch (error: Exception) {
            Log.e("$TAG Media preparation failed (${error.javaClass.simpleName})")
            if (candidate != null) failPlayer(candidate) else reportPlaybackError()
        }
    }

    private fun startUpdatePlaybackPosition(player: MediaPlayer) {
        stopUpdatePlaybackPosition()
        updatePositionJob = viewModelScope.launch {
            while (mediaPlayer === player && isMediaPlaying.value == true) {
                delay(250)
                if (mediaPlayer !== player || isMediaPlaying.value != true) break
                try {
                    val now = elapsedRealtime()
                    pendingPosition = progress.sample(now, player.currentPosition)
                    position.value = pendingPosition
                    if (progress.isStalled(now)) {
                        if (progress.claimRecovery()) {
                            // One recovery per explicit playback/seek action. Keep
                            // the original file, target and latest user intent;
                            // invalidate old callbacks before preparing anew.
                            releasePlayer()
                            initMediaPlayer()
                        } else {
                            // Never remain "playing" forever or loop allocations.
                            // The normal retry control allows an explicit retry.
                            failPlayer(player)
                        }
                        break
                    }
                } catch (_: IllegalStateException) {
                    failPlayer(player)
                    break
                }
            }
        }
    }

    private fun stopUpdatePlaybackPosition() {
        updatePositionJob?.cancel()
        updatePositionJob = null
    }

    @UiThread
    fun setMediaPlayerSurface(surface: Surface?) {
        this.surface = surface
        val player = mediaPlayer ?: return
        // During Preparing, setSurface and other side effects are deferred until
        // onPrepared. release remains valid from every native state.
        if (isPrepared.value == true) {
            try {
                player.setSurface(surface?.takeIf { it.isValid })
                startPreparedPlayer()
            } catch (_: IllegalStateException) {
                failPlayer(player)
            }
        }
    }
}
