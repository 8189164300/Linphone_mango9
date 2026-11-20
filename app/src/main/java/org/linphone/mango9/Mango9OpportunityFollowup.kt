// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import org.json.JSONObject
import java.util.UUID

/** The server owns Calendar synchronization; retry only the original Opportunity update. */
internal class Mango9OpportunityFollowup(
    private val read: suspend () -> JSONObject,
    private val mutate: suspend (String, JSONObject) -> JSONObject,
    private val stored: () -> JSONObject?,
    private val persist: (JSONObject?) -> Unit
) {
    suspend fun save(row: JSONObject, metadata: Mango9OpportunityMetadata, title: String, start: String?): JSONObject {
        val id = row.getInt("id")
        var state = stored()
        if (state == null) {
            require(
                metadata.raw.optJSONObject("permissions")?.optBoolean("edit") == true &&
                Mango9OpportunityPresentation.text(row, "archived_at").isEmpty()
            ) { "You cannot edit this follow-up." }
            val draft = Mango9OpportunityPresentation.draft(row).apply { nextAction = title.trim(); nextDue = start ?: "" }
            val command = JSONObject().put("values", draft.values(metadata, row.getString("stage")))
                .put("revision", row.getString("revision")).put("requestId", UUID.randomUUID().toString())
            state = JSONObject().put("format", 2).put("id", id).put("command", command)
            persist(state)
        }
        require(state.optInt("format") == 2 && state.optInt("id") == id) {
            "Review the previous Calendar save before scheduling a server-managed follow-up."
        }
        if (!state.optBoolean("confirmed")) {
            val receipt = try { mutate("update", state.getJSONObject("command")) } catch (e: Exception) {
                if (Mango9OpportunityCommands.definite(e)) persist(null)
                throw e
            }
            require(receipt.optBoolean("saved") && receipt.optInt("id") == id && receipt.optString("action") == "update") {
                "The follow-up save could not be confirmed. Retry the same request."
            }
            state.put("confirmed", true); persist(state)
        }
        val saved = read()
        require(saved.optInt("id") == id) { "The saved Opportunity could not be loaded." }
        persist(null)
        return saved
    }

    companion object {
        fun calendarStatus(row: JSONObject): String {
            if (!row.optBoolean("onCalendar")) return "Not on Calendar"
            val info = row.optJSONObject("followUp") ?: return "On Calendar"
            if (info.optBoolean("mine")) return "On your Calendar"
            val name = Mango9OpportunityPresentation.text(info, "holderName")
            return if (name.isEmpty()) "On Calendar" else "On $name’s Calendar"
        }
    }
}
