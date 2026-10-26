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
    private val requests = Mango9AuthorizedRequests(sessions, api::refresh)

    internal fun sessionStorageError() = sessions.lastStorageError

    fun hasActiveSession(): Boolean = sessions.load() != null

    fun activeIdentity(): String? = sessions.activeIdentity

    fun activeSession(): Mango9Session? = sessions.load()

    internal fun captureSession(): Mango9SessionLease? = sessions.captureSession()

    internal fun isCurrent(lease: Mango9SessionLease): Boolean = sessions.currentSession(lease) != null

    internal suspend fun updateRecord(
        lease: Mango9SessionLease,
        kind: Mango9RecordKind,
        id: Int,
        values: Map<String, String>,
    ): Mango9CrmRecordDetail = requests.execute(lease) { api.updateRecord(it, kind, id, values) }

    internal suspend fun deleteRecord(lease: Mango9SessionLease, kind: Mango9RecordKind, id: Int) =
        requests.execute(lease) { api.deleteRecord(it, kind, id) }

    internal suspend fun createRecord(
        lease: Mango9SessionLease,
        kind: Mango9RecordKind,
        firstName: String,
        lastName: String,
        phone: String,
        email: String,
        groupId: String,
    ): Mango9CrmRecordDetail = requests.execute(lease) {
        api.createRecord(it, kind, firstName, lastName, phone, email, groupId)
    }

    suspend fun dashboard(): Mango9CrmDashboard = authorized(api::dashboard)

    internal suspend fun dashboard(lease: Mango9SessionLease): Mango9CrmDashboard = requests.execute(lease, api::dashboard)

    internal suspend fun schema(lease: Mango9SessionLease, kind: Mango9RecordKind): Mango9CrmSchema =
        requests.execute(lease) { api.schema(it, kind) }

    internal suspend fun groups(lease: Mango9SessionLease, kind: Mango9RecordKind): List<Mango9CrmGroup> =
        requests.execute(lease) { api.groups(it, kind) }

    internal suspend fun record(lease: Mango9SessionLease, kind: Mango9RecordKind, id: Int): Mango9CrmRecordDetail =
        requests.execute(lease) { api.record(it, kind, id) }

    internal suspend fun records(
        lease: Mango9SessionLease, kind: Mango9RecordKind, search: String, status: String,
        groupId: String, dateFilter: String, page: Int,
    ): Mango9CrmRecordPage = requests.execute(lease) { api.records(it, kind, search, status, groupId, dateFilter, page) }

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

    internal suspend fun records(lease: Mango9SessionLease, kind: Mango9RecordKind, search: String, page: Int): Mango9CrmRecordPage =
        requests.execute(lease) { api.records(it, kind, search, "", "", "", page) }

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

    internal suspend fun teamMembers(lease: Mango9SessionLease): List<Mango9TeamMember> =
        requests.execute(lease, api::teamMembers)

    suspend fun chatBootstrap(): Mango9ChatBootstrap = authorized(api::chatBootstrap)

    internal suspend fun chatBootstrap(lease: Mango9SessionLease): Mango9ChatBootstrap =
        requests.execute(lease, api::chatBootstrap)

    suspend fun callSettings(): Mango9CallSettings = authorized(api::callSettings)

    internal suspend fun callSettings(lease: Mango9SessionLease): Mango9CallSettings =
        requests.execute(lease, api::callSettings)

    internal suspend fun lineIdentity(lease: Mango9SessionLease): Mango9LineIdentity =
        requests.execute(lease, api::lineIdentity)

    suspend fun updateCallForwarding(enabled: Boolean, destination: String): Mango9CallSettings =
        authorized { api.updateCallForwarding(it, enabled, destination) }

    internal suspend fun updateCallForwarding(
        lease: Mango9SessionLease, enabled: Boolean, destination: String,
    ): Mango9CallSettings = requests.execute(lease) { api.updateCallForwarding(it, enabled, destination) }

    private suspend fun <T> authorized(action: suspend (Mango9Session) -> T): T = requests.execute(action)
}
