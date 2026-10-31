// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.ui.main.crm.viewmodel

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import org.json.JSONObject
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
    var lease: Mango9SessionLease? = null; private set
    var authorized = false; private set
    var accepted = false; private set
    var loading = true; private set
    var sending = false; private set
    var voicePage = false; private set
    var error: String? = null; private set
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
    private val configurationFlights = mutableSetOf<String>()
    private var historyCursor = "0"
    private var listCursor: String? = null
    private var generation = 0
    private var refreshing = false
    private var historyRequested = false
    private var poll: Job? = null
    private var sendJob: Job? = null
    private var loadJob: Job? = null
    private var actionJob: Job? = null
    private var bound = false

    init {
        voice.changed = { notifyChange() }
        voice.historyChanged = { historyRequested = true; viewModelScope.launch { refresh() } }
    }

    fun owner() = lease?.takeIf { sessions.currentSession(it) != null }

    fun notifyChange() { changed.value = (changed.value ?: 0) + 1 }

    fun canSend() = authorized && accepted && !loading && !sending && pending == null && !voice.running && owner() != null && Mango9SupervisorWire.validText(draft)

    fun canVoice() = authorized && accepted && !loading && !sending && pending == null && owner() != null

    fun canManageHistory() = authorized && !loading && !sending && !actionBusy && !voice.running && pending == null && deletingConversation == null && owner() != null

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
        val saved = JSONObject().put("id", conversation).put("messages", org.json.JSONArray(rows)).put("configurations", org.json.JSONArray(configs))
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
        ).take(10)
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

    fun bind(value: Mango9SessionLease?) {
        if (bound) return
        bound = true; lease = value
        if (value == null) { loading = false; error = Mango9SupervisorFailure("scope_changed").message; notifyChange(); return }
        loadJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    accepted = store.accepted(value.session); pending = store.pending(value.session)
                    conversation = pending?.optString("conversationId") ?: store.conversation(value.session) ?: UUID.randomUUID().toString()
                    if (runCatching { UUID.fromString(conversation) }.isFailure) conversation = UUID.randomUUID().toString()
                    savedChats = store.savedChats(value.session)
                }
                restoreLocal(); notifyChange()
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
                if (ticks % 8 == 0 && (pending != null || voice.running || historyRequested)) refresh()
            }
        }
    }

    private fun clearAccount() {
        generation++; listFlight++; savedChats = emptyList(); savedConfigurationIds.clear(); configurationDetails.clear(); configurationRequested.clear(); configurationFlights.clear(); detail = null; sendJob?.cancel(); loadJob?.cancel(); actionJob?.cancel(); voice.stop()
        authorized = false; accepted = false; loading = false; sending = false; voicePage = false; draft = ""; pending = null
        messages = emptyList(); conversations = emptyList(); actions = emptyList(); activity = emptyList(); detail = null
        error = Mango9SupervisorFailure("scope_changed").message; notifyChange()
    }

    fun retry() { loadJob?.cancel(); loadJob = viewModelScope.launch { reload() } }

    private suspend fun reload() {
        val owner = owner() ?: return
        loading = true; error = null; notifyChange()
        try {
            val status = api.authorize(owner); authorized = true; billing = status.optBoolean("billingEnabled")
            loadConversations()
            if (pending == null && store.conversation(owner.session) == null) {
                conversations.firstOrNull()?.optString("id")?.takeIf {
                runCatching { UUID.fromString(it) }.isSuccess
            }?.let { conversation = it }
            }
            store.conversation(owner.session, conversation)
            refresh()
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
            conversations = if (more) (conversations + rows).distinctBy { it.optString("id") } else rows
            listCursor = result.optString("nextCursor").takeIf {
                it.isNotBlank() && it != "null"
            }; moreConversations = listCursor != null
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (flight == listFlight && stamp == generation) { historyError = failure(e); accessFailure(e) }
        } finally { if (flight == listFlight) { loadingConversations = false; notifyChange() } }
    }

    fun deleteConversation(id: String) {
        val owner = owner() ?: return
        if (!canManageHistory() || conversations.none {
            it.optString("id") == id
        } || !Mango9SupervisorPresentation.uuid(id)
        ) {
            return
        }
        val stamp = generation; deletingConversation = id; listFlight++; historyError = null; notifyChange()
        viewModelScope.launch {
            try {
                val result = api.request(owner, "history", JSONObject().put("action", "delete").put("conversationId", id))
                if (stamp != generation) return@launch
                if (!result.optBoolean("deleted") || result.optString("id") != id) throw Mango9SupervisorFailure("invalid_response")
                savedChats = savedChats.filterNot { it.optString("id") == id }; store.savedChats(owner.session, savedChats)
                conversations = conversations.filterNot { it.optString("id") == id }
                deletingConversation = null
                if (id == conversation) newConversation()
                loadConversations()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (stamp == generation) {
                    historyError = "Couldn’t confirm deletion. Refresh History before trying again."; accessFailure(e)
                }
            } finally { deletingConversation = null; notifyChange() }
        }
    }

    fun select(id: String) {
        if (loading || sending || actionBusy || pending != null || runCatching { UUID.fromString(id) }.isFailure) return
        val owner = owner() ?: return
        generation++; configurationDetails.clear(); configurationRequested.clear(); configurationFlights.clear(); detail = null; actionBusy = false; voice.stop(); voicePage = false; conversation = id; messages = emptyList(); historyCursor = "0"; moreMessages = false; error = null
        listFlight++; savedConfigurationIds.clear(); historyReady = false; olderCursor = null; loadingOlder = false; restoreLocal()
        loadJob = viewModelScope.launch {
            try {
                store.conversation(owner.session, id); loading = true; notifyChange(); while (refreshing) delay(20); refresh()
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

    suspend fun refresh() {
        val owner = owner() ?: return
        if (!authorized) return
        if (refreshing) { historyRequested = true; return }
        val stamp = generation; val id = conversation
        refreshing = true; historyRequested = false
        try {
            val tail = !historyReady
            val body = JSONObject().put("action", "read").put("conversationId", id)
            if (tail) body.put("latest", true) else body.put("after", historyCursor)
            pending?.optString("requestId")?.let { body.put("requestId", it) }
            val result = api.request(owner, "history", body)
            if (stamp != generation || id != conversation) return
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
            if (tail) {
                olderCursor = result.optString("olderCursor").takeIf {
                    it.isNotBlank() && it != "null"
                }; moreMessages = olderCursor != null
            }
            val requestId = pending?.optString("requestId")
            if (requestId != null && (
                result.optString("confirmedRequestId") == requestId || messages.any {
                it.optString("role") == "assistant" && (it.optString("id") == requestId || it.optJSONObject("metadata")?.optString("requestId") == requestId)
            }
            )
            ) {
                store.pending(owner.session, null); pending = null; error = null
            }
            if (result.optBoolean("pending") || result.optString("nextCursor").let {
                it.isNotBlank() && it != "null"
            }
            ) {
                historyRequested = true
            }
            persistLocal()
            Mango9SupervisorPresentation.actionIds(messages).forEach { loadConfiguration(it) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is Mango9SupervisorFailure && e.status == 404 && e.code == "conversation_not_found") {
                if (pending != null) error = Mango9SupervisorFailure("history_pending").message
                if (stamp == generation) { messages = emptyList(); configurationDetails.clear(); persistLocal() }
            } else if (stamp == generation) { error = failure(e); accessFailure(e) }
        } finally { refreshing = false; notifyChange() }
    }

    suspend fun loadOlder() {
        val owner = owner() ?: return
        val cursor = olderCursor ?: return
        if (!authorized || loadingOlder || refreshing) return
        val stamp = generation; val id = conversation; loadingOlder = true; notifyChange()
        try {
            val result = api.request(owner, "history", JSONObject().put("action", "read").put("conversationId", id).put("before", cursor))
            if (stamp != generation || id != conversation) return
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
            if (stamp == generation) { error = failure(e); accessFailure(e) }
        } finally { if (stamp == generation) { loadingOlder = false; notifyChange() } }
    }

    fun send() {
        val owner = owner() ?: return
        if (!canSend()) return
        val command = Mango9SupervisorWire.command(conversation, draft.trim()); val stamp = generation
        sending = true; error = null; notifyChange()
        sendJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.pending(owner.session, command) }
                ensureActive(); pending = command; draft = ""; notifyChange()
                val reply = api.request(owner, "chat", command)
                if (stamp != generation) return@launch
                if (reply.optString("role") != "assistant") throw Mango9SupervisorFailure("invalid_response")
                refresh()
                val local = command.getJSONObject("message")
                if (messages.none { it.optString("id") == local.optString("id") }) messages += local
                if (messages.none { it.optString("id") == reply.optString("id") }) messages += reply
                store.pending(owner.session, null); pending = null
                runCatching { loadConversations() }
                persistLocal()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (stamp == generation) { error = failure(e); refresh() }
            } finally { if (stamp == generation) { sending = false; notifyChange() } }
        }
    }

    fun consent(allow: Boolean) {
        val owner = owner() ?: return
        try {
            store.consent(owner.session, allow); accepted = store.accepted(owner.session)
            if (!allow) {
                generation++; configurationDetails.clear(); configurationRequested.clear(); configurationFlights.clear(); detail = null; sendJob?.cancel(); actionJob?.cancel(); voice.stop(); voicePage = false; sending = false; actionBusy = false
            }
        } catch (e: Exception) { error = failure(e) }
        notifyChange()
    }

    fun showVoice() { if (canVoice()) { voice.ready(); voicePage = true; notifyChange() } }

    fun connectVoice() { val owner = owner() ?: return; if (voicePage && canVoice()) voice.start(api, owner, conversation) }

    fun endVoice() { voice.stop(); voicePage = false; notifyChange(); viewModelScope.launch { refresh() } }

    fun background() {
        foregroundActive = false; configurationEpoch++
        voice.stop(); voicePage = false
        configurationDetails.replaceAll { _, value -> Mango9SupervisorPresentation.savedConfiguration(value) ?: value }
        savedConfigurationIds.addAll(configurationDetails.keys); configurationRequested.clear(); notifyChange()
    }

    fun foreground() {
        foregroundActive = true
        if (!bound || owner() == null || loading) return
        viewModelScope.launch {
            if (!authorized) {
                reload()
            } else { refresh(); Mango9SupervisorPresentation.actionIds(messages).forEach { loadConfiguration(it, true) } }
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
        configurationRequested.add(id); configurationFlights.add(id)
        viewModelScope.launch {
            try {
                val result = api.request(owner, "actions/$id")
                if (stamp == generation && chat == conversation && owner() != null && result.optString("id") == id && !actionBusy) {
                    if (foregroundActive && epoch == configurationEpoch) {
                        configurationDetails[id] = result; savedConfigurationIds.remove(id)
                    } else {
                        configurationDetails[id] = Mango9SupervisorPresentation.savedConfiguration(result) ?: result
                        savedConfigurationIds.add(id)
                    }
                    persistLocal()
                }
            } catch (e: Exception) {
                if (stamp == generation && e !is CancellationException) {
                    savedConfigurationIds.add(id); error = failure(e); accessFailure(e)
                    if (e is Mango9SupervisorFailure && e.status == 404) { configurationDetails.remove(id); persistLocal() }
                }
            } finally {
                if (stamp == generation) {
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

    override fun onCleared() { voice.changed = {}; voice.historyChanged = {}; voice.stop(); super.onCleared() }
}
