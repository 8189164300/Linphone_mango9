/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.mango9

import android.content.Context
import androidx.core.content.edit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

sealed interface Mango9MessagePushTarget {
    data class Chat(val userId: Int, val name: String, val roomId: String?) : Mango9MessagePushTarget

    data class Sms(val phone: String, val name: String) : Mango9MessagePushTarget

    data class Lead(val id: Int) : Mango9MessagePushTarget
}

data class Mango9MessagePush(
    val target: Mango9MessagePushTarget,
    val sipIdentity: String?,
    val crmId: String?,
) {
    fun toJson(): String = JSONObject().apply {
        put("sip_identity", sipIdentity ?: JSONObject.NULL)
        put("crm_id", crmId ?: JSONObject.NULL)
        when (val value = target) {
            is Mango9MessagePushTarget.Chat -> {
                put("type", "chat")
                put("user_id", value.userId)
                put("name", value.name)
                put("room_id", value.roomId ?: JSONObject.NULL)
            }
            is Mango9MessagePushTarget.Sms -> {
                put("type", "sms")
                put("phone", value.phone)
                put("name", value.name)
            }
            is Mango9MessagePushTarget.Lead -> {
                put("type", "lead")
                put("lead_id", value.id)
            }
        }
    }.toString()

    companion object {
        fun parse(data: Map<String, String>): Mango9MessagePush? {
            val nested = data["mango9"]?.let { runCatching { JSONObject(it) }.getOrNull() }

            fun value(vararg keys: String): String? {
                keys.forEach { key ->
                    nested?.takeIf { it.has(key) && !it.isNull(key) }
                        ?.optString(key)
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?.let { return it }
                    data[key]?.trim()?.takeIf(String::isNotEmpty)?.let { return it }
                }
                return null
            }

            val event = value("event", "mango9_event") ?: return null
            val target = when (event) {
                "chat.message" -> {
                    val userId = value("sender_user_id", "senderUserId")?.toIntOrNull() ?: 0
                    val roomId = value("room_id", "roomId")
                    if (userId <= 0 && roomId == null) return null
                    Mango9MessagePushTarget.Chat(
                        userId = userId,
                        name = value("name") ?: "Team Chat",
                        roomId = roomId,
                    )
                }
                "sms.received" -> {
                    val digits = value("phone")?.filter(Char::isDigit).orEmpty()
                    if (digits.length < 10) return null
                    val phone = if (digits.length == 10) "1$digits" else digits
                    Mango9MessagePushTarget.Sms(phone, value("name") ?: phone)
                }
                "lead.created", "lead.assigned" -> {
                    val leadId = value("lead_id", "leadId")?.toIntOrNull()?.takeIf { it > 0 }
                        ?: return null
                    Mango9MessagePushTarget.Lead(leadId)
                }
                else -> return null
            }
            return Mango9MessagePush(
                target = target,
                sipIdentity = value("sip_identity", "sipIdentity"),
                crmId = value("crm_id", "crmId"),
            )
        }

        fun fromJson(rawValue: String?): Mango9MessagePush? {
            val json = runCatching { JSONObject(rawValue.orEmpty()) }.getOrNull() ?: return null
            val target = when (json.optString("type")) {
                "chat" -> {
                    val userId = json.optInt("user_id")
                    val roomId = json.optionalString("room_id")
                    if (userId <= 0 && roomId == null) return null
                    Mango9MessagePushTarget.Chat(
                        userId,
                        json.optionalString("name") ?: "Team Chat",
                        roomId,
                    )
                }
                "sms" -> {
                    val phone = json.optionalString("phone") ?: return null
                    Mango9MessagePushTarget.Sms(phone, json.optionalString("name") ?: phone)
                }
                "lead" -> Mango9MessagePushTarget.Lead(json.optInt("lead_id").takeIf { it > 0 } ?: return null)
                else -> return null
            }
            return Mango9MessagePush(
                target,
                json.optionalString("sip_identity"),
                json.optionalString("crm_id"),
            )
        }
    }
}

class Mango9MessagePushTokenStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_FILE,
        Context.MODE_PRIVATE,
    )

    fun save(token: String): Boolean {
        if (!isValidToken(token)) return false
        preferences.edit(commit = true) { putString(KEY_TOKEN, token) }
        return true
    }

    fun load(): String? = preferences.getString(KEY_TOKEN, null)?.takeIf(::isValidToken)

    fun clear() = preferences.edit(commit = true) { remove(KEY_TOKEN) }

    companion object {
        const val PREFERENCES_FILE = "mango9_message_push"
        private const val KEY_TOKEN = "fcm_token"

        fun isValidToken(token: String): Boolean =
            token.length in 20..4096 && token.all { it.code in 33..126 && !it.isWhitespace() }
    }
}

object Mango9MessagePushRegistration {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun registerRequest(
        session: Mango9Session,
        chatToken: String,
        pushToken: String,
        deviceId: String,
        sipIdentity: String,
    ): Request? {
        if (!validBearer(chatToken) || !Mango9MessagePushTokenStore.isValidToken(pushToken)) return null
        val body = JSONObject()
            .put("token", pushToken)
            .put("device_id", deviceId)
            .put("crm_id", session.crmId)
            .put("sip_identity", sipIdentity)
            .put("environment", "production")
        return request(session, chatToken, body, delete = false)
    }

    fun unregisterRequest(
        session: Mango9Session,
        chatToken: String,
        deviceId: String,
    ): Request? {
        if (!validBearer(chatToken)) return null
        return request(
            session,
            chatToken,
            JSONObject().put("device_id", deviceId),
            delete = true,
        )
    }

    private fun request(
        session: Mango9Session,
        chatToken: String,
        body: JSONObject,
        delete: Boolean,
    ): Request? {
        val base = Mango9Configuration.verifiedHttpsUrl(session.smsChatApi) ?: return null
        val httpUrl = base.toString().toHttpUrlOrNull() ?: return null
        if (httpUrl.querySize > 0) return null
        val endpoint = httpUrl.newBuilder().addPathSegment("push").addPathSegment("register").build()
        val requestBody = body.toString().toRequestBody(jsonMediaType)
        return Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $chatToken")
            .apply { if (delete) delete(requestBody) else post(requestBody) }
            .build()
    }

    private fun validBearer(value: String): Boolean =
        value.isNotBlank() && value.length <= 8192 && value.none { it == '\r' || it == '\n' }
}
