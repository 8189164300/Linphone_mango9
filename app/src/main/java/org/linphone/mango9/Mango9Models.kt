/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.mango9

import org.json.JSONObject

data class Mango9SipEnrollment(
    val identity: String,
    val username: String,
    val domain: String,
    val realm: String,
    val ha1: String,
)

data class Mango9Session(
    val crmId: String,
    val crmBaseUrl: String,
    val crmApiBaseUrl: String,
    val userId: String,
    val parentClientId: String,
    val role: String,
    val loginId: String,
    val displayName: String?,
    val accessToken: String,
    val refreshToken: String,
    val smsChatApi: String,
    val connectWebsocket: String,
    val enrollmentExpiresAtEpochSeconds: Long,
    val sipIdentity: String?,
) {
    fun associatedWith(identity: String): Mango9Session = copy(sipIdentity = identity)

    fun toJson(): JSONObject = JSONObject().apply {
        put("crm_id", crmId)
        put("crm_base_url", crmBaseUrl)
        put("crm_api_base_url", crmApiBaseUrl)
        put("user_id", userId)
        put("parent_client_id", parentClientId)
        put("role", role)
        put("login_id", loginId)
        put("display_name", displayName ?: JSONObject.NULL)
        put("access_token", accessToken)
        put("refresh_token", refreshToken)
        put("sms_chat_api", smsChatApi)
        put("connect_websocket", connectWebsocket)
        put("enrollment_expires_at", enrollmentExpiresAtEpochSeconds)
        put("sip_identity", sipIdentity ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(json: JSONObject): Mango9Session = Mango9Session(
            crmId = json.requiredString("crm_id"),
            crmBaseUrl = json.requiredString("crm_base_url"),
            crmApiBaseUrl = json.requiredString("crm_api_base_url"),
            userId = json.requiredString("user_id"),
            parentClientId = json.requiredString("parent_client_id"),
            role = json.requiredString("role"),
            loginId = json.requiredString("login_id"),
            displayName = json.optionalString("display_name"),
            accessToken = json.requiredString("access_token"),
            refreshToken = json.requiredString("refresh_token"),
            smsChatApi = json.requiredString("sms_chat_api"),
            connectWebsocket = json.requiredString("connect_websocket"),
            enrollmentExpiresAtEpochSeconds = json.getLong("enrollment_expires_at"),
            sipIdentity = json.optionalString("sip_identity"),
        )
    }
}

internal data class Mango9LoginResponse(
    val enrollmentUrl: String,
    val expiresIn: Int,
    val session: Mango9Session,
)

internal fun JSONObject.requiredObject(key: String): JSONObject =
    optJSONObject(key) ?: throw Mango9ApiException.InvalidResponse

internal fun JSONObject.requiredString(key: String): String {
    val value = optionalString(key)?.trim().orEmpty()
    if (value.isEmpty()) throw Mango9ApiException.InvalidResponse
    return value
}

internal fun JSONObject.optionalString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).takeIf { it.isNotBlank() }
}
