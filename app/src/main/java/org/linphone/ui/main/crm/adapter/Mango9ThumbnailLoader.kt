/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.ui.main.crm.adapter

import android.content.Context
import android.graphics.Bitmap
import androidx.annotation.UiThread
import coil3.Image
import coil3.ImageLoader
import coil3.imageLoader
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowConversionToBitmap
import coil3.request.allowHardware
import coil3.request.maxBitmapSize
import coil3.request.transformations
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import coil3.transform.Transformation
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.linphone.mango9.Mango9ChatMedia
import org.linphone.mango9.Mango9MediaAsset
import org.linphone.mango9.Mango9MediaCache
import org.linphone.mango9.Mango9SessionLease
import org.linphone.mango9.Mango9SessionStore

/** One view-lifetime loader shared by conversation bubbles and its attachment browser. */
@UiThread
internal class Mango9ThumbnailLoader(
    private val context: Context,
    private val scope: CoroutineScope,
    private val imageLoader: ImageLoader,
    owner: String,
    private val isCurrent: () -> Boolean,
    private val acquire: suspend (Mango9ChatMedia) -> Mango9MediaAsset,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val prefix = "$CACHE_PREFIX$owner:"

    private class Target(val media: Mango9ChatMedia, val width: Int, val height: Int, val update: (Image?) -> Unit) {
        var flight: Flight? = null
    }

    private class Flight(val key: String) {
        val targets = mutableSetOf<Target>()
        var job: Job? = null
    }

    private val targets = mutableSetOf<Target>()
    private val flights = mutableMapOf<String, Flight>()
    internal val pendingRequests: Int get() = flights.size
    private var foreground = false
    private var invalid = false
    private var disposed = false
    private val watcher = scope.launch {
        Mango9SessionStore.contextChanges.collect { if (!isCurrent()) invalidate() }
    }

    fun observe(media: Mango9ChatMedia, width: Int, height: Int, update: (Image?) -> Unit): AutoCloseable {
        val target = Target(media, width.coerceIn(1, MAX_PIXELS), height.coerceIn(1, MAX_PIXELS), update)
        if (disposed || invalid || !isCurrent()) {
            update(null)
            return AutoCloseable {}
        }
        targets.add(target)
        start(target)
        return AutoCloseable { targets.remove(target); release(target) }
    }

    fun setForeground(value: Boolean) {
        if (disposed || foreground == value) return
        foreground = value
        if (value && !isCurrent()) invalidate()
        targets.toList().forEach {
            if (value) {
                start(it)
            } else { release(it); it.update(null) }
        }
    }

    private fun allowed() = !disposed && !invalid && foreground && isCurrent()

    private fun start(target: Target) {
        if (!allowed() || target.flight != null) return
        if (target.media.kind != Mango9ChatMedia.Kind.Image && target.media.kind != Mango9ChatMedia.Kind.Video) return
        val key = key(target)
        imageLoader.memoryCache?.get(MemoryCache.Key(key))?.image?.let {
            if (allowed() && target in targets) target.update(it)
            return
        }
        flights[key]?.let { flight -> target.flight = flight; flight.targets.add(target); return }
        val flight = Flight(key)
        flights[key] = flight
        flight.targets.add(target)
        target.flight = flight
        flight.job = scope.launch {
            var image: Image? = null
            try {
                val asset = acquire(target.media)
                try {
                    ensureActive()
                    if (!allowed()) return@launch
                    val request = ImageRequest.Builder(context)
                        .data(asset.file)
                        .size(target.width, target.height)
                        .maxBitmapSize(Size(MAX_PIXELS, MAX_PIXELS))
                        .scale(Scale.FIT)
                        .precision(Precision.EXACT)
                        .allowHardware(false)
                        // A preview is a still image, including animated/vector sources.
                        // Do not share a mutable drawable between recycled views.
                        .allowConversionToBitmap(true)
                        .transformations(StaticThumbnail)
                        // Only publish/cache after validating this original view/account.
                        // Source bytes use the bounded account cache, not URL-only Coil disk entries.
                        .memoryCachePolicy(CachePolicy.DISABLED)
                        .diskCachePolicy(CachePolicy.DISABLED)
                        .networkCachePolicy(CachePolicy.DISABLED)
                        .build()
                    val result = imageLoader.execute(request)
                    ensureActive()
                    if (result is SuccessResult) {
                        image = result.image
                    } else {
                        withContext(Dispatchers.IO) { asset.invalidate() }
                    }
                } finally {
                    // File unlink/snapshot release can touch the disk journal.
                    // Finish ownership cleanup even if the view was canceled.
                    withContext(NonCancellable + Dispatchers.IO) { asset.close() }
                }
                ensureActive()
                if (allowed() && flight.targets.isNotEmpty() && flights[key] === flight) {
                    image?.takeIf { it.shareable }?.let { cacheImage(key, it) }
                    flight.targets.toList().forEach { if (it in targets) it.update(image) }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (allowed() && flights[key] === flight) flight.targets.toList().forEach { if (it in targets) it.update(null) }
            } finally {
                if (flights[key] === flight) flights.remove(key)
                flight.targets.forEach { if (it.flight === flight) it.flight = null }
                flight.targets.clear()
            }
        }
    }

    private fun release(target: Target) {
        val flight = target.flight ?: return
        target.flight = null
        flight.targets.remove(target)
        if (flight.targets.isEmpty()) {
            if (flights[flight.key] === flight) flights.remove(flight.key)
            flight.job?.cancel()
        }
    }

    private fun key(target: Target): String {
        val source = "${target.media.url}\u0000${target.media.mimeType}\u0000${target.width}x${target.height}"
        val hash = MessageDigest.getInstance("SHA-256").digest(source.toByteArray()).joinToString("") { "%02x".format(it) }
        return "$prefix${clock().coerceAtLeast(0) / DAY_MILLIS}:$hash"
    }

    private fun clearCache() {
        val cache = imageLoader.memoryCache ?: return
        cache.keys.filter { it.key.startsWith(prefix) }.forEach(cache::remove)
    }

    private fun cacheImage(key: String, image: Image) {
        val cache = imageLoader.memoryCache ?: return
        // Coil supplies the shared byte-bounded cache. Also bound tiny-thumbnail
        // bookkeeping across conversations without evicting unrelated avatars.
        val previous = cache.keys.filter { it.key.startsWith(CACHE_PREFIX) && it.key != key }
        previous.take((previous.size - MAX_ENTRIES + 1).coerceAtLeast(0)).forEach(cache::remove)
        cache[MemoryCache.Key(key)] = MemoryCache.Value(image)
    }

    private fun invalidate() {
        if (invalid) return
        invalid = true
        targets.toList().forEach { release(it); it.update(null) }
        clearCache()
    }

    fun dispose() {
        if (disposed) return
        invalidate()
        disposed = true
        watcher.cancel()
        targets.clear()
        clearCache()
    }

    companion object {
        internal const val MAX_PIXELS = 1024
        internal const val MAX_ENTRIES = 80
        private const val CACHE_PREFIX = "mango9-thumbnail:"
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000

        private object StaticThumbnail : Transformation() {
            override val cacheKey = "mango9-static-thumbnail"

            override suspend fun transform(input: Bitmap, size: Size): Bitmap = input
        }

        fun create(context: Context, scope: CoroutineScope, lease: Mango9SessionLease?): Mango9ThumbnailLoader {
            val app = context.applicationContext
            val sessions = Mango9SessionStore(app)
            return Mango9ThumbnailLoader(
                app, scope, app.imageLoader, lease?.let(Mango9MediaCache::ownerKey).orEmpty(),
                { lease != null && sessions.currentSession(lease) != null },
                { media -> Mango9MediaCache.acquire(app, media, requireNotNull(lease)) },
            )
        }
    }
}
