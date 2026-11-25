// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class Mango9ChatBulkResult(val items: List<Mango9SocialReadState>, val hiddenIds: List<String>, val skippedIds: List<String>)

/** One server snapshot and one command. Retry the same command when its reply is lost. */
internal class Mango9ChatBulkActions(
    private val channel: String,
    private val post: suspend (String, JSONObject) -> JSONObject,
    private val current: () -> Boolean,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    init { require(channel in listOf("sms", "team", "social", "live")) }
    private data class Snapshot(val query: String, val id: String, val ids: Set<String>)
    private var snapshot: Snapshot? = null
    private var pending: Pair<String, JSONObject>? = null
    private var generation = 0
    private fun valid(id: String) = when (channel) {
        "sms" -> Regex("[0-9]{3,20}").matches(id)
        "team" -> Regex("[1-9][0-9]*").matches(id)
        else -> Regex("[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}").matches(id)
    }
    private fun strings(raw: JSONArray) = (0 until raw.length()).map { raw.getString(it) }
    private fun states(raw: JSONArray): List<Mango9SocialReadState> {
        require(raw.length() <= 5000)
        return (0 until raw.length()).map { index ->
            val row = raw.getJSONObject(index); val sequence = row.get("readThrough")
            require(sequence is Number && sequence.toDouble() == sequence.toInt().toDouble() && sequence.toInt() >= 0)
            require(row.get("unread") is Boolean && row.get("markedUnread") is Boolean)
            Mango9SocialReadState(row.getString("id"), sequence.toInt(), row.getBoolean("unread"), row.getBoolean("markedUnread")).also { require(valid(it.id)) }
        }.also { require(it.map { row -> row.id }.distinct().size == it.size) }
    }
    suspend fun select(mode: String, query: Map<String, String> = emptyMap(), ids: List<String>? = null): List<Mango9SocialReadState> {
        check(current()); require(mode in listOf("all", "read", "unread"))
        require(ids == null || ids.isNotEmpty() && ids.size <= 5000 && ids.all(::valid))
        val ticket = ++generation; val key = query.toSortedMap().toString(); val id = newId()
        val body = JSONObject(query).put("mode", mode).put("requestId", id)
        ids?.let { body.put("ids", JSONArray(it.distinct())) }
        val result = post("select", body)
        check(current() && ticket == generation)
        check(result.getString("type") == (if (channel == "social") "inbox-selection" else "chat-selection") && result.getString("selectionId") == id)
        check(channel == "social" || result.getString("channel") == channel)
        val rows = states(result.getJSONArray("items"))
        check(ids == null || rows.all { it.id in ids }); check(mode == "all" || rows.all { it.unread == (mode == "unread") })
        snapshot = Snapshot(key, id, rows.map { it.id }.toSet()); pending = null; return rows
    }
    suspend fun apply(ids: List<String>, action: String, query: Map<String, String> = emptyMap()): Mango9ChatBulkResult {
        check(current()); require(action in listOf("read", "unread", "delete"))
        require(ids.isNotEmpty() && ids.size <= 5000 && ids.distinct().size == ids.size && ids.all(::valid))
        val key = query.toSortedMap().toString()
        if (snapshot?.query != key || snapshot?.ids?.containsAll(ids) != true) select("all", query, ids)
        val captured = requireNotNull(snapshot); val targets = ids.filter { it in captured.ids }.sorted()
        if (targets.isEmpty()) return Mango9ChatBulkResult(emptyList(), emptyList(), ids)
        val commandKey = listOf(captured.id, action, targets).toString()
        if (pending?.first != commandKey) pending = commandKey to JSONObject().put("selectionId", captured.id).put("ids", JSONArray(targets)).put("action", action).put("requestId", newId())
        val command = requireNotNull(pending); val result = post("apply", command.second)
        check(current() && snapshot === captured)
        val legacy = channel == "social" && action != "delete"
        check(result.getString("type") == (if (legacy) "inbox-bulk-read" else "chat-bulk-result") && result.getString("action") == action && result.getString("requestId") == command.second.getString("requestId"))
        check(legacy || result.getString("channel") == channel)
        val rows = states(result.getJSONArray("items")); val hidden = result.optJSONArray("hiddenIds")?.let(::strings) ?: emptyList()
        val skipped = strings(result.getJSONArray("skippedIds")); val covered = rows.map { it.id } + hidden + skipped
        check(action == "delete" || hidden.isEmpty())
        check(covered.size == targets.size && covered.toSet() == targets.toSet()) { "Some chats were not confirmed. Retry the selection." }
        pending = null; snapshot = null; return Mango9ChatBulkResult(rows, hidden, skipped)
    }
}
