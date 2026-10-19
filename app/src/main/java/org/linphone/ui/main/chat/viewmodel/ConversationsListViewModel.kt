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
package org.linphone.ui.main.chat.viewmodel

import androidx.annotation.AnyThread
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import org.linphone.contacts.ContactSearch
import org.linphone.core.Account
import org.linphone.core.GlobalState
import org.linphone.ui.main.model.ContactSuggestionRows
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MutableLiveData
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.R
import org.linphone.contacts.ContactsManager
import org.linphone.core.Address
import org.linphone.core.ChatMessage
import org.linphone.core.ChatRoom
import org.linphone.core.Conference
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Friend
import org.linphone.core.tools.Log
import org.linphone.mango9.Mango9ChatRouting
import org.linphone.mango9.Mango9ChatRoomNavigation
import org.linphone.mango9.Mango9NativeConversationRoute
import org.linphone.mango9.Mango9SmsRouting
import org.linphone.ui.main.chat.model.ConversationModel
import org.linphone.ui.main.chat.model.ConversationModelWrapper
import org.linphone.ui.main.model.ConversationContactOrSuggestionModel
import org.linphone.ui.main.viewmodel.AbstractMainViewModel
import org.linphone.utils.AppUtils
import org.linphone.utils.Event
import org.linphone.utils.LinphoneUtils

