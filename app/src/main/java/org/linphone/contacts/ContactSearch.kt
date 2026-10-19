/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.contacts

import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import java.text.Collator
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.core.Address
import org.linphone.core.Friend
import org.linphone.core.MagicSearch
import org.linphone.core.MagicSearchListenerStub
import org.linphone.core.SearchResult
import org.linphone.mango9.Mango9ContactSearchLimits
import org.linphone.utils.LinphoneUtils

/** Native values and bounded SDK directory results. SDK references remain core-thread-only. */
class ContactSearch(
    private val scope: CoroutineScope,
    private val onResults: (Long, List<Candidate>, Boolean) -> Unit,
) {
    data class Candidate(
        val key: String,
        val name: String,
        val native: NativeContactValue? = null,
        val friend: Friend? = null,
        val address: Address? = null,
        val domain: String? = null,
        val request: Boolean = false,
        val favorite: Boolean = false,
    )

    private val generation = AtomicLong()
    // Jobs and SDK listener ownership below are confined to the core queue.
    private var search: MagicSearch? = null
    private var listener: MagicSearchListenerStub? = null
    private var nativeJob: Job? = null
    private var sortJob: Job? = null
    private var sortRevision = 0L

    @AnyThread
    fun isCurrent(token: Long): Boolean = generation.get() == token

    @AnyThread
    fun cancel() {
        val token = generation.incrementAndGet()
        coreContext.postOnCoreThread { if (isCurrent(token)) retire() }
    }

    @WorkerThread
    private fun retire() {
        listener?.let { search?.removeListener(it) }
        search = null
        listener = null
        nativeJob?.cancel()
        sortJob?.cancel()
        sortRevision++
    }

    @WorkerThread
    fun search(filter: String, domain: String, favoritesOnly: Boolean = false): Long {
        val token = generation.incrementAndGet()
        retire()
        val manager = coreContext.contactsManager
        val directory = manager.nativeContactsSnapshot()
        val account = LinphoneUtils.getDefaultAccount()
        val accountPointer = account?.nativePointer
        val accountAddress = account?.params?.identityAddress
        val accountDomain = account?.params?.domain
        val firstName = corePreferences.sortContactsByFirstName
        var nativeResults: List<Candidate>? = null
        var sdkResults: List<Candidate>? = null
        var limited = false

        fun current(): Boolean = isCurrent(token) &&
            directory === manager.nativeContactsSnapshot() &&
            accountPointer == LinphoneUtils.getDefaultAccount()?.nativePointer

        fun publish() {
            val native = nativeResults ?: return
            val sdk = sdkResults ?: return
            if (!current()) return
            val revision = ++sortRevision
            val limit = limited
            sortJob?.cancel()
            sortJob = scope.launch(Dispatchers.Default) {
                val collator = Collator.getInstance(Locale.getDefault())
                val rows = (native + sdk).sortedWith { a, b ->
                    fun group(row: Candidate) = when {
                        row.request -> 2
                        row.native != null || row.friend != null -> 0
                        else -> 1
                    }
                    val groupOrder = group(a).compareTo(group(b))
                    if (groupOrder != 0) {
                        groupOrder
                    } else {
                        val nameOrder = collator.compare(a.name, b.name)
                        if (nameOrder != 0) nameOrder else a.key.compareTo(b.key)
                    }
                }
                coreContext.postOnCoreThread {
                    if (current() && revision == sortRevision) onResults(token, rows, limit)
                }
            }
        }

        nativeJob = scope.launch(Dispatchers.Default) {
            val values = directory.search(filter, domain, true, firstName) { !isCurrent(token) }
            val candidates = values.mapNotNull {
                val favorite = manager.nativeFavorite(it)
                if (favoritesOnly && !favorite) {
                    null
                } else {
                    Candidate(
                    key = NativeContactDirectory.REF_PREFIX + it.id,
                    name = it.sortName(firstName), native = it, favorite = favorite,
                )
                }
            }
            coreContext.postOnCoreThread {
                if (current()) {
                    nativeResults = candidates
                    publish()
                }
            }
        }

        val query = coreContext.core.createMagicSearch()
        query.limitedSearch = true
        query.searchLimit = Mango9ContactSearchLimits.SDK_RESULTS
        val callback = object : MagicSearchListenerStub() {
            @WorkerThread
            override fun onSearchResultsReceived(magicSearch: MagicSearch) {
                if (!current() || magicSearch.nativePointer != query.nativePointer) return
                val seen = hashSetOf<String>()
                val results = magicSearch.lastSearch
                limited = limited || results.size >= Mango9ContactSearchLimits.SDK_RESULTS
                sdkResults = results.mapNotNull { result: SearchResult ->
                    val friend = result.friend
                    if (friend != null) {
                        val key = "friend:${friend.nativePointer}"
                        if (!seen.add(key)) null else Candidate(key, friend.name.orEmpty(), friend = friend)
                    } else {
                        val address = result.address ?: return@mapNotNull null
                        val request = result.sourceFlags == MagicSearch.Source.Request.toInt()
                        if (favoritesOnly || (!request && accountAddress != null && address.weakEqual(accountAddress))) {
                            return@mapNotNull null
                        }
                        val key = "address:${address.asStringUriOnly()}"
                        if (!seen.add(key)) {
                            null
                        } else {
                            Candidate(
                            key, address.username ?: address.domain.orEmpty(), address = address,
                            domain = accountDomain, request = request,
                        )
                        }
                    }
                }
                publish()
            }

            @WorkerThread
            override fun onResultsLimitReached(magicSearch: MagicSearch, sourcesFlag: Int) {
                if (current() && magicSearch.nativePointer == query.nativePointer) {
                    limited = true
                    publish()
                }
            }
        }
        search = query
        listener = callback
        query.addListener(callback)
        query.getContactsListAsync(
            filter, domain,
            if (favoritesOnly) MagicSearch.Source.FavoriteFriends.toInt() else MagicSearch.Source.All.toInt(),
            MagicSearch.Aggregation.Friend,
        )
        return token
    }
}
