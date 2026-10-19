/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.ui.main.model

import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MutableLiveData
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.contacts.NativeContactValue
import org.linphone.contacts.NativeContactDirectory
import org.linphone.contacts.sortName
import org.linphone.core.Address
import org.linphone.core.Friend
import org.linphone.mango9.Mango9SipDisplay
import org.linphone.ui.main.contacts.model.ContactAvatarModel
import org.linphone.utils.AppUtils
import org.linphone.utils.LinphoneUtils

class ConversationContactOrSuggestionModel
    @WorkerThread
    constructor(
    address: Address? = null,
    val conversationId: String = "",
    conversationSubject: String? = null,
    friend: Friend? = null,
    val defaultAccountDomain: String? = null,
    val nativeContact: NativeContactValue? = null,
    private val nativeFavorite: Boolean? = null,
    private val onClicked: ((Address) -> Unit)? = null,
) {
    private val sdkAddress = address
    private val sdkFriend = friend
    val address: Address
        @WorkerThread get() = sdkAddress ?: LinphoneUtils.getFirstAvailableAddressForFriend(this.friend!!)
            ?: error("Native contact no longer has a callable destination")
    val friend: Friend?
        @WorkerThread get() = sdkFriend ?: nativeContact?.let { coreContext.contactsManager.resolveNativeFriend(id) }

    val isAvailable: Boolean
        @WorkerThread get() = nativeContact == null || coreContext.contactsManager.nativeContactsSnapshot().find(id) != null
    // Ref keys/names can collide across native, CardDAV and team directories.
    // Full URIs avoid hash collisions for suggestions, and chat IDs stay distinct
    // from the same recipient's contact row. The retained Friend owns its pointer.
    val id = when {
        conversationId.isNotEmpty() -> "conversation:$conversationId"
        nativeContact != null -> NativeContactDirectory.REF_PREFIX + nativeContact.id
        friend != null -> "friend:${friend.nativePointer}"
        else -> "address:${address!!.asStringUriOnly()}"
    }

    val isFriend = friend != null || nativeContact != null

    val starred = nativeFavorite ?: (friend?.starred == true)

    val name = conversationSubject ?: nativeContact?.displayName?.ifBlank { nativeContact.sortName(true) }
        ?: if (friend != null) {
            friend.name ?: LinphoneUtils.getDisplayName(address!!)
        } else {
            address!!.username ?: address.domain.orEmpty()
        }

    val sipUri = if (nativeContact != null) {
        nativeContact.phones.firstOrNull()?.number ?: nativeContact.sipAddresses.firstOrNull().orEmpty()
    } else if (corePreferences.onlyDisplaySipUriUsername) {
        Mango9SipDisplay.friendlyUsername(address!!.username, address.asStringUriOnly())
    } else if (!corePreferences.hideSipAddresses) {
        // Hide SIP address and only show username for suggestions
        // on the same domain as the currently selected account
        if (!defaultAccountDomain.isNullOrEmpty() && defaultAccountDomain == address!!.domain) {
            address.username
        } else {
            address!!.asStringUriOnly()
        }
    } else {
        address!!.username
    }

    val initials = AppUtils.getInitials(conversationSubject ?: name)

    val avatarModel = MutableLiveData<ContactAvatarModel>()

    val selected = MutableLiveData<Boolean>()

    @UiThread
    fun onClicked() {
        onClicked?.let { callback -> coreContext.postOnCoreThread { callback(address) } }
    }
}
