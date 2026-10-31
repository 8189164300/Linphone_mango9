// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.ui.main.crm.viewmodel

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.mango9.*

internal data class Mango9SocialState(
    val accounts: List<Mango9SocialAccount> = emptyList(), val threads: List<Mango9SocialThread> = emptyList(),
    val platform: String = "", val account: String = "", val search: String = "", val nextThread: String? = null,
    val accountWarning: String? = null, val providerWarning: String? = null,
    val thread: Mango9SocialThread? = null, val messages: List<Mango9SocialMessage> = emptyList(), val nextMessage: String? = null,
    val media: Map<String, Mango9ChatMedia> = emptyMap(), val muted: Boolean? = null,
    val loading: Boolean = false, val error: String? = null, val pending: Boolean = false,
    val sendNotice: String? = null,
    val previews: Map<String, String> = emptyMap(), val previewEpoch: Long = 0,
)

/** Native inbox state survives rotation. Every completion is fenced to its navigation/login. */
internal class Mango9SocialViewModel(
    private val repository: Mango9SocialRepository = Mango9SocialRepository(coreContext.context),
    private val commands: Mango9MessageCommands = Mango9MessageCommands(coreContext.context),
    private val notification: suspend (Mango9SessionLease, String, Boolean?) -> Boolean? = { owner, id, muted ->
        Mango9ConversationNotifications(coreContext.context).request(owner, Mango9MessageChannel.social, id, muted)
    },
    private val clock: () -> Long = android.os.SystemClock::elapsedRealtime,
) : ViewModel() {
    private var lease: Mango9SessionLease? = null
    private var generation = 0L
    val state = MutableLiveData(Mango9SocialState())
    val confirmedSend = MutableLiveData<org.linphone.utils.Event<String>>()
    private var providerAttempt: Pair<String, Long>? = null
    private var accountsLoadedAt: Long? = null
    private var readJob: Job? = null
    private var inboxJob: Job? = null
    private var searchJob: Job? = null
    private var readingThread: String? = null
    private var pendingRead: ThreadRead? = null
    private var pendingOlder = false
    private var pendingInbox = false
    private var pendingAccounts = false
    private var restartMessagePaging = false

    private data class ThreadRead(val id: String, val more: Boolean, val refresh: Boolean, val poll: Boolean)

    private val consumedCursors = mutableSetOf<String>()

    private fun cancelReads() {
        generation++
        pendingRead = null; pendingOlder = false; pendingInbox = false; pendingAccounts = false
        readJob?.cancel(); readJob = null; readingThread = null
        inboxJob?.cancel(); inboxJob = null
        searchJob?.cancel(); searchJob = null
    }

    private var draft = ""
    private val previewJobs = mutableMapOf<String, Job>()
    private val previewSlots = Semaphore(4)

    private fun cancelPreviews() {
        previewJobs.values.forEach { it.cancel() }
        previewJobs.clear()
    }

    /** Resolve only rows on screen; never render the provider's unfiltered summary. */
    fun preview(id: String) {
        val owner = activeLease() ?: return
        if (id in current().previews || id in previewJobs || current().threads.none { it.id == id }) return
        val request = generation
        previewJobs[id] = viewModelScope.launch {
            val text = try {
                previewSlots.withPermit {
                    val page = repository.messages(owner, id)
                    val hidden = if (page.items.isEmpty()) {
                        emptySet()
                    } else {
                        commands.visibility(
                        owner, Mango9MessageChannel.social, id, page.items.map { it.id.toString() }, false
                    )
                    }
                    Mango9SocialPreview.text(page.items, hidden, page.next != null)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                "Preview unavailable"
            }
            if (valid(owner, request) && current().threads.any { it.id == id }) {
                state.value = current().copy(previews = current().previews + (id to text))
            }
        }
    }

    init {
        viewModelScope.launch {
            Mango9SocialRecency.state.collect {
                val owner = activeLease() ?: return@collect
                val rows = Mango9SocialRecency.overlay(owner, current().threads)
                if (rows != current().threads) state.value = current().copy(threads = rows)
            }
        }
        viewModelScope.launch {
            Mango9SessionStore.contextChanges.collect {
                if (lease != null && activeLease() == null) {
                    cancelPreviews(); cancelReads(); providerAttempt = null; accountsLoadedAt = null; lease = null; state.value = Mango9SocialState(); cached.clear(); draft = ""
                }
            }
        }
    }

    fun current() = state.value ?: Mango9SocialState()

    fun bind(value: Mango9SessionLease?) {
        if (value == lease) return
        cancelPreviews(); cancelReads(); lease = value; state.value = Mango9SocialState(); draft = ""; providerAttempt = null; accountsLoadedAt = null; consumedCursors.clear()
    }

    fun activeLease() = lease?.takeIf { repository.isCurrent(it) }

    private fun cacheKey(id: String): String? = activeLease()?.let {
        "${Mango9MessagingApi.account(it.session)}:${it.loginGeneration}:${it.selectionGeneration}:$id"
    }

    fun restoreDraft(id: String): String = cacheKey(id)?.let { cached[it]?.second }.orEmpty().also { draft = it }

    fun retainDraft(text: String) { draft = text }

    override fun onCleared() {
        current().thread?.id?.let { id ->
            cacheKey(id)?.let { key ->
            cached.remove(key)
            cached[key] = current().copy(messages = current().messages.takeLast(200), media = emptyMap(), loading = false, error = null) to draft
            while (cached.size > 12) cached.remove(cached.keys.first())
        }
        }
        super.onCleared()
    }

    private fun valid(owner: Mango9SessionLease, request: Long) = activeLease() == owner && generation == request

    fun filters(platform: String, account: String) {
        cancelPreviews(); cancelReads(); consumedCursors.clear()
        state.value = current().copy(platform = platform, account = account, threads = emptyList(), nextThread = null, loading = false)
        inbox(forceAccounts = false)
    }

    fun search(value: String) {
        val query = Mango9SocialSearch.normalize(value)
        if (query == current().search) return
        cancelPreviews(); cancelReads(); consumedCursors.clear()
        state.value = current().copy(search = query, threads = emptyList(), nextThread = null, loading = true, error = null)
        searchJob = viewModelScope.launch { delay(250); inbox(forceAccounts = false) }
    }

    /** Manual refresh reloads connections; foreground polling uses the bounded inventory cache. */
    fun inbox(more: Boolean = false, forceAccounts: Boolean = true) {
        val owner = activeLease() ?: return
        if (inboxJob?.isActive == true) {
            if (!more) { pendingInbox = true; pendingAccounts = pendingAccounts || forceAccounts }
            return
        }
        if (more && current().nextThread == null) return
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                var append = more; var reloadAccounts = forceAccounts
                do {
                    pendingInbox = false; pendingAccounts = false
                    loadInbox(owner, append, reloadAccounts)
                    append = false; reloadAccounts = pendingAccounts
                } while (pendingInbox && activeLease() == owner)
            } finally {
                if (inboxJob === coroutineContext[Job]) inboxJob = null
            }
        }
        inboxJob = job; job.start()
    }

    private suspend fun loadInbox(owner: Mango9SessionLease, more: Boolean, forceAccounts: Boolean) {
        val previous = current()
        val request = ++generation
        cancelPreviews()
        state.value = previous.copy(loading = true, error = null, previews = emptyMap(), previewEpoch = request)
        try {
            var accounts = previous.accounts
            var warning = previous.accountWarning
            val due = accountsLoadedAt?.let { clock() - it >= 300_000 || clock() < it } ?: true
            if (!more && (forceAccounts || due)) {
                try {
                    accounts = repository.accounts(owner)
                    if (!valid(owner, request)) return
                    accountsLoadedAt = clock(); warning = null
                } catch (e: Exception) {
                    if (e is CancellationException || revoked(e)) throw e
                    warning = "Account filters unavailable. Tap to retry."
                }
            }
            val account = previous.account.takeIf { selected ->
                selected.isEmpty() || accounts.any {
                    it.id == selected && (previous.platform.isEmpty() || it.platform == previous.platform)
                }
            }.orEmpty()
            val page = repository.inbox(owner, previous.platform, account, if (more) previous.nextThread else null, previous.search)
            val merged = Mango9SocialPages.inbox(previous.threads, page, more)
            val ids = merged.map { it.id }
            val hidden = if (ids.isEmpty()) emptySet() else commands.visibility(owner, Mango9MessageChannel.social, null, ids, false, true)
            if (!valid(owner, request)) return
            if (more) previous.nextThread?.let { consumedCursors.add(it) } else consumedCursors.clear()
            state.value = current().copy(
                accounts = accounts, account = account, accountWarning = warning,
                threads = Mango9SocialRecency.overlay(owner, merged.filterNot { it.id in hidden }),
                nextThread = page.next?.takeUnless { it in consumedCursors }, loading = false
            )
        } catch (e: Exception) { failure(owner, request, e) }
    }

    fun poll(id: String) = requestRead(ThreadRead(id, false, false, true))

    fun open(id: String, more: Boolean = false, refreshProvider: Boolean = false) =
        requestRead(ThreadRead(id, more, refreshProvider, false))

    private fun requestRead(value: ThreadRead) {
        val owner = activeLease() ?: return
        if (value.more && current().nextMessage == null) return
        if (readJob?.isActive == true && readingThread == value.id) {
            if (value.more) {
                pendingOlder = true
            } else {
                val pending = pendingRead
                pendingRead = value.copy(refresh = value.refresh || pending?.refresh == true, poll = value.poll || pending?.poll == true)
            }
            return
        }
        cancelReads()
        if (current().thread == null) {
            cacheKey(value.id)?.let { cached[it]?.first }?.let {
            state.value = it; restartMessagePaging = true
        }
        }
        if (current().thread?.id != value.id) {
            providerAttempt = null
            state.value = Mango9SocialState(loading = true)
        }
        readingThread = value.id
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                var next: ThreadRead? = value
                while (next != null && activeLease() == owner) {
                    loadThread(owner, next)
                    next = if (pendingOlder && current().nextMessage != null) {
                        pendingOlder = false
                        ThreadRead(value.id, true, false, false)
                    } else {
                        pendingOlder = false
                        pendingRead.also { pendingRead = null }
                    }
                }
            } finally {
                if (readJob === coroutineContext[Job]) {
                    readJob = null; readingThread = null; pendingRead = null; pendingOlder = false
                }
            }
        }
        readJob = job; job.start()
    }

    private suspend fun loadThread(owner: Mango9SessionLease, value: ThreadRead) {
        val request = ++generation
        val id = value.id
        state.value = current().copy(loading = true, error = current().providerWarning)
        try {
            var detail = repository.thread(owner, id)
            if (!valid(owner, request)) return
            if (!detail.connected) state.value = current().copy(providerWarning = null)
            // Publish authorized saved rows before the provider or auxiliary preferences can block.
            val attachmentMessages = loadSaved(owner, detail, value.more, request).associateBy { it.id }.toMutableMap()
            if (!valid(owner, request)) return
            val attempted = providerAttempt
            val due = attempted == null || attempted.first != id || clock() < attempted.second || clock() - attempted.second >= 60_000
            if (detail.connected && (value.refresh || (value.poll && due))) {
                providerAttempt = id to clock()
                try {
                    repository.refreshMessages(owner, detail.sourceId)
                    if (!valid(owner, request)) return
                    detail = repository.thread(owner, id)
                    if (!valid(owner, request)) return
                    attachmentMessages.putAll(loadSaved(owner, detail, false, request).associateBy { it.id })
                    if (!valid(owner, request)) return
                    state.value = current().copy(providerWarning = null, error = null)
                } catch (e: Exception) {
                    if (e is CancellationException || revoked(e)) throw e
                    if (!valid(owner, request)) return
                    val warning = "Couldn’t refresh the provider. Showing saved history. Tap Retry to try again."
                    state.value = current().copy(providerWarning = warning, error = warning)
                }
            }
            val muted = try { notification(owner, id, null) } catch (e: Exception) {
                if (e is CancellationException || (revoked(e) && !(e is Mango9MessagingFailure && e.status == 404))) throw e
                current().muted
            }
            val confirmed = if (repository.hasPending(owner, id)) repository.reconcile(owner, id) else null
            if (!valid(owner, request)) return
            confirmed?.takeIf { Mango9SocialSendOutcome.of(it.getString("state")) == Mango9SocialSendOutcome.Sent }
                ?.let { confirmedSend.value = org.linphone.utils.Event(it.getString("message")) }
            state.value = current().copy(
                muted = muted, pending = detail.openSend || repository.hasPending(owner, id), loading = false,
                error = current().providerWarning,
                sendNotice = if (confirmed != null) Mango9SocialSendOutcome.of(confirmed.getString("state")).notice else current().sendNotice
            )
            try {
                repository.markRead(owner, detail)
            } catch (e: Exception) { if (e is CancellationException || revoked(e)) throw e }
            if (!valid(owner, request)) return
            val visibleIds = current().messages.filterNot { it.deleted }.map { it.id }.toSet()
            for (message in attachmentMessages.values.filter { it.id in visibleIds }) for (media in message.attachments) {
                val key = "${message.id}:${media.index}"
                if (current().media.containsKey(key)) continue
                val resolved = try { repository.attachment(owner, id, message.id, media) } catch (e: Exception) {
                    if (e is CancellationException || revoked(e)) throw e
                    null
                } ?: continue
                if (!valid(owner, request)) return
                state.value = current().copy(media = current().media + (key to resolved))
            }
        } catch (e: Exception) { failure(owner, request, e) }
    }

    private suspend fun loadSaved(owner: Mango9SessionLease, detail: Mango9SocialThread, more: Boolean, request: Long): List<Mango9SocialMessage> {
        val prior = current()
        val page = repository.messages(owner, detail.id, if (more) prior.nextMessage else null)
        val all = ((if (prior.thread?.id == detail.id) prior.messages else emptyList()) + page.items)
            .associateBy { it.id }.values.sortedWith(compareBy({ it.time }, { it.id }))
        val hidden = if (all.isEmpty()) {
            emptySet()
        } else {
            commands.visibility(
            owner, Mango9MessageChannel.social, detail.id, all.map { it.id.toString() }, false
        )
        }
        if (!valid(owner, request)) return emptyList()
        val visible = all.filterNot { it.id.toString() in hidden }.takeLast(2000)
        val preview = Mango9SocialPreview.text(all, hidden, page.next != null)
        val latest = visible.filterNot { it.deleted }.maxByOrNull { Mango9ConversationRecency.time(it.time) }
        val activity = if (latest != null && Mango9ConversationRecency.newer(latest.time, detail.activityAt)) {
            detail.copy(activityAt = latest.time, summary = preview)
        } else {
            detail.copy(summary = preview)
        }
        Mango9SocialRecency.record(owner, activity)
        state.value = current().copy(
            thread = detail, messages = visible, loading = false, error = current().providerWarning,
            nextMessage = if (more || restartMessagePaging || prior.thread?.id != detail.id || prior.messages.isEmpty()) page.next else prior.nextMessage,
            pending = detail.openSend || repository.hasPending(owner, detail.id)
        )
        restartMessagePaging = false
        return page.items
    }

    fun deletedMessages(ids: Set<String>) {
        // Fence reads started before the deletion receipt so they cannot briefly resurrect rows.
        cancelReads()
        val id = current().thread?.id ?: return
        state.value = current().copy(messages = current().messages.filterNot { it.id.toString() in ids }, loading = false)
        cacheKey(id)?.let { cached.remove(it) }
        open(id)
    }

    suspend fun freshAttachment(media: Mango9ChatMedia): Mango9ChatMedia {
        val owner = activeLease() ?: throw Mango9ApiException.Unauthorized
        val snapshot = current()
        val thread = snapshot.thread ?: throw Mango9ApiException.Unauthorized
        val key = snapshot.media.entries.firstOrNull { it.value.url == media.url }?.key
            ?: throw Mango9MessagingFailure(0, "invalid_response")
        val message = snapshot.messages.firstOrNull { it.id.toString() == key.substringBefore(':') }
            ?: throw Mango9MessagingFailure(0, "invalid_response")
        val attachment = message.attachments.firstOrNull { it.index.toString() == key.substringAfter(':') }
            ?: throw Mango9MessagingFailure(0, "invalid_response")
        // Storage links expire. Reauthorize the original on every open/forward;
        // never add a bearer token or cookies to the signed storage request.
        val resolved = repository.attachment(owner, thread.id, message.id, attachment)
        if (activeLease() != owner || current().thread?.id != thread.id) throw Mango9ApiException.Unauthorized
        return resolved
    }

    suspend fun send(text: String, attachments: List<Mango9PendingAttachment>): Boolean {
        val owner = activeLease() ?: return false
        val thread = current().thread ?: return false
        return try {
            val sent = repository.send(owner, thread, text, attachments)
            if (activeLease() == owner && current().thread?.id == thread.id) {
                val pending = repository.hasPending(owner, thread.id)
                if (sent) Mango9SocialRecency.record(owner, thread.copy(activityAt = java.time.Instant.now().toString(), summary = text))
                state.value = current().copy(
                    pending = pending,
                    sendNotice = when {
                    sent -> null
                    pending -> Mango9SocialSendOutcome.Unknown.notice
                    else -> Mango9SocialSendOutcome.Failed.notice
                }
                )
                open(thread.id)
            }
            sent
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (activeLease() == owner && current().thread?.id == thread.id) {
                val pending = repository.hasPending(owner, thread.id)
                state.value = current().copy(sendNotice = if (pending) Mango9SocialSendOutcome.Unknown.notice else e.message, pending = pending)
            }
            false
        }
    }

    fun mute() {
        val owner = activeLease() ?: return
        val thread = current().thread ?: return
        val muted = current().muted ?: return
        viewModelScope.launch {
            try {
                val saved = notification(owner, thread.id, !muted)
                if (activeLease() == owner && current().thread?.id == thread.id) state.value = current().copy(muted = saved)
            } catch (e: Exception) { failure(owner, generation, e) }
        }
    }

    private fun failure(owner: Mango9SessionLease, request: Long, error: Exception) {
        if (error is CancellationException) throw error
        if (!valid(owner, request)) return
        val revoked = revoked(error)
        state.value = (if (revoked) Mango9SocialState() else current()).copy(loading = false, error = error.message ?: "Social Chat could not be loaded.")
        if (revoked) {
            cached.clear(); draft = ""; pendingRead = null; pendingOlder = false; pendingInbox = false; accountsLoadedAt = null; providerAttempt = null
        }
    }

    private fun revoked(error: Exception) = error is Mango9ApiException ||
        error is Mango9MessagingFailure && error.status in setOf(401, 403, 404)

    companion object { private val cached = LinkedHashMap<String, Pair<Mango9SocialState, String>>() }
}
