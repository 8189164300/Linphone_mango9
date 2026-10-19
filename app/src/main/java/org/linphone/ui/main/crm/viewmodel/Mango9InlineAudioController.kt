/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.ui.main.crm.viewmodel

import androidx.annotation.UiThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.linphone.mango9.Mango9ChatMedia
import org.linphone.mango9.Mango9MediaAsset
import org.linphone.ui.fileviewer.viewmodel.MediaViewModel
import org.linphone.ui.main.crm.adapter.Mango9MessageListItem

internal data class Mango9AudioClip(val messageId: String, val index: Int, val media: Mango9ChatMedia)

internal data class Mango9AudioState(
    val clip: Mango9AudioClip? = null,
    val loading: Boolean = false,
    val requested: Boolean = false,
    val prepared: Boolean = false,
    val position: Int = 0,
    val duration: Int = 0,
    val error: Boolean = false,
    val focusDenied: Boolean = false,
)

/** One decoder and held file for the whole conversation, never one for every message. */
@UiThread
internal class Mango9InlineAudioController(
    private val scope: CoroutineScope,
    private val isCurrent: () -> Boolean,
    private val acquire: suspend (Mango9ChatMedia) -> Mango9MediaAsset,
    private val focus: Mango9InlineAudioFocus,
) {
    private val models = ViewModelStore()
    internal val player = ViewModelProvider(models, ViewModelProvider.NewInstanceFactory())[MediaViewModel::class.java]
    private val removeObservers = mutableListOf<() -> Unit>()

    private data class Listener(val clip: Mango9AudioClip, val update: (Mango9AudioState) -> Unit)

    private val listeners = mutableMapOf<Any, Listener>()
    private var selected: Mango9AudioClip? = null
    private var playerClip: Mango9AudioClip? = null
    private var bookmarkPosition = 0
    private var bookmarkDuration = 0
    private var asset: Mango9MediaAsset? = null
    private var download: Job? = null
    private var generation = 0L
    private var loading = false
    private var failed = false
    private var denied = false
    private var focused = false
    private var foreground = false
    private var disposed = false
    var state = Mango9AudioState()
        private set

    init {
        watch(player.position); watch(player.duration); watch(player.isPrepared)
        watch(player.isPreparing); watch(player.isPlaybackRequested); watch(player.hasPlaybackError)
    }

    private fun <T> watch(value: LiveData<T>) {
        val observer = Observer<T> { publish() }
        value.observeForever(observer)
        removeObservers += { value.removeObserver(observer) }
    }

    fun observe(clip: Mango9AudioClip, update: (Mango9AudioState) -> Unit): AutoCloseable {
        if (disposed) { update(Mango9AudioState()); return AutoCloseable {} }
        val key = Any()
        listeners[key] = Listener(clip, update)
        update(if (state.clip == clip) state else Mango9AudioState())
        return AutoCloseable {
            listeners.remove(key)
            // RecyclerView can detach/reattach the same row during a stable-ID diff.
            scope.launch {
                yield()
                if (!disposed && selected == clip && !visible(clip)) stop()
            }
        }
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (!value) stop()
    }

    private fun visible(clip: Mango9AudioClip) = listeners.values.any { it.clip == clip }

    private fun allowed(clip: Mango9AudioClip) = !disposed && foreground && isCurrent() && visible(clip)

    fun toggle(clip: Mango9AudioClip) {
        if (clip.media.kind != Mango9ChatMedia.Kind.Audio || !allowed(clip)) return
        if (selected == clip && loading) {
            stop()
            return
        }
        if (selected == clip && player.isPlaybackRequested.value == true) {
            // A user pause keeps the visible clip seekable. Lifecycle/offscreen
            // stops still release the decoder immediately.
            player.pause()
            abandonFocus()
            publish()
            return
        }
        if (selected != clip) {
            clear()
            selected = clip
        }
        failed = false
        denied = false
        if (asset != null) {
            start(clip)
            return
        }
        val request = ++generation
        loading = true
        publish()
        download = scope.launch {
            var loaded: Mango9MediaAsset? = null
            try {
                loaded = acquire(clip.media)
                if (request != generation || selected != clip || !allowed(clip)) return@launch
                asset = loaded
                loaded = null
                playerClip = null
                player.loadFile(requireNotNull(asset).file.absolutePath, bookmarkPosition)
                playerClip = clip
                loading = false
                if (player.hasPlaybackError.value != true) start(clip) else publish()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (request == generation && allowed(clip)) failed = true
            } finally {
                loaded?.close()
                if (request == generation) { loading = false; download = null; publish() }
            }
        }
    }

    private fun start(clip: Mango9AudioClip) {
        if (!allowed(clip)) return
        if (!requestFocus()) { publish(); return }
        if (player.hasPlaybackError.value == true) player.retryPlayback() else player.play()
        publish()
    }

    private fun requestFocus(): Boolean {
        if (focused) return true
        focused = focus.request { stop() }
        denied = !focused
        return focused
    }

    fun beginSeek(clip: Mango9AudioClip): Boolean {
        if (selected != clip || !allowed(clip) || player.isPrepared.value != true) return false
        val resume = player.isPlaybackRequested.value == true
        player.pause()
        return resume
    }

    fun seek(clip: Mango9AudioClip, position: Int, resume: Boolean) {
        if (selected != clip || !allowed(clip)) return
        // MediaViewModel pauses before seeking. Release the old focus first so
        // its synchronous pause observer cannot abandon a newly acquired one.
        player.pause()
        player.seekTo(position, resume && requestFocus())
        publish()
    }

    fun reconcile(items: List<Mango9MessageListItem>) {
        val clip = selected ?: return
        val item = items.firstOrNull { it.stableId == clip.messageId }
        if (!isCurrent() || item == null || Mango9ChatMedia.parse(item.files).getOrNull(clip.index) != clip.media) clear()
    }

    fun stop() {
        if (disposed) return
        generation++
        download?.cancel(); download = null
        loading = false
        player.releasePlayback()
        // A background/back-stack conversation must not reserve one of the
        // cache's bounded open-file slots. Keep a bookmark, reacquire on demand.
        playerClip = null
        asset?.close(); asset = null
        abandonFocus()
        publish()
    }

    fun clear() {
        selected = null
        playerClip = null
        stop()
        asset?.close(); asset = null
        bookmarkPosition = 0; bookmarkDuration = 0
        failed = false; denied = false
        publish()
    }

    private fun abandonFocus() {
        if (focused) { focused = false; focus.abandon() }
    }

    private fun publish() {
        if (disposed) return
        val previousRequested = state.requested
        val owned = selected != null && playerClip == selected
        val badMedia = owned && !loading && player.hasPlaybackError.value == true
        if (owned && !badMedia) {
            bookmarkPosition = (player.position.value ?: 0).coerceAtLeast(0)
            bookmarkDuration = (player.duration.value ?: 0).coerceAtLeast(0)
        }
        if (badMedia) {
            failed = true
            bookmarkPosition = 0; bookmarkDuration = 0
            // A retry must fetch new bytes, not repeatedly decode a poisoned hit.
            val invalid = asset
            asset = null
            playerClip = null
            invalid?.invalidate()
            invalid?.close()
        }
        val requested = owned && player.isPlaybackRequested.value == true
        if (badMedia || previousRequested && !requested) abandonFocus()
        state = if (selected == null) {
            Mango9AudioState()
        } else {
            Mango9AudioState(
            selected, loading || owned && requested && player.isPreparing.value == true, requested,
            owned && player.isPrepared.value == true, bookmarkPosition, bookmarkDuration, failed, denied,
        )
        }
        listeners.values.toList().forEach { it.update(if (it.clip == selected) state else Mango9AudioState()) }
    }

    fun dispose() {
        if (disposed) return
        clear()
        disposed = true
        removeObservers.forEach { it() }; removeObservers.clear()
        listeners.clear()
        models.clear()
    }
}
