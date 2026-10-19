// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.ui.main.crm.viewmodel

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.mango9.*

internal data class Mango9SocialState(
    val accounts: List<Mango9SocialAccount> = emptyList(), val threads: List<Mango9SocialThread> = emptyList(),
    val platform: String = "", val account: String = "", val nextThread: String? = null,
    val thread: Mango9SocialThread? = null, val messages: List<Mango9SocialMessage> = emptyList(), val nextMessage: String? = null,
    val media: Map<String, Mango9ChatMedia> = emptyMap(), val muted: Boolean? = null,
    val loading: Boolean = false, val error: String? = null, val pending: Boolean = false,
    val sendNotice: String? = null,
)

/** Native inbox state survives rotation. Every completion is fenced to its navigation/login. */
internal class Mango9SocialViewModel : ViewModel() {
    private val sessions = Mango9SessionStore(coreContext.context)
    private val repository = Mango9SocialRepository(coreContext.context)
    private val commands = Mango9MessageCommands(coreContext.context)
    private var lease: Mango9SessionLease? = null
    private var generation = 0L
    val state = MutableLiveData(Mango9SocialState())
    val confirmedSend = MutableLiveData<org.linphone.utils.Event<String>>()
    private var lastProviderRefresh = 0L
    private var draft = ""

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
                    generation++; lease = null; state.value = Mango9SocialState(); cached.clear(); draft = ""
                }
            }
        }
    }

    fun current() = state.value ?: Mango9SocialState()

    fun bind(value: Mango9SessionLease?) {
        if (value == lease) return
        lease = value; generation++; state.value = Mango9SocialState(); draft = ""; lastProviderRefresh = 0L
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
        generation++
        state.value = current().copy(platform = platform, account = account, threads = emptyList(), nextThread = null, loading = false)
        inbox()
    }

    fun inbox(more: Boolean = false) {
        val owner = activeLease() ?: return
        val previous = current()
        if (previous.loading || (more && previous.nextThread == null)) return
        val request = ++generation
        state.value = previous.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val accounts = if (previous.accounts.isEmpty()) repository.accounts(owner) else previous.accounts
                val page = repository.inbox(owner, previous.platform, previous.account, if (more) previous.nextThread else null)
                val merged = Mango9SocialPages.inbox(previous.threads, page, more)
                val ids = merged.map { it.id }
                val hidden = if (ids.isEmpty()) emptySet() else commands.visibility(owner, Mango9MessageChannel.social, null, ids, false, true)
                if (!valid(owner, request)) return@launch
                state.value = current().copy(
                    accounts = accounts,
                    threads = Mango9SocialRecency.overlay(owner, merged.filterNot { it.id in hidden }),
                    nextThread = Mango9SocialPages.cursor(previous.nextThread, page, more, previous.threads.isNotEmpty()), loading = false
                )
            } catch (e: Exception) { failure(owner, request, e) }
        }
    }

    fun open(id: String, more: Boolean = false, refreshProvider: Boolean = false) {
        val owner = activeLease() ?: return
        val restoring = current().thread == null
        if (restoring) cacheKey(id)?.let { cached[it]?.first }?.let { state.value = it }
        if (current().loading || (more && current().nextMessage == null)) return
        val request = ++generation
        val prior = current()
        state.value = if (prior.thread?.id == id) prior.copy(loading = true, error = null) else Mango9SocialState(loading = true)
        viewModelScope.launch {
            try {
                var detail = repository.thread(owner, id)
                if (refreshProvider && detail.connected && System.currentTimeMillis() - lastProviderRefresh > 30_000) {
                    lastProviderRefresh = System.currentTimeMillis()
                    try {
                        repository.refreshMessages(owner, detail.sourceId)
                        detail = repository.thread(owner, id)
                    } catch (e: Exception) {
                        if (e is CancellationException || e is Mango9ApiException || e is Mango9MessagingFailure && e.status in setOf(401, 403, 404)) throw e
                        // Keep authorized saved history if provider reconciliation is unavailable.
                    }
                }
                val page = repository.messages(owner, id, if (more) prior.nextMessage else null)
                val all = ((if (prior.thread?.id == id) prior.messages else emptyList()) + page.items).associateBy {
                    it.id
                }.values.sortedWith(compareBy({ it.time }, { it.id }))
                val hidden = if (all.isEmpty()) {
                    emptySet()
                } else {
                    commands.visibility(
                        owner, Mango9MessageChannel.social, id,
                        all.map {
                    it.id.toString()
                },
                    false
                    )
                }
                val muted = try {
                    Mango9ConversationNotifications(coreContext.context).request(owner, Mango9MessageChannel.social, id)
                } catch (e: Mango9MessagingFailure) { if (e.status == 404) null else throw e }
                val confirmed = if (repository.hasPending(owner, id)) repository.reconcile(owner, id) else null
                if (!valid(owner, request)) return@launch
                val latest = all.maxByOrNull { Mango9ConversationRecency.time(it.time) }
                val activity = if (latest != null && Mango9ConversationRecency.newer(latest.time, detail.activityAt)) {
                    detail.copy(activityAt = latest.time, summary = latest.text)
                } else {
                    detail
                }
                Mango9SocialRecency.record(owner, activity)
                confirmed?.takeIf { Mango9SocialSendOutcome.of(it.getString("state")) == Mango9SocialSendOutcome.Sent }
                    ?.let { confirmedSend.value = org.linphone.utils.Event(it.getString("message")) }
                state.value = current().copy(
                    thread = detail, messages = all.filterNot { it.id.toString() in hidden }.takeLast(2000),
                    // Cached rows are bounded. Restart pagination from the fresh page so
                    // an older retained cursor cannot skip messages dropped from the cache.
                    muted = muted, nextMessage = if (more || restoring || prior.thread?.id != id) page.next else prior.nextMessage,
                    loading = false, pending = detail.openSend || repository.hasPending(owner, id),
                    sendNotice = if (confirmed != null) Mango9SocialSendOutcome.of(confirmed.getString("state")).notice else current().sendNotice
                )
                runCatching { repository.markRead(owner, detail) }
                // Thumbnails are resolved only for the latest page; actual byte downloads
                // use the shared bounded media cache and are never run on the UI thread.
                for (message in page.items.filterNot { it.id.toString() in hidden }) for (media in message.attachments) {
                    val key = "${message.id}:${media.index}"
                    if (current().media.containsKey(key)) continue
                    val resolved = runCatching { repository.attachment(owner, id, message.id, media) }.getOrNull() ?: continue
                    if (!valid(owner, request)) return@launch
                    state.value = current().copy(media = current().media + (key to resolved))
                }
            } catch (e: Exception) { failure(owner, request, e) }
        }
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
            if (activeLease() == owner) {
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
            if (activeLease() == owner) {
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
                val saved = Mango9ConversationNotifications(coreContext.context).request(owner, Mango9MessageChannel.social, thread.id, !muted)
                if (activeLease() == owner && current().thread?.id == thread.id) state.value = current().copy(muted = saved)
            } catch (e: Exception) { failure(owner, generation, e) }
        }
    }

    private fun failure(owner: Mango9SessionLease, request: Long, error: Exception) {
        if (error is CancellationException) throw error
        if (!valid(owner, request)) return
        val revoked = error is Mango9ApiException || error is Mango9MessagingFailure && error.status in setOf(401, 403, 404)
        state.value = (if (revoked) Mango9SocialState() else current()).copy(loading = false, error = error.message ?: "Social Chat could not be loaded.")
        if (revoked) { cached.clear(); draft = "" }
    }

    companion object { private val cached = LinkedHashMap<String, Pair<Mango9SocialState, String>>() }
}
