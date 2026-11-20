// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9
import org.json.JSONObject

internal data class Mango9CrmActivityTarget(val screen: String, val id: String = "", val contactId: String = "", val key: String = "", val phone: String = "") {
    val title get() = mapOf("quote" to "Quote", "invoice" to "Invoice", "opportunity" to "Opportunity", "sms" to "SMS", "social" to "Social", "live" to "Live Chat")[screen] ?: "Activity"
    val path get() = when (screen){"quote", "invoice" -> "documents/${if (screen == "quote")"quotes" else "invoices"}/$id"; "activity" -> "contacts/$contactId/activity/item"; else -> null}

    companion object {
        fun parse(raw: JSONObject?): Mango9CrmActivityTarget? {
            raw ?: return null
            val s = raw.optString("screen"); val id = raw.optString(if (s == "sms")"conversationId" else "id"); val cid = raw.optString("contactId"); val key = raw.optString("key"); val phone = raw.optString("phone")
            val valid = when (s){
                "quote", "invoice", "opportunity" -> id.toIntOrNull()?.let{it > 0} == true
                "activity" -> cid.toIntOrNull()?.let{
                    it > 0
                } == true && key.matches(Regex("^(note|email|event|event-history|pbx|ai-call|ai-usage|ai-sms-usage):[A-Za-z0-9_-]{1,140}$"))
                "sms" -> id.matches(Regex("^[a-f0-9]{64}$")) && phone.matches(Regex("^\\+?[\\d ()-]{7,35}$"))
                "social", "live" -> id.matches(Regex("^[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}$"))
                else -> false
            }
            return if (valid)Mango9CrmActivityTarget(s, id, cid, key, phone) else null
        }
    }
}
