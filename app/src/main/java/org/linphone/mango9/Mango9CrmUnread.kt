// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject

internal data class Mango9CrmNewRecords(var initialized: Boolean = false, var known: Set<Int> = emptySet(), var unread: Set<Int> = emptySet()) {
    fun reconcile(ids: Set<Int>) {
        unread = (unread + if (initialized) ids - known else emptySet()).intersect(ids); known = ids; initialized = true
    }

    fun read(id: Int) { known = known + id; unread = unread - id }
}

/** No server read receipt exists for CRM records. Persist only IDs for this account/device. */
internal object Mango9CrmUnread {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val counts = MutableStateFlow<Map<Mango9RecordKind, Int>>(emptyMap())
    val revision = MutableStateFlow(0L)
    private val records = mutableMapOf<Mango9RecordKind, Mango9CrmNewRecords>()
    val opportunities = MutableStateFlow<Map<Int, Long>>(emptyMap())
    private var opportunityState = Mango9CrmNewRecords()
    private var account = ""
    private var running = false
    private var again = false
    private var polling: Job? = null
    private var foreground = false
    private val context get() = org.linphone.LinphoneApplication.coreContext.context
    private val sessions get() = Mango9SessionStore(context)
    private val preferences get() = context.getSharedPreferences("mango9_crm_new", android.content.Context.MODE_PRIVATE)
    val total get() = counts.value.values.sum() + opportunities.value.size

    fun isNew(kind: Mango9RecordKind, id: Int) = records[kind]?.unread?.contains(id) == true

    private fun bind(): Mango9SessionLease? {
        val lease = sessions.captureSession(); val next = lease?.session?.let { session ->
            java.security.MessageDigest.getInstance("SHA-256").digest(listOf(session.crmApiBaseUrl.trimEnd('/'), session.userId, session.sipIdentity.orEmpty()).joinToString("|").toByteArray()).joinToString("") {
            "%02x".format(it)
        }
        }.orEmpty()
        if (next != account) {
            account = next; records.clear(); opportunities.value = emptyMap(); opportunityState = Mango9CrmNewRecords()
            if (next.isNotEmpty()) {
                runCatching {
                val raw = JSONObject(preferences.getString(next, "{}")!!)
                raw.optJSONObject("opportunities")?.let { v ->
                   fun ids(k: String): Set<Int> {
                       val a = v.optJSONArray(k) ?: JSONArray(); return (0 until a.length()).map { a.getInt(it) }.filter { it > 0 }.toSet()
                   }
                   opportunityState = Mango9CrmNewRecords(v.optBoolean("initialized"), ids("known"), ids("unread"))
               }
               for (kind in Mango9RecordKind.values()) raw.optJSONObject(kind.path)?.let { value ->
                    fun ids(field: String): Set<Int> {
                        val a = value.optJSONArray(field) ?: JSONArray(); return (0 until a.length()).map {
                            a.getInt(it)
                        }.filter { it > 0 }.toSet()
                    }
                    records[kind] = Mango9CrmNewRecords(value.optBoolean("initialized"), ids("known"), ids("unread"))
                }
            }
            }
            publish()
        }
        return lease
    }

    private fun publish() {
        opportunities.value = opportunityState.unread.associateWith {
            1L
        }; counts.value = records.mapValues { it.value.unread.size }; revision.value++
    }

    private fun save() {
        if (account.isEmpty()) return
        val raw = JSONObject().put("opportunities", JSONObject().put("initialized", opportunityState.initialized).put("known", JSONArray(opportunityState.known.toList())).put("unread", JSONArray(opportunityState.unread.toList())))
        records.forEach { (kind, state) -> raw.put(kind.path, JSONObject().put("initialized", state.initialized).put("known", JSONArray(state.known.toList())).put("unread", JSONArray(state.unread.toList()))) }
        preferences.edit().putString(account, raw.toString()).apply(); publish()
    }

    fun read(kind: Mango9RecordKind, id: Int, lease: Mango9SessionLease) {
        if (sessions.currentSession(lease) == null) return
        bind(); records.getOrPut(kind) { Mango9CrmNewRecords() }.read(id); save()
    }

    init { scope.launch { Mango9SessionStore.contextChanges.collect { bind(); if (foreground) refresh() } } }

    fun visible(active: Boolean) {
        foreground = active
        polling?.cancel(); polling = null
        if (active) polling = scope.launch { while (isActive) { refresh(); delay(60_000) } }
    }

    fun converted(id: Int, lease: Mango9SessionLease) {
        if (sessions.currentSession(lease) == null) return
        bind(); records[Mango9RecordKind.Lead]?.let { it.read(id) }
        records.getOrPut(Mango9RecordKind.Client) { Mango9CrmNewRecords() }.read(id); save()
    }

    fun refreshSoon() { scope.launch { refresh() } }

    suspend fun refresh() {
        val lease = bind() ?: return
        if (running) { again = true; return }
        running = true
        try {
            do {
                again = false; val selected = account
                refreshOpportunities(lease)
                for (kind in Mango9RecordKind.values()) {
                    try {
                        val ids = snapshot(lease, kind)
                        if (sessions.currentSession(lease) == null || selected != account) return
                        records.getOrPut(kind) { Mango9CrmNewRecords() }.reconcile(ids); save()
                    } catch (e: Exception) { if (e is CancellationException) throw e }
                }
            } while (again && sessions.currentSession(lease) != null)
        } finally { running = false }
    }

    suspend fun refreshOpportunities(lease: Mango9SessionLease) {
       if (sessions.currentSession(lease) == null) return
       try {
           val ids = mutableSetOf<Int>(); var page = 1; var total: Int? = null
           while (page <= 200) {
               val r = Mango9Opportunities(context).request(lease, query = mapOf("page" to page.toString(), "limit" to "50", "sort" to "created_at"))
               val p = r.getJSONObject("pagination"); val rows = r.getJSONArray("items").opportunityObjects(); val expected = p.getInt("total")
               require(p.getInt("page") == page && p.getInt("limit") == 50 && expected >= 0 && (total == null || total == expected) && rows.size == minOf(50, maxOf(0, expected - (page - 1) * 50)) && p.getBoolean("hasMore") == (page * 50 < expected))
               total = expected; for (row in rows) { val id = row.getInt("id"); require(id > 0 && ids.add(id)) }
               if (!p.getBoolean("hasMore")) break; page++
           }
           require(ids.size == total)
           if (sessions.currentSession(lease) == null) return
           opportunityState.reconcile(ids); save()
       } catch (e: Exception) { if (e is CancellationException) throw e }
   }

   suspend fun readOpportunity(id: Int, lease: Mango9SessionLease) {
       if (sessions.currentSession(lease) == null) return
       bind(); opportunityState.read(id); save()
   }

    private suspend fun snapshot(lease: Mango9SessionLease, kind: Mango9RecordKind): Set<Int> {
        val repo = Mango9CrmRepository(context); val ids = mutableSetOf<Int>(); var page = 1; var pages: Int; var total: Int? = null
        do {
            currentCoroutineContext().ensureActive(); check(page <= 200 && repo.isCurrent(lease))
            val result = repo.records(lease, kind, "", "", "", "", page)
            val p = result.pagination; val rows = result.records.map { it.id }
            require(
                p.page == page && p.pages <= 200 && p.total >= 0 && (total == null || total == p.total) && rows.all {
                it > 0
            } && rows.toSet().size == rows.size
            )
            total = p.total; pages = p.pages; ids.addAll(rows); page++
        } while (page <= pages)
        require(ids.size == total); return ids
    }
}
