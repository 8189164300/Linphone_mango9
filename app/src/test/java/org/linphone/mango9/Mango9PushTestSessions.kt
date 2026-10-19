/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

internal class Mango9PushTestSessions(vararg initial: Mango9Session) : Mango9LoginAccess, Mango9SessionAccess {
    private val sessions = linkedMapOf<String, Mango9Session>()
    private val generations = Mango9SessionGenerations()
    var active: String? = initial.firstOrNull()?.sipIdentity
        private set
    var writes = 0
        private set

    init { initial.forEach(::save) }

    fun save(session: Mango9Session) {
        val identity = requireNotNull(session.sipIdentity)
        generations.loginChanged(identity)
        sessions[identity] = session
    }

    fun remove(session: Mango9Session) {
        generations.loginChanged(requireNotNull(session.sipIdentity))
        sessions.remove(session.sipIdentity)
        if (active == session.sipIdentity) select(null)
    }

    fun select(identity: String?) {
        if (active != identity) generations.selectionChanged()
        active = identity
    }

    override fun captureLogins() = sessions.values.sortedBy { it.sipIdentity != active }.map(generations::captureLogin)

    override fun currentLogin(lease: Mango9LoginLease) =
        sessions[lease.session.sipIdentity].takeIf { generations.matchesLogin(lease) }

    override fun captureSession() = sessions[active]?.let(generations::capture)

    override fun currentSession(lease: Mango9SessionLease) =
        sessions[active].takeIf { generations.matches(lease, active) }

    override fun replaceTokens(lease: Mango9SessionLease, expected: Mango9Session, refreshed: Mango9Session): Boolean =
        replace(currentSession(lease), expected, refreshed)

    override fun replaceLoginTokens(lease: Mango9LoginLease, expected: Mango9Session, refreshed: Mango9Session): Boolean =
        replace(currentLogin(lease), expected, refreshed)

    private fun replace(current: Mango9Session?, expected: Mango9Session, refreshed: Mango9Session): Boolean {
        if (current != expected) return false
        if (refreshed != current.copy(accessToken = refreshed.accessToken, refreshToken = refreshed.refreshToken)) return false
        sessions[requireNotNull(current.sipIdentity)] = refreshed
        writes++
        return true
    }
}

internal fun pushTestSession(extension: String = "1", user: String = extension) = Mango9Session(
    crmId = "crm", crmBaseUrl = "https://crm.example.com", crmApiBaseUrl = "https://crm.example.com/app/api/v2",
    userId = user, parentClientId = "tenant", role = "agent", loginId = "user@example.com", displayName = "User",
    accessToken = "old-access", refreshToken = "old-refresh", smsChatApi = "https://chat.example.com/api",
    connectWebsocket = "wss://chat.example.com/socket", enrollmentExpiresAtEpochSeconds = 1_900_000_000,
    sipIdentity = "sip:$extension@tenant.example.com",
)
