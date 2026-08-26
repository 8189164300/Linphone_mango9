/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.mango9

import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

data class Mango9PushCallerIdentity(
    val callId: String,
    val handle: String,
    val displayName: String,
) {
    companion object {
        fun parse(payload: String): Mango9PushCallerIdentity? {
            val root = runCatching { JSONObject(payload) }.getOrNull() ?: return null
            val aps = root.optJSONObject("aps") ?: JSONObject()
            val alert = aps.optJSONObject("alert") ?: JSONObject()
            val dictionaries = listOf(root, aps, alert)
            val callId = firstString(dictionaries, listOf("call-id", "call_id", "callId"))
                ?: return null
            val locationArguments = stringValues(aps.optJSONArray("loc-args"))
                .ifEmpty { stringValues(alert.optJSONArray("loc-args")) }
            val fromValue = firstString(
                dictionaries,
                listOf("from-uri", "from_uri", "from"),
            ) ?: locationArguments.firstOrNull()
            val pushedDisplayName = firstString(
                dictionaries,
                listOf("display-name", "display_name", "caller-name", "caller_name"),
            )
            val identityValue = Mango9CallerIdentity.normalizedLabel(fromValue)
                ?: Mango9CallerIdentity.normalizedLabel(pushedDisplayName)
                ?: return null
            val phoneNumber = Mango9CallerIdentity.externalPhoneNumber(identityValue)
            return Mango9PushCallerIdentity(
                callId = callId,
                handle = phoneNumber ?: identityValue,
                displayName = Mango9CallerIdentity.normalizedLabel(pushedDisplayName)
                    ?: phoneNumber?.let(Mango9CallerIdentity::formattedPhoneNumber)
                    ?: identityValue,
            )
        }

        private fun firstString(dictionaries: List<JSONObject>, keys: List<String>): String? {
            for (dictionary in dictionaries) {
                for (key in keys) {
                    if (!dictionary.has(key) || dictionary.isNull(key)) continue
                    dictionary.optString(key).trim().takeIf(String::isNotEmpty)?.let {
                        return it
                    }
                }
            }
            return null
        }

        private fun stringValues(array: JSONArray?): List<String> {
            if (array == null) return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                array.optString(index).trim().takeIf(String::isNotEmpty)
            }
        }
    }
}

object Mango9CallerIdentity {
    private val unusableLabels = setOf(
        "anonymous",
        "anonymous@anonymous.invalid",
        "anonymous caller",
        "call from mango9",
        "calling",
        "mango 9",
        "mango9",
        "no caller id",
        "private",
        "restricted",
        "unavailable",
        "unknown",
    )
    private const val PHONE_CHARACTERS = "+0123456789-(). "

    fun normalizedLabel(rawValue: String?): String? {
        val value = rawValue?.trim().orEmpty()
        if (value.isEmpty()) return null
        val lowercase = value.lowercase()
        val uri = lowercase.removePrefix("sip:")
        val uriUser = uri.substringBefore('@')
        return value.takeUnless {
            lowercase in unusableLabels || uriUser == "anonymous" || uriUser == "anonimous"
        }
    }

    fun externalPhoneNumber(rawValue: String?): String? {
        val value = addressUser(rawValue) ?: return null
        if (!value.all(PHONE_CHARACTERS::contains)) return null
        val digits = value.filter(Char::isDigit)
        if (digits.length !in 7..15) return null
        return when {
            digits.length == 10 -> "+1$digits"
            digits.length == 11 && digits.startsWith('1') -> "+$digits"
            value.startsWith('+') -> "+$digits"
            else -> digits
        }
    }

    fun formattedPhoneNumber(number: String): String {
        val digits = number.filter(Char::isDigit).let {
            if (it.length == 11 && it.startsWith('1')) it.drop(1) else it
        }
        return if (digits.length == 10) {
            "${digits.take(3)}-${digits.substring(3, 6)}-${digits.takeLast(4)}"
        } else {
            number
        }
    }

    private fun addressUser(rawValue: String?): String? {
        var value = normalizedLabel(rawValue) ?: return null
        value = removeSipPrefix(value)
        val bracket = value.lastIndexOf('<')
        if (bracket >= 0) {
            value = removeSipPrefix(value.substring(bracket + 1))
        }
        return normalizedLabel(value.substringBefore('@').substringBefore(';'))
    }

    private fun removeSipPrefix(value: String): String =
        if (value.startsWith("sip:", ignoreCase = true)) value.drop(4) else value
}

object Mango9PushCallerIdentityCache {
    private data class Cached(val identity: Mango9PushCallerIdentity, val receivedAt: Long)

    private val identities = ConcurrentHashMap<String, Cached>()

    fun cache(payload: String, nowMillis: Long = System.currentTimeMillis()): Mango9PushCallerIdentity? {
        val identity = Mango9PushCallerIdentity.parse(payload) ?: return null
        prune(nowMillis)
        identities[identity.callId] = Cached(identity, nowMillis)
        return identity
    }

    fun get(callId: String?, nowMillis: Long = System.currentTimeMillis()): Mango9PushCallerIdentity? {
        if (callId.isNullOrBlank()) return null
        prune(nowMillis)
        return identities[callId]?.identity
    }

    fun remove(callId: String?) {
        if (!callId.isNullOrBlank()) identities.remove(callId)
    }

    internal fun clear() = identities.clear()

    private fun prune(nowMillis: Long) {
        identities.entries.removeIf { nowMillis - it.value.receivedAt >= MAX_AGE_MS }
    }

    private const val MAX_AGE_MS = 120_000L
}
