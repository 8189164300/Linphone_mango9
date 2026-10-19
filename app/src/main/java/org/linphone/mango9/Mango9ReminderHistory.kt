/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import android.content.Context
import android.util.AtomicFile
import androidx.annotation.MainThread
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

@MainThread
internal interface Mango9ReminderHistory {
    suspend fun ready()

    fun allows(key: String, now: Instant, repeatMinutes: Int): Boolean

    fun record(key: String, now: Instant)
}

/** Process-shared cadence, never customer/event content. Disk work survives Activity rotation. */
@MainThread
internal class Mango9ReminderFileHistory internal constructor(
    private val file: File,
    scope: CoroutineScope,
) : Mango9ReminderHistory {
    private val initialization = Mutex()
    private var cadence: Mango9ReminderCadence? = null

    private data class Write(val version: Long, val values: Map<String, Long>)

    private val pending = Channel<Write>(Channel.CONFLATED)
    private var version = 0L
    private val completed = MutableStateFlow(0L)

    init {
        // One writer; conflation keeps the latest complete bounded snapshot and
        // cannot let an older Activity overwrite a newer reminder presentation.
        scope.launch(Dispatchers.IO) {
            for (write in pending) {
                val atomic = AtomicFile(file)
                var stream: java.io.FileOutputStream? = null
                try {
                    file.parentFile?.mkdirs()
                    stream = atomic.startWrite()
                    stream.write(JSONObject(write.values).toString().toByteArray(Charsets.UTF_8))
                    atomic.finishWrite(stream)
                } catch (_: Exception) {
                    runCatching { if (stream != null) atomic.failWrite(stream) }
                    // Cadence remains valid in memory if storage is unavailable.
                } finally { completed.value = write.version }
            }
        }
    }

    override suspend fun ready() {
        initialization.withLock {
            if (cadence != null) return
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = ByteArray(32_769)
                    val size = AtomicFile(file).openRead().use { input ->
                        var count = 0
                        while (count < bytes.size) {
                            val read = input.read(bytes, count, bytes.size - count)
                            if (read <= 0) break
                            count += read
                        }
                        count
                    }
                    require(size <= 32_768)
                    val json = JSONObject(String(bytes, 0, size, Charsets.UTF_8))
                    json.keys().asSequence().filter { it.matches(Regex("[0-9a-f]{64}")) }
                        .mapNotNull { key -> json.optLong(key, -1).takeIf { it >= 0 }?.let { key to it } }
                        .sortedByDescending { it.second }.take(256).toMap()
                }.getOrDefault(emptyMap())
            }
            cadence = Mango9ReminderCadence(saved)
        }
    }

    override fun allows(key: String, now: Instant, repeatMinutes: Int) = requireNotNull(cadence).allows(key, now, repeatMinutes)

    override fun record(key: String, now: Instant) {
        requireNotNull(cadence).record(key, now)
        pending.trySend(Write(++version, requireNotNull(cadence).snapshot()))
    }

    internal suspend fun awaitWrites() { val target = version; completed.first { it >= target } }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var shared: Mango9ReminderFileHistory? = null

        fun get(context: Context): Mango9ReminderFileHistory = shared ?: Mango9ReminderFileHistory(
            File(context.applicationContext.noBackupFilesDir, "mango9-reminder-cadence.json"), scope,
        ).also { shared = it }
    }
}
