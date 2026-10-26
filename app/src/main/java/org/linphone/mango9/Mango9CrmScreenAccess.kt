/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import android.content.Context

/** Every screen operation uses the login that supplied its displayed data. */
internal interface Mango9CrmScreenAccess {
    fun sessionStorageError(): Mango9ApiException? = null

    fun captureSession(): Mango9SessionLease?

    fun isCurrent(lease: Mango9SessionLease): Boolean

    suspend fun dashboard(lease: Mango9SessionLease): Mango9CrmDashboard

    suspend fun schema(lease: Mango9SessionLease, kind: Mango9RecordKind): Mango9CrmSchema

    suspend fun groups(lease: Mango9SessionLease, kind: Mango9RecordKind): List<Mango9CrmGroup>

    suspend fun record(lease: Mango9SessionLease, kind: Mango9RecordKind, id: Int): Mango9CrmRecordDetail

    suspend fun records(
        lease: Mango9SessionLease, kind: Mango9RecordKind, search: String, status: String,
                        groupId: String, dateFilter: String, page: Int
    ): Mango9CrmRecordPage

    suspend fun createRecord(
        lease: Mango9SessionLease, kind: Mango9RecordKind, firstName: String, lastName: String,
                             phone: String, email: String, groupId: String
    ): Mango9CrmRecordDetail

    suspend fun updateRecord(
        lease: Mango9SessionLease, kind: Mango9RecordKind, id: Int,
                             values: Map<String, String>
    ): Mango9CrmRecordDetail

    suspend fun deleteRecord(lease: Mango9SessionLease, kind: Mango9RecordKind, id: Int)
}

internal class Mango9CrmScreenRepository(context: Context) : Mango9CrmScreenAccess {
    private val repository = Mango9CrmRepository(context)

    override fun sessionStorageError() = repository.sessionStorageError()

    override fun captureSession() = repository.captureSession()

    override fun isCurrent(lease: Mango9SessionLease) = repository.isCurrent(lease)

    override suspend fun dashboard(lease: Mango9SessionLease) = repository.dashboard(lease)

    override suspend fun schema(lease: Mango9SessionLease, kind: Mango9RecordKind) = repository.schema(lease, kind)

    override suspend fun groups(lease: Mango9SessionLease, kind: Mango9RecordKind) = repository.groups(lease, kind)

    override suspend fun record(lease: Mango9SessionLease, kind: Mango9RecordKind, id: Int) = repository.record(lease, kind, id)

    override suspend fun records(
        lease: Mango9SessionLease, kind: Mango9RecordKind, search: String, status: String,
                                 groupId: String, dateFilter: String, page: Int
    ) =
        repository.records(lease, kind, search, status, groupId, dateFilter, page)

    override suspend fun createRecord(
        lease: Mango9SessionLease, kind: Mango9RecordKind, firstName: String, lastName: String,
                                      phone: String, email: String, groupId: String
    ) =
        repository.createRecord(lease, kind, firstName, lastName, phone, email, groupId)

    override suspend fun updateRecord(lease: Mango9SessionLease, kind: Mango9RecordKind, id: Int, values: Map<String, String>) =
        repository.updateRecord(lease, kind, id, values)

    override suspend fun deleteRecord(lease: Mango9SessionLease, kind: Mango9RecordKind, id: Int) =
        repository.deleteRecord(lease, kind, id)
}

/** Memory-only capability for one displayed record or creation form. Never restore a write from a Bundle. */
internal data class Mango9CrmEditorOwner(
    val lease: Mango9SessionLease,
    val context: Long,
    val kind: Mango9RecordKind,
    val recordId: Int?,
    val presentation: Long,
)
