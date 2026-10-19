package org.linphone.mango9

import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Account-owned cached media; the app and external viewers explicitly hold/release their files. */
internal object Mango9MediaCache {
    const val OWNED_MEDIA = "mango9_owned_media"
    private const val TICKET = "mango9_media_ticket"
    private val process = UUID.randomUUID().toString()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class Pending(val asset: Mango9MediaAsset, val lease: Mango9SessionLease)

    private val tickets = mutableMapOf<String, Pending>()
    private var disk: Mango9MediaDiskCache? = null
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(false)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    suspend fun acquire(context: Context, media: Mango9ChatMedia, lease: Mango9SessionLease): Mango9MediaAsset {
        val sessions = Mango9SessionStore(context.applicationContext)
        val cache = withContext(Dispatchers.IO) { instance(context.applicationContext) }
        val cacheAllowed = withContext(Dispatchers.IO) { !org.linphone.core.VFS.isEnabled(context.applicationContext) }
        return acquire(cache, sessions, media, lease, cacheAllowed)
    }

    internal suspend fun acquire(
        cache: Mango9MediaDiskCache,
        sessions: Mango9SessionStore,
        media: Mango9ChatMedia,
        lease: Mango9SessionLease,
        cacheAllowed: Boolean = true,
    ): Mango9MediaAsset {
        val url = Mango9Configuration.verifiedHttpsUrl(media.url)
            ?: error("Invalid media address")
        val owner = ownerKey(lease)
        var acquired: Mango9MediaAsset? = null
        try {
            return coroutineScope {
        val operation = async {
            cache.acquire(owner, Request.Builder().url(url).build(), extensionFor(media), cacheAllowed) {
                sessions.currentSession(lease) != null
            }.also { acquired = it }
        }
        val watcher = launch {
            Mango9SessionStore.contextChanges.collect {
                if (sessions.currentSession(lease) == null) operation.cancel()
            }
        }
        try {
            operation.await().also {
                if (sessions.currentSession(lease) == null) throw Mango9ApiException.Unauthorized
            }
        } finally {
            watcher.cancel()
            // Also cover removal before the process-wide observer saw this owner.
            if (sessions.currentLogin(Mango9LoginLease(lease.session, lease.loginGeneration)) == null) {
                cache.evictOwner(owner)
            }
        }
            }
        } catch (failure: Throwable) {
            // Include cancellation while coroutineScope waits for its watcher/worker
            // to finish, not only cancellation of operation.await().
            acquired?.close()
            throw failure
        }
    }

    private fun instance(context: Context): Mango9MediaDiskCache = synchronized(this) {
        disk?.let { return it }
        // These are only obsolete downloader-owned files, not the app's other caches.
        File(context.cacheDir, "mango9-media").listFiles().orEmpty().filter {
            it.isFile && !Files.isSymbolicLink(it.toPath()) &&
                it.name.matches(Regex("(?:[a-f0-9]{64}\\.[a-z0-9]{1,8}|download-[-0-9]+\\.part)"))
        }.forEach { it.delete() }
        val cache =
            Mango9MediaDiskCache(File(context.cacheDir, "mango9-media-v2"), File(context.cacheDir, "mango9-media-open"), client)
        disk = cache
        val sessions = Mango9SessionStore(context)
        scope.launch {
            Mango9SessionStore.contextChanges.collect {
                val current = sessions.captureLogins().map { login ->
                    ownerKey(Mango9SessionLease(login.session, login.loginGeneration, 0))
                }.toSet()
                cache.retainOwners(current)
            }
        }
        cache
    }

    internal fun ownerKey(lease: Mango9SessionLease): String {
        val session = lease.session
        val source = listOf(
            process, lease.loginGeneration.toString(), session.sipIdentity.orEmpty(),
            session.crmBaseUrl, session.crmId, session.userId, session.parentClientId, session.loginId
        ).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun bindViewer(intent: Intent, asset: Mango9MediaAsset, lease: Mango9SessionLease, media: Mango9ChatMedia? = null): String {
        val ticket = UUID.randomUUID().toString()
        synchronized(tickets) { tickets[ticket] = Pending(asset, lease) }
        val args = Bundle().apply {
            putBoolean(OWNED_MEDIA, true)
            putString(TICKET, ticket)
            putString("path", asset.file.absolutePath)
            putString(Mango9NativeAttachment.MIME, media?.mimeType)
            putString(Mango9NativeAttachment.NAME, media?.name)
            Mango9NavigationSession.bind(this, lease)
        }
        intent.putExtras(args)
        scope.launch { delay(60_000); discardViewer(ticket) }
        return ticket
    }

    fun takeViewer(args: Bundle, current: Mango9SessionLease?): Mango9MediaAsset? {
        val ticket = args.getString(TICKET) ?: return null
        val pending = synchronized(tickets) { tickets.remove(ticket) } ?: return null
        val matching = Mango9NavigationSession.matching(args, current)
        if (matching == null || ownerKey(matching) != ownerKey(pending.lease) ||
            matching.selectionGeneration != pending.lease.selectionGeneration ||
            args.getString("path") != pending.asset.file.absolutePath
        ) {
            pending.asset.close()
            return null
        }
        return pending.asset
    }

    fun discardViewer(ticket: String) {
        synchronized(tickets) { tickets.remove(ticket) }?.asset?.close()
    }

    internal fun extensionFor(media: Mango9ChatMedia): String = when (Mango9MediaType.normalized(media.mimeType)) {
        "audio/mp4", "audio/x-m4a" -> "m4a"
        "audio/mpeg" -> "mp3"
        "audio/wav", "audio/x-wav" -> "wav"
        "audio/ogg" -> "ogg"
        "audio/webm", "audio/x-matroska" -> "mka"
        "audio/flac" -> "flac"
        "audio/aac" -> "aac"
        "video/mp4" -> "mp4"
        "video/quicktime" -> "mov"
        "video/webm" -> "webm"
        "video/x-matroska" -> "mkv"
        "video/3gpp" -> "3gp"
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/heic", "image/heif" -> "heic"
        "image/avif" -> "avif"
        "application/pdf" -> "pdf"
        else -> media.name.substringAfterLast('.', "").lowercase()
            .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "bin"
    }
}
