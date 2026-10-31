// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Display snapshots only. Never restores an authorization, digest or pending command. */
internal object Mango9SupervisorCache {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_AGE = 30L * 24 * 60 * 60 * 1000

    fun scope(session: Mango9Session): String = MessageDigest.getInstance("SHA-256").digest(
        JSONArray(
            listOf(
                session.crmApiBaseUrl, session.crmId, session.userId, session.parentClientId,
            session.role, session.sipIdentity.orEmpty(), session.loginEpoch ?: session.refreshToken
            )
        )
            .toString().toByteArray(Charsets.UTF_8)
    ).joinToString("") { "%02x".format(it) }

    fun bounded(rows: List<JSONObject>, now: Long = System.currentTimeMillis()): List<JSONObject> {
        val seen = mutableSetOf<String>()
        return rows.sortedByDescending { it.optLong("savedAt") }.mapNotNull { row ->
            val id = row.optString("id"); val savedAt = row.optLong("savedAt")
            val messages = row.optJSONArray("messages") ?: return@mapNotNull null
            val configurations = row.optJSONArray("configurations") ?: return@mapNotNull null
            val summary = row.optJSONObject("conversation")
            if (!Mango9SupervisorPresentation.uuid(id) || savedAt <= 0 || savedAt > now + 60_000 || now - savedAt >= MAX_AGE ||
                messages.length() > 200 || configurations.length() > 200 || (summary != null && summary.optString("id") != id) || !seen.add(id)
            ) {
                return@mapNotNull null
            }
            val safeMessages = Mango9SupervisorPresentation.objects(messages).mapNotNull(Mango9SupervisorPresentation::savedMessage)
            val safeConfigurations = Mango9SupervisorPresentation.objects(configurations).mapNotNull(Mango9SupervisorPresentation::savedConfiguration)
            if (safeMessages.size != messages.length() || safeConfigurations.size != configurations.length() ||
                safeMessages.map { it.optString("id") }.distinct().size != safeMessages.size ||
                safeConfigurations.map { it.optString("id") }.distinct().size != safeConfigurations.size
            ) {
                return@mapNotNull null
            }
            JSONObject().put("id", id).put("savedAt", savedAt).put("messages", JSONArray(safeMessages))
                .put("configurations", JSONArray(safeConfigurations)).apply {
                    if (summary != null) {
                        put(
                            "conversation",
                            JSONObject().put("id", id).put("title", summary.optString("title").take(300))
                        .put("updatedAt", summary.optString("updatedAt")).put("hasVoice", summary.optBoolean("hasVoice")).put("messageCount", summary.optInt("messageCount"))
                        )
                    }
                }
        }.take(20)
    }

    fun encode(rows: List<JSONObject>, now: Long = System.currentTimeMillis()): String {
        val safe = bounded(rows, now).toMutableList()

        fun json() = JSONObject().put("version", 2).put("chats", JSONArray(safe)).toString()
        var encoded = json()
        while (encoded.toByteArray(Charsets.UTF_8).size > MAX_BYTES && safe.isNotEmpty()) {
            safe.removeAt(safe.lastIndex); encoded = json()
        }
        return encoded
    }

    fun decode(raw: String?, now: Long = System.currentTimeMillis()): List<JSONObject> = runCatching {
        if (raw == null || raw.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return emptyList()
        val envelope = JSONObject(raw)
        if (envelope.optInt("version") != 2) return emptyList()
        bounded(Mango9SupervisorPresentation.objects(envelope.getJSONArray("chats")), now)
    }.getOrDefault(emptyList())
}
