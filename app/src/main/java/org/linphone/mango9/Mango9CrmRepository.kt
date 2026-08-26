/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.mango9

import android.content.Context

class Mango9CrmRepository(context: Context) {
    private val api = Mango9ApiClient(context)
    private val sessions = Mango9SessionStore(context)

    fun hasActiveSession(): Boolean = sessions.load() != null

    fun activeIdentity(): String? = sessions.activeIdentity

    fun activeSession(): Mango9Session? = sessions.load()

    suspend fun dashboard(): Mango9CrmDashboard = authorized(api::dashboard)

    suspend fun records(
        kind: Mango9RecordKind,
        search: String = "",
        status: String = "",
        groupId: String = "",
        dateFilter: String = "",
        page: Int = 1,
    ): Mango9CrmRecordPage = authorized {
        api.records(it, kind, search, status, groupId, dateFilter, page)
    }

    suspend fun schema(kind: Mango9RecordKind): Mango9CrmSchema = authorized { api.schema(it, kind) }

    suspend fun groups(kind: Mango9RecordKind): List<Mango9CrmGroup> = authorized { api.groups(it, kind) }

    suspend fun record(kind: Mango9RecordKind, id: Int): Mango9CrmRecordDetail =
        authorized { api.record(it, kind, id) }

    suspend fun createRecord(
        kind: Mango9RecordKind,
        firstName: String,
        lastName: String,
        phone: String,
        email: String,
        groupId: String = "",
    ): Mango9CrmRecordDetail = authorized {
        api.createRecord(it, kind, firstName, lastName, phone, email, groupId)
    }

    suspend fun updateRecord(
        kind: Mango9RecordKind,
        id: Int,
        values: Map<String, String>,
    ): Mango9CrmRecordDetail = authorized { api.updateRecord(it, kind, id, values) }

    suspend fun deleteRecord(kind: Mango9RecordKind, id: Int) =
        authorized { api.deleteRecord(it, kind, id) }

    suspend fun teamMembers(): List<Mango9TeamMember> = authorized(api::teamMembers)

    suspend fun chatBootstrap(): Mango9ChatBootstrap = authorized(api::chatBootstrap)

    suspend fun callSettings(): Mango9CallSettings = authorized(api::callSettings)

    suspend fun updateCallForwarding(enabled: Boolean, destination: String): Mango9CallSettings =
        authorized { api.updateCallForwarding(it, enabled, destination) }

    private suspend fun <T> authorized(action: suspend (Mango9Session) -> T): T {
        var session = sessions.load() ?: throw Mango9ApiException.Unauthorized
        val requestedIdentity = session.sipIdentity ?: throw Mango9ApiException.Unauthorized
        val result = try {
            action(session)
        } catch (_: Mango9ApiException.InvalidCredentials) {
            session = api.refresh(session)
            sessions.save(
                session,
                session.sipIdentity,
                persist = sessions.rememberLogin,
                makeActive = true,
            )
            action(session)
        }
        if (!sessions.isActive(requestedIdentity)) throw Mango9ApiException.Unauthorized
        return result
    }
}
