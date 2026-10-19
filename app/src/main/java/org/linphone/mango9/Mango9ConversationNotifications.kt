// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.content.Context
import org.json.JSONObject

/** Cache is a display hint only. Every change first reads and verifies the server preference. */
internal class Mango9ConversationNotifications(private val context: Context) {
    private val sessions = Mango9SessionStore(context)
    private val api = Mango9MessagingApi(context)
    private val preferences = context.getSharedPreferences("mango9_conversation_notifications", Context.MODE_PRIVATE)

    private fun key(lease: Mango9SessionLease, channel: Mango9MessageChannel, id: String) = "${Mango9MessagingApi.account(lease.session)}:$channel:$id"

    fun cached(lease: Mango9SessionLease, channel: Mango9MessageChannel, id: String): Boolean =
        if (channel == Mango9MessageChannel.sms) {
            Mango9ChatModerationStore(context).isSmsMuted(id)
        } else {
            preferences.getBoolean(key(lease, channel, id), false)
        }

    suspend fun request(lease: Mango9SessionLease, channel: Mango9MessageChannel, id: String, muted: Boolean? = null): Boolean {
        if (sessions.currentSession(lease) == null) throw Mango9ApiException.Unauthorized
        if (channel == Mango9MessageChannel.sms) {
            val store = Mango9ChatModerationStore(context)
            muted?.let { store.setSmsMuted(id, it) }
            return store.isSmsMuted(id)
        }
        val value = if (channel == Mango9MessageChannel.social) {
            Mango9SocialRepository(context).mute(lease, id, muted)
        } else {
            require(id.toIntOrNull()?.let { it > 0 && it.toString() == id } == true)
            val json = api.request(
                lease, channel, listOf("push", "rooms", id),
                body = muted?.let { JSONObject().put("muted", it) }, method = if (muted == null) "GET" else "PUT", envelope = false
            )
            require(json.getString("room_id") == id && json.get("muted") is Boolean)
            json.getBoolean("muted")
        }
        if (sessions.currentSession(lease) == null) throw Mango9ApiException.Unauthorized
        preferences.edit().putBoolean(key(lease, channel, id), value).apply()
        return value
    }
}
