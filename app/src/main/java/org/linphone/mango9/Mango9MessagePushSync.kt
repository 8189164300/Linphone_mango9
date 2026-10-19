/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import android.content.Context
import androidx.core.content.edit
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import org.linphone.core.tools.Log
import okhttp3.OkHttpClient

/** One application-owned queue for Firebase callbacks, login, reconnect and logout. */
internal class Mango9MessagePushSync private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val sessions = Mango9SessionStore(appContext)
    private val scope = applicationScope
    private val api = Mango9ApiClient(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS).followRedirects(false).build()
    private val tokens = Mango9MessagePushTokenStore(context)
    private val lifecycle = Mango9MessagePushLifecycle(
        sessions = Mango9SessionStore(context), scope = scope,
        token = tokens::load, deviceId = { Mango9MessageDeviceIdentity.get(context) },
        bootstrap = { api.chatBootstrap(it).token }, refresh = api::refresh,
        send = { request -> client.newCall(request).execute().use { it.code } },
    )
    private val cleanupLock = Any()
    private val cleanups = mutableMapOf<String, kotlinx.coroutines.Deferred<Boolean>>()

    fun refreshAsync() {
        scope.launch {
            safely {
                if (sessions.pendingPushRemovals().isNotEmpty()) {
                    runCatching { Mango9PushCleanupJob.schedule(appContext) }
                    sessions.pendingPushRemovals().forEach(::cleanup)
                }
                refresh()
            }
        }
    }

    suspend fun refresh(): Boolean {
        val operations = lifecycle.refreshAll()
        val results = operations.map { it.await() }
        if (results.any { !it }) Log.w("[Mango9 Message Push] Some stored logins could not renew registration")
        return results.isNotEmpty() && results.all { it }
    }

    fun retire(session: Mango9Session) {
        // Journal creation belongs to the atomic local logout, before this callback.
        val entries = sessions.pendingPushRemovals().filter { it.session.sipIdentity == session.sipIdentity }
        runCatching { Mango9PushCleanupJob.schedule(appContext) }
        entries.forEach(::cleanup)
    }

    internal suspend fun retryRemovals(): Boolean {
        val results = sessions.pendingPushRemovals().map(::cleanup).map { it.await() }
        return results.all { it } && sessions.pendingPushRemovals().isEmpty()
    }

    private fun cleanup(entry: Mango9PushRetirement): kotlinx.coroutines.Deferred<Boolean> = synchronized(cleanupLock) {
        cleanups[entry.id] ?: scope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                val current = sessions.pendingPushRemovals().firstOrNull { it.id == entry.id } ?: return@async true
                val operation = lifecycle.retire(current.session) { before, after -> sessions.updatePushRemoval(entry.id, before, after) }
                if (operation?.await() == true) {
                    sessions.acknowledgePushRemoval(entry.id)
                    true
                } else {
                    false
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            } finally { synchronized(cleanupLock) { cleanups.remove(entry.id) } }
        }.also { cleanups[entry.id] = it; it.start() }
    }

    internal fun resumeCleanup() {
        scope.launch {
            safely {
                if (sessions.pendingPushRemovals().isNotEmpty()) {
                    runCatching { Mango9PushCleanupJob.schedule(appContext) }
                    retryRemovals()
                }
            }
        }
    }

    private suspend fun safely(action: suspend () -> Unit) = runMango9MessagePushWork(
        onFailure = { Log.w("[Mango9 Message Push] Background registration work could not complete") },
        action = action,
    )

    companion object {
        private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        @Volatile private var instance: Mango9MessagePushSync? = null

        /** Do not initialize HTTP clients or encrypted storage on Application's main thread. */
        fun resumeAfterProcessStart(context: Context) {
            val appContext = context.applicationContext
            applicationScope.launch {
                runMango9MessagePushWork(
                    onFailure = { Log.w("[Mango9 Message Push] Deferred cleanup could not start") },
                    action = { get(appContext).resumeCleanup() },
                )
            }
        }

        fun get(context: Context): Mango9MessagePushSync = instance ?: synchronized(this) {
            instance ?: Mango9MessagePushSync(context.applicationContext).also { instance = it }
        }
    }
}

/** Preserve the existing device key; socket and background push must never generate competing IDs. */
internal object Mango9MessageDeviceIdentity {
    @Synchronized fun get(context: Context): String {
        val preferences = context.applicationContext.getSharedPreferences("mango9_device", Context.MODE_PRIVATE)
        return preferences.getString("chat_client_uuid", null)?.takeIf(String::isNotBlank)
            ?: UUID.randomUUID().toString().lowercase().also { generated ->
                preferences.edit(commit = true) { putString("chat_client_uuid", generated) }
            }
    }
}
