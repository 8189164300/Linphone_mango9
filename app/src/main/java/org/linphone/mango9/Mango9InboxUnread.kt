// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9
import org.json.JSONObject
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect

/** An older inbox snapshot cannot undo a confirmed read or clear newer content. */
internal class Mango9InboxReadLedger {
    private val through = mutableMapOf<String, Long>()

    fun read(id: String, channel: String, version: Long?) {
        if (version == null || version <= 0) return
        val key = "$channel:$id"; through[key] = maxOf(through[key] ?: 0, version)
    }

    fun unread(value: Boolean, id: String, channel: String, version: Long?): Boolean =
        value && (version == null || through["$channel:$id"]?.let { version > it } != false)
}

/** Counts unread chats, scoped to the current login; reads invalidate older snapshots. */
internal object Mango9InboxUnread {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    val counts = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Int>>(emptyMap())

    @Volatile private var account: Mango9SessionLease? = null
    private var ids = mutableMapOf<String, Set<String>>()
    private var revision = 0
    private var reads = Mango9InboxReadLedger()
    val readChanges = kotlinx.coroutines.flow.MutableStateFlow(0)
    private var running = false
    private var again = false
    private var foreground = false
    private var polling: kotlinx.coroutines.Job? = null
    private val context get() = org.linphone.LinphoneApplication.coreContext.context
    private val sessions get() = Mango9SessionStore(context)

    private fun publish() {
        val preferences = Mango9ChatTabPreferences(context)
        counts.value = ids.mapValues { (channel, values) -> if (channel == "sms" || preferences.visible(channel)) values.size else 0 }
    }

    fun total(): Int = if (account?.let {
        sessions.currentSession(it)
    } != null
    ) {
        Mango9InboxCountPolicy.total(counts.value, counts.value.keys)
    } else {
        0
    }

    fun visible(active: Boolean) {
        foreground = active; polling?.cancel(); polling = null
        if (active) {
            polling = scope.launch {
            launch { Mango9ChatStore.get(context).state.collect { local() } }
            while (kotlinx.coroutines.currentCoroutineContext().isActive) { refresh(); kotlinx.coroutines.delay(30_000) }
        }
        }
    }

    private fun bind(): Mango9SessionLease? {
        val current = sessions.captureSession()
        if (account?.selectionGeneration != current?.selectionGeneration || account?.loginGeneration != current?.loginGeneration || account?.session?.let(Mango9MessagingApi::account) != current?.session?.let(Mango9MessagingApi::account)) {
            account = current; revision++; reads = Mango9InboxReadLedger(); readChanges.value++; ids.clear(); counts.value = emptyMap()
        }
        return current
    }

    private fun local() {
        bind() ?: return
        val state = Mango9ChatStore.get(context).state.value; val moderation = Mango9ChatModerationStore(context)
        if (Mango9SessionStore.normalizedIdentity(state.connectedIdentity) != Mango9SessionStore.normalizedIdentity(account?.session?.sipIdentity)) {
            ids["sms"] = emptySet(); ids["team"] = emptySet(); publish(); return
        }
        ids["sms"] = Mango9InboxCountPolicy.ids(state.smsParties.map { it.phone to it.unread }, sms = true)
        ids["team"] = state.rooms.filter {
            it.unread > 0 && !moderation.isConversationDeleted(it.id) && (
                !it.isDirect || it.userIds.firstOrNull()?.let { id ->
                    state.users.any {
                it.id == id && !it.isSystem
            } && !moderation.isBlocked(id)
            } == true
            )
        }.map { it.id }.toSet()
        publish()
    }

    fun socialRow(row: Mango9SocialThread, owner: Mango9SessionLease): Mango9SocialThread =
        if (sameAccount(owner) && !row.markedUnread) row.copy(unread = reads.unread(row.unread, row.id, "social", row.readThrough?.toLong())) else row

    private fun sameAccount(owner: Mango9SessionLease) = account?.loginGeneration == owner.loginGeneration &&
        account?.selectionGeneration == owner.selectionGeneration && account?.session?.let(Mango9MessagingApi::account) == Mango9MessagingApi.account(owner.session)

    fun socialReceipt(row: Mango9SocialThread, owner: Mango9SessionLease) {
        if (sessions.currentSession(owner) == null) return
        bind()
        if (row.unread) { revision++; ids["social"] = ids["social"].orEmpty() + row.id; publish() } else {
            didRead(row.id, "social", owner, row.readThrough?.toLong())
        }
    }

