/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.contacts

import android.content.ContentUris
import android.provider.ContactsContract
import androidx.annotation.WorkerThread
import org.linphone.core.Core
import org.linphone.core.Factory
import org.linphone.core.Friend
import org.linphone.core.SubscribePolicy

/** Materialize only the contact being used by a detail/call/message action. Never bulk import. */
@WorkerThread
fun NativeContactValue.asFriend(core: Core, favorite: Boolean = starred): Friend = core.createFriend().apply {
    refKey = NativeContactDirectory.REF_PREFIX + id
    name = displayName.ifBlank { sortName(true) }
    firstName = this@asFriend.firstName
    lastName = this@asFriend.lastName
    organization = this@asFriend.organization
    jobTitle = this@asFriend.jobTitle
    starred = favorite
    nativeUri = (
        ContactsContract.Contacts.getLookupUri(id.toLong(), lookupKey)
        ?: ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id.toLong())
    ).toString()
    photo = photoUri
    isSubscribesEnabled = false
    incSubscribePolicy = SubscribePolicy.SPDeny
    phones.forEach { addPhoneNumberWithLabel(Factory.instance().createFriendPhoneNumber(it.number, it.label)) }
    sipAddresses.forEach { core.interpretUrl(it, false)?.let(::addAddress) }
}
