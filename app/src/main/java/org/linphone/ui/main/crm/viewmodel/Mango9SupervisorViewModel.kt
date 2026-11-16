// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.ui.main.crm.viewmodel

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONObject
import org.json.JSONArray
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.mango9.*
import java.util.UUID

internal class Mango9SupervisorViewModel(
    private val sessions: Mango9SessionAccess = Mango9SessionStore(coreContext.context),
    private val store: Mango9SupervisorStore = Mango9SupervisorStore(coreContext.context),
    val api: Mango9SupervisorApi =
    Mango9SupervisorApi(sessions, Mango9ApiClient(coreContext.context)::refresh, store::accepted, Mango9SupervisorHttp()),
) : ViewModel() {
    val voice = Mango9SupervisorVoice(coreContext.context, viewModelScope)
    val changed = MutableLiveData(0)
    val transfer by lazy { Mango9AssistantTransfer(coreContext.context, api, ::owner, ::notifyChange) }
    var replyPhase = "idle"; private set
    private var receipts = listOf<JSONObject>()
    private val textRequests = mutableSetOf<String>()
    private val textJobs = mutableMapOf<String, Job>()
    val working get() = sending || receipts.any {
        it.getJSONObject("command").let { c -> c.optString("conversationId") == conversation && c.optString("requestId") in textRequests }
    } || replyPhase != "idle"

    fun delivery(id: String): String? = receipts.firstOrNull {
        it.getJSONObject("command").getJSONObject("message").optString("id") == id
    }?.let {
        if (it.optString("state") == "failed") "Not sent" else if (it.getJSONObject("command").optString("requestId") in textRequests) "Sending…" else "Delivery unconfirmed"
    }

    private fun syncDelivery() {
        pending = receipts.firstOrNull {
            it.getJSONObject("command").optString("conversationId") == conversation && it.optString("state") != "failed"
        }?.getJSONObject("command")
    }

    private fun saveDeliveries(rows: List<JSONObject>) {
        val owner = owner() ?: throw Mango9SupervisorFailure("scope_changed")
        store.deliveries(owner.session, rows); receipts = rows; syncDelivery()
    }

    private fun restoreDeliveries(session: Mango9Session) {
        var rows = store.deliveries(session)
        val legacy = store.pending(session)
        if (legacy != null && rows.none {
            it.getJSONObject("command").optString("requestId") == legacy.optString("requestId")
        }
        ) {
            rows = rows + JSONObject().put("command", legacy).put("state", "unconfirmed").put("createdAt", java.time.Instant.now().toString())
            store.deliveries(session, rows)
        }
        store.pending(session, null); receipts = rows; syncDelivery()
    }

    private fun includeLocalMessages() {
        val local = receipts.filter { it.getJSONObject("command").optString("conversationId") == conversation }.map {
            JSONObject(it.getJSONObject("command").getJSONObject("message").toString()).put("metadata", JSONObject().put("createdAt", it.getString("createdAt")))
        }
        messages = messages + local.filter { row -> messages.none { it.optString("id") == row.optString("id") } }
    }

    var readingConversation = true
    var bulkBusy = false; private set
    var unread = emptyMap<String, Int>(); private set
    val unreadChats get() = unread.size
    private var draftState = JSONObject()
    private val deleted = mutableSetOf<String>()
    private val readIds = mutableSetOf<String>()
    private var unreadFlight = 0
    private var draftJob: Job? = null

    var lease: Mango9SessionLease? = null; private set
    var authorized = false; private set
    var accepted = false; private set
    var loading = true; private set
    var sending = false; private set
    var voicePage = false; private set
    var error: String? = null; private set
    var historyRefreshError: String? = null; private set
    var billing = false; private set
    var draft = ""
    var conversation = UUID.randomUUID().toString(); private set
    var messages = listOf<JSONObject>(); private set
    var conversations = listOf<JSONObject>(); private set
    var pending: JSONObject? = null; private set
    var moreMessages = false; private set
    var loadingOlder = false; private set
    var historyQuery = ""; private set
    var historyChannel = "all"; private set
    var historyError: String? = null; private set
    var deletingConversation: String? = null; private set
    var loadingConversations = false; private set
    val savedConfigurationIds = mutableSetOf<String>()
    private var savedChats = listOf<JSONObject>()
    private var olderCursor: String? = null
    private var historyReady = false
    private var listFlight = 0
    private var foregroundActive = true
    private var configurationEpoch = 0
    var moreConversations = false; private set
    var actions = listOf<JSONObject>(); private set
    var activity = listOf<JSONObject>(); private set
    var detail: JSONObject? = null; private set
    var actionBusy = false; private set
    val configurationDetails = mutableMapOf<String, JSONObject>()
    private val configurationRequested = mutableSetOf<String>()
    private val configurationFlights = mutableMapOf<String, UUID>()
    private var historyCursor = "0"
    private var listCursor: String? = null
    private var generation = 0
    private var refreshing = false
    private var historyRequested = false
    private var historyFlight: UUID? = null
    private var olderFlight: UUID? = null
    private var preferLatestHistory = false
    private var storageReady = false
    private var poll: Job? = null
    private var sendJob: Job? = null
    private var loadJob: Job? = null
    private var actionJob: Job? = null
    private var bound = false
    private var requestedConversation: String? = null

    init {
        viewModelScope.launch {
            Mango9AssistantPushEvents.events.collect { push ->
                val active = owner()?.session ?: return@collect
                if (push.crmId != active.crmId || push.recipientUserId != active.userId || Mango9SessionStore.normalizedIdentity(push.sipIdentity) != Mango9SessionStore.normalizedIdentity(active.sipIdentity)) return@collect
                if ((push.target as? Mango9MessagePushTarget.Assistant)?.conversationId == conversation && foregroundActive) refresh()
                refreshUnread()
            }
        }
        voice.changed = { notifyChange() }
        voice.historyChanged = { historyRequested = true; viewModelScope.launch { refresh() } }
    }

    fun owner() = lease?.takeIf { sessions.currentSession(it) != null }

    private fun current(stamp: Int, captured: Mango9SessionLease, chat: String = conversation) =
        stamp == generation && lease == captured && sessions.currentSession(captured) != null && conversation == chat

    private fun invalidateHistory() {
        transfer.pause(); replyPhase = "idle"; readIds.clear()
        historyFlight = null; olderFlight = null; refreshing = false; loadingOlder = false; preferLatestHistory = false
    }

    fun notifyChange() { changed.value = (changed.value ?: 0) + 1 }

    fun canSend() = storageReady && authorized && accepted && !loading && !sending && !voice.running && owner() != null && !transfer.active && (transfer.draft != null || Mango9SupervisorWire.validText(draft))

    fun canVoice() = pending == null && storageReady && authorized && accepted && !loading && !sending && !transfer.active && owner() != null

    fun canManageHistory() = storageReady && authorized && !loading && !sending && !actionBusy && !voice.running && deletingConversation == null && !bulkBusy && !transfer.active && owner() != null

    private fun restoreLocal() {
        val saved = savedChats.firstOrNull { it.optString("id") == conversation } ?: return
        messages = Mango9SupervisorPresentation.objects(saved.optJSONArray("messages"))
        val ids = Mango9SupervisorPresentation.actionIds(messages).toSet()
        Mango9SupervisorPresentation.objects(saved.optJSONArray("configurations")).filter {
            it.optString("id") in ids
        }.forEach {
            configurationDetails[it.optString("id")] = it; savedConfigurationIds.add(it.optString("id"))
        }
    }

    private fun persistLocal() {
        val owner = owner() ?: return
        val rows = messages.takeLast(200).mapNotNull(Mango9SupervisorPresentation::savedMessage)
        val ids = Mango9SupervisorPresentation.actionIds(rows).toSet()
        val configs = configurationDetails.filterKeys {
            it in ids
        }.values.mapNotNull(Mango9SupervisorPresentation::savedConfiguration)
        val summary = conversations.firstOrNull { it.optString("id") == conversation }
        val saved = JSONObject().put("id", conversation).put("savedAt", System.currentTimeMillis()).put("messages", org.json.JSONArray(rows)).put("configurations", org.json.JSONArray(configs))
        if (summary != null) {
            saved.put(
                "conversation",
                JSONObject().put("id", conversation).put("title", summary.optString("title").take(300))
            .put("updatedAt", summary.optString("updatedAt")).put("hasVoice", summary.optBoolean("hasVoice")).put("messageCount", summary.optInt("messageCount"))
            )
        }
        savedChats = (
            (if (rows.isEmpty()) emptyList() else listOf(saved)) + savedChats.filterNot {
            it.optString("id") == conversation
        }
        ).take(20)
        runCatching {
            store.savedChats(owner.session, savedChats)
        }.onFailure { error = "Couldn’t save a local copy. Your server history is unchanged." }
    }

    private fun accessFailure(e: Exception) {
        if ((e is Mango9SupervisorFailure && e.status in listOf(401, 403)) || e == Mango9ApiException.Unauthorized || e == Mango9ApiException.InvalidCredentials) {
            lease?.session?.let { runCatching { store.clearDisplay(it) } }
            savedChats = emptyList(); messages = emptyList(); conversations = emptyList(); configurationDetails.clear(); savedConfigurationIds.clear(); authorized = false
        }
    }

    fun bind(value: Mango9SessionLease?, requested: String? = null) {
        if (bound) return
        bound = true; lease = value; requestedConversation = requested?.takeIf(Mango9SupervisorPresentation::uuid)?.lowercase()
        if (value == null) { loading = false; error = Mango9SupervisorFailure("scope_changed").message; notifyChange(); return }
        loadJob = viewModelScope.launch {
            val stamp = generation
            try {
                withContext(Dispatchers.IO) {
                    accepted = store.accepted(value.session); pending = store.pending(value.session)
                    conversation = requestedConversation ?: store.conversation(value.session) ?: pending?.optString("conversationId") ?: UUID.randomUUID().toString()
                    if (runCatching { UUID.fromString(conversation) }.isFailure) conversation = UUID.randomUUID().toString()
                    savedChats = store.savedChats(value.session)
                    val state = store.state(value.session); draftState = state.optJSONObject("drafts") ?: JSONObject()
                    val tombstones = state.optJSONArray("deleted"); deleted.addAll(
                        (0 until (tombstones?.length() ?: 0)).map {
                        tombstones!!.getString(it).lowercase()
                    }.filter(Mango9SupervisorPresentation::uuid).takeLast(256)
                    )
                    if (conversation in deleted) {
                        conversation = UUID.randomUUID().toString(); pending = null; store.pending(value.session, null)
                    }
                    draft = draftState.optString(conversation)

                }
                if (!current(stamp, value)) return@launch
                restoreDeliveries(value.session)
                storageReady = true
                restoreLocal(); includeLocalMessages(); notifyChange()
                reload()
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    error = "Couldn’t open secure Supervisor history. Try reopening Supervisor."; loading = false; notifyChange()
                }
            }
        }
        poll = viewModelScope.launch {
            var ticks = 0
            while (isActive) {
                delay(1000)
                if (owner() == null) { clearAccount(); return@launch }
                ticks++
                if (foregroundActive && ticks % (if (working || voice.running || historyRequested) 5 else 15) == 0) {
                    refresh(); refreshUnread()
                }
            }
        }
    }

    private fun clearAccount() {
        generation++; listFlight++; invalidateHistory(); storageReady = false; savedChats = emptyList(); savedConfigurationIds.clear(); configurationDetails.clear(); configurationRequested.clear(); configurationFlights.clear(); detail = null; sendJob?.cancel(); textJobs.values.toList().forEach {
            it.cancel()
        }; loadJob?.cancel(); actionJob?.cancel(); voice.stop()
        authorized = false; accepted = false; loading = false; sending = false; voicePage = false; draft = ""; pending = null; receipts = emptyList(); textRequests.clear()
        messages = emptyList(); conversations = emptyList(); actions = emptyList(); activity = emptyList(); detail = null
        error = Mango9SupervisorFailure("scope_changed").message; notifyChange()
    }

    fun retry() { loadJob?.cancel(); loadJob = viewModelScope.launch { reload() } }

    private suspend fun reload() {
        val owner = owner() ?: return
        val stamp = generation
        loading = true; error = null; historyRefreshError = null; notifyChange()
        try {
            storageReady = false
            restoreDeliveries(owner.session)
            if (!current(stamp, owner)) return
            storageReady = true
            val status = api.authorize(owner)
            if (!current(stamp, owner)) return
            authorized = true; billing = status.optBoolean("billingEnabled")
            loadConversations()
            if (!current(stamp, owner)) return
            if (pending == null && requestedConversation == null && store.conversation(owner.session) == null) {
                conversations.firstOrNull()?.optString("id")?.takeIf {
                runCatching { UUID.fromString(it) }.isSuccess
            }?.let { conversation = it; draft = draftState.optString(conversation) }
            }
            store.conversation(owner.session, conversation)
            transfer.configure(owner, conversation)
            refresh(); refreshUnread()
        } catch (e: Exception) {
            if (e is CancellationException) throw e; authorized = false; error = failure(e); accessFailure(e)
        } finally { loading = false; notifyChange() }
    }

    suspend fun loadConversations(more: Boolean = false, query: String = historyQuery, channel: String = historyChannel) {
        val owner = owner() ?: return
        if (channel !in listOf("all", "text", "voice") || (more && (loadingConversations || listCursor == null))) return
        historyQuery = query.trim().take(100); historyChannel = channel
        if (!authorized) {
            conversations = savedChats.mapNotNull {
                it.optJSONObject("conversation")
            }.filter { Mango9SupervisorPresentation.historyMatches(it, historyQuery, channel) }
            moreConversations = false; notifyChange(); return
        }
        val flight = ++listFlight; val stamp = generation
        loadingConversations = true; historyError = null; notifyChange()
        val body = JSONObject().put("action", "list")
        if (historyQuery.isNotEmpty()) body.put("query", historyQuery)
        if (channel != "all") body.put("channel", channel)
        if (more) body.put("before", listCursor)
        try {
            val result = api.request(owner, "history", body)
            if (flight != listFlight || stamp != generation) return
            val rows = result.optJSONArray("items")?.liveObjects().orEmpty()
            conversations = ((if (more) conversations else emptyList()) + rows)
                .filter { Mango9SupervisorPresentation.uuid(it.optString("id")) && it.optString("id").lowercase() !in deleted }
                .map {
                    it.put("id", it.getString("id").lowercase())
                }.sortedByDescending { it.optString("updatedAt") }.distinctBy { it.getString("id") }
            listCursor = result.optString("nextCursor").takeIf {
                it.isNotBlank() && it != "null" && it != listCursor
            }; moreConversations = listCursor != null
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (flight == listFlight && stamp == generation) { historyError = failure(e); accessFailure(e) }
        } finally { if (flight == listFlight) { loadingConversations = false; notifyChange() } }
    }

    fun deleteConversation(id: String) { viewModelScope.launch { deleteConversations(setOf(id)) } }

    suspend fun deleteConversations(ids: Set<String>): Set<String> {
        val owner = owner() ?: return emptySet()
        if (!canManageHistory() || ids.isEmpty() || ids.any { !Mango9SupervisorPresentation.uuid(it) }) return emptySet()
        val stamp = generation; bulkBusy = true; listFlight++; historyError = null; notifyChange()
        val removed = mutableSetOf<String>()
        try {
            for (batch in ids.chunked(4)) {
                if (!current(stamp, owner)) break
                val results = coroutineScope {
                    batch.map { id ->
                        async {
                    try {
                        val raw = api.request(owner, "history", JSONObject().put("action", "delete").put("conversationId", id)); if (raw.optBoolean("deleted") && raw.optString("id").lowercase() == id) id else null
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e; if (e is Mango9SupervisorFailure && e.status == 404 && e.code == "conversation_deleted") id else null
                    }
                }
                    }.awaitAll()
                }
                removed.addAll(results.filterNotNull())
            }
            if (!current(stamp, owner)) return emptySet()
            removed.forEach { rememberDeleted(it) }; persistState()
            savedChats = savedChats.filterNot { it.optString("id") in removed }; store.savedChats(owner.session, savedChats)
            if (conversation in removed) recoverDeleted()
            if (removed.size != ids.size) historyError = "Some conversations could not be deleted. They remain selected; try again."
            return removed
        } finally { bulkBusy = false; notifyChange() }
    }

    private fun rememberDeleted(id: String) {
        runCatching { saveDeliveries(receipts.filterNot { it.getJSONObject("command").optString("conversationId") == id }) }
        deleted.remove(id); deleted.add(id); while (deleted.size > 256) deleted.remove(deleted.first())
        draftState.remove(id); unread = unread - id; conversations = conversations.filterNot { it.optString("id") == id }
    }

    private suspend fun recoverDeleted() {
        val owner = owner() ?: return
        val unsent = draft; rememberDeleted(conversation)
        saveDeliveries(
            receipts.filterNot {
            it.getJSONObject("command").optString("conversationId") == conversation
        }
        ); pending = null; sending = false
        savedChats = savedChats.filterNot { it.optString("id") == conversation }; store.savedChats(owner.session, savedChats)
        generation++; invalidateHistory(); conversation = UUID.randomUUID().toString(); store.conversation(owner.session, conversation)
        messages = emptyList(); configurationDetails.clear(); historyCursor = "0"; historyReady = false; moreMessages = false; olderCursor = null; error = null; historyRefreshError = null
        draft = unsent; saveDraftNow(); transfer.configure(owner, conversation)
    }

    fun select(id: String) {
        if (loading || sending || actionBusy || runCatching { UUID.fromString(id) }.isFailure) return
        val owner = owner() ?: return
        if (id.lowercase() in deleted || transfer.active) return
        saveDraftNow(); draft = draftState.optString(id.lowercase())
        generation++; configurationDetails.clear(); configurationRequested.clear(); configurationFlights.clear(); detail = null; actionBusy = false; voice.stop(); voicePage = false; conversation = id.lowercase(); messages = emptyList(); historyCursor = "0"; moreMessages = false; error = null; historyRefreshError = null; syncDelivery()
        listFlight++; invalidateHistory(); preferLatestHistory = false; savedConfigurationIds.clear(); historyReady = false; olderCursor = null; restoreLocal(); includeLocalMessages()
        loadJob = viewModelScope.launch {
            try {
                store.conversation(owner.session, conversation); loading = true; notifyChange(); transfer.configure(owner, conversation); refresh()
            } catch (e: Exception) {
                if (e !is CancellationException) error = failure(e)
            } finally { loading = false; notifyChange() }
        }
    }

    fun newConversation() = select(UUID.randomUUID().toString())

    fun leavePending() {
        val owner = owner() ?: return
        if (sending) return
        try {
            store.pending(owner.session, null); pending = null; newConversation()
        } catch (e: Exception) { error = failure(e); notifyChange() }
    }

    suspend fun refresh(latest: Boolean = false) {
        val owner = owner() ?: return
        if (!authorized) return
        if (latest) { preferLatestHistory = true; olderFlight = null; loadingOlder = false }
        if (refreshing) { historyRequested = true; return }
        val stamp = generation; val id = conversation
        val flight = UUID.randomUUID(); historyFlight = flight
        val tail = !historyReady || preferLatestHistory
        preferLatestHistory = false
        if (tail) { olderFlight = null; loadingOlder = false }
        refreshing = true; historyRequested = false
        try {
            val body = JSONObject().put("action", "read").put("conversationId", id)
            if (tail) body.put("latest", true) else body.put("after", historyCursor)
            pending?.optString("requestId")?.let { body.put("requestId", it) }
            val result = api.request(owner, "history", body)
            if (!current(stamp, owner, id) || historyFlight != flight || preferLatestHistory) return
            val rows = result.optJSONArray("messages")?.liveObjects().orEmpty().filter {
                it.optString("role") in listOf("assistant", "user")
            }
            val merged = if (tail) mutableListOf() else messages.toMutableList()
            rows.forEach { row ->
                val index = merged.indexOfFirst {
                it.optString("id") == row.optString("id")
            }; if (index >= 0) merged[index] = row else merged.add(row)
            }
            messages = merged; historyCursor = result.optString("cursor", historyCursor)
            historyReady = true
            historyRefreshError = null
            if (tail) {
                olderCursor = result.optString("olderCursor").takeIf {
                    it.isNotBlank() && it != "null"
                }; moreMessages = olderCursor != null
            }
            saveDeliveries(
                receipts.filter { r ->
                val c = r.getJSONObject("command")
                c.optString("conversationId") != id || !(
                    result.optString("confirmedRequestId") == c.optString("requestId") || rows.any {
                    it.optString("id") == c.getJSONObject("message").optString("id") || it.optString("id") == c.optString("requestId") || it.optJSONObject("metadata")?.optString("requestId") == c.optString("requestId")
                }
                )
            }
            ); includeLocalMessages()
            replyPhase = if (result.optBoolean("pending")) "thinking" else "idle"
            if (result.optBoolean("pending") || result.optString("nextCursor").let {
                it.isNotBlank() && it != "null"
            }
            ) {
                historyRequested = true
            }
            persistLocal(); markVisibleRead()
            Mango9SupervisorPresentation.actionIds(messages).forEach { loadConfiguration(it) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (!current(stamp, owner, id) || historyFlight != flight) return
            if (e is Mango9SupervisorFailure && e.status == 404 && e.code == "conversation_deleted") {
                recoverDeleted()
            } else if (e is Mango9SupervisorFailure && e.status == 404 && e.code == "conversation_not_found") {
                if (stamp == generation) {
                    messages = emptyList(); includeLocalMessages(); configurationDetails.clear(); persistLocal()
                }
            } else if (stamp == generation) {
                if ((e is Mango9SupervisorFailure && e.status in listOf(401, 403)) || e == Mango9ApiException.Unauthorized || e == Mango9ApiException.InvalidCredentials) {
                    error = failure(e); accessFailure(e)
                } else {
                    historyRefreshError = failure(e)
                }
            }
        } finally {
            if (historyFlight == flight) {
                historyFlight = null; refreshing = false; refreshReplyPhase(id, stamp, owner); notifyChange()
                if (preferLatestHistory && foregroundActive && current(stamp, owner, id)) viewModelScope.launch { refresh() }
            }
        }
    }

    suspend fun loadOlder() {
        val owner = owner() ?: return
        val cursor = olderCursor ?: return
        if (!authorized || loadingOlder || refreshing) return
        val stamp = generation; val id = conversation; val flight = UUID.randomUUID(); olderFlight = flight; loadingOlder = true; notifyChange()
        try {
            val result = api.request(owner, "history", JSONObject().put("action", "read").put("conversationId", id).put("before", cursor))
            if (!current(stamp, owner, id) || olderFlight != flight) return
            val rows = Mango9SupervisorPresentation.objects(result.optJSONArray("messages")).filter {
                it.optString("role") in listOf("user", "assistant")
            }
            messages = (rows + messages).distinctBy { it.optString("id") }
            olderCursor = result.optString("olderCursor").takeIf {
                it.isNotBlank() && it != "null" && it != cursor
            }; moreMessages = olderCursor != null
            persistLocal()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (current(stamp, owner, id) && olderFlight == flight) { error = failure(e); accessFailure(e) }
        } finally { if (olderFlight == flight) { olderFlight = null; loadingOlder = false; notifyChange() } }
    }

    fun send() {
        if (canSend() && transfer.draft != null) {
            val text = draft; val owner = owner() ?: return; val stamp = generation; val chat = conversation
            sending = true; notifyChange()
            sendJob = viewModelScope.launch {
                try {
                    val caption = transfer.send(text) ?: return@launch
                    if (!current(stamp, owner, chat)) return@launch
                    if (draft.trim() == caption) { draft = ""; saveDraftNow() }
                    replyPhase = "waiting"; historyRequested = true; refresh(); loadConversations()
                } finally { if (current(stamp, owner, chat)) { sending = false; notifyChange() } }
            }; return
        }
        if (!canSend()) return
        if (receipts.size >= 100) { error = "Reconnect to synchronize unconfirmed messages."; notifyChange(); return }
        val command = Mango9SupervisorWire.command(conversation, draft.trim())
        try {
            saveDeliveries(receipts + JSONObject().put("command", command).put("state", "unconfirmed").put("createdAt", java.time.Instant.now().toString()))
        } catch (_: Exception) {
            error = "Couldn’t save this message. Your text is still in the composer."; notifyChange(); return
        }
        draft = ""; saveDraftNow(); error = null; historyRefreshError = null; includeLocalMessages(); persistLocal(); notifyChange()
        transmit(command)
    }

    fun retryMessage(id: String) {
        if (!authorized || !accepted || owner() == null || voice.running) return
        val row = receipts.firstOrNull {
            it.optString("state") == "failed" && it.getJSONObject("command").optString("conversationId") == conversation && it.getJSONObject("command").getJSONObject("message").optString("id") == id
        } ?: return
        val replacement = JSONObject(row.toString()).put("state", "unconfirmed")
        try {
            saveDeliveries(
                receipts.map {
                if (it === row) replacement else it
            }
            )
        } catch (_: Exception) { error = "Couldn’t save message delivery."; notifyChange(); return }
        transmit(replacement.getJSONObject("command"))
    }

    private fun transmit(command: JSONObject) {
        val owner = owner() ?: return
        val stamp = generation; val chat = command.getString("conversationId"); val request = command.getString("requestId")
        historyFlight = null; refreshing = false; textRequests.add(request); notifyChange()
        textJobs[request] = viewModelScope.launch {
            try {
                val reply = api.request(owner, "chat", command)
                if (!current(stamp, owner, chat) || !store.accepted(owner.session)) return@launch
                if (reply.optString("role") != "assistant" || (reply.optString("id") != request && reply.optJSONObject("metadata")?.optString("requestId") != request) || reply.optJSONObject("metadata")?.optString("conversationId")?.takeIf {
                    it.isNotBlank()
                }?.let { it != chat } == true
                ) {
                    throw Mango9SupervisorFailure("invalid_response")
                }
                historyFlight = null; refreshing = false
                if (messages.none { it.optString("id") == reply.optString("id") }) messages += reply
                saveDeliveries(receipts.filterNot { it.getJSONObject("command").optString("requestId") == request })
                persistLocal(); refresh(); runCatching { loadConversations() }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (current(stamp, owner, chat)) {
                    if (e is Mango9SupervisorFailure && (e.status in listOf(400, 401, 402, 403, 413, 422, 429) || e.status == 409 && e.code == "support_pilot_busy")) {
                        runCatching {
                            saveDeliveries(
                                receipts.map {
                                if (it.getJSONObject("command").optString("requestId") == request) JSONObject(it.toString()).put("state", "failed") else it
                            }
                            )
                        }
                    }
                    refresh()
                }
            } finally { textRequests.remove(request); textJobs.remove(request); notifyChange() }
        }
    }

    fun draftChanged() {
        draftJob?.cancel(); draftJob = viewModelScope.launch { delay(300); saveDraftNow() }
    }

    private fun persistState() {
        val owner = owner() ?: return
        store.state(owner.session, JSONObject().put("drafts", draftState).put("deleted", JSONArray(deleted.toList())))
    }

    private fun saveDraftNow() {
        draftJob?.cancel(); if (!storageReady || owner() == null || conversation in deleted) return
        if (draftState.length() >= 10 && !draftState.has(conversation)) draftState.remove(draftState.keys().next())
        if (draft.isBlank()) draftState.remove(conversation) else draftState.put(conversation, draft.take(8000))
        runCatching { persistState() }
    }

    fun selectAttachment(uri: android.net.Uri, name: String) {
        if (!authorized || !accepted || transfer.active || voice.running) return
        viewModelScope.launch { transfer.select(uri, name) }
    }

    private suspend fun refreshReplyPhase(id: String, stamp: Int, owner: Mango9SessionLease) {
        if (!foregroundActive || !current(stamp, owner, id)) return
        try {
            val r = api.request(owner, "transport", JSONObject().put("action", "replyStatus").put("conversationId", id))
            if (current(stamp, owner, id) && r.optInt("version") == 1 && r.optString("conversationId") == id && r.optString("phase") in setOf("idle", "waiting", "thinking")) replyPhase = r.getString("phase")
        } catch (e: Exception) { if (e is CancellationException) throw e }
    }

    suspend fun refreshUnread() {
        val owner = owner() ?: return; if (!authorized || !foregroundActive) return; val stamp = generation; val flight = ++unreadFlight
        try {
            val status = Mango9AssistantUnread.parse(api.request(owner, "notifications", JSONObject().put("action", "status")))
            if (current(stamp, owner) && flight == unreadFlight) {
                unread = status.first.filterKeys { it !in deleted }; Mango9InboxUnread.assistant(unread, owner); notifyChange()
            }
        } catch (e: Exception) { if (e is CancellationException) throw e }
    }

    private suspend fun markVisibleRead() {
        val owner = owner() ?: return; if (!foregroundActive || !readingConversation) return
        val stamp = generation; val id = conversation
        val ids = messages.filter {
            it.optString("role") == "assistant" && Mango9SupervisorPresentation.uuid(it.optString("id")) && it.getString("id") !in readIds
        }.takeLast(120).map { it.getString("id") }
        if (ids.isEmpty()) return
        unreadFlight++
        try {
            api.request(owner, "notifications", JSONObject().put("action", "read").put("conversationId", id).put("messageIds", JSONArray(ids))); if (current(stamp, owner, id)) {
                readIds.addAll(ids); refreshUnread()
            }
        } catch (e: Exception) { if (e is CancellationException) throw e }
    }

    suspend fun markConversationsRead(ids: Set<String>) {
        val owner = owner() ?: return; if (!canManageHistory() || ids.isEmpty()) return; val stamp = generation; unreadFlight++
        try {
            val status = Mango9AssistantUnread.parse(api.request(owner, "notifications", JSONObject().put("action", "status")))
            for (batch in ids.chunked(200)) {
                if (!current(stamp, owner)) return; api.request(owner, "notifications", JSONObject().put("action", "read_conversations").put("conversationIds", JSONArray(batch)).put("through", status.second))
            }
            refreshUnread()
        } catch (e: Exception) {
            if (e is CancellationException) throw e; historyError = "Couldn’t mark these conversations read. Try again."
        }
        notifyChange()
    }

    suspend fun allHistoryIDs(): Set<String> {
        val cursors = mutableSetOf<String>()
        while (moreConversations && listCursor != null && cursors.add(listCursor!!)) {
            loadConversations(true); if (historyError != null) return emptySet()
        }
        return conversations.map { it.getString("id") }.toSet()
    }

    suspend fun report(message: JSONObject, reason: String, details: String, reportId: String) {
        val owner = owner() ?: throw Mango9SupervisorFailure("scope_changed"); val stamp = generation; val id = conversation
        require(message.optString("role") == "assistant" && Mango9SupervisorPresentation.uuid(message.optString("id")) && reason in setOf("unsafe", "inappropriate", "incorrect", "privacy", "other") && details.toByteArray().size <= 2000)
        val raw = api.request(owner, "reports", JSONObject().put("conversationId", id).put("messageId", message.getString("id")).put("reason", reason).put("details", details).put("text", Mango9SupervisorWire.messageText(message).take(4000)).put("reportId", reportId))
        check(current(stamp, owner, id) && raw.optBoolean("accepted") && raw.optString("reportId") == reportId)
    }

    fun consent(allow: Boolean) {
        val owner = owner() ?: return
        try {
            store.consent(owner.session, allow); accepted = store.accepted(owner.session)
            if (!allow) {
                generation++; invalidateHistory(); configurationDetails.clear(); configurationRequested.clear(); configurationFlights.clear(); detail = null; sendJob?.cancel(); textJobs.values.toList().forEach {
                    it.cancel()
                }; actionJob?.cancel(); voice.stop(); voicePage = false; sending = false; actionBusy = false
            }
        } catch (e: Exception) { error = failure(e) }
        notifyChange()
    }

    fun showVoice() { if (canVoice()) { voice.ready(); voicePage = true; notifyChange() } }

    fun connectVoice() { val owner = owner() ?: return; if (voicePage && canVoice()) voice.start(api, owner, conversation) }

    fun endVoice() { voice.stop(); voicePage = false; notifyChange(); viewModelScope.launch { refresh() } }

    fun background() {
        saveDraftNow(); transfer.pause(); foregroundActive = false; configurationEpoch++; invalidateHistory()
        voice.stop(); voicePage = false
        configurationDetails.replaceAll { _, value -> Mango9SupervisorPresentation.savedConfiguration(value) ?: value }
        savedConfigurationIds.addAll(configurationDetails.keys); configurationRequested.clear(); notifyChange()
    }

    fun foreground(latest: Boolean = true) {
        foregroundActive = true
        if (!bound || owner() == null || loading) return
        viewModelScope.launch {
            if (!authorized) {
                reload()
            } else { refresh(latest); Mango9SupervisorPresentation.actionIds(messages).forEach { loadConfiguration(it, true) } }
        }
    }

    fun actions() {
        val owner = owner() ?: return
        if (actionBusy) return
        actionBusy = true; detail = null; notifyChange()
        actionJob = viewModelScope.launch {
            try {
                actions = api.request(owner, "actions").optJSONArray("items")?.liveObjects().orEmpty(); val result = api.request(owner, "activity"); activity = (result.optJSONArray("items") ?: result.optJSONArray("events"))?.liveObjects().orEmpty()
            } catch (e: Exception) {
                if (e !is CancellationException) error = failure(e)
            } finally { actionBusy = false; notifyChange() }
        }
    }

    fun review(id: String) {
        val owner = owner() ?: return
        if (actionBusy || runCatching { UUID.fromString(id) }.isFailure) return
        actionBusy = true; notifyChange()
        actionJob = viewModelScope.launch {
            try {
                detail = api.request(owner, "actions/$id")
            } catch (e: Exception) {
                if (e !is CancellationException) error = failure(e)
            } finally { actionBusy = false; notifyChange() }
        }
    }

    fun loadConfiguration(id: String, force: Boolean = false) {
        val owner = owner() ?: return
        if (!authorized || id !in Mango9SupervisorPresentation.actionIds(messages) || actionBusy || runCatching {
            UUID.fromString(id)
        }.isFailure || id in configurationFlights || (!force && id in configurationRequested)
        ) {
            return
        }
        val stamp = generation; val chat = conversation; val epoch = configurationEpoch
        val flight = UUID.randomUUID()
        configurationRequested.add(id); configurationFlights[id] = flight
        viewModelScope.launch {
            try {
                val result = api.request(owner, "actions/$id")
                if (current(stamp, owner, chat) && configurationFlights[id] == flight && result.optString("id") == id && !actionBusy) {
                    if (foregroundActive && epoch == configurationEpoch) {
                        configurationDetails[id] = result; savedConfigurationIds.remove(id)
                    } else {
                        configurationDetails[id] = Mango9SupervisorPresentation.savedConfiguration(result) ?: result
                        savedConfigurationIds.add(id)
                    }
                    persistLocal()
                }
            } catch (e: Exception) {
                if (current(stamp, owner, chat) && configurationFlights[id] == flight && e !is CancellationException) {
                    savedConfigurationIds.add(id); error = failure(e); accessFailure(e)
                    if (e is Mango9SupervisorFailure && e.status == 404) { configurationDetails.remove(id); persistLocal() }
                }
            } finally {
                if (current(stamp, owner, chat) && configurationFlights[id] == flight) {
                    configurationFlights.remove(id); notifyChange()
                    if (foregroundActive && epoch != configurationEpoch) loadConfiguration(id, true)
                }
            }
        }
    }

    fun decide(decision: String, expected: JSONObject? = null) {
        val owner = owner() ?: return; val row = expected ?: detail ?: return
        if (expected != null && configurationDetails[row.optString("id")] !== expected) return
        val id = row.optString("id"); val digest = row.optString("approvalDigest")
        if (!foregroundActive || !authorized || id in savedConfigurationIds || actionBusy || sending || decision !in listOf("approve", "reject") || (decision == "approve" && !accepted) ||
            runCatching {
                UUID.fromString(id)
            }.isFailure || !digest.matches(Regex("[a-fA-F0-9]{64}")) || row.optString("state") != "PENDING_APPROVAL" || row.optBoolean("summaryOnly")
        ) {
                return
            }
        val stamp = generation
        // A GET started before Apply must never replace the receipt returned by Apply.
        configurationFlights.remove(id); configurationRequested.remove(id)
        actionBusy = true; notifyChange()
        actionJob = viewModelScope.launch {
            try {
                val result = api.request(owner, "actions/$id/decision", JSONObject().put("decision", decision).put("approvalDigest", digest))
                if (stamp == generation && owner() != null) {
                    detail = result; configurationDetails[id] = result; savedConfigurationIds.remove(id); persistLocal()
                }
            } catch (e: Exception) {
                if (stamp == generation && e !is CancellationException) {
                    error = failure(e); detail = null; savedConfigurationIds.add(id); accessFailure(e)
                }
            } finally { if (stamp == generation) { actionBusy = false; notifyChange() } }
            if (stamp != generation || owner() == null) return@launch
            refresh(); loadConfiguration(id, true)
            repeat(12) {
                if (stamp != generation || owner() == null || configurationDetails[id]?.optString("state") !in listOf("APPROVED", "EXECUTING")) return@launch
                delay(5000)
                if (stamp != generation || owner() == null) return@launch
                loadConfiguration(id, true)
            }
        }
    }

    private fun failure(e: Exception) = if (owner() == null) Mango9SupervisorFailure("scope_changed").message else (e as? Mango9SupervisorFailure)?.message ?: "Couldn’t reach Supervisor. Check your connection and try again."

    override fun onCleared() {
        saveDraftNow(); transfer.close(); voice.changed = {}; voice.historyChanged = {}; voice.stop(); super.onCleared()
    }
}
