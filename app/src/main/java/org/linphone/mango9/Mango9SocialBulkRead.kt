// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class Mango9SocialReadState(val id: String, val readThrough: Int, val unread: Boolean, val markedUnread: Boolean)

/** One lightweight selection and one atomic command, including safe lost-response retries. */
internal class Mango9SocialBulkRead(
    private val post: suspend (String, JSONObject) -> JSONObject,
    private val current: () -> Boolean,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private data class Snapshot(val query: String, val id: String, val items: List<Mango9SocialReadState>)

    private var snapshot: Snapshot? = null
    private var pending: Pair<String, JSONObject>? = null
    private var generation = 0
    private val guid = Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")

    private fun states(raw: JSONArray): List<Mango9SocialReadState> {
        require(raw.length() <= 5000)
        return (0 until raw.length()).map { index ->
            val row = raw.getJSONObject(index)
            val sequence = row.get("readThrough")
            require(sequence is Number && sequence.toDouble() == sequence.toInt().toDouble() && sequence.toInt() >= 0)
            require(row.get("unread") is Boolean && row.get("markedUnread") is Boolean)
            Mango9SocialReadState(row.getString("id"), sequence.toInt(), row.getBoolean("unread"), row.getBoolean("markedUnread")).also {
                require(guid.matches(it.id))
            }
        }.also { require(it.map { row -> row.id }.distinct().size == it.size) }
    }

    suspend fun select(mode: String, query: Map<String, String>, ids: List<String>? = null): List<Mango9SocialReadState> {
        check(current()); require(mode in listOf("all", "read", "unread"))
        require(ids == null || ids.isNotEmpty() && ids.size <= 5000 && ids.all(guid::matches))
        val ticket = ++generation
        val key = query.toSortedMap().toString(); val id = newId()
        val body = JSONObject(query).put("mode", mode).put("requestId", id)
        ids?.let { body.put("ids", JSONArray(it.distinct())) }
        val result = post("inbox-bulk-selection", body)
        check(current() && ticket == generation)
        check(result.getString("type") == "inbox-selection" && result.getString("selectionId") == id)
        val rows = states(result.getJSONArray("items"))
        check(ids == null || rows.all { it.id in ids })
        check(mode == "all" || rows.all { it.unread == (mode == "unread") })
        snapshot = Snapshot(key, id, rows); pending = null
        return rows
    }

    suspend fun apply(ids: List<String>, action: String, query: Map<String, String>): List<Mango9SocialReadState> {
        check(current()); require(action in listOf("read", "unread"))
        require(ids.isNotEmpty() && ids.size <= 5000 && ids.all(guid::matches))
        val key = query.toSortedMap().toString()
        if (snapshot?.query != key || ids.any { id ->
            snapshot?.items?.none {
            it.id == id
        } != false
        }
        ) {
            select("all", query, ids)
        }
        val captured = requireNotNull(snapshot)
        val present = captured.items.map { it.id }.toSet()
        val targets = ids.distinct().filter { it in present }.sorted()
        if (targets.isEmpty()) return emptyList()
        val commandKey = listOf(captured.id, action, targets).toString()
        if (pending?.first != commandKey) {
            pending = commandKey to JSONObject().put("selectionId", captured.id)
            .put("ids", JSONArray(targets)).put("action", action).put("requestId", newId())
        }
        val command = requireNotNull(pending)
        val result = post("inbox-bulk-read", command.second)
        check(current() && snapshot === captured)
        check(result.getString("type") == "inbox-bulk-read" && result.getString("action") == action && result.getString("requestId") == command.second.getString("requestId"))
        val rows = states(result.getJSONArray("items")); val skipped = result.getJSONArray("skippedIds")
        val covered = rows.map { it.id } + (0 until skipped.length()).map { skipped.getString(it) }
        check(covered.size == targets.size && covered.toSet() == targets.toSet()) {
            "Read status was not fully confirmed. Retry the selection."
        }
        if (pending === command) pending = null
        snapshot = null
        return rows
    }
}
