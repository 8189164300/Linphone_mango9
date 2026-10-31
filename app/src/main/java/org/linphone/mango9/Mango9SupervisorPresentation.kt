// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

/** Display-only data: never a persisted authorization to apply an action. */
internal object Mango9SupervisorPresentation {
    fun uuid(value: String) = runCatching { UUID.fromString(value).toString().equals(value, true) }.getOrDefault(false)

    fun objects(rows: JSONArray?) = (0 until (rows?.length() ?: 0)).mapNotNull { rows?.optJSONObject(it) }

    fun actionIds(messages: List<JSONObject>) = messages.filter { it.optString("role") == "assistant" }.flatMap { row ->
        val ids = row.optJSONObject("metadata")?.optJSONArray("actionIds")
        (0 until (ids?.length() ?: 0)).mapNotNull { ids?.optString(it)?.takeIf(::uuid) }
    }.distinct()

    fun date(raw: String, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        val stamp = runCatching { Instant.parse(raw).atZone(zone) }.getOrNull() ?: return ""
        val days = ChronoUnit.DAYS.between(stamp.toLocalDate(), now.atZone(zone).toLocalDate())
        val time = stamp.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
        return when {
            days == 0L -> "Today, $time"
            days == 1L -> "Yesterday, $time"
            days in 2..6 -> stamp.format(DateTimeFormatter.ofPattern("EEE, h:mm a", Locale.US))
            else -> stamp.format(DateTimeFormatter.ofPattern("MM/dd/yyyy, h:mm a", Locale.US))
        }
    }

    fun historyMatches(row: JSONObject, query: String, channel: String) =
        row.optString("title").contains(query.trim(), true) &&
            (channel == "all" || row.optBoolean("hasVoice") == (channel == "voice"))

    fun creation(detail: JSONObject, origin: String): Creation? {
        if (detail.optString("state") != "SUCCEEDED") return null
        val proof = detail.optJSONObject("recap")?.optJSONObject("creation") ?: return null
        if (proof.optInt("version") != 1) return null
        val status = proof.optString("statusText").takeIf { it.isNotBlank() && it.length <= 100 } ?: return null
        val raw = objects(proof.optJSONArray("links"))
        if (raw.isEmpty() || raw.size > 4) return null
        val links = raw.map { link ->
            val label = link.optString("label").takeIf { it.isNotBlank() && it.length <= 100 } ?: return null
            val href = link.optString("href").takeIf { it.startsWith("/app/") } ?: return null
            val url = Mango9SupervisorWire.webUrl(href, origin) ?: return null
            if (URI(url).host != URI(origin).host || URI(url).port != URI(origin).port) return null
            label to url
        }
        val recipients = objects(proof.optJSONArray("recipients")).take(30).mapNotNull { row ->
            val email = row.optString("email").takeIf {
                it.length <= 254 && it.contains('@') && !it.contains('\n')
            } ?: return@mapNotNull null
            listOf(row.optString("name"), email).filter { it.isNotBlank() }.joinToString(" · ")
        }
        return Creation(status, links, recipients)
    }

    data class Creation(val status: String, val links: List<Pair<String, String>>, val recipients: List<String>)

    fun savedConfiguration(detail: JSONObject): JSONObject? {
        val id = detail.optString("id").takeIf(::uuid) ?: return null
        val recap = detail.optJSONObject("recap") ?: return null
        if (recap.optInt("version") != 1) return null
        val fields = recap.optJSONArray("fields") ?: return null
        if (fields.length() > 64 || objects(fields).size != fields.length()) return null
        val display = JSONObject().put("version", 1)
        for (key in listOf("title", "effect", "question", "statusText")) {
            (recap.opt(key) as? String)?.let { display.put(key, it.take(1000)) }
        }
        val safeFields = JSONArray()
        for (field in objects(fields)) {
            val label = field.opt("label") as? String ?: return null
            val value = field.opt("value") as? String ?: return null
            safeFields.put(JSONObject().put("label", label.take(200)).put("value", value.take(2000)).put("incomplete", field.optBoolean("incomplete")))
        }
        display.put("fields", safeFields)
        recap.optJSONObject("creation")?.let { display.put("creation", savedProof(it)) }
        return JSONObject().put("id", id).put("state", detail.optString("state").take(40)).put("summaryOnly", true).put("recap", display)
    }

    private fun savedProof(proof: JSONObject): JSONObject {
        val links = JSONArray(
            objects(proof.optJSONArray("links")).take(4).map {
            JSONObject().put("label", it.optString("label").take(100)).put("href", it.optString("href").take(2048))
        }
        )
        val recipients = JSONArray(
            objects(proof.optJSONArray("recipients")).take(30).map {
            JSONObject().put("name", it.optString("name").take(200)).put("email", it.optString("email").take(254))
        }
        )
        return JSONObject().put("version", proof.optInt("version")).put("statusText", proof.optString("statusText").take(100)).put("links", links).put("recipients", recipients)
    }

    fun savedMessage(row: JSONObject): JSONObject? {
        if (row.optString("role") !in listOf("assistant", "user") || row.optString("id").isBlank()) return null
        val metadata = row.optJSONObject("metadata") ?: JSONObject()
        val safe = JSONObject().put("createdAt", metadata.optString("createdAt", row.optString("createdAt")))
            .put("actionIds", JSONArray(actionIds(listOf(row))))
        if (row.optString("role") == "assistant") {
            safe.put("creations", JSONArray(objects(metadata.optJSONArray("creations")).take(16).map(::savedProof)))
        }
        return JSONObject().put("id", row.optString("id").take(200)).put("role", row.optString("role"))
            .put("text", Mango9SupervisorWire.messageText(row).take(32000)).put("metadata", safe)
    }
}
