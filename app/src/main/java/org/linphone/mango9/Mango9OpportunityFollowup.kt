// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import org.json.JSONObject
import java.util.UUID

/** Durable receipt between Calendar and Opportunity writes. A lost create response is never replayed. */
internal class Mango9OpportunityFollowup(
    private val read: suspend () -> JSONObject,
    private val metadata: suspend () -> Mango9OpportunityMetadata,
    private val mutate: suspend (String, JSONObject) -> JSONObject,
    private val event: suspend (Int) -> Mango9Appointment,
    private val stored: () -> JSONObject?,
    private val persist: (JSONObject?) -> Unit
) {
    suspend fun save(
        contactId: Int, existingId: Int?, title: String, start: String, end: String,
                     createOrUpdate: suspend () -> Mango9Appointment
    ) {
        var state = stored()
        if (state == null) {
            state = JSONObject().put("contactId", contactId).put("title", title.trim()).put("start", start).put("end", end)
            if (existingId != null) state.put("existingId", existingId)
            persist(state)
            val saved = try { createOrUpdate() } catch (e: Exception) {
                // Only a definite rejection permits another write. Transport failures retain the fence.
                if (e is Mango9CalendarFailure && e.status in setOf(400, 403, 404, 409, 413, 415, 422, 428)) persist(null)
                throw e
            }
            require(matches(saved, state)) { "Calendar did not confirm this follow-up. Check Calendar before trying again." }
            state.put("eventId", saved.id); persist(state)
        }
        require(state.getInt("contactId") == contactId)
        var eventId = state.optInt("eventId")
        if (eventId == 0) {
            // A reschedule can be verified by ID. A lost creation can only be recovered after the user links its event.
            val row = read()
            val linkedId = row.optJSONObject("activity")?.optInt("event_id") ?: 0
            eventId = state.optInt("existingId").takeIf { it > 0 } ?: linkedId
            check(eventId > 0) {
                "The previous Calendar response was interrupted. Check Calendar and link the saved appointment from Follow-up; this request will not create a second event."
            }
            require(matches(event(eventId), state)) {
                "Calendar has not confirmed the previous schedule. Check its date and time before retrying."
            }
            state.put("eventId", eventId); persist(state)
        }
        val saved = event(eventId)
        require(matches(saved, state)) { "The saved appointment changed. Review it in Calendar before retrying." }

        suspend fun command(action: String, fields: JSONObject, row: JSONObject) {
            val pending = state!!.optJSONObject("command") ?: JSONObject(fields.toString()).put("action", action)
                .put("revision", row.getString("revision")).put("requestId", UUID.randomUUID().toString()).also {
                    state.put("command", it); persist(state)
                }
            val kind = pending.getString("action")
            val body = JSONObject(pending.toString()).apply { remove("action") }
            val receipt = try { mutate(kind, body) } catch (e: Exception) {
                if (Mango9OpportunityCommands.definite(e)) { state.remove("command"); persist(state) }
                throw e
            }
            require(receipt.optBoolean("saved") && receipt.getInt("id") == row.getInt("id") && receipt.getString("action") == kind)
            state.remove("command"); persist(state)
        }
        var row = read()

        fun validate() {
            require(row.optInt("contact_id") == contactId && Mango9OpportunityPresentation.contact(row) != null && Mango9OpportunityPresentation.text(row, "archived_at").isEmpty()) {
                "The Opportunity changed. Review the saved Calendar event before continuing."
            }
        }
        validate()
        if (state.has("command")) {
            command(state.getJSONObject("command").getString("action"), JSONObject(), row); row = read(); validate(); validate()
        }
        if (row.optString("activity_key") != "event-$eventId") {
            command("activity", JSONObject().put("activityKey", "event-$eventId"), row); row = read(); validate()
        }
        // Preserve every other Opportunity field, including retired but existing stages.
        if (Mango9OpportunityPresentation.text(row, "next_action") != saved.title ||
            runCatching {
                java.time.Instant.parse(Mango9OpportunityPresentation.iso(Mango9OpportunityPresentation.text(row, "next_due_at")))
            }.getOrNull() != saved.startAt
        ) {
            val draft = Mango9OpportunityPresentation.draft(row).apply {
                nextAction = saved.title; nextDue = saved.startAt.toString()
            }
            command("update", JSONObject().put("values", draft.values(metadata(), row.getString("stage"))), row)
        }
        persist(null)
    }

    private fun matches(value: Mango9Appointment, state: JSONObject) = value.contact?.id == state.getInt("contactId") &&
        value.title == state.getString("title") && value.startAt.toString() == state.getString("start") && value.endAt.toString() == state.getString("end")
}
