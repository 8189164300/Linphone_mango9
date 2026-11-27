// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import org.json.JSONObject
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale

internal object Mango9OpportunityPresentation {
    fun text(row: JSONObject, key: String) = if (row.isNull(key)) "" else row.optString(key)

    fun contact(row: JSONObject): JSONObject? = if (row.optBoolean("canOpenContact") && row.optString("contact_state") == "available") row.optJSONObject("contact") else null

    fun can(row: JSONObject, meta: Mango9OpportunityMetadata, action: String): Boolean {
        if (action !in setOf("stage", "update", "share", "archive", "restore", "note", "activity")) return false
        val permission = when (action) { "share" -> "share"; "archive", "restore" -> "remove"; else -> "edit" }
        return meta.permissions.optBoolean(permission) && text(row, "revision").matches(Regex("[0-9]{1,18}")) &&
            (text(row, "archived_at").isEmpty() || action == "restore") &&
            (action !in setOf("share", "archive", "restore") || row.optBoolean("canManage"))
    }

    fun weighted(value: String, probability: Int): String? = value.toBigDecimalOrNull()?.takeIf {
        it.signum() >= 0 && probability in 0..100
    }?.multiply(BigDecimal(probability))?.movePointLeft(2)?.toPlainString()

    fun money(value: String?, currency: String): String {
        val amount = value?.toBigDecimalOrNull() ?: return "Not entered"
        return runCatching {
            NumberFormat.getCurrencyInstance(Locale.US).apply {
                this.currency = Currency.getInstance(currency); minimumFractionDigits = 0; maximumFractionDigits = 2
            }.format(amount)
        }.getOrDefault("$value $currency")
    }

    fun iso(value: String) = value.replace(' ', 'T').replace(Regex("([+-][0-9]{2})$"), "$1:00")

    fun date(value: String, time: Boolean = false): String = if (value.isBlank()) {
        "Not set"
    } else {
        runCatching {
        if (value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) {
            LocalDate.parse(value).format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US))
        } else {
            (if (value.matches(Regex("[0-9]{9,12}(\\.[0-9]+)?"))) java.time.Instant.ofEpochMilli((value.toDouble() * 1000).toLong()).atZone(ZoneId.systemDefault()) else OffsetDateTime.parse(iso(value)).atZoneSameInstant(ZoneId.systemDefault())).format(DateTimeFormatter.ofPattern(if (time) "MMM d · h:mm a" else "MMM d, yyyy", Locale.US))
        }
    }.getOrDefault(value)
    }

    fun draft(row: JSONObject) = Mango9OpportunityDraft(
        title = text(row, "title"), stage = text(row, "stage"), value = text(row, "value"), currency = text(row, "currency"), closeDate = text(row, "close_date"),
        ownerId = row.optInt("owner_id"), probability = row.optInt("probability"), manualProbability = row.optString("probability_mode") == "manual", source = text(row, "source"), description = text(row, "description"),
        nextAction = text(row, "next_action"), nextDue = iso(text(row, "next_due_at")), actualValue = text(row, "actual_value"),
            actualClose = text(row, "actual_close_date").ifBlank {
            LocalDate.now().toString()
        },
            lossReason = text(row, "loss_reason")
    )
}