    fun didRead(id: String, channel: String, owner: Mango9SessionLease, version: Long? = null) {
        if (sessions.currentSession(owner) == null) return
        bind(); reads.read(id, channel, version); readChanges.value++; revision++; ids[channel] = ids[channel].orEmpty() - id; publish(); refreshSoon()
    }

    fun refreshSoon() { scope.launch { if (foreground) refresh() } }

    suspend fun refresh() {
        val owner = bind() ?: return
        local()
        if (running) { again = true; return }
        running = true
        try {
            do {
                again = false; val mark = revision
                for (channel in listOf("social", "live")) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (sessions.currentSession(owner) == null) return
                    if (!Mango9ChatTabPreferences(context).visible(channel)) {
                        ids[channel] = emptySet(); continue
                    }
                    try {
                        val result = fetch(owner, channel)
                        if (sessions.currentSession(owner) == null) return
                        if (mark == revision) ids[channel] = result else again = true
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        val status = when (e) { is Mango9MessagingFailure -> e.status; is Mango9LiveChatFailure -> e.status; else -> 0 }
                        if (mark == revision && status in setOf(401, 403, 404)) ids[channel] = emptySet()
                    }
                }
                publish()
            } while (again && sessions.currentSession(owner) != null)
        } finally { running = false }
    }

    private suspend fun fetch(owner: Mango9SessionLease, channel: String): Set<String> {

        val found = mutableSetOf<String>(); val visited = mutableSetOf<String>(); var before: String? = null; var beforeId: String? = null; var pages = 0
        do {
            kotlinx.coroutines.currentCoroutineContext().ensureActive(); require(++pages <= 100)
            if (channel == "social") {
                val page = Mango9SocialRepository(context).inbox(owner, before = before)
                require(page.items.size <= 50 && (page.next == null || page.items.isNotEmpty() && page.next == page.items.last().id))
                found.addAll(page.items.map { socialRow(it, owner) }.filter { it.unread }.map { it.id }); before = page.next
            } else {
                val query = mutableMapOf("limit" to "100", "unread" to "true")
                if (before != null && beforeId != null) { query["before"] = before!!; query["beforeId"] = beforeId!! }
                val page = Mango9LiveChatApi(context).request(owner, "list", query)
                val raw = page.getJSONArray("conversations"); require(raw.length() <= 100)
                val rows = (0 until raw.length()).map { Mango9LiveConversation.parse(raw.getJSONObject(it)) }
                found.addAll(
                    rows.filter {
                    it.unread
                }.map {
                    it.id
                }
                ); before = page.optString("next").takeIf {
                    it.isNotEmpty() && it != "null"
                }; beforeId = page.optString("nextId").takeIf { it.isNotEmpty() && it != "null" }
                require(before == null || rows.isNotEmpty() && beforeId == rows.last().id)
            }
            if (before != null) require(visited.add(before + ":" + beforeId.orEmpty()))
        } while (before != null)
        val api = Mango9MessagingApi(context)
        for (batch in found.toList().chunked(200)) {
            val body = JSONObject().put("conversationIds", org.json.JSONArray(batch)).put("hide", false)
            val receipt = if (channel == "social") {
                api.request(owner, Mango9MessageChannel.social, listOf("inbox-conversation-visibility"), body = body)
            } else {
                Mango9LiveChatApi(context).request(owner, "conversation-visibility", body = body)
            }
            require(receipt.getString("scope") == "user" && receipt.getString("channel") == (if (channel == "live") "livechat" else channel) && receipt.getString("state") == "succeeded")
            val hidden = receipt.getJSONArray("hiddenConversationIds"); val removed = (0 until hidden.length()).map {
                hidden.getString(it)
            }; require(batch.containsAll(removed)); found.removeAll(removed.toSet())
        }
        return found
    }
}

internal object Mango9InboxCountPolicy {
    fun ids(rows: List<Pair<String, Int>>, sms: Boolean = false): Set<String> = rows.filter {
        it.second > 0 && it.first.isNotEmpty()
    }.map { if (sms) it.first.trimStart('+') else it.first }.toSet()

    fun total(counts: Map<String, Int>, enabled: Set<String>) = enabled.sumOf { (counts[it] ?: 0).coerceAtLeast(0) }
}
