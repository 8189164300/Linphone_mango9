/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.ui.main.crm.viewmodel

import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.mango9.Mango9ApiException
import org.linphone.mango9.Mango9CrmDashboard
import org.linphone.mango9.Mango9CrmGroup
import org.linphone.mango9.Mango9CrmRecord
import org.linphone.mango9.Mango9CrmRecordDetail
import org.linphone.mango9.Mango9CrmRepository
import org.linphone.mango9.Mango9CrmSchema
import org.linphone.mango9.Mango9LatestRequestGate
import org.linphone.mango9.Mango9RecordKind
import org.linphone.mango9.Mango9Session
import org.linphone.ui.GenericViewModel
import org.linphone.utils.Event

class Mango9CrmViewModel : GenericViewModel() {
    val activeIdentity = MutableLiveData<String?>()
    val activeSession = MutableLiveData<Mango9Session?>()
    val dashboard = MutableLiveData<Mango9CrmDashboard?>()
    val records = MutableLiveData<List<Mango9CrmRecord>>(emptyList())
    val schema = MutableLiveData<Mango9CrmSchema?>()
    val groups = MutableLiveData<List<Mango9CrmGroup>>(emptyList())
    val selectedStatus = MutableLiveData("")
    val selectedGroupId = MutableLiveData("")
    val selectedDateFilter = MutableLiveData("")
    val search = MutableLiveData("")
    val loading = MutableLiveData(false)
    val loadingMore = MutableLiveData(false)
    val errorMessage = MutableLiveData<String?>(null)
    val showDashboard = MutableLiveData(true)
    val showRecords = MutableLiveData(false)
    val recordTitle = MutableLiveData("")
    val empty = MediatorLiveData<Boolean>()
    val detailLoadedEvent = MutableLiveData<Event<Pair<Mango9RecordKind, Mango9CrmRecordDetail>>>()
    val createRequestedEvent = MutableLiveData<Event<Mango9RecordKind>>()

    private val repository = Mango9CrmRepository(coreContext.context)
    private var selectedKind: Mango9RecordKind? = null
    private var page = 1
    private var pages = 1
    private var searchJob: Job? = null
    private val contextRequests = Mango9LatestRequestGate()
    private val recordsRequests = Mango9LatestRequestGate()
    private val dashboardRequests = Mango9LatestRequestGate()
    private val detailRequests = Mango9LatestRequestGate()
    private val searchObserver = Observer<String> { value ->
        if (selectedKind != null) {
            searchJob?.cancel()
            searchJob = viewModelScope.launch {
                delay(350)
                loadRecords(reset = true, searchValue = value.orEmpty())
            }
        }
    }

    init {
        reloadAccountContext(refresh = false)
        empty.addSource(records) { updateEmpty() }
        empty.addSource(loading) { updateEmpty() }
        search.observeForever(searchObserver)
        selectDashboard()
    }

    fun reloadAccountContext(refresh: Boolean = true) {
        val previousIdentity = activeIdentity.value
        val currentIdentity = repository.activeIdentity()
        activeIdentity.value = currentIdentity
        activeSession.value = repository.activeSession()
        if (refresh) {
            if (previousIdentity == currentIdentity && selectedKind != null) {
                loadRecords(reset = true)
            } else {
                selectDashboard()
            }
        }
    }

    fun selectDashboard() {
        contextRequests.invalidate()
        recordsRequests.invalidate()
        detailRequests.invalidate()
        selectedKind = null
        showDashboard.value = true
        showRecords.value = false
        recordTitle.value = ""
        refreshDashboard()
    }

    fun selectLeads() = selectRecords(Mango9RecordKind.Lead)

    fun selectClients() = selectRecords(Mango9RecordKind.Client)

    fun refresh() {
        if (selectedKind == null) refreshDashboard() else loadRecords(reset = true)
    }

    fun loadMore() {
        if (selectedKind != null && page < pages && loadingMore.value != true) {
            loadRecords(reset = false)
        }
    }

