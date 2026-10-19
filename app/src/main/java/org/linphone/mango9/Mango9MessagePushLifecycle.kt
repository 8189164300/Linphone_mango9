/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import okhttp3.Request

/** A background renewal/storage failure must not terminate the app; cancellation is not an error. */
internal suspend fun runMango9MessagePushWork(onFailure: (Exception) -> Unit, action: suspend () -> Unit) {
    try {
        action()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        onFailure(error)
    }
}

/** Messaging push belongs to stored logins, not the selected account or its WebSocket. */
internal class Mango9MessagePushLifecycle(
    private val sessions: Mango9LoginAccess,
    scope: CoroutineScope,
    private val token: () -> String?,
    private val deviceId: () -> String,
    private val bootstrap: suspend (Mango9Session) -> String,
    private val refresh: suspend (Mango9Session) -> Mango9Session,
    private val send: suspend (Request) -> Int,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val authorized = Mango9AuthorizedLoginRequests(sessions, refresh)
    private val pushOperations = Mango9PushOperationQueue<Mango9PushOwner>(scope)
    private val pendingLock = Any()
    private val pending = mutableMapOf<RegistrationKey, Mango9PushOperationQueue.Result<Boolean>>()

    /** Reserve every owner before returning; one failed owner must not block other accounts. */
    fun refreshAll(): List<Mango9PushOperationQueue.Result<Boolean>> {
        val capturedToken = token()?.takeIf(Mango9MessagePushTokenStore::isValidToken) ?: return emptyList()
        return sessions.captureLogins().filter { Mango9PushOwner.from(it.session) != null }
            .groupBy { Mango9PushOwner.from(it.session)!! }
            .map { (owner, logins) ->
                val key =
                    RegistrationKey(owner, logins.map { it.session.sipIdentity to it.loginGeneration }, capturedToken)
                // Never acquire a session-store lock while holding pendingLock: logout reserves
                // queue order from inside the session store's own removal boundary.
                synchronized(pendingLock) {
                    pending[key] ?: pushOperations.enqueue(owner) {
                        try {
                            register(logins, capturedToken)
                        } finally {
                            synchronized(pendingLock) { pending.remove(key) }
                        }
                    }.also { pending[key] = it }
                }
            }
    }

    /** Called synchronously inside removeIfCurrent, after local removal, without awaiting. */
    fun retire(session: Mango9Session, renewed: (Mango9Session, Mango9Session) -> Unit = { _, _ -> }): Mango9PushOperationQueue.Result<Boolean>? {
        val owner = Mango9PushOwner.from(session) ?: return null
        if (session.sipIdentity.isNullOrBlank()) return null
        return pushOperations.enqueue(owner) {
            unregisterRemotePushToken(owner, session, renewed)
        }
    }

    private fun retainedLogins(owner: Mango9PushOwner) =
        sessions.captureLogins().filter { Mango9PushOwner.from(it.session) == owner }

    private suspend fun register(logins: List<Mango9LoginLease>, capturedToken: String): Boolean {
        for (login in logins) {
            for (attempt in 0..RETRY_DELAYS.size) {
                coroutineContext.ensureActive()
                if (token() != capturedToken) return false
                if (sessions.currentLogin(login) == null) break
                val status = try {
                    val bearer = authorized.execute(login, bootstrap)
                    val current = sessions.currentLogin(login) ?: break
                    if (token() != capturedToken) return false
                    val request = Mango9MessagePushRegistration.registerRequest(
                        current, bearer, capturedToken, deviceId(), current.sipIdentity ?: break,
                    ) ?: return false
                    send(request)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (!retryable(error)) break
                    null
                }
                coroutineContext.ensureActive()
                if (sessions.currentLogin(login) == null || token() != capturedToken) return false
                if (status != null && status in 200..299) return true
                if (status != null && !retryable(status)) break
                if (attempt < RETRY_DELAYS.size) pause(RETRY_DELAYS[attempt])
            }
        }
        return false
    }

    private suspend fun unregisterRemotePushToken(owner: Mango9PushOwner, removed: Mango9Session, persistRenewed: (Mango9Session, Mango9Session) -> Unit): Boolean {
        var detached = removed
        repeat(2) { attempt ->
            coroutineContext.ensureActive()
            val retained = retainedLogins(owner)
            if (retained.isNotEmpty()) {
                // Run inline within this owner's queue, not by enqueueing and awaiting ourselves.
                // This retargets the SIP alias even if the remaining account is inactive/offline.
                val currentToken = token() ?: return false
                return register(retained, currentToken)
            }
            val result = try {
                val bearer = try {
                    bootstrap(detached)
                } catch (_: Mango9ApiException.InvalidCredentials) {
                    Mango9TokenRefresh.lock.withLock {
                        val renewed = refresh(detached)
                        coroutineContext.ensureActive()
                        // Removed credentials stay detached: never save or select this login.
                        val tokenOnly = detached.copy(accessToken = renewed.accessToken, refreshToken = renewed.refreshToken)
                        if (tokenOnly != renewed) throw Mango9ApiException.Unauthorized
                        persistRenewed(detached, renewed)
                        detached = renewed
                    }
                    bootstrap(detached)
                }
                coroutineContext.ensureActive()
                val replacement = retainedLogins(owner)
                if (replacement.isNotEmpty()) return register(replacement, token() ?: return false)
                val request = Mango9MessagePushRegistration.unregisterRequest(detached, bearer, deviceId())
                    ?: return false
                send(request) in 200..299
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (result) return true
            if (attempt == 0) pause(500)
        }
        return false
    }

    private fun retryable(code: Int) = code == 401 || code == 408 || code == 425 || code == 429 || code >= 500

    private fun retryable(error: Exception) = error is java.io.IOException ||
        error == Mango9ApiException.Network || error == Mango9ApiException.CrmUnavailable ||
        error == Mango9ApiException.RateLimited

    private data class RegistrationKey(
        val owner: Mango9PushOwner,
        val logins: List<Pair<String?, Long>>,
        val token: String,
    )

    private companion object {
        val RETRY_DELAYS = longArrayOf(2_000, 5_000, 10_000)
    }
}
