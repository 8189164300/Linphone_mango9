// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import java.time.Instant
import java.time.OffsetDateTime
import org.json.JSONObject

enum class Mango9ActivityKind {
    Appointments,
    Events,
    Tasks;

    companion object {
        fun of(activity: String): Mango9ActivityKind = when (activity.trim().lowercase(java.util.Locale.ROOT)) {
            "", "appointment" -> Appointments
            "task", "follow up call", "follow_up" -> Tasks
            else -> Events
        }
    }
}

data class Mango9ManagedTask(
    val id: Int, val eventId: Int?, val title: String, val description: String, val status: String,
    val type: String, val priority: String, val execution: String, val dueAt: String?, val timezone: String,
    val assignees: List<String>, val contact: String?, val agent: String?, val history: List<String>,
) {
    val dueDate: Instant? get() = dueAt?.let(::date)

    companion object {
        fun date(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull() ?: runCatching {
            val normalized = value.replace(' ', 'T').let { if (Regex("[+-][0-9]{2}$").containsMatchIn(it)) "$it:00" else it }
            OffsetDateTime.parse(normalized).toInstant()
        }.getOrNull()

        fun parse(json: JSONObject) = Mango9ManagedTask(
            json.getInt("id").also { require(it > 0) },
            if (json.isNull("event_id")) null else json.getInt("event_id"), json.getString("title"), json.optString("description"),
            json.getString("status"), json.getString("type"), json.getString("priority"), json.getString("execution"),
            json.stringOrNull("due_at"), json.getString("timezone"),
                json.getJSONArray("assignees").objects().map {
                it.getString("name")
            },
            json.stringOrNull("contact_name"), json.stringOrNull("ai_agent_name"),
                json.optJSONArray("history")?.objects()?.map {
                listOfNotNull(it.stringOrNull("action"), it.stringOrNull("actor_name"), it.stringOrNull("created_at")).joinToString(" · ")
            }.orEmpty()
        )
    }
}

data class Mango9TaskPage(val items: List<Mango9ManagedTask>, val page: Int, val hasMore: Boolean)

suspend fun Mango9CalendarRepository.tasks(lease: Mango9SessionLease, page: Int, query: String): Mango9TaskPage =
    send(lease, "", query = mapOf("q" to query.trim().take(100), "page" to page.toString(), "limit" to "50"), tasksScope = true) { json ->
        val paging = json.getJSONObject("pagination")
        val items = json.getJSONArray("items").objects().map(Mango9ManagedTask::parse)
        val more = paging.getBoolean(if (paging.has("hasMore")) "hasMore" else "has_more")
        require(paging.getInt("page") == page && items.size <= 50 && (!more || items.isNotEmpty()))
        Mango9TaskPage(items, page, more)
    }

suspend fun Mango9CalendarRepository.task(lease: Mango9SessionLease, id: Int): Mango9ManagedTask =
    send(lease, id.toString(), tasksScope = true, parse = Mango9ManagedTask::parse).also { require(it.id == id) }
