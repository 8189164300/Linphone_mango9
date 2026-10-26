// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlin.random.Random

/** All store instances share an owner, while isolated test stores keep separate flights. */
internal interface Mango9RenewalOwner { val renewalOwner: Any }

/** The application owns renewal through checked persistence, never a screen or push job. */
internal object Mango9TokenRefresh {
    val lock = Mutex() // Compatibility cleanup of already removed, unbound logins only.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val monitor = Any()

    private data class Key(val owner: Any, val origin: String, val user: String, val identity: String?, val epoch: String?, val generation: Long)

    private val flights = mutableMapOf<Key, Deferred<Mango9Session>>()
    private val retryAt = mutableMapOf<Key, Long>()
    private val failures = mutableMapOf<Key, Int>()
    private val expiredTokens = linkedSetOf<String>()

    private fun fingerprint(token: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

    fun check(session: Mango9Session) = checkBearer("Bearer ${session.accessToken}")

    fun checkBearer(bearer: String?) = synchronized(monitor) {
        if (bearer?.substringBefore(' ')?.equals("Bearer", true) == true &&
            fingerprint(bearer.substringAfter(' ')) in expiredTokens
        ) {
                throw Mango9ApiException.SessionExpired
            }
    }

    suspend fun renew(
        owner: Any, lease: Mango9LoginLease, failed: Mango9Session,
        current: () -> Mango9Session?, replace: (Mango9Session, Mango9Session) -> Boolean,
        refresh: suspend (Mango9Session) -> Mango9Session
    ): Mango9Session {
        val saved = current() ?: throw Mango9ApiException.Unauthorized
        if (saved.accessToken != failed.accessToken || saved.refreshToken != failed.refreshToken) return saved
        val key = Key(
            (owner as? Mango9RenewalOwner)?.renewalOwner ?: owner, saved.crmApiBaseUrl,
            saved.userId, saved.sipIdentity, saved.loginEpoch, lease.loginGeneration
        )
        val flight = synchronized(monitor) {
            check(saved)
            flights[key] ?: run {
                val remaining = (retryAt[key] ?: 0L) - System.nanoTime()
                if (remaining > 0) throw Mango9ApiException.RetryAfter((remaining / 1_000_000_000L + 1).coerceAtLeast(1))
                scope.async(start = CoroutineStart.LAZY) {
                    try {
                        val renewed = withTimeout(60_000) { refresh(saved) }
                        if (!replace(saved, renewed)) throw Mango9ApiException.Unauthorized
                        synchronized(monitor) { retryAt.remove(key); failures.remove(key) }
                        renewed
                    } catch (error: Exception) {
                        val latest = current() ?: throw Mango9ApiException.Unauthorized
                        if (latest.accessToken != saved.accessToken || latest.refreshToken != saved.refreshToken) return@async latest
                        synchronized(monitor) {
                            if (error == Mango9ApiException.SessionExpired) {
                                expiredTokens.add(fingerprint(saved.accessToken))
                                if (expiredTokens.size > 2048) expiredTokens.remove(expiredTokens.first())
                            }
                            val count = ((failures[key] ?: 0) + 1).coerceAtMost(5)
                            failures[key] = count
                            val delay =
                                maxOf(Random.nextLong(1, (1L shl count) + 1), (error as? Mango9ApiException.RetryAfter)?.seconds ?: 0)
                            retryAt[key] = System.nanoTime() + delay.coerceAtMost(3600) * 1_000_000_000L
                        }
                        throw error
                    }
                }.also { task ->
                    flights[key] = task
                    task.invokeOnCompletion { synchronized(monitor) { if (flights[key] === task) flights.remove(key) } }
                    task.start()
                }
            }
        }
        return flight.await()
    }
}
