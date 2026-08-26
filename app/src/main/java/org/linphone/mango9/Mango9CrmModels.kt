/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.mango9

enum class Mango9RecordKind(val path: String, val responseKey: String) {
    Lead("leads", "leads"),
    Client("clients", "clients"),
}

data class Mango9CrmDashboard(
    val totalBalance: Double,
    val usedBalance: Double,
    val remainingBalance: Double,
    val leads: Int,
    val clients: Int,
)

data class Mango9CrmRecord(
    val id: Int,
    val ownerUserId: Int,
    val ownerName: String,
    val name: String,
    val phone: String,
    val email: String,
    val status: String,
    val source: String,
    val createdAt: String,
)

data class Mango9CrmPagination(
    val total: Int,
    val page: Int,
    val limit: Int,
    val pages: Int,
)

data class Mango9CrmRecordPage(
    val records: List<Mango9CrmRecord>,
    val pagination: Mango9CrmPagination,
)

data class Mango9CrmSchema(
    val entity: String,
    val version: String,
    val sections: List<Section>,
    val fields: List<Field>,
    val statuses: List<String>,
) {
    data class Section(val id: String, val label: String)

    data class Field(
        val key: String,
        val fieldId: Int?,
        val name: String?,
        val label: String,
        val type: String,
        val section: String,
        val required: Boolean,
        val editable: Boolean,
        val visible: Boolean,
        val custom: Boolean,
        val options: List<String>,
    )
}

data class Mango9CrmGroup(val id: String, val name: String)

data class Mango9CrmRecordDetail(
    val record: Mango9CrmRecord,
    val schemaVersion: String,
    val values: Map<String, String>,
)

data class Mango9TeamMember(
    val userId: Int,
    val loginId: String,
    val name: String,
    val email: String,
    val mobile: String,
    val role: String,
    val extension: String,
    val sipUri: String?,
) {
    val displayName: String get() = name.trim().ifEmpty { loginId }
}

data class Mango9ChatBootstrap(
    val websocketUrl: String,
    val token: String,
    val expiresIn: Int,
    val userId: Int,
    val transport: String,
)

data class Mango9CallSettings(
    val activeNumber: String,
    val extension: String,
    val forwardingEnabled: Boolean,
    val forwardingDestination: String,
)
