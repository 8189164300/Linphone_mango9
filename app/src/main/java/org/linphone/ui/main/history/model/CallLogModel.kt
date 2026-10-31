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
package org.linphone.ui.main.history.model

import androidx.annotation.IntegerRes
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.R
import org.linphone.core.CallLog
import org.linphone.core.tools.Log
import org.linphone.ui.main.contacts.model.ContactAvatarModel
import org.linphone.utils.AppUtils
import org.linphone.utils.LinphoneUtils
import org.linphone.utils.TimestampUtils

class CallLogModel
    @WorkerThread
    constructor(private val callLog: CallLog) {
    companion object {
        private const val TAG = "[Call Log Model]"
    }

    val id = callLog.callId ?: callLog.refKey

    val timestamp = callLog.startDate

    val address = callLog.remoteAddress

    val sipUri = address.asStringUriOnly()
    private val savedPresentation = if (callLog.dir == org.linphone.core.Call.Dir.Incoming) {
        org.linphone.mango9.Mango9CallHistoryPresentation.decode(callLog.refKey)?.takeIf { it.source != "sip" }
    } else null

    val displayedAddress: String

    fun matchesCallerFilter(filter: String): Boolean {
        val visibleName = avatarModel.contactName ?: LinphoneUtils.getDisplayName(address)
        if (visibleName.contains(filter, ignoreCase = true) || displayedAddress.contains(filter, ignoreCase = true)) return true
        if (savedPresentation?.source == "withheld") return false
        return savedPresentation?.handle?.contains(filter, ignoreCase = true) == true || sipUri.contains(filter, ignoreCase = true)
    }

    val avatarModel: ContactAvatarModel

    val wasConference: Boolean

    @IntegerRes
    val iconResId: Int

    val dateTime: String

    val friendRefKey: String?

    var friendExists: Boolean = false

    init {
        val date = if (TimestampUtils.isToday(timestamp)) {
            AppUtils.getString(R.string.today)
        } else if (TimestampUtils.isYesterday(timestamp)) {
            AppUtils.getString(R.string.yesterday)
        } else {
            TimestampUtils.toString(timestamp, onlyDate = true, shortDate = true, hideYear = true)
        }
        val time = TimestampUtils.timeToString(timestamp)
        dateTime = "$date | $time"

        wasConference = callLog.wasConference()
        if (wasConference) {
            val conferenceInfo = callLog.conferenceInfo
            if (conferenceInfo != null) {
                avatarModel = coreContext.contactsManager.getContactAvatarModelForConferenceInfo(
                    conferenceInfo
                )
            } else {
                Log.w("$TAG Failed to retrieve conference info attached to call log!")
                val fakeFriend = coreContext.core.createFriend()
                fakeFriend.address = address
                fakeFriend.name = LinphoneUtils.getDisplayName(address)
                avatarModel = ContactAvatarModel(fakeFriend)
                avatarModel.forceConferenceIcon.postValue(true)
            }

            friendRefKey = null
            friendExists = false
        } else if (savedPresentation != null) {
            val displayFriend = coreContext.core.createFriend()
            displayFriend.address = address
            displayFriend.name = savedPresentation.name
            avatarModel = ContactAvatarModel(displayFriend, address)
            friendRefKey = null
            friendExists = false
        } else {
            avatarModel = coreContext.contactsManager.getContactAvatarModelForAddress(address)
            if (avatarModel.nativeContact != null) {
                friendRefKey = avatarModel.id
                friendExists = coreContext.contactsManager.nativeContactsSnapshot().find(avatarModel.id) != null
            } else {
                val friend = avatarModel.friend
                friendRefKey = friend.refKey
                friendExists = coreContext.contactsManager.isContactAvailable(friend)
            }
        }
        displayedAddress = savedPresentation?.let {
            if (it.source == "withheld") it.name else org.linphone.mango9.Mango9CallerIdentity.externalPhoneNumber(it.handle)
                ?.let(org.linphone.mango9.Mango9CallerIdentity::formattedPhoneNumber) ?: it.handle
        } ?: if (corePreferences.onlyDisplaySipUriUsername) {
            address.username ?: ""
        } else {
            sipUri
        }

        iconResId = LinphoneUtils.getCallIconResId(callLog.status, callLog.dir)
    }

    @UiThread
    fun delete() {
        coreContext.postOnCoreThread { core ->
            core.removeCallLog(callLog)
        }
    }
}
