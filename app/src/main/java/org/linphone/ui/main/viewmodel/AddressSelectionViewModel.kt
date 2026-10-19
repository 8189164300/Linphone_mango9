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
package org.linphone.ui.main.viewmodel

import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import org.linphone.mango9.Mango9ContactPage
import org.linphone.mango9.Mango9ContactSearchLimits
import java.text.Collator
import java.util.Locale
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.R
import org.linphone.contacts.ContactsManager
import org.linphone.contacts.NativeContactValue
import org.linphone.contacts.sortName
import org.linphone.contacts.getListOfSipAddressesAndPhoneNumbers
import org.linphone.core.Address
import org.linphone.core.ChatRoom
import org.linphone.core.Friend
import org.linphone.core.MagicSearch
import org.linphone.core.MagicSearchListenerStub
import org.linphone.core.SearchResult
import org.linphone.mediastream.Log
import org.linphone.ui.main.contacts.model.ContactAvatarModel
import org.linphone.ui.main.contacts.model.ContactNumberOrAddressClickListener
import org.linphone.ui.main.contacts.model.ContactNumberOrAddressModel
import org.linphone.ui.main.model.ConversationContactOrSuggestionModel
import org.linphone.ui.main.model.SelectedAddressModel
import org.linphone.utils.AppUtils
import org.linphone.utils.Event
import org.linphone.utils.LinphoneUtils
import kotlin.collections.find
import kotlin.collections.orEmpty