    fun openRecord(record: Mango9CrmRecord) {
        val kind = selectedKind ?: return
        loadRecordDetail(kind, record.id)
    }

    fun openRecordFromPush(kind: Mango9RecordKind, id: Int) {
        if (id <= 0) return
        selectRecords(kind)
        loadRecordDetail(kind, id)
    }

    private fun loadRecordDetail(kind: Mango9RecordKind, id: Int) {
        val requestedContext = contextRequests.current
        val requestedIdentity = repository.activeIdentity()
        val request = detailRequests.next()
        viewModelScope.launch {
            loading.value = true
            errorMessage.value = null
            try {
                val detail = repository.record(kind, id)
                if (
                    detailRequests.isLatest(request) &&
                    requestedContext == contextRequests.current &&
                    repository.activeIdentity() == requestedIdentity &&
                    selectedKind == kind
                ) {
                    detailLoadedEvent.postValue(Event(kind to detail))
                }
            } catch (error: Exception) {
                if (detailRequests.isLatest(request) && requestedContext == contextRequests.current) {
                    errorMessage.postValue(userMessage(error))
                }
            } finally {
                if (detailRequests.isLatest(request) && requestedContext == contextRequests.current) {
                    loading.postValue(false)
                }
            }
        }
    }

    fun requestCreate() {
        selectedKind?.let { createRequestedEvent.value = Event(it) }
    }

    fun setStatus(value: String) {
        selectedStatus.value = value
        loadRecords(reset = true)
    }

    fun setGroup(value: String) {
        selectedGroupId.value = value
        loadRecords(reset = true)
    }

    fun setDateFilter(value: String) {
        selectedDateFilter.value = value
        loadRecords(reset = true)
    }

    fun createRecord(
        kind: Mango9RecordKind,
        firstName: String,
        lastName: String,
        phone: String,
        email: String,
        groupId: String,
        onComplete: (Boolean, String?) -> Unit,
    ) {
        viewModelScope.launch {
            try {
                repository.createRecord(kind, firstName, lastName, phone, email, groupId)
                loadRecords(reset = true)
                coreContext.postOnMainThread { onComplete(true, null) }
            } catch (error: Exception) {
                coreContext.postOnMainThread { onComplete(false, userMessage(error)) }
            }
        }
    }

    fun updateRecord(
        kind: Mango9RecordKind,
        id: Int,
        values: Map<String, String>,
        onComplete: (Boolean, String?) -> Unit,
    ) {
        viewModelScope.launch {
            try {
                repository.updateRecord(kind, id, values)
                loadRecords(reset = true)
                coreContext.postOnMainThread { onComplete(true, null) }
            } catch (error: Exception) {
                coreContext.postOnMainThread { onComplete(false, userMessage(error)) }
            }
        }
    }

    fun deleteRecord(
        kind: Mango9RecordKind,
        id: Int,
        onComplete: (Boolean, String?) -> Unit,
    ) {
        viewModelScope.launch {
            try {
                repository.deleteRecord(kind, id)
                loadRecords(reset = true)
                coreContext.postOnMainThread { onComplete(true, null) }
            } catch (error: Exception) {
                coreContext.postOnMainThread { onComplete(false, userMessage(error)) }
            }
        }
    }

    private fun selectRecords(kind: Mango9RecordKind) {
        contextRequests.invalidate()
        dashboardRequests.invalidate()
        detailRequests.invalidate()
        selectedKind = kind
        showDashboard.value = false
        showRecords.value = true
        recordTitle.value = if (kind == Mango9RecordKind.Lead) "Leads" else "Clients"
        searchJob?.cancel()
        search.value = ""
        selectedStatus.value = ""
        selectedGroupId.value = ""
        selectedDateFilter.value = ""
        loadRecordContext(kind, contextRequests.current)
    }

