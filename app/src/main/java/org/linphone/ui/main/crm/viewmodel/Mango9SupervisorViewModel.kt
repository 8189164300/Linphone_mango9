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

internal class Mango9SupervisorViewModel : ViewModel() {
    private val sessions = Mango9SessionStore(coreContext.context)
    private val store = Mango9SupervisorStore(coreContext.context)
    val api = Mango9SupervisorApi(coreContext.context, store)
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
    var moreConversations = false; private set
    var actions = listOf<JSONObject>(); private set
    var activity = listOf<JSONObject>(); private set
    var detail: JSONObject? = null; private set
    var actionBusy = false; private set
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
                }
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
        generation++; sendJob?.cancel(); loadJob?.cancel(); actionJob?.cancel(); voice.stop()
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
            if (e is CancellationException) throw e; authorized = false; error = failure(e)
        } finally { loading = false; notifyChange() }
    }

    suspend fun loadConversations(more: Boolean = false) {
        val owner = owner() ?: return
        val body = JSONObject().put("action", "list"); if (more && listCursor != null) body.put("before", listCursor)
        val result = api.request(owner, "history", body)
        val rows = result.optJSONArray("items")?.liveObjects().orEmpty()
        conversations = if (more) (conversations + rows).distinctBy { it.optString("id") } else rows
        listCursor = result.optString("nextCursor").takeIf {
            it.isNotBlank() && it != "null"
        }; moreConversations = listCursor != null; notifyChange()
    }

    fun select(id: String) {
        if (loading || sending || pending != null || runCatching { UUID.fromString(id) }.isFailure) return
        val owner = owner() ?: return
        generation++; voice.stop(); voicePage = false; conversation = id; messages = emptyList(); historyCursor = "0"; moreMessages = false; error = null
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
            val result = api.request(owner, "history", JSONObject().put("action", "read").put("conversationId", id).put("after", historyCursor))
            if (stamp != generation || id != conversation) return
            val rows = result.optJSONArray("messages")?.liveObjects().orEmpty().filter {
                it.optString("role") in listOf("assistant", "user")
            }
            val merged = messages.toMutableList()
            rows.forEach { row ->
                val index = merged.indexOfFirst {
                it.optString("id") == row.optString("id")
            }; if (index >= 0) merged[index] = row else merged.add(row)
            }
            messages = merged; historyCursor = result.optString("cursor", historyCursor)
            moreMessages = result.optString("nextCursor").let { it.isNotBlank() && it != "null" }
            val requestId = pending?.optString("requestId")
            if (requestId != null && messages.any {
                it.optString("role") == "assistant" && (it.optString("id") == requestId || it.optJSONObject("metadata")?.optString("requestId") == requestId)
            }
            ) {
                store.pending(owner.session, null); pending = null; error = null
            }
            if (result.optBoolean("pending")) historyRequested = true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is Mango9SupervisorFailure && e.status == 404 && e.code == "conversation_not_found") {
                if (pending != null) error = Mango9SupervisorFailure("history_pending").message
            } else if (stamp == generation) error = failure(e)
        } finally { refreshing = false; notifyChange() }
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
                generation++; sendJob?.cancel(); actionJob?.cancel(); voice.stop(); voicePage = false; sending = false; actionBusy = false
            }
        } catch (e: Exception) { error = failure(e) }
        notifyChange()
    }

    fun showVoice() { if (canVoice()) { voice.ready(); voicePage = true; notifyChange() } }

    fun connectVoice() { val owner = owner() ?: return; if (voicePage && canVoice()) voice.start(api, owner, conversation) }

    fun endVoice() { voice.stop(); voicePage = false; notifyChange(); viewModelScope.launch { refresh() } }

    fun background() { voice.stop(); voicePage = false; notifyChange() }

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

    fun decide(decision: String) {
        val owner = owner() ?: return; val row = detail ?: return
        val id = row.optString("id"); val digest = row.optString("approvalDigest")
        if (actionBusy || decision !in listOf("approve", "reject") || (decision == "approve" && !accepted) ||
            runCatching {
                UUID.fromString(id)
            }.isFailure || !digest.matches(Regex("[a-fA-F0-9]{64}")) || row.optString("state") != "PENDING_APPROVAL" || row.optBoolean("summaryOnly")
        ) {
                return
            }
        actionBusy = true; notifyChange()
        actionJob = viewModelScope.launch {
            try {
                detail = api.request(owner, "actions/$id/decision", JSONObject().put("decision", decision).put("approvalDigest", digest)); refresh()
            } catch (e: Exception) {
                if (e !is CancellationException) { error = failure(e); detail = null }
            } finally { actionBusy = false; notifyChange() }
        }
    }

    private fun failure(e: Exception) = if (owner() == null) Mango9SupervisorFailure("scope_changed").message else (e as? Mango9SupervisorFailure)?.message ?: "Couldn’t reach Supervisor. Check your connection and try again."

    override fun onCleared() { voice.changed = {}; voice.historyChanged = {}; voice.stop(); super.onCleared() }
}