abstract class AddressSelectionViewModel
    @UiThread
    constructor() : DefaultAccountChangedViewModel() {
    companion object {
        private const val TAG = "[Address Selection ViewModel]"
    }

    val multipleSelectionMode = MutableLiveData<Boolean>()

    val selection = MutableLiveData<ArrayList<SelectedAddressModel>>()

    val selectionCount = MutableLiveData<String>()

    val searchFilter = MutableLiveData<String>()

    val searchInProgress = MutableLiveData<Boolean>()

    val modelsList = MutableLiveData<ArrayList<ConversationContactOrSuggestionModel>>()

    val isEmpty = MutableLiveData<Boolean>()

    val showResultsLimitReached = MutableLiveData<Boolean>()

    val showNumberOrAddressPickerDialogEvent: MutableLiveData<Event<List<ContactNumberOrAddressModel>>> by lazy {
        MutableLiveData()
    }

    val dismissNumberOrAddressPickerDialogEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    protected var magicSearchSourceFlags = MagicSearch.Source.All.toInt()

    protected var skipConversation: Boolean = true

    @Volatile private var currentFilter = ""

    @Volatile private var searchSuspended = false
    private val searchGeneration = AtomicLong()
    private var searchJob: Job? = null
    // Everything below is owned by the SIP thread. No native objects are used
    // by background DiffUtil or by a debounce coroutine.
    private var searches: List<Pair<MagicSearch, MagicSearchListenerStub>> = emptyList()
    private val contactPage = Mango9ContactPage<ContactCandidate>()
    private val visibleModels = arrayListOf<ConversationContactOrSuggestionModel>()
    private val ownedAvatars = arrayListOf<ContactAvatarModel>()
    private var conversationCount = 0
    private var pageGeneration = -1L
    private var sortRequest = 0L

    private data class ContactCandidate(
        val address: Address?,
        val friend: Friend?,
        val name: String,
        val domain: String? = null,
        val request: Boolean = false,
        val native: NativeContactValue? = null,
        val favorite: Boolean = false,
    )

    protected val numberOrAddressClickListener = object : ContactNumberOrAddressClickListener {
        @UiThread
        override fun onClicked(model: ContactNumberOrAddressModel) {
            val address = model.address
            coreContext.postOnCoreThread {
                if (address != null) {
                    Log.i(
                        "$TAG Selected address [${model.address.asStringUriOnly()}] from friend [${model.friend.name}]"
                    )
                    onAddressSelected(model.address, model.friend)
                }
            }

            dismissNumberOrAddressPickerDialogEvent.postValue(Event(true))
        }

        @UiThread
        override fun onLongPress(model: ContactNumberOrAddressModel) {
        }
    }

    private val contactsListener = object : ContactsManager.ContactsListener {
        @WorkerThread
        override fun onContactsLoaded() {
            if (!searchSuspended) {
                coreContext.postOnMainThread {
                if (!searchSuspended) applyFilter(currentFilter, immediately = true)
            }
            }
        }

        @WorkerThread
        override fun onContactFoundInRemoteDirectory(friend: Friend) { }
    }

    @WorkerThread
    abstract fun onSingleAddressSelected(address: Address, friend: Friend?)

    @WorkerThread
    open fun onFriendSelectedDirectly(friend: Friend): Boolean = false

    init {
        multipleSelectionMode.value = false
        isEmpty.value = true

        coreContext.postOnCoreThread {
            coreContext.contactsManager.addListener(contactsListener)
        }
        applyFilter(currentFilter, immediately = true)
    }

    @UiThread
    override fun onCleared() {
        cancelContactSearch()
        coreContext.postOnCoreThread {
            coreContext.contactsManager.removeListener(contactsListener)
        }
        super.onCleared()
    }

    @UiThread
    fun clearFilter() {
        if (searchFilter.value.orEmpty().isNotEmpty()) {
            searchFilter.value = ""
        }
    }

    @UiThread
    fun switchToMultipleSelectionMode() {
        Log.i("$TAG Multiple selection mode ON")
        multipleSelectionMode.value = true

        selectionCount.postValue(
            AppUtils.getStringWithPlural(
                R.plurals.selection_count_label,
                0,
                "0"
            )
        )
    }

    @WorkerThread
    fun updateSelectedParticipants(selectedAddresses: List<Address>) {
        for (address in selectedAddresses) {
            val found = modelsList.value.orEmpty().find {
                matchesSelection(it, address)
            }
            found?.selected?.postValue(true)
        }
    }

    @WorkerThread
    fun addAddressModelToSelection(model: SelectedAddressModel) {
        val actual = selection.value.orEmpty()
        if (actual.find {
            it.address.weakEqual(model.address)
        } == null
        ) {
            Log.i("$TAG Adding [${model.address.asStringUriOnly()}] address to selection")

            val list = arrayListOf<SelectedAddressModel>()
            list.add(model)
            list.addAll(actual)

            val found = modelsList.value.orEmpty().find {
                matchesSelection(it, model.address, model.avatarModel)
            }
            found?.selected?.postValue(true)

            selectionCount.postValue(
                AppUtils.getStringWithPlural(
                    R.plurals.selection_count_label,
                    list.size,
                    list.size.toString()
                )
            )
            selection.postValue(list)
        } else {
            Log.w("$TAG Address is already in selection, doing nothing")
        }
    }

    @WorkerThread
    fun removeAddressModelFromSelection(model: SelectedAddressModel) {
        val actual = selection.value.orEmpty()
        if (actual.find {
            it.address.weakEqual(model.address)
        } != null
        ) {
            Log.i("$TAG Removing [${model.address.asStringUriOnly()}] address from selection")

            val list = arrayListOf<SelectedAddressModel>()
            list.addAll(actual)
            // Selected-address avatars are cached and owned by ContactsManager.
            list.remove(model)

            val found = modelsList.value.orEmpty().find {
                matchesSelection(it, model.address, model.avatarModel)
            }
            found?.selected?.postValue(false)

            selectionCount.postValue(
                AppUtils.getStringWithPlural(
                    R.plurals.selection_count_label,
                    list.size,
                    list.size.toString()
                )
            )
            selection.postValue(list)
        } else {
            Log.w("$TAG Address isn't in selection, doing nothing")
        }
    }

    @WorkerThread
    private fun matchesSelection(model: ConversationContactOrSuggestionModel, address: Address, avatar: ContactAvatarModel? = null): Boolean {
        if (model.nativeContact != null) {
            if (avatar?.id == model.id) return true
            return coreContext.contactsManager.nativeContactsSnapshot()
                .findDestination(address.asStringUriOnly(), address.username)?.id == model.nativeContact.id
        }
        return model.address.weakEqual(address) ||
            (model.friend != null && avatar?.nativeContact == null && model.friend == avatar?.friend)
    }

    @UiThread
    fun applyFilter(filter: String, immediately: Boolean = false) {
        searchSuspended = false
        currentFilter = filter
        val token = searchGeneration.incrementAndGet()
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (!immediately && filter.isNotEmpty()) delay(250)
            coreContext.postOnCoreThread {
                if (isSearchCurrent(token)) startSearch(filter, magicSearchSourceFlags, token)
            }
        }
    }

    @UiThread
    fun cancelContactSearch() {
        searchSuspended = true
        searchGeneration.incrementAndGet()
        searchJob?.cancel()
        coreContext.postOnCoreThread {
            retireSearches()
            contactPage.clear()
            retireAvatars()
            visibleModels.clear()
        }
    }

    private fun isSearchCurrent(token: Long) = !searchSuspended && searchGeneration.get() == token

    @WorkerThread
    private fun retireSearches() {
        searches.forEach { (search, listener) -> search.removeListener(listener) }
        searches = emptyList()
    }

    @WorkerThread
    private fun retireAvatars() {
        ownedAvatars.forEach(ContactAvatarModel::destroy)
        ownedAvatars.clear()
    }

    @WorkerThread
    private fun startSearch(filter: String, sources: Int, token: Long) {
        retireSearches()
        val domain = corePreferences.contactsFilter
        var contacts: Array<SearchResult>? = null
        var favorites: Array<SearchResult>? = null
        val directory = coreContext.contactsManager.nativeContactsSnapshot()
        val firstName = corePreferences.sortContactsByFirstName
        var nativeContacts: List<NativeContactValue>? = null

        fun publishAvailable() {
            val regular = contacts ?: return
            val starred = favorites ?: return
            val native = nativeContacts ?: return
            processMagicSearchResults(regular, starred, native, filter, token)
        }
        viewModelScope.launch(Dispatchers.Default) {
            val found = directory.search(filter, domain, true, firstName) { !isSearchCurrent(token) }
            coreContext.postOnCoreThread {
                if (isSearchCurrent(token) && directory === coreContext.contactsManager.nativeContactsSnapshot()) {
                    nativeContacts = found
                    publishAvailable()
                }
            }
        }
        coreContext.postOnMainThread {
            if (isSearchCurrent(token)) {
                searchInProgress.value = true
                showResultsLimitReached.value = false
            }
        }

        // Each request has its own SDK instance, so late callbacks can be
        // identified reliably. Both directory queries are asynchronous.
        fun query(flags: Int, favoriteQuery: Boolean) {
            val search = coreContext.core.createMagicSearch()
            search.limitedSearch = true
            search.searchLimit = Mango9ContactSearchLimits.SDK_RESULTS
            val listener = object : MagicSearchListenerStub() {
                override fun onSearchResultsReceived(magicSearch: MagicSearch) {
                    if (!isSearchCurrent(token) || magicSearch.nativePointer != search.nativePointer) return
                    if (favoriteQuery) favorites = magicSearch.lastSearch else contacts = magicSearch.lastSearch
                    publishAvailable()
                }

                override fun onResultsLimitReached(magicSearch: MagicSearch, sourcesFlag: Int) {
                    coreContext.postOnMainThread {
                        if (isSearchCurrent(token)) showResultsLimitReached.value = true
                    }
                }
            }
            searches = searches + (search to listener)
            search.addListener(listener)
            search.getContactsListAsync(filter, domain, flags, MagicSearch.Aggregation.Friend)
        }
        query(sources, false)
        query(MagicSearch.Source.FavoriteFriends.toInt(), true)
    }

    @WorkerThread
    private fun processMagicSearchResults(results: Array<SearchResult>, favorites: Array<SearchResult>, nativeResults: List<NativeContactValue>, filter: String, token: Long) {
        if (!isSearchCurrent(token)) return
        val defaultAccountDomain = LinphoneUtils.getDefaultAccount()?.params?.domain
        val defaultAccountAddress = coreContext.core.defaultAccount?.params?.identityAddress
        val favoritesList = arrayListOf<ContactCandidate>()
        val contactsList = arrayListOf<ContactCandidate>()
        val suggestionsList = arrayListOf<ContactCandidate>()
        val requestList = arrayListOf<ContactCandidate>()
        val seenFriends = hashSetOf<Long>()
        val seenSuggestions = hashSetOf<String>()

        fun contact(result: SearchResult, destination: MutableList<ContactCandidate>) {
            val friend = result.friend ?: return
            if (!seenFriends.add(friend.nativePointer)) return
            // Do not obtain an Address wrapper for every result. Resolve the
            // destination only when its row is revealed or selected.
            destination.add(ContactCandidate(null, friend, friend.name.orEmpty()))
        }
        favorites.forEach { contact(it, favoritesList) }
        results.forEach { result ->
            if (result.friend != null) {
                contact(result, contactsList)
            } else {
                val address = result.address ?: return@forEach
                val requested = result.sourceFlags == MagicSearch.Source.Request.toInt()
                if (!requested && defaultAccountAddress != null && address.weakEqual(defaultAccountAddress)) return@forEach
                if (!seenSuggestions.add(address.asStringUriOnly())) return@forEach
                val row =
                    ContactCandidate(address, null, address.username ?: address.domain.orEmpty(), defaultAccountDomain, requested)
                if (requested) requestList.add(row) else suggestionsList.add(row)
            }
        }
        val firstName = corePreferences.sortContactsByFirstName
        val request = ++sortRequest
        viewModelScope.launch(Dispatchers.Default) {
            val native = nativeResults.map {
                ContactCandidate(null, null, it.sortName(firstName), native = it, favorite = coreContext.contactsManager.nativeFavorite(it))
            }
            val collator = Collator.getInstance(Locale.getDefault())
            val ordering = Comparator<ContactCandidate> { a, b -> collator.compare(a.name, b.name) }
            // Compare captured strings only. SDK objects stay on the core queue.
            val candidates = (native.filter { it.favorite } + favoritesList).sortedWith(ordering) +
                (native.filterNot { it.favorite } + contactsList).sortedWith(ordering) +
                suggestionsList.sortedWith(ordering) + requestList
            coreContext.postOnCoreThread {
                if (!isSearchCurrent(token) || request != sortRequest) return@postOnCoreThread
                retireAvatars()
                visibleModels.clear()
                if (!skipConversation) visibleModels.addAll(getConversationsList(filter))
                conversationCount = visibleModels.size
                pageGeneration = token
                appendCandidates(contactPage.replace(candidates))
                publishModels(token)
            }
        }
    }

    @WorkerThread
    private fun appendCandidates(candidates: List<ContactCandidate>) {
        for (candidate in candidates) {
            val native = candidate.native
            if (native != null) {
                val model =
                    ConversationContactOrSuggestionModel(nativeContact = native, nativeFavorite = candidate.favorite)
                val avatar = ContactAvatarModel(native, candidate.favorite)
                ownedAvatars.add(avatar)
                model.avatarModel.postValue(avatar)
                model.selected.postValue(selection.value.orEmpty().any { matchesSelection(model, it.address, it.avatarModel) })
                visibleModels.add(model)
                continue
            }
            val friend = candidate.friend
            val address = candidate.address ?: friend?.let(LinphoneUtils::getFirstAvailableAddressForFriend) ?: continue
            val model = if (friend != null) {
                ConversationContactOrSuggestionModel(address, friend = friend).apply {
                    val avatar = coreContext.contactsManager.getContactAvatarModelForFriend(friend)
                    ownedAvatars.add(avatar)
                    avatarModel.postValue(avatar)
                }
            } else {
                ConversationContactOrSuggestionModel(address, defaultAccountDomain = if (candidate.request) null else candidate.domain) {
                    coreContext.startAudioCall(address)
                }.apply {
                    val avatar = getContactAvatarModelForAddress(address)
                    ownedAvatars.add(avatar)
                    avatarModel.postValue(avatar)
                }
            }
            if (selection.value.orEmpty().any {
                it.address.weakEqual(address) || (friend != null && it.avatarModel?.friend == friend)
            }
            ) {
                model.selected.postValue(true)
            }
            visibleModels.add(model)
        }
    }

    @UiThread
    fun revealMoreContacts(near: Int) {
        val token = searchGeneration.get()
        coreContext.postOnCoreThread {
            if (isSearchCurrent(token) && pageGeneration == token) {
                val additional = contactPage.reveal(near - conversationCount)
                if (additional.isNotEmpty()) {
                    appendCandidates(additional)
                    publishModels(token)
                }
            }
        }
    }

    @WorkerThread
    private fun publishModels(token: Long) {
        val snapshot = ArrayList(visibleModels)
        coreContext.postOnMainThread {
            if (isSearchCurrent(token)) {
                searchInProgress.value = false
                modelsList.value = snapshot
                isEmpty.value = snapshot.isEmpty()
            }
        }
    }

    @WorkerThread
    private fun getConversationsList(filter: String): ArrayList<ConversationContactOrSuggestionModel> {
        val conversationsList = arrayListOf<ConversationContactOrSuggestionModel>()
        for (chatRoom in LinphoneUtils.getDefaultAccount()?.chatRooms.orEmpty()) {
            // Do not list conversations in which we can't send a message
            val isBasic = chatRoom.hasCapability(ChatRoom.Capabilities.Basic.toInt())
            if (chatRoom.isReadOnly || (!isBasic && chatRoom.participants.isEmpty())) continue

            val isOneToOne = chatRoom.hasCapability(ChatRoom.Capabilities.OneToOne.toInt())
            val conversationId = LinphoneUtils.getConversationId(chatRoom)
            val remoteAddress = chatRoom.peerAddress
            val matchesFilter: Any? = if (filter.isEmpty()) {
                null
            } else {
                if (isBasic) {
                    // Search in address but also in contact name if exists
                    val model =
                        coreContext.contactsManager.getContactAvatarModelForAddress(remoteAddress)
                    if (model.contactName?.contains(filter, ignoreCase = true) == true ||
                        remoteAddress.asStringUriOnly().contains(
                                filter,
                                ignoreCase = true
                            )
                    ) {
                        model
                    } else {
                        null
                    }
                } else {
                    if (chatRoom.subjectUtf8.orEmpty().contains(filter, ignoreCase = true)) {
                        chatRoom
                    } else {
                        chatRoom.participants.find {
                            // Search in address but also in contact name if exists
                            val model =
                                coreContext.contactsManager.getContactAvatarModelForAddress(
                                    it.address
                                )
                            model.contactName?.contains(
                                filter,
                                ignoreCase = true
                            ) == true || it.address.asStringUriOnly().contains(
                                filter,
                                ignoreCase = true
                            )
                        }
                    }
                }
            }
            if (filter.isEmpty() || matchesFilter != null) {
                val friend = if (isBasic) {
                    coreContext.contactsManager.findContactByAddress(remoteAddress)
                } else {
                    val participantAddress = chatRoom.participants.firstOrNull()?.address
                    if (participantAddress != null) {
                        val friendFound = coreContext.contactsManager.findContactByAddress(
                            participantAddress
                        )
                        if (friendFound == null) {
                            val fakeFriend = coreContext.core.createFriend()
                            fakeFriend.name = LinphoneUtils.getDisplayName(participantAddress)
                            fakeFriend
                        } else {
                            friendFound
                        }
                    } else {
                        null
                    }
                }
                val subject = if (isOneToOne) {
                    friend?.name
                } else {
                    chatRoom.subjectUtf8
                }
                val model = ConversationContactOrSuggestionModel(
                    remoteAddress,
                    conversationId,
                    subject,
                    friend
                )

                val avatarModel = if (!isOneToOne) {
                    val fakeFriend = coreContext.core.createFriend()
                    fakeFriend.name = chatRoom.subjectUtf8
                    val avatarModel = ContactAvatarModel(fakeFriend)
                    avatarModel.defaultToConversationIcon.postValue(true)
                    avatarModel
                } else {
                    coreContext.contactsManager.getContactAvatarModelForFriend(friend)
                }
                ownedAvatars.add(avatarModel)
                model.avatarModel.postValue(avatarModel)
                conversationsList.add(model)
            }
        }
        return conversationsList
    }

    @UiThread
    fun handleClickOnContactModel(model: ConversationContactOrSuggestionModel) {
        coreContext.postOnCoreThread { core ->
        if (model.nativeContact != null && coreContext.contactsManager.nativeContactsSnapshot().find(model.id) == null) {
            coreContext.postOnMainThread { applyFilter(currentFilter, immediately = true) }
            showRedToast(R.string.mango9_contact_no_longer_available, R.drawable.warning_circle)
            return@postOnCoreThread
        }
        if (model.selected.value == true) {
            Log.i(
                "$TAG User clicked on already selected item [${model.name}], removing it from selection"
            )
            val found = selection.value.orEmpty().find {
                matchesSelection(model, it.address, it.avatarModel)
            }
            if (found != null) {
                removeAddressModelFromSelection(found)
                return@postOnCoreThread
            } else {
                Log.e("$TAG Failed to find already selected entry matching the one clicked")
            }
        }

            val friend = if (model.nativeContact != null) coreContext.contactsManager.resolveNativeFriend(model.id) else model.friend
            if (friend == null) {
                Log.i("$TAG Friend is null, using address [${model.address.asStringUriOnly()}]")
                val fakeFriend = core.createFriend()
                fakeFriend.addAddress(model.address)
                onAddressSelected(model.address, fakeFriend)
                return@postOnCoreThread
            }

            if (multipleSelectionMode.value != true && onFriendSelectedDirectly(friend)) {
                coreContext.postOnMainThread { clearFilter() }
                return@postOnCoreThread
            }

            val singleAvailableAddress = LinphoneUtils.getSingleAvailableAddressForFriend(friend)
            if (singleAvailableAddress != null) {
                Log.i(
                    "$TAG Only 1 SIP address or phone number found for contact [${friend.name}], using it"
                )
                onAddressSelected(singleAvailableAddress, friend)
            } else {
                val list = friend.getListOfSipAddressesAndPhoneNumbers(numberOrAddressClickListener)
                Log.i(
                    "$TAG [${list.size}] numbers or addresses found for contact [${friend.name}], showing selection dialog"
                )

                showNumberOrAddressPickerDialogEvent.postValue(Event(list))
            }
        }
    }

    @WorkerThread
    private fun onAddressSelected(address: Address, friend: Friend) {
        if (multipleSelectionMode.value == true) {
            val avatarModel = coreContext.contactsManager.getContactAvatarModelForAddress(address)
            val model = SelectedAddressModel(address, avatarModel) {
                removeAddressModelFromSelection(it)
            }
            addAddressModelToSelection(model)
        } else {
            onSingleAddressSelected(address, friend)
        }

        // Clear filter after it was used
        coreContext.postOnMainThread {
            clearFilter()
        }
    }

    @WorkerThread
    private fun getContactAvatarModelForAddress(address: Address): ContactAvatarModel {
        val fakeFriend = coreContext.core.createFriend()
        fakeFriend.name = LinphoneUtils.getDisplayName(address)
        fakeFriend.address = address
        return ContactAvatarModel(fakeFriend)
    }
}
