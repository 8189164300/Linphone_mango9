/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.contacts

import android.database.Cursor
import android.provider.ContactsContract
import android.os.OperationCanceledException
import android.util.Patterns
import org.linphone.utils.PhoneNumberUtils

/** Plain text/URI values only: never move a live SDK wrapper or provider cursor between queues. */
data class NativeContactValue(
    val id: String,
    val displayName: String,
    val lookupKey: String,
    val starred: Boolean,
    val photoUri: String?,
    val firstName: String?,
    val lastName: String?,
    val organization: String?,
    val jobTitle: String?,
    val phones: List<NativePhoneValue>,
    val sipAddresses: List<String>,
)

data class NativePhoneValue(val number: String, val label: String, val normalizedNumber: String? = null)

sealed interface NativeContactSnapshot {
    data class Complete(val contacts: List<NativeContactValue>) : NativeContactSnapshot {
        // Built on the provider worker, not when the UI receives the snapshot.
        val directory = NativeContactDirectory.build(contacts)
    }

    data object PermissionDenied : NativeContactSnapshot

    data object Unavailable : NativeContactSnapshot
}

object NativeContactSnapshotReader {
    // Bounds reject an incomplete read; they never truncate a destructive reconciliation.
    const val MAX_CONTACTS = 50_000
    const val MAX_ROWS = 1_000_000
    val projection = arrayOf(
        ContactsContract.Data.CONTACT_ID,
        ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
        ContactsContract.Data.MIMETYPE,
        ContactsContract.Contacts.STARRED,
        ContactsContract.Contacts.LOOKUP_KEY,
        ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
        ContactsContract.Data.DATA1,
        ContactsContract.Data.DATA2,
        ContactsContract.Data.DATA3,
        ContactsContract.Data.DATA4,
    )

    fun read(cursor: Cursor, cancelled: () -> Boolean): List<NativeContactValue> {
        val idColumn = cursor.getColumnIndexOrThrow(ContactsContract.Data.CONTACT_ID)
        val nameColumn = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
        val mimeColumn = cursor.getColumnIndexOrThrow(ContactsContract.Data.MIMETYPE)
        val starredColumn = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.STARRED)
        val lookupColumn = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.LOOKUP_KEY)
        val photoColumn = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.PHOTO_THUMBNAIL_URI)
        val data1 = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA1)
        val data2 = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA2)
        val data3 = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA3)
        val data4 = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA4)
        val values = LinkedHashMap<String, Builder>()
        var rows = 0
        cursor.moveToPosition(-1)
        while (cursor.moveToNext()) {
            if (cancelled()) throw OperationCanceledException()
            check(++rows <= MAX_ROWS) { "Address book exceeds safe row limit" }
            val id = cursor.getString(idColumn)
            check(!id.isNullOrEmpty()) { "Address book entry has no identity" }
            val contact = values.getOrPut(id) {
                check(values.size < MAX_CONTACTS) { "Address book exceeds safe contact limit" }
                Builder(
                    id, cursor.getString(nameColumn).orEmpty(), cursor.getString(lookupColumn).orEmpty(),
                    cursor.getInt(starredColumn) == 1, cursor.getString(photoColumn)
                )
            }
            when (cursor.getString(mimeColumn)) {
                ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> {
                    contact.first = cursor.getString(data2)
                    contact.last = cursor.getString(data3)
                }
                ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> {
                    contact.organization = cursor.getString(data1)
                    contact.job = cursor.getString(data4)
                }
                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> {
                    val raw = cursor.getString(data1)
                    val normalized = cursor.getString(data4)
                    val number = if (raw.isNullOrEmpty() || !Patterns.PHONE.matcher(raw).matches()) normalized ?: raw else raw
                    if (!number.isNullOrBlank()) {
                        val type = cursor.getString(data2)?.toIntOrNull() ?: ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM
                        val label = PhoneNumberUtils.addressBookLabelTypeToVcardParamString(type, cursor.getString(data3))
                        contact.phones.add(NativePhoneValue(number, label, normalized))
                    }
                }
                ContactsContract.CommonDataKinds.SipAddress.CONTENT_ITEM_TYPE -> {
                    cursor.getString(data1)?.takeIf(String::isNotBlank)?.let(contact.addresses::add)
                }
            }
        }
        if (cancelled()) throw OperationCanceledException()
        return values.values.map(Builder::build)
    }

    private class Builder(val id: String, val name: String, val lookup: String, val starred: Boolean, val photo: String?) {
        var first: String? = null
        var last: String? = null
        var organization: String? = null
        var job: String? = null
        val phones = linkedSetOf<NativePhoneValue>()
        val addresses = linkedSetOf<String>()

        fun build() = NativeContactValue(id, name, lookup, starred, photo, first, last, organization, job, phones.toList(), addresses.toList())
    }
}
