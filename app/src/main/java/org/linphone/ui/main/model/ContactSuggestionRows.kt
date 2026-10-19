/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.ui.main.model

import androidx.annotation.WorkerThread
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.contacts.ContactSearch
import org.linphone.mango9.Mango9ContactPage
import org.linphone.ui.main.contacts.model.ContactAvatarModel
import org.linphone.utils.LinphoneUtils

/** Core-thread-owned paging and avatar lifetime for History and conversation search. */
class ContactSuggestionRows {
    private val page = Mango9ContactPage<ContactSearch.Candidate>()
    private val rows = arrayListOf<ConversationContactOrSuggestionModel>()
    private val avatars = arrayListOf<ContactAvatarModel>()

    @WorkerThread
    fun replace(candidates: List<ContactSearch.Candidate>): List<ConversationContactOrSuggestionModel> {
        clear()
        append(page.replace(candidates))
        return rows.toList()
    }

    @WorkerThread
    fun reveal(near: Int): List<ConversationContactOrSuggestionModel>? {
        // Candidates without a callable SDK destination are skipped. They must
        // not prevent the last visible row from revealing the next page.
        val more = page.reveal(near + page.limit - rows.size)
        if (more.isEmpty()) return null
        append(more)
        return rows.toList()
    }

    @WorkerThread
    fun clear() {
        avatars.forEach(ContactAvatarModel::destroy)
        avatars.clear()
        rows.clear()
        page.clear()
    }

    @WorkerThread
    private fun append(candidates: List<ContactSearch.Candidate>) {
        for (candidate in candidates) {
            val native = candidate.native
            val model: ConversationContactOrSuggestionModel
            val avatar: ContactAvatarModel
            if (native != null) {
                model = ConversationContactOrSuggestionModel(nativeContact = native, nativeFavorite = candidate.favorite)
                avatar = ContactAvatarModel(native, candidate.favorite)
            } else {
                val friend = candidate.friend
                val address = candidate.address ?: friend?.let(LinphoneUtils::getFirstAvailableAddressForFriend) ?: continue
                model = ConversationContactOrSuggestionModel(
                    address, friend = friend, defaultAccountDomain = if (candidate.request) null else candidate.domain,
                ) { coreContext.startAudioCall(it) }
                avatar = if (friend != null) {
                    coreContext.contactsManager.getContactAvatarModelForFriend(friend)
                } else {
                    val temporary = coreContext.core.createFriend()
                    temporary.name = LinphoneUtils.getDisplayName(address)
                    temporary.address = address
                    ContactAvatarModel(temporary)
                }
            }
            avatars.add(avatar)
            model.avatarModel.postValue(avatar)
            rows.add(model)
        }
    }
}
