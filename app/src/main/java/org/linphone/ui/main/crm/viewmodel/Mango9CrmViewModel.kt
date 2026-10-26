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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.mango9.Mango9ApiException
import org.linphone.mango9.Mango9CrmDashboard
import org.linphone.mango9.Mango9CrmGroup
import org.linphone.mango9.Mango9CrmRecord
import org.linphone.mango9.Mango9CrmRecordDetail
import org.linphone.mango9.Mango9CrmScreenAccess
import org.linphone.mango9.Mango9CrmScreenRepository
import org.linphone.mango9.Mango9CrmEditorOwner
import org.linphone.mango9.Mango9SessionStore
import org.linphone.mango9.Mango9CrmSchema
import org.linphone.mango9.Mango9CrmQuickStatus
import org.linphone.mango9.Mango9SessionLease
import org.linphone.mango9.Mango9LatestRequestGate
import org.linphone.mango9.Mango9RecordKind
import org.linphone.mango9.Mango9Session
import org.linphone.ui.GenericViewModel
import org.linphone.utils.Event

class Mango9CrmViewModel internal constructor(
    private val repository: Mango9CrmScreenAccess,
    contextChanges: Flow<Long>,
) : GenericViewModel() {
    constructor() : this(Mango9CrmScreenRepository(coreContext.context), Mango9SessionStore.contextChanges)

    val accountRevision = MutableLiveData(0L)
    val connectionLabel = MutableLiveData("CONNECTING")
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
    val detailLoadedEvent = MutableLiveData<Event<Pair<Mango9RecordKind, Mango9CrmRecordDetail>>?>()
    val createRequestedEvent = MutableLiveData<Event<Mango9RecordKind>?>()

    private var screenSession: Mango9SessionLease? = null
    private var selectedKind: Mango9RecordKind? = null
    private var page = 1
    private var pages = 1
    private var searchJob: Job? = null
    private val contextRequests = Mango9LatestRequestGate()
    private val recordsRequests = Mango9LatestRequestGate()
    private val dashboardRequests = Mango9LatestRequestGate()
    private val detailRequests = Mango9LatestRequestGate()
    private var detailSession: Mango9SessionLease? = null
    private var createSession: Mango9SessionLease? = null
    private var detailId: Int? = null
    private var presentation = 0L
    private val writes = mutableSetOf<Mango9CrmEditorOwner>()
    private val searchObserver = Observer<String> { value ->
        if (selectedKind != null) {
            searchJob?.cancel()
            // Invalidate the old query immediately, including while debounce is
            // pending. A queued page must not publish against the new search text.
            recordsRequests.invalidate()
            page = 1; pages = 1
            loadingMore.value = false
            searchJob = viewModelScope.launch {
                delay(350)
                loadRecords(reset = true, searchValue = value.orEmpty())
            }
        }
    }

    init {
        empty.addSource(records) { updateEmpty() }
        empty.addSource(loading) { updateEmpty() }
        search.observeForever(searchObserver)
        reloadAccountContext()
        viewModelScope.launch { contextChanges.collect { reloadAccountContext(refresh = false) } }
    }

    fun reloadAccountContext(refresh: Boolean = true) {
        val current = repository.captureSession()
        val previous = screenSession
        val changed = previous?.let(repository::isCurrent) != true && (previous != null || current != null)
        if (changed) {
            contextRequests.invalidate(); recordsRequests.invalidate(); dashboardRequests.invalidate(); detailRequests.invalidate()
            searchJob?.cancel()
            selectedKind = null
            clearDisplayedRecords()
            dashboard.value = null
            errorMessage.value = null
            loading.value = false; loadingMore.value = false
            showDashboard.value = true; showRecords.value = false; recordTitle.value = ""
            accountRevision.value = accountRevision.value!! + 1
        }
        screenSession = current
        activeIdentity.value = current?.session?.sipIdentity
        activeSession.value = current?.session
        if (current == null) {
            connectionLabel.value = "SIGN IN REQUIRED"
            repository.sessionStorageError()?.let { errorMessage.value = it.userMessage }
        }
        if (refresh || changed) {
            if (selectedKind != null) {
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
        clearDisplayedRecords()
        showDashboard.value = true
        showRecords.value = false
        recordTitle.value = ""
        refreshDashboard()
    }

    fun selectLeads() = selectRecords(Mango9RecordKind.Lead)

    fun selectClients() = selectRecords(Mango9RecordKind.Client)

    fun refresh() {
        reloadAccountContext()
    }

    fun loadMore() {
        if (selectedKind != null && page < pages && loading.value != true &&
            loadingMore.value != true && searchJob?.isActive != true
        ) {
            loadRecords(reset = false)
        }
    }

    fun openRecord(record: Mango9CrmRecord) {
        val kind = selectedKind ?: return
        // RecyclerView can still dispatch a click from an old submitted list.
        if (currentReadSession() == null || records.value.orEmpty().none { it === record }) return
        loadRecordDetail(kind, record.id)
    }

    internal fun currentReadSession(): Mango9SessionLease? = screenSession?.takeIf(repository::isCurrent)

    fun openRecordFromPush(kind: Mango9RecordKind, id: Int, lease: Mango9SessionLease? = repository.captureSession()) {
        if (id <= 0 || lease == null || !repository.isCurrent(lease)) return
        reloadAccountContext(refresh = false)
        selectRecords(kind)
        loadRecordDetail(kind, id, lease)
    }

    private fun loadRecordDetail(kind: Mango9RecordKind, id: Int, captured: Mango9SessionLease? = currentReadSession()) {
        detailSession = null
        detailId = null
        presentation++
        val lease = captured?.takeIf(repository::isCurrent) ?: return
        val requestedContext = contextRequests.current
        val requestedIdentity = lease.session.sipIdentity
        val request = detailRequests.next()
        viewModelScope.launch {
            loading.value = true
            errorMessage.value = null
            try {
                val detail = repository.record(lease, kind, id)
                if (
                    detailRequests.isLatest(request) &&
                    requestedContext == contextRequests.current &&
                    activeIdentity.value == requestedIdentity &&
                    selectedKind == kind && repository.isCurrent(lease)
                ) {
                    if (detail.record.id != id) throw Mango9ApiException.InvalidResponse
                    detailSession = lease
                    detailId = id
                    detailLoadedEvent.value = Event(kind to detail)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (repository.isCurrent(lease) && detailRequests.isLatest(request) && requestedContext == contextRequests.current) {
                    connectionLabel.value = connectionFailure(error)
                    errorMessage.value = userMessage(error)
                }
            } finally {
                if (detailRequests.isLatest(request) && requestedContext == contextRequests.current) {
                    loading.value = false
                }
            }
        }
    }

    internal fun calendarSession(kind: Mango9RecordKind): Mango9SessionLease? =
        detailSession?.takeIf { selectedKind == kind && repository.isCurrent(it) }

    internal fun isCalendarSessionCurrent(lease: Mango9SessionLease): Boolean = repository.isCurrent(lease)

    internal fun editorOwner(kind: Mango9RecordKind, id: Int?): Mango9CrmEditorOwner? {
        val lease = if (id == null) createSession else detailSession?.takeIf { detailId == id }
        return lease?.takeIf { selectedKind == kind && repository.isCurrent(it) }?.let {
            Mango9CrmEditorOwner(it, contextRequests.current, kind, id, presentation)
        }
    }

    internal fun isEditorCurrent(owner: Mango9CrmEditorOwner): Boolean =
        repository.isCurrent(owner.lease) && owner.context == contextRequests.current &&
            owner.kind == selectedKind && owner.presentation == presentation &&
            if (owner.recordId == null) createSession != null else detailId == owner.recordId && detailSession != null

    internal fun quickStatus(kind: Mango9RecordKind, detail: Mango9CrmRecordDetail): Mango9CrmQuickStatus {
        val owner = editorOwner(kind, detail.record.id)
        return Mango9CrmQuickStatus(
            detail, schema.value,
            isCurrent = {
                owner != null && isEditorCurrent(owner)
            },
            write = { values ->
                if (owner == null || !isEditorCurrent(owner)) throw Mango9ApiException.Unauthorized
                repository.updateRecord(owner.lease, kind, detail.record.id, values)
            },
        )
    }

    internal fun reflectStatus(detail: Mango9CrmRecordDetail) {
        records.value = records.value.orEmpty().map {
            if (it.id == detail.record.id) it.copy(status = detail.record.status) else it
        }
    }

    fun requestCreate() {
        createSession = currentReadSession() ?: return
        detailSession = null; detailId = null; presentation++
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

    internal fun createRecord(
        owner: Mango9CrmEditorOwner,
        firstName: String,
        lastName: String,
        phone: String,
        email: String,
        groupId: String,
        onComplete: (Boolean, String?) -> Unit,
    ) {
        if (owner.recordId != null) return
        mutate(owner, onComplete) {
            repository.createRecord(owner.lease, owner.kind, firstName, lastName, phone, email, groupId)
        }
    }

    internal fun updateRecord(
        owner: Mango9CrmEditorOwner,
        values: Map<String, String>,
        onComplete: (Boolean, String?) -> Unit,
    ) {
        val id = owner.recordId ?: return
        val snapshot = values.toMap()
        mutate(owner, onComplete) {
            repository.updateRecord(owner.lease, owner.kind, id, snapshot)
        }
    }

    internal fun deleteRecord(
        owner: Mango9CrmEditorOwner,
        onComplete: (Boolean, String?) -> Unit,
    ) {
        val id = owner.recordId ?: return
        mutate(owner, onComplete) { repository.deleteRecord(owner.lease, owner.kind, id) }
    }

    private fun mutate(owner: Mango9CrmEditorOwner, onComplete: (Boolean, String?) -> Unit, action: suspend () -> Unit) {
        if (!isEditorCurrent(owner) || !writes.add(owner)) return
        viewModelScope.launch {
            try {
                if (!isEditorCurrent(owner)) return@launch
                action()
                if (!isEditorCurrent(owner)) return@launch
                loadRecords(reset = true)
                onComplete(true, null)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (isEditorCurrent(owner)) onComplete(false, userMessage(error))
            } finally {
                writes.remove(owner)
            }
        }
    }

    private fun clearDisplayedRecords() {
        detailSession = null; createSession = null; detailId = null; presentation++
        detailLoadedEvent.value = null; createRequestedEvent.value = null
        records.value = emptyList(); schema.value = null; groups.value = emptyList()
        page = 1; pages = 1
        search.value = ""; selectedStatus.value = ""; selectedGroupId.value = ""; selectedDateFilter.value = ""
    }

    private fun selectRecords(kind: Mango9RecordKind) {
        reloadAccountContext(refresh = false)
        contextRequests.invalidate()
        dashboardRequests.invalidate()
        detailRequests.invalidate()
        recordsRequests.invalidate()
        selectedKind = null
        clearDisplayedRecords()
        selectedKind = kind
        showDashboard.value = false
        showRecords.value = true
        recordTitle.value = if (kind == Mango9RecordKind.Lead) "Leads" else "Clients"
        searchJob?.cancel()
        loadRecordContext(kind, contextRequests.current)
    }

    private fun loadRecordContext(kind: Mango9RecordKind, requestedContext: Long) {
        val lease = currentReadSession() ?: return
        val requestedIdentity = lease.session.sipIdentity
        viewModelScope.launch {
            loading.value = true
            errorMessage.value = null
            try {
                val loadedSchema = repository.schema(lease, kind)
                val loadedGroups = try {
                    repository.groups(lease, kind)
                } catch (error: Exception) {
                    if (error is CancellationException || !repository.isCurrent(lease)) throw error
                    emptyList()
                }
                if (!repository.isCurrent(lease) || !isCurrentContext(requestedContext, requestedIdentity, kind)) return@launch
                connectionLabel.value = "CONNECTED"
                schema.value = loadedSchema
                groups.value = loadedGroups
                loadRecords(reset = true)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (repository.isCurrent(lease) && isCurrentContext(requestedContext, requestedIdentity, kind)) {
                    connectionLabel.value = connectionFailure(error)
                    errorMessage.value = userMessage(error)
                    loading.value = false
                }
            }
        }
    }

    private fun refreshDashboard() {
        val lease = currentReadSession() ?: run { loading.value = false; dashboard.value = null; return }
        val requestedContext = contextRequests.current
        val requestedIdentity = lease.session.sipIdentity
        val request = dashboardRequests.next()
        connectionLabel.value = "CONNECTING"
        viewModelScope.launch {
            loading.value = true
            errorMessage.value = null
            try {
                val loaded = repository.dashboard(lease)
                if (
                    repository.isCurrent(lease) && dashboardRequests.isLatest(request) &&
                    requestedContext == contextRequests.current &&
                    selectedKind == null &&
                    activeIdentity.value == requestedIdentity
                ) {
                    dashboard.value = loaded
                    connectionLabel.value = "CONNECTED"
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (
                    repository.isCurrent(lease) && dashboardRequests.isLatest(request) &&
                    requestedContext == contextRequests.current &&
                    selectedKind == null &&
                    activeIdentity.value == requestedIdentity
                ) {
                    connectionLabel.value = connectionFailure(error)
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
        val lease = currentReadSession() ?: return
        val requestedContext = contextRequests.current
        val requestedIdentity = lease.session.sipIdentity
        val request = recordsRequests.next()
        val status = selectedStatus.value.orEmpty()
        val groupId = selectedGroupId.value.orEmpty()
        val dateFilter = selectedDateFilter.value.orEmpty()
        val requestedPage = if (reset) 1 else page + 1
        if (reset) {
            // Keep visible rows stable during a refresh, but never reuse their
            // pagination for a different filter or after a failed first page.
            page = 1; pages = 1
        }
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
                    lease,
                    kind,
                    searchValue,
                    status,
                    groupId,
                    dateFilter,
                    requestedPage,
                )
                if (
                    !repository.isCurrent(lease) || !recordsRequests.isLatest(request) ||
                    !isCurrentContext(requestedContext, requestedIdentity, kind)
                ) {
                    return@launch
                }
                connectionLabel.value = "CONNECTED"
                page = result.pagination.page
                pages = result.pagination.pages
                records.value = if (reset) result.records else records.value.orEmpty() + result.records
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (
                    repository.isCurrent(lease) && recordsRequests.isLatest(request) &&
                    isCurrentContext(requestedContext, requestedIdentity, kind)
                ) {
                    connectionLabel.value = connectionFailure(error)
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
            activeIdentity.value == requestedIdentity

    private fun updateEmpty() {
        empty.value = loading.value != true && records.value.orEmpty().isEmpty()
    }

    private fun connectionFailure(error: Exception): String = when (error) {
        Mango9ApiException.SessionExpired -> "SIGN IN REQUIRED"
        Mango9ApiException.Forbidden -> "ACCESS UNAVAILABLE"
        else -> "RECONNECTING"
    }

    private fun userMessage(error: Exception): String =
        (error as? Mango9ApiException)?.userMessage ?: "The Mango9 CRM is temporarily unavailable."

    override fun onCleared() {
        searchJob?.cancel()
        search.removeObserver(searchObserver)
        super.onCleared()
    }
}
