/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/** An authenticated operation belongs to one login and one account selection, not just a SIP URI. */
data class Mango9SessionLease(
    val session: Mango9Session,
    val loginGeneration: Long,
    val selectionGeneration: Long,
)

/** Identifies a login even when another account is selected in the UI. */
data class Mango9LoginLease(val session: Mango9Session, val loginGeneration: Long)

/** Non-secret UI ownership; capturing this never decrypts credentials. */
internal data class Mango9SelectionStamp(val identity: String?, val login: Long, val selection: Long)

interface Mango9LoginAccess {
    /** Captured atomically, with the selected login first when present. */
    fun captureLogins(): List<Mango9LoginLease>

    fun currentLogin(lease: Mango9LoginLease): Mango9Session?

    fun replaceLoginTokens(lease: Mango9LoginLease, expected: Mango9Session, refreshed: Mango9Session): Boolean
}

interface Mango9SessionAccess {
    fun captureSession(): Mango9SessionLease?

    fun currentSession(lease: Mango9SessionLease): Mango9Session?

    fun replaceTokens(lease: Mango9SessionLease, expected: Mango9Session, refreshed: Mango9Session): Boolean
}

/** Used under the session store's process-wide lock, including across store instances. */
internal class Mango9SessionGenerations {
    private val logins = mutableMapOf<String, Long>()
    private var selection = 0L

    val selectionVersion: Long get() = selection

    fun stamp(identity: String?) = Mango9SelectionStamp(identity, logins[identity] ?: 0L, selection)

    fun loginChanged(identity: String) {
        logins[identity] = (logins[identity] ?: 0L) + 1
    }

    fun selectionChanged() { selection++ }

    fun capture(session: Mango9Session) = Mango9SessionLease(
        session,
        logins[session.sipIdentity] ?: 0L,
        selection,
    )

    fun captureLogin(session: Mango9Session) = Mango9LoginLease(session, logins[session.sipIdentity] ?: 0L)

    fun matchesLogin(lease: Mango9LoginLease): Boolean =
        lease.session.sipIdentity != null && lease.loginGeneration == (logins[lease.session.sipIdentity] ?: 0L)

    fun matches(lease: Mango9SessionLease, activeIdentity: String?): Boolean =
        activeIdentity != null && activeIdentity == lease.session.sipIdentity &&
            lease.loginGeneration == (logins[activeIdentity] ?: 0L) &&
            lease.selectionGeneration == selection
}

/** Refresh is single-flight and can neither select an account nor recreate a removed login. */
internal class Mango9AuthorizedRequests(
    private val sessions: Mango9SessionAccess,
    private val refresh: suspend (Mango9Session) -> Mango9Session,
) {
    suspend fun <T> execute(action: suspend (Mango9Session) -> T): T {
        val lease = sessions.captureSession() ?: throw Mango9ApiException.Unauthorized
        return execute(lease, action)
    }

    suspend fun <T> execute(
        lease: Mango9SessionLease,
        action: suspend (Mango9Session) -> T,
    ): T {
        coroutineContext.ensureActive()
        val original = sessions.currentSession(lease) ?: throw Mango9ApiException.Unauthorized
        Mango9TokenRefresh.check(original)
        val result = try {
            action(original)
        } catch (error: Mango9ApiException) {
            if (error != Mango9ApiException.InvalidCredentials && error != Mango9ApiException.Unauthorized) throw error
            val renewed = renew(lease, original)
            coroutineContext.ensureActive()
            if (sessions.currentSession(lease) == null) throw Mango9ApiException.Unauthorized
            action(renewed)
        }
        coroutineContext.ensureActive()
        if (sessions.currentSession(lease) == null) throw Mango9ApiException.Unauthorized
        return result
    }

    private suspend fun renew(lease: Mango9SessionLease, failed: Mango9Session): Mango9Session {
        val login = Mango9LoginLease(lease.session, lease.loginGeneration)
        val loginAccess = sessions as? Mango9LoginAccess
        return Mango9TokenRefresh.renew(
            sessions, login, failed,
            current = { loginAccess?.currentLogin(login) ?: if (loginAccess == null) sessions.currentSession(lease) else null },
            replace = { before, after -> if (loginAccess != null) loginAccess.replaceLoginTokens(login, before, after) else sessions.replaceTokens(lease, before, after) },
            refresh = refresh
        )
    }
}

/** Background authentication may renew an existing login, but can never select or recreate it. */
internal class Mango9AuthorizedLoginRequests(
    private val sessions: Mango9LoginAccess,
    private val refresh: suspend (Mango9Session) -> Mango9Session,
) {
    suspend fun <T> execute(lease: Mango9LoginLease, action: suspend (Mango9Session) -> T): T {
        coroutineContext.ensureActive()
        val original = sessions.currentLogin(lease) ?: throw Mango9ApiException.Unauthorized
        Mango9TokenRefresh.check(original)
        val result = try {
            action(original)
        } catch (error: Mango9ApiException) {
            if (error != Mango9ApiException.InvalidCredentials && error != Mango9ApiException.Unauthorized) throw error
            val renewed = Mango9TokenRefresh.renew(
                sessions, lease, original,
                current = { sessions.currentLogin(lease) },
                replace = { before, after -> sessions.replaceLoginTokens(lease, before, after) },
                refresh = refresh
            )
            coroutineContext.ensureActive()
            if (sessions.currentLogin(lease) == null) throw Mango9ApiException.Unauthorized
            action(renewed)
        }
        coroutineContext.ensureActive()
        if (sessions.currentLogin(lease) == null) throw Mango9ApiException.Unauthorized
        return result
    }
}