    private fun loadRecordContext(kind: Mango9RecordKind, requestedContext: Long) {
        val requestedIdentity = repository.activeIdentity()
        viewModelScope.launch {
            loading.value = true
            errorMessage.value = null
            try {
                val loadedSchema = repository.schema(kind)
                val loadedGroups = try {
                    repository.groups(kind)
                } catch (_: Exception) {
                    emptyList()
                }
                if (!isCurrentContext(requestedContext, requestedIdentity, kind)) return@launch
                schema.value = loadedSchema
                groups.value = loadedGroups
                loadRecords(reset = true)
            } catch (error: Exception) {
                if (isCurrentContext(requestedContext, requestedIdentity, kind)) {
                    errorMessage.value = userMessage(error)
                    loading.value = false
                }
            }
        }
    }

    private fun refreshDashboard() {
        val requestedContext = contextRequests.current
        val requestedIdentity = repository.activeIdentity()
        val request = dashboardRequests.next()
        viewModelScope.launch {
            loading.value = true
            errorMessage.value = null
            try {
                val loaded = repository.dashboard()
                if (
                    dashboardRequests.isLatest(request) &&
                    requestedContext == contextRequests.current &&
                    selectedKind == null &&
                    repository.activeIdentity() == requestedIdentity
                ) {
                    dashboard.value = loaded
                }
            } catch (error: Exception) {
                if (
                    dashboardRequests.isLatest(request) &&
                    requestedContext == contextRequests.current &&
                    selectedKind == null &&
                    repository.activeIdentity() == requestedIdentity
                ) {
                    errorMessage.value = userMessage(error)
                }
            } finally {
                if (dashboardRequests.isLatest(request) && requestedContext == contextRequests.current) {
                    loading.value = false
                }
            }
        }
    }

    private fun loadRecords(reset: Boolean, searchValue: String = search.value.orEmpty()) {
        val kind = selectedKind ?: return
        val requestedContext = contextRequests.current
        val requestedIdentity = repository.activeIdentity()
        val request = recordsRequests.next()
        val status = selectedStatus.value.orEmpty()
        val groupId = selectedGroupId.value.orEmpty()
        val dateFilter = selectedDateFilter.value.orEmpty()
        val requestedPage = if (reset) 1 else page + 1
        viewModelScope.launch {
            if (reset) {
                loading.value = true
                loadingMore.value = false
            } else {
                loadingMore.value = true
            }
            errorMessage.value = null
            try {
                val result = repository.records(
                    kind,
                    searchValue,
                    status,
                    groupId,
                    dateFilter,
                    requestedPage,
                )
                if (
                    !recordsRequests.isLatest(request) ||
                    !isCurrentContext(requestedContext, requestedIdentity, kind)
                ) {
                    return@launch
                }
                page = result.pagination.page
                pages = result.pagination.pages
                records.value = if (reset) result.records else records.value.orEmpty() + result.records
            } catch (error: Exception) {
                if (
                    recordsRequests.isLatest(request) &&
                    isCurrentContext(requestedContext, requestedIdentity, kind)
                ) {
                    errorMessage.value = userMessage(error)
                }
            } finally {
                if (recordsRequests.isLatest(request) && requestedContext == contextRequests.current) {
                    loading.value = false
                    loadingMore.value = false
                }
            }
        }
    }

    private fun isCurrentContext(
        requestedContext: Long,
        requestedIdentity: String?,
        kind: Mango9RecordKind,
    ): Boolean =
        requestedContext == contextRequests.current &&
            selectedKind == kind &&
            repository.activeIdentity() == requestedIdentity

    private fun updateEmpty() {
        empty.value = loading.value != true && records.value.orEmpty().isEmpty()
    }

    private fun userMessage(error: Exception): String =
        (error as? Mango9ApiException)?.userMessage ?: "The Mango9 CRM is temporarily unavailable."

    override fun onCleared() {
        searchJob?.cancel()
        search.removeObserver(searchObserver)
        super.onCleared()
    }
}
