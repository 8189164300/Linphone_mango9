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
package org.linphone.ui.main.history.viewmodel

import androidx.annotation.AnyThread
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import org.linphone.contacts.ContactSearch
import org.linphone.core.Account
import org.linphone.ui.main.model.ContactSuggestionRows
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MutableLiveData
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.contacts.ContactsManager
import org.linphone.core.CallLog
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Friend
import org.linphone.core.GlobalState
import org.linphone.core.tools.Log
import org.linphone.ui.main.history.model.CallLogModel
import org.linphone.ui.main.history.model.CallLogModelWrapper
import org.linphone.ui.main.model.ConversationContactOrSuggestionModel
import org.linphone.ui.main.viewmodel.AbstractMainViewModel
import org.linphone.utils.Event
import org.linphone.utils.LinphoneUtils

class HistoryListViewModel
    @UiThread
    constructor() : AbstractMainViewModel() {
    companion object {
        private const val TAG = "[History List ViewModel]"
    }

    val callLogs = MutableLiveData<ArrayList<CallLogModelWrapper>>()

    val fetchInProgress = MutableLiveData<Boolean>()

    val historyInsertedEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    val historyDeletedEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    private val tempCallLogsList = ArrayList<CallLogModelWrapper>()

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

    private val coreListener = object : CoreListenerStub() {
        override fun onDefaultAccountChanged(core: Core, defaultAccount: Account?) { refresh() }

        @WorkerThread
        override fun onGlobalStateChanged(core: Core, state: GlobalState?, message: String) {
            if (state == GlobalState.On) {
                Log.i("$TAG Core just started, fetching history")
                refresh()
            }
        }

        @WorkerThread
        override fun onCallLogUpdated(core: Core, callLog: CallLog) {
            Log.i("$TAG A call log was created, updating list")
            refresh()
            historyInsertedEvent.postValue(Event(true))
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
        cancelContactSearch()
        super.onCleared()

        coreContext.postOnCoreThread { core ->
            coreContext.contactsManager.removeListener(contactsListener)
            core.removeListener(coreListener)
        }
    }

    @UiThread
    fun removeAllCallLogs() {
        coreContext.postOnCoreThread { core ->
            // TODO FIXME: remove workaround later
            if (coreContext.core.accountList.size > 1) {
                val account = LinphoneUtils.getDefaultAccount()
                if (account != null) {
                    account.clearCallLogs()
                } else {
                    core.clearCallLogs()
                }
            } else {
                core.clearCallLogs()
            }

            historyDeletedEvent.postValue(Event(true))
            refresh()
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
                if (isCurrent(token)) computeCallLogsList(filter, token)
            }
        }
    }

    private fun isCurrent(token: Long) = !closed && !suspended && revision.get() == token

    @UiThread
    fun revealMoreContacts(near: Int) {
        val token = revision.get()
        coreContext.postOnCoreThread {
            if (isCurrent(token) && searchRevision == token) {
                suggestionRows.reveal(near - tempCallLogsList.size)?.let {
                    visibleSuggestions = it
                    publish(token, showResultsLimitReached.value == true)
                }
            }
        }
    }

    @WorkerThread
    private fun publish(token: Long, limited: Boolean = false) {
        val list = ArrayList(tempCallLogsList)
        list.addAll(visibleSuggestions.map { CallLogModelWrapper(null, it) })
        coreContext.postOnMainThread {
            if (isCurrent(token)) {
                showResultsLimitReached.value = limited
                callLogs.value = list
                fetchInProgress.value = false
            }
        }
    }

    @WorkerThread
    private fun computeCallLogsList(filter: String, token: Long) {
        if (coreContext.core.globalState != GlobalState.On) {
            Log.e("$TAG Core isn't ON yet, do not attempt to get calls history")
            return
        }

        if (callLogs.value.orEmpty().isEmpty()) {
            fetchInProgress.postValue(true)
        }

        val isFilterEmpty = filter.isEmpty()
        val list = arrayListOf<CallLogModelWrapper>()

        val account = LinphoneUtils.getDefaultAccount()
        // Fetch all call logs if only one account to workaround no history issue
        // TODO FIXME: remove workaround later
        val logs = if (coreContext.core.accountList.size > 1) {
            account?.callLogs ?: coreContext.core.callLogs
        } else {
            coreContext.core.callLogs
        }

        for (callLog in logs) {
            val model = CallLogModel(callLog)
            if (isCallLogMatchingFilter(model, filter)) {
                list.add(CallLogModelWrapper(model))
            }
        }

        primaryRevision = token
        tempCallLogsList.clear()
        tempCallLogsList.addAll(list)
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
    private fun isCallLogMatchingFilter(model: CallLogModel, filter: String): Boolean {
        if (filter.isEmpty()) return true

        val friendName = model.avatarModel.contactName ?: LinphoneUtils.getDisplayName(model.address)
        return friendName.contains(filter, ignoreCase = true) || model.address.asStringUriOnly().contains(filter, ignoreCase = true)
    }

}
