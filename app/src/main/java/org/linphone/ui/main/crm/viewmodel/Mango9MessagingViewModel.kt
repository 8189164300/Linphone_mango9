/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.ui.main.crm.viewmodel

import android.net.Uri
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.mango9.Mango9ChatModerationStore
import org.linphone.mango9.Mango9ChatRoom
import org.linphone.mango9.Mango9ChatState
import org.linphone.mango9.Mango9ChatStore
import org.linphone.mango9.Mango9ConversationInsightsRepository
import org.linphone.mango9.Mango9PendingAttachment
import org.linphone.mango9.Mango9SmsConversationInsights
import org.linphone.ui.GenericViewModel
import org.linphone.utils.Event

class Mango9MessagingViewModel : GenericViewModel() {
    private val store = Mango9ChatStore.get(coreContext.context)
    private val insights = Mango9ConversationInsightsRepository(coreContext.context)
    val moderation = Mango9ChatModerationStore(coreContext.context)
    val state = MutableLiveData(store.state.value)
    val sending = MutableLiveData(false)
    val openedRoomEvent = MutableLiveData<Event<Mango9ChatRoom>>()
    val conversationInsightsEvent = MutableLiveData<Event<Mango9SmsConversationInsights>>()

    init {
        viewModelScope.launch { store.state.collect(state::postValue) }
    }

    fun connect(force: Boolean = false) {
        viewModelScope.launch { store.connect(force) }
    }

    fun refresh() {
        viewModelScope.launch { store.refreshDirectory() }
    }

    fun createGroup(userIds: Set<Int>) {
        viewModelScope.launch {
            store.createGroup(userIds)?.let { openedRoomEvent.postValue(Event(it)) }
        }
    }

    fun openTeamConversation(userId: Int, roomId: String?, name: String) {
        viewModelScope.launch {
            if (roomId.isNullOrBlank()) store.openDirectConversation(userId, name) else store.openRoom(roomId)
        }
    }

    fun openSmsConversation(phone: String) {
        viewModelScope.launch { store.openSmsConversation(phone) }
    }

    fun closeTeamConversation(roomId: String?) = store.closeConversation(roomId)

    fun closeSmsConversation(phone: String?) = store.closeSmsConversation(phone)

    fun sendTeamMessage(text: String, attachments: List<Mango9PendingAttachment>, done: (Boolean) -> Unit) {
        if (sending.value == true) return
        sending.value = true
        viewModelScope.launch {
            val sent = store.sendChatMessage(text, attachments)
            sending.postValue(false)
            coreContext.postOnMainThread { done(sent) }
        }
    }

    fun sendSms(
        phone: String,
        senderId: String,
        text: String,
        attachments: List<Mango9PendingAttachment>,
        done: (Boolean) -> Unit,
    ) {
        if (sending.value == true) return
        sending.value = true
        viewModelScope.launch {
            val sent = store.sendSms(phone, senderId, text, attachments)
            sending.postValue(false)
            coreContext.postOnMainThread { done(sent) }
        }
    }

    fun notifyTyping() = store.notifyTyping()

    fun attachment(uri: Uri): Mango9PendingAttachment? = store.attachment(uri)

    fun roomTitle(room: Mango9ChatRoom): String = store.roomTitle(room)

    fun userName(userId: Int): String = store.userName(userId)

    fun updateGroupMembers(roomId: String, userIds: Set<Int>, done: (Boolean) -> Unit) {
        viewModelScope.launch {
            val saved = store.updateGroupMembers(roomId, userIds)
            coreContext.postOnMainThread { done(saved) }
        }
    }

    fun deleteConversation(roomId: String) = store.deleteConversationLocally(roomId)

    fun clearError() = store.clearError()

    fun currentState(): Mango9ChatState = store.state.value

    fun loadConversationInsights(phone: String) {
        val messages = store.state.value.smsMessages.toList()
        viewModelScope.launch {
            conversationInsightsEvent.postValue(Event(insights.load(phone, messages)))
        }
    }
}
