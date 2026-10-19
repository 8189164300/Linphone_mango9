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
package org.linphone.ui.main.contacts.viewmodel

import androidx.annotation.UiThread
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import java.io.File
import java.text.Collator
import java.util.ArrayList
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import org.linphone.contacts.NativeContactValue
import org.linphone.contacts.sortName
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.R
import org.linphone.contacts.ContactsManager.ContactsListener
import org.linphone.core.Friend
import org.linphone.core.Address
import org.linphone.core.FriendList
import org.linphone.core.FriendListListenerStub
import org.linphone.core.MagicSearch
import org.linphone.core.MagicSearchListenerStub
import org.linphone.core.SearchResult
import org.linphone.core.tools.Log
import org.linphone.mango9.Mango9ContactPage
import org.linphone.mango9.Mango9ContactSearchLimits
import org.linphone.ui.main.contacts.model.ContactAvatarModel
import org.linphone.ui.main.viewmodel.AbstractMainViewModel
import org.linphone.utils.Event
import org.linphone.utils.FileUtils

class ContactsListViewModel
    @UiThread
    constructor() : AbstractMainViewModel() {
    companion object {
        private const val TAG = "[Contacts List ViewModel]"
    }

    val contactsList = MutableLiveData<ArrayList<ContactAvatarModel>>()

    val favouritesList = MutableLiveData<ArrayList<ContactAvatarModel>>()

    val fetchInProgress = MutableLiveData<Boolean>()

    val showFavourites = MutableLiveData<Boolean>()

    val showFilter = MutableLiveData<Boolean>()

    val isListFiltered = MutableLiveData<Boolean>()

    val areAllContactsDisplayed = MutableLiveData<Boolean>()

    val searchInProgress = MutableLiveData<Boolean>()

    val isDefaultAccountLinphone = MutableLiveData<Boolean>()

    val showResultsLimitReached = MutableLiveData<Boolean>()

    val disableAddContact = MutableLiveData<Boolean>()

    val vCardTerminatedEvent: MutableLiveData<Event<Pair<String, File>>> by lazy {
        MutableLiveData()
    }

    val cardDavSynchronizationCompletedEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    private var domainFilter = ""
    private val searchGeneration = AtomicLong()

    @Volatile private var searchSuspended = false
    private var searchJob: Job? = null
    // SDK searches, descriptors and owned avatar listeners live on the SIP queue.
    private var searches: List<Pair<MagicSearch, MagicSearchListenerStub>> = emptyList()

    private data class ContactCandidate(val friend: Friend?, val address: Address?, val sortName: String, val native: NativeContactValue? = null)

    private class ContactRows {
        val page = Mango9ContactPage<ContactCandidate>()
        val models = arrayListOf<ContactAvatarModel>()
        var generation = -1L
        var sortRequest = 0L

        fun clear() {
            models.forEach(ContactAvatarModel::destroy)
            models.clear()
            page.clear()
        }
    }

    private val regularRows = ContactRows()
    private val favoriteRows = ContactRows()

    private val friendListListener = object : FriendListListenerStub() {
        @WorkerThread
        override fun onSyncStatusChanged(
            friendList: FriendList,
            status: FriendList.SyncStatus?,
            message: String?
        ) {
            Log.i("$TAG Synchronization status changed to [$status] for friend list [${friendList.displayName}] with message [$message]")
            if (status == FriendList.SyncStatus.Successful || status == FriendList.SyncStatus.Failure) {
                friendList.removeListener(this)
                cardDavSynchronizationCompletedEvent.postValue(Event(true))
            }
            // TODO FIXME: alert user when failure ?
        }
    }

    private val contactsListener = object : ContactsListener {
        @WorkerThread
        override fun onContactsLoaded() {
            coreContext.postOnMainThread {
                if (!searchSuspended) refreshContacts()
            }
        }

        @WorkerThread
        override fun onContactFoundInRemoteDirectory(friend: Friend) { }
    }

    init {
        fetchInProgress.value = true
        showFavourites.value = corePreferences.showFavoriteContacts
        showFilter.value = !corePreferences.hidePhoneNumbers && !corePreferences.hideSipAddresses
        disableAddContact.value = corePreferences.disableAddContact
        isListFiltered.value = false

        coreContext.postOnCoreThread { core ->
            domainFilter = corePreferences.contactsFilter
            areAllContactsDisplayed.postValue(domainFilter.isEmpty())
            checkIfDefaultAccountOnDefaultDomain()

            coreContext.contactsManager.addListener(contactsListener)
        }
        refreshContacts()
    }

    @UiThread
    override fun onCleared() {
        cancelContactSearch()
        coreContext.postOnCoreThread {
            coreContext.contactsManager.removeListener(contactsListener)
            coreContext.core.friendsLists.forEach { it.removeListener(friendListListener) }
        }
        super.onCleared()
    }

    @UiThread
    override fun filter() {
        isListFiltered.value = currentFilter.isNotEmpty()
        requestSearch(immediately = currentFilter.isEmpty())
    }

    @UiThread
    fun applyCurrentDefaultAccountFilter() {
        refreshContacts()
    }

    @UiThread
    fun changeContactsFilter(onlyLinphoneContacts: Boolean, onlySipContacts: Boolean) {
        // Invalidate queued callbacks before the new domain reaches the SIP queue.
        searchGeneration.incrementAndGet()
        coreContext.postOnCoreThread {
            domainFilter = if (onlyLinphoneContacts) {
                corePreferences.defaultDomain
            } else if (onlySipContacts) {
                "*"
            } else {
                ""
            }
            areAllContactsDisplayed.postValue(domainFilter.isEmpty())
            corePreferences.contactsFilter = domainFilter
            Log.i("$TAG Newly set filter is [${corePreferences.contactsFilter}]")

            coreContext.postOnMainThread {
                if (!searchSuspended) refreshContacts()
            }
        }
    }

    @UiThread
    fun toggleFavouritesVisibility() {
        val show = showFavourites.value == false
        showFavourites.value = show
        
        coreContext.postOnCoreThread {
            corePreferences.showFavoriteContacts = show
        }
    }

    @AnyThread
    fun exportContactAsVCard(friend: Friend) {
        coreContext.postOnCoreThread {
            val vCard = friend.dumpVcard()
            if (!vCard.isNullOrEmpty()) {
                Log.i("$TAG Friend has been successfully dumped as vCard string")
                val fileName = friend.name.orEmpty().replace(" ", "_").lowercase(
                    Locale.getDefault()
                )
                val file = FileUtils.getFileStorageCacheDir(
                    "$fileName.vcf",
                    overrideExisting = true
                )
                val exportedName = friend.name.orEmpty()
                viewModelScope.launch(Dispatchers.IO) {
                    if (FileUtils.dumpStringToFile(vCard, file)) {
                        Log.i("$TAG vCard string saved as file in cache folder")
                        vCardTerminatedEvent.postValue(Event(Pair(exportedName, file)))
                    } else {
                        Log.e("$TAG Failed to save vCard string as file in cache folder")
                    }
                }
            } else {
                Log.e("$TAG Failed to dump contact as vCard string")
            }
        }
    }

    @UiThread
    fun exportContactAsVCard(model: ContactAvatarModel) {
        coreContext.postOnCoreThread {
            val friend = if (model.nativeContact != null) coreContext.contactsManager.resolveNativeFriend(model.id) else model.friend
            if (friend != null) exportContactAsVCard(friend)
        }
    }

    @UiThread
    fun toggleContactFavoriteFlag(contactModel: ContactAvatarModel) {
        coreContext.postOnCoreThread {
            if (contactModel.nativeContact != null) {
                coreContext.contactsManager.setNativeFavorite(contactModel.id, contactModel.isFavourite.value != true)
                return@postOnCoreThread
            }
            contactModel.friend.edit()
            val starred = !contactModel.friend.starred
            Log.i(
                "$TAG Friend [${contactModel.name.value}] will be [${if (starred) "added to" else "removed from"}] favourites"
            )
            contactModel.friend.starred = starred
            contactModel.friend.done()
            coreContext.contactsManager.notifyContactsListChanged()
        }
    }

    @UiThread
    fun deleteContact(contactModel: ContactAvatarModel) {
        coreContext.postOnCoreThread {
            if (contactModel.isNative) return@postOnCoreThread
            Log.w("$TAG Removing friend [${contactModel.contactName}]")
            coreContext.contactsManager.contactRemoved(contactModel.friend)
            contactModel.friend.remove()
            coreContext.contactsManager.notifyContactsListChanged()
            showGreenToast(R.string.contact_deleted_toast, R.drawable.warning_circle)
        }
    }

    @UiThread
    fun refreshCardDavContacts() {
        coreContext.postOnCoreThread { core ->
            for (friendList in core.friendsLists) {
                if (friendList.type == FriendList.Type.CardDAV) {
                    Log.i("$TAG Found CardDAV friend list [${friendList.displayName}], starting update")
                    friendList.addListener(friendListListener)
                    friendList.synchronizeFriendsFromServer()
                }
            }
        }
    }

    @UiThread
    fun refreshContacts() = requestSearch(immediately = true)

    @UiThread
    private fun requestSearch(immediately: Boolean) {
        searchSuspended = false
        val token = searchGeneration.incrementAndGet()
        val filter = currentFilter
        searchJob?.cancel()
        searchInProgress.value = true
        fetchInProgress.value = contactsList.value.isNullOrEmpty()
        showResultsLimitReached.value = false
        searchJob = viewModelScope.launch {
            if (!immediately) delay(250)
            coreContext.postOnCoreThread {
                if (isCurrent(token)) startSearch(filter, token)
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
            regularRows.clear()
            favoriteRows.clear()
        }
    }

    private fun isCurrent(token: Long) = !searchSuspended && token == searchGeneration.get()

    @WorkerThread
    private fun retireSearches() {
        searches.forEach { (search, listener) -> search.removeListener(listener) }
        searches = emptyList()
    }

    @WorkerThread
    private fun startSearch(filter: String, token: Long) {
        retireSearches()
        domainFilter = corePreferences.contactsFilter
        areAllContactsDisplayed.postValue(domainFilter.isEmpty())
        checkIfDefaultAccountOnDefaultDomain()
        val directory = coreContext.contactsManager.nativeContactsSnapshot()
        val domain = domainFilter
        val hideEmpty = corePreferences.hideContactsWithoutPhoneNumberOrSipAddress
        val firstName = corePreferences.sortContactsByFirstName
        var nativeMatches: List<NativeContactValue>? = null
        var regularResults: Array<SearchResult>? = null
        var favoriteResults: Array<SearchResult>? = if (filter.isEmpty()) null else emptyArray()

        fun publishAvailable() {
            val native = nativeMatches ?: return
            regularResults?.let { processMagicSearchResults(it, false, token, native) }
            favoriteResults?.let { processMagicSearchResults(it, true, token, if (filter.isEmpty()) native else emptyList()) }
        }
        viewModelScope.launch(Dispatchers.Default) {
            val matches = directory.search(filter, domain, hideEmpty, firstName) { !isCurrent(token) }
            coreContext.postOnCoreThread {
                if (isCurrent(token) && directory === coreContext.contactsManager.nativeContactsSnapshot()) {
                    nativeMatches = matches
                    publishAvailable()
                }
            }
        }

        fun query(sources: Int, favorites: Boolean) {
            val search = coreContext.core.createMagicSearch()
            search.limitedSearch = true
            search.searchLimit = Mango9ContactSearchLimits.SDK_RESULTS
            val listener = object : MagicSearchListenerStub() {
                override fun onSearchResultsReceived(magicSearch: MagicSearch) {
                    if (isCurrent(token) && search.nativePointer == magicSearch.nativePointer) {
                        if (favorites) favoriteResults = magicSearch.lastSearch else regularResults = magicSearch.lastSearch
                        publishAvailable()
                    }
                }

                override fun onResultsLimitReached(magicSearch: MagicSearch, sourcesFlag: Int) {
                    coreContext.postOnMainThread {
                        if (isCurrent(token)) showResultsLimitReached.value = true
                    }
                }
            }
            searches = searches + (search to listener)
            search.addListener(listener)
            search.getContactsListAsync(filter, domainFilter, sources, MagicSearch.Aggregation.Friend)
        }
        query(MagicSearch.Source.Friends.toInt() or MagicSearch.Source.LdapServers.toInt() or MagicSearch.Source.RemoteCardDAV.toInt(), false)
        if (filter.isEmpty()) {
            query(MagicSearch.Source.FavoriteFriends.toInt(), true)
        } else {
            publishAvailable()
        }
    }

    @WorkerThread
    private fun processMagicSearchResults(results: Array<SearchResult>, favorites: Boolean, token: Long, nativeResults: List<NativeContactValue>) {
        if (!isCurrent(token)) return
        val hideEmpty = corePreferences.hideContactsWithoutPhoneNumberOrSipAddress
        val seen = hashSetOf<String>()
        val firstName = corePreferences.sortContactsByFirstName
        val candidates = results.mapNotNull { result ->
            val friend = result.friend
            val address = if (friend == null) result.address else null
            if (friend != null && hideEmpty && friend.phoneNumbers.isEmpty() && friend.address == null) return@mapNotNull null
            val key = friend?.let { "friend:${it.nativePointer}" } ?: address?.asStringUriOnly() ?: return@mapNotNull null
            if (!seen.add(key)) return@mapNotNull null
            val preferredName = if (corePreferences.sortContactsByFirstName) friend?.firstName else friend?.lastName
            // Elvis operands are lazy; sequenceOf(...) would eagerly allocate
            // a JNI Vcard/Address wrapper for every contact just to sort names.
            val name = preferredName?.takeIf { it.isNotBlank() }
                ?: friend?.name?.takeIf { it.isNotBlank() }
                ?: friend?.organization?.takeIf { it.isNotBlank() }
                ?: address?.displayName?.takeIf { it.isNotBlank() }
                ?: address?.username.orEmpty()
            ContactCandidate(friend, address, name)
        }
        val rows = if (favorites) favoriteRows else regularRows
        val request = ++rows.sortRequest
        viewModelScope.launch(Dispatchers.Default) {
            val native = nativeResults.asSequence().filter { !favorites || coreContext.contactsManager.nativeFavorite(it) }
                .map { ContactCandidate(null, null, it.sortName(firstName), it) }.toList()
            val collator = Collator.getInstance(Locale.getDefault())
            // Only captured strings are compared off the core thread, never SDK properties.
            val ordered = (native + candidates).sortedWith { a, b -> collator.compare(a.sortName, b.sortName) }
            coreContext.postOnCoreThread {
                if (isCurrent(token) && request == rows.sortRequest) {
                    rows.clear()
                    rows.generation = token
                    appendCandidates(rows, rows.page.replace(ordered))
                    publishRows(rows, favorites, token)
                }
            }
        }
    }

    @WorkerThread
    private fun appendCandidates(rows: ContactRows, candidates: List<ContactCandidate>) {
        candidates.forEach { candidate ->
            if (candidate.native != null) {
                rows.models.add(ContactAvatarModel(candidate.native, coreContext.contactsManager.nativeFavorite(candidate.native)))
                return@forEach
            }
            // Own these models explicitly; address-cache avatars belong to ContactsManager.
            val friend = candidate.friend ?: coreContext.core.createFriend().apply {
                address = candidate.address
                name = candidate.sortName
            }
            // The existing detail/editor route uses a ref key. Assign it lazily
            // for displayed directory rows, never use a potentially duplicate name.
            if (friend.refKey.isNullOrBlank()) {
                friend.vcard?.generateUniqueId()
                friend.refKey = friend.vcard?.uid?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
            }
            rows.models.add(ContactAvatarModel(friend))
        }
    }

    @UiThread
    fun revealMoreContacts(near: Int, favorites: Boolean = false) {
        val token = searchGeneration.get()
        coreContext.postOnCoreThread {
            val rows = if (favorites) favoriteRows else regularRows
            if (isCurrent(token) && rows.generation == token) {
                val additional = rows.page.reveal(near)
                if (additional.isNotEmpty()) {
                    appendCandidates(rows, additional)
                    publishRows(rows, favorites, token)
                }
            }
        }
    }

    @WorkerThread
    private fun publishRows(rows: ContactRows, favorites: Boolean, token: Long) {
        val snapshot = ArrayList(rows.models)
        coreContext.postOnMainThread {
            if (isCurrent(token)) {
                if (favorites) {
                    favouritesList.value = snapshot
                } else {
                    contactsList.value = snapshot
                    searchInProgress.value = false
                    fetchInProgress.value = false
                }
            }
        }
    }

    @WorkerThread
    private fun checkIfDefaultAccountOnDefaultDomain() {
        val defaultAccount = coreContext.core.defaultAccount
        val defaultDomain = corePreferences.defaultDomain
        val isAccountOnDefaultDomain = defaultAccount?.params?.domain == defaultDomain
        isDefaultAccountLinphone.postValue(isAccountOnDefaultDomain)
    }
}