class ConversationsListViewModel
    @UiThread
    constructor() : AbstractMainViewModel() {
    companion object {
        private const val TAG = "[Conversations List ViewModel]"
    }

    val conversations = MutableLiveData<ArrayList<ConversationModelWrapper>>()

    val fetchInProgress = MutableLiveData<Boolean>()

    val mango9MessagesAvailable = MutableLiveData(false)

    val chatRoomCreatedEvent: MutableLiveData<Event<Mango9NativeConversationRoute>> by lazy {
        MutableLiveData()
    }

    private val tempConversationsList = ArrayList<ConversationModelWrapper>()

    private val revision = AtomicLong()
    private var searchRevision = -1L
    private var primaryRevision = -1L

    @Volatile private var suspended = false

    @Volatile private var closed = false
    private val suggestionRows = ContactSuggestionRows()
    private var visibleSuggestions = emptyList<ConversationContactOrSuggestionModel>()
    val showResultsLimitReached = MutableLiveData(false)
    private val contactSearch: ContactSearch = ContactSearch(viewModelScope) { token, candidates, limited ->
        if (isCurrent(searchRevision) && contactSearchCurrent(token)) {
            visibleSuggestions = suggestionRows.replace(candidates)
            publish(searchRevision, limited)
        }
    }

    private fun contactSearchCurrent(token: Long): Boolean = contactSearch.isCurrent(token)

    private val roomNavigation = Mango9ChatRoomNavigation { route ->
        fetchInProgress.postValue(false)
        if (route != null) {
            chatRoomCreatedEvent.postValue(Event(route))
        } else {
            showRedToast(R.string.conversation_failed_to_create_toast, R.drawable.warning_circle)
        }
    }

    private val coreListener = object : CoreListenerStub() {
        override fun onDefaultAccountChanged(core: Core, defaultAccount: Account?) { refresh() }

        override fun onGlobalStateChanged(core: Core, state: GlobalState?, message: String) {
            if (state == GlobalState.On) refresh()
        }

        @WorkerThread
        override fun onChatRoomStateChanged(
            core: Core,
            chatRoom: ChatRoom,
            state: ChatRoom.State?
        ) {
            Log.i(
                "$TAG Conversation [${LinphoneUtils.getConversationId(chatRoom)}] state changed [$state]"
            )

            when (state) {
                ChatRoom.State.Created -> addChatRoom(chatRoom)
                ChatRoom.State.Deleted -> removeChatRoom(chatRoom)
                else -> {}
            }
        }

        @WorkerThread
        override fun onMessageSent(core: Core, chatRoom: ChatRoom, message: ChatMessage) {
            val id = LinphoneUtils.getConversationId(chatRoom)
            val found = tempConversationsList.find {
                it.conversationModel?.id == id
            }
            if (found == null) {
                Log.i("$TAG Message sent for a conversation not yet in the list (probably was empty), adding it")
                addChatRoom(chatRoom)
            } else {
                Log.i("$TAG Message sent for an existing conversation, re-order them")
                reorderChatRooms()
            }
        }

        @WorkerThread
        override fun onMessagesReceived(
            core: Core,
            chatRoom: ChatRoom,
            messages: Array<out ChatMessage>
        ) {
            val id = LinphoneUtils.getConversationId(chatRoom)
            val found = tempConversationsList.find {
                it.conversationModel?.id == id
            }
            if (found == null) {
                Log.i("$TAG Message(s) received for a conversation not yet in the list (probably was empty), adding it")
                addChatRoom(chatRoom)
            } else {
                Log.i("$TAG Message(s) received for an existing conversation, re-order them")
                reorderChatRooms()
            }
        }
    }

    private val contactsListener = object : ContactsManager.ContactsListener {
        @WorkerThread
        override fun onContactsLoaded() {
            Log.i("$TAG Contacts have been (re)loaded, updating list")
            refresh()
        }

        @WorkerThread
        override fun onContactFoundInRemoteDirectory(friend: Friend) { }
    }

    init {
        fetchInProgress.value = true

        coreContext.postOnCoreThread { core ->
            coreContext.contactsManager.addListener(contactsListener)
            core.addListener(coreListener)

            refresh()
        }
    }

    @UiThread
    override fun onCleared() {
        closed = true
        roomNavigation.dispose()
        cancelContactSearch()
        super.onCleared()

        coreContext.postOnCoreThread { core ->
            tempConversationsList.forEach(ConversationModelWrapper::destroy)
            tempConversationsList.clear()
            coreContext.contactsManager.removeListener(contactsListener)
            core.removeListener(coreListener)
        }
    }

    @UiThread
    override fun filter() {
        suspended = false
        refresh(debounce = true)
    }

    @UiThread
    fun cancelContactSearch() {
        suspended = true
        revision.incrementAndGet()
        contactSearch.cancel()
        coreContext.postOnCoreThread {
            suggestionRows.clear()
            visibleSuggestions = emptyList()
        }
    }

    @AnyThread
    private fun refresh(debounce: Boolean = false) {
        if (suspended || closed) return
        val token = revision.incrementAndGet()
        val filter = currentFilter
        contactSearch.cancel()
        viewModelScope.launch {
            if (debounce && filter.isNotEmpty()) delay(250)
            coreContext.postOnCoreThread {
                if (isCurrent(token)) computeChatRoomsList(filter, token)
            }
        }
    }

    private fun isCurrent(token: Long) = !closed && !suspended && revision.get() == token

    @UiThread
    fun revealMoreContacts(near: Int) {
        val token = revision.get()
        coreContext.postOnCoreThread {
            if (isCurrent(token) && searchRevision == token) {
                suggestionRows.reveal(near - tempConversationsList.size)?.let {
                    visibleSuggestions = it
                    publish(token, showResultsLimitReached.value == true)
                }
            }
        }
    }

    @WorkerThread
    private fun publish(token: Long, limited: Boolean = false) {
        val list = ArrayList(tempConversationsList)
        list.addAll(visibleSuggestions.map { ConversationModelWrapper(null, it) })
        coreContext.postOnMainThread {
            if (isCurrent(token)) {
                showResultsLimitReached.value = limited
                conversations.value = list
                fetchInProgress.value = false
            }
        }
    }

    @WorkerThread
    private fun computeChatRoomsList(filter: String, token: Long) {
        if (coreContext.core.globalState != GlobalState.On) return
        tempConversationsList.forEach(ConversationModelWrapper::destroy)

        if (conversations.value.orEmpty().isEmpty()) {
            fetchInProgress.postValue(true)
        }

        val isFilterEmpty = filter.isEmpty()
        val list = arrayListOf<ConversationModelWrapper>()

        val account = LinphoneUtils.getDefaultAccount()
        val chatRooms = if (filter.isEmpty()) {
            account?.chatRooms
        } else {
            account?.filterChatRooms(filter)
        }
        for (chatRoom in chatRooms.orEmpty()) {
            val model = ConversationModel(chatRoom)
            list.add(ConversationModelWrapper(model))
        }

        primaryRevision = token
        tempConversationsList.clear()
        tempConversationsList.addAll(list)
        suggestionRows.clear()
        visibleSuggestions = emptyList()
        publish(token)
        if (!isFilterEmpty) {
            searchRevision = token
            fetchInProgress.postValue(true)
            contactSearch.search(filter, corePreferences.contactsFilter)
        }
    }

    @WorkerThread
    private fun addChatRoom(chatRoom: ChatRoom) {
        if (suspended || closed || primaryRevision != revision.get()) return
        val identifier = chatRoom.identifier
        val chatRoomAccount = chatRoom.account
        val defaultAccount = LinphoneUtils.getDefaultAccount()
        if (defaultAccount == null || chatRoomAccount == null || chatRoomAccount != defaultAccount) {
            Log.w(
                "$TAG Chat room with identifier [$identifier] was created but not displaying it because it doesn't belong to currently default account"
            )
            return
        }

        val conferenceInfo = chatRoom.conferenceInfo
        if (conferenceInfo != null) {
            Log.w(
                "$TAG Chat room with identifier [$identifier] was created but not displaying it because it is related to a conference"
            )
            return
        }

        val hideEmptyChatRooms = coreContext.core.config.getBool("misc", "hide_empty_chat_rooms", true)
        // Hide empty chat rooms only applies to 1-1 conversations
        if (hideEmptyChatRooms && !LinphoneUtils.isChatRoomAGroup(chatRoom) && chatRoom.lastMessageInHistory == null) {
            Log.w("$TAG Chat room with identifier [$identifier] is empty, not adding it to match Core setting")
            return
        }

        val currentList = tempConversationsList
        val found = currentList.find {
            it.conversationModel?.chatRoom?.identifier == identifier
        }
        if (found != null) {
            Log.w("$TAG Created chat room with identifier [$identifier] is already in the list, skipping")
            return
        }

        if (currentFilter.isNotEmpty()) {
            val filteredRooms = defaultAccount.filterChatRooms(currentFilter)
            val found = filteredRooms.find {
                it == chatRoom
            }
            if (found == null) return
        }

        val newList = arrayListOf<ConversationModelWrapper>()
        val model = ConversationModel(chatRoom)
        newList.add(ConversationModelWrapper(model))
        newList.addAll(currentList)
        Log.i("$TAG Adding chat room with identifier [$identifier] to list")
        tempConversationsList.clear()
            tempConversationsList.addAll(newList)
            publish(revision.get(), showResultsLimitReached.value == true)
    }

    @WorkerThread
    private fun removeChatRoom(chatRoom: ChatRoom) {
        if (suspended || closed || primaryRevision != revision.get()) return
        val currentList = tempConversationsList
        val identifier = chatRoom.identifier
        val found = currentList.find {
            it.conversationModel?.chatRoom?.identifier == identifier
        }
        if (found != null) {
            val newList = arrayListOf<ConversationModelWrapper>()
            newList.addAll(currentList)
            newList.remove(found)
            found.destroy()
            Log.i("$TAG Removing chat room with identifier [$identifier] from list")
            tempConversationsList.clear()
            tempConversationsList.addAll(newList)
            publish(revision.get(), showResultsLimitReached.value == true)
        } else {
            Log.w(
                "$TAG Failed to find item in list matching deleted chat room identifier [$identifier]"
            )
        }

        showGreenToast(R.string.conversation_deleted_toast, R.drawable.chat_teardrop_text)
    }

    @WorkerThread
    private fun reorderChatRooms() {
        if (suspended || closed || primaryRevision != revision.get()) return
        if (currentFilter.isNotEmpty()) {
            Log.w("$TAG List filter isn't empty, do not re-order list")
            return
        }

        Log.i("$TAG Re-ordering conversations")
        val sortedList = arrayListOf<ConversationModelWrapper>()
        sortedList.addAll(tempConversationsList)
        sortedList.sortByDescending {
            it.conversationModel?.chatRoom?.lastUpdateTime
        }
        tempConversationsList.clear()
        tempConversationsList.addAll(sortedList)
        publish(revision.get())
    }

    @WorkerThread
    fun createOneToOneChatRoomWith(remote: Address, shareRequestId: String? = null) {
        if (Mango9ChatRouting.openIfNeeded(remote, shareRequestId)) return
        if (Mango9SmsRouting.openIfNeeded(remote, shareRequestId = shareRequestId)) return
        val core = coreContext.core
        val account = core.defaultAccount
        if (account == null || core.globalState != GlobalState.On) {
            Log.e(
                "$TAG No default account found, can't create conversation with [${remote.asStringUriOnly()}]!"
            )
            return
        }

        fetchInProgress.postValue(true)

        val params = coreContext.core.createConferenceParams(null)
        params.isChatEnabled = true
        params.isGroupEnabled = false
        params.subject = AppUtils.getString(R.string.conversation_one_to_one_hidden_subject)
        params.account = account

        val chatParams = params.chatParams ?: return
        chatParams.ephemeralLifetime = 0 // Make sure ephemeral is disabled by default

        val sameDomain = remote.domain == corePreferences.defaultDomain && remote.domain == account.params.domain
        if (account.params.instantMessagingEncryptionMandatory && sameDomain) {
            Log.i("$TAG Account is in secure mode & domain matches, creating an E2E encrypted conversation")
            chatParams.backend = ChatRoom.Backend.FlexisipChat
            params.securityLevel = Conference.SecurityLevel.EndToEnd
        } else if (!account.params.instantMessagingEncryptionMandatory) {
            if (LinphoneUtils.isEndToEndEncryptedChatAvailable(core)) {
                Log.i(
                    "$TAG Account is in interop mode but LIME is available, creating an E2E encrypted conversation"
                )
                chatParams.backend = ChatRoom.Backend.FlexisipChat
                params.securityLevel = Conference.SecurityLevel.EndToEnd
            } else {
                Log.i(
                    "$TAG Account is in interop mode but LIME isn't available, creating a SIP simple conversation"
                )
                chatParams.backend = ChatRoom.Backend.Basic
                params.securityLevel = Conference.SecurityLevel.None
            }
        } else {
            Log.e(
                "$TAG Account is in secure mode, can't chat with SIP address of different domain [${remote.asStringUriOnly()}]"
            )
            fetchInProgress.postValue(false)
            showRedToast(R.string.conversation_invalid_participant_due_to_security_mode_toast, R.drawable.warning_circle)
            return
        }

        val participants = arrayOf(remote)
        val localAddress = account.params.identityAddress
        val existingChatRoom = core.searchChatRoom(params, localAddress, null, participants)
        if (existingChatRoom == null) {
            Log.i(
                "$TAG No existing 1-1 conversation between local account [${localAddress?.asStringUriOnly()}] and remote [${remote.asStringUriOnly()}] was found for given parameters, let's create it"
            )
            val chatRoom = core.createChatRoom(params, participants)
            if (chatRoom != null) {
                if (chatParams.backend == ChatRoom.Backend.FlexisipChat) {
                    val state = chatRoom.state
                    if (state == ChatRoom.State.Created) {
                        val id = LinphoneUtils.getConversationId(chatRoom)
                        Log.i("$TAG 1-1 conversation [$id] has been created")
                        fetchInProgress.postValue(false)
                        roomNavigation.watch(chatRoom, shareRequestId)
                    } else {
                        Log.i("$TAG Conversation isn't in Created state yet (state is [$state]), wait for it")
                        roomNavigation.watch(chatRoom, shareRequestId)
                    }
                } else {
                    val id = LinphoneUtils.getConversationId(chatRoom)
                    Log.i("$TAG Conversation successfully created [$id]")
                    fetchInProgress.postValue(false)
                    roomNavigation.watch(chatRoom, shareRequestId)
                }
            } else {
                Log.e("$TAG Failed to create 1-1 conversation with [${remote.asStringUriOnly()}]!")
                fetchInProgress.postValue(false)
                showRedToast(R.string.conversation_failed_to_create_toast, R.drawable.warning_circle)
            }
        } else {
            Log.w(
                "$TAG A 1-1 conversation between local account [${localAddress?.asStringUriOnly()}] and remote [${remote.asStringUriOnly()}] for given parameters already exists!"
            )
            fetchInProgress.postValue(false)
            roomNavigation.watch(existingChatRoom, shareRequestId)
        }
    }
}
