/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.mango9

import android.content.Context
import androidx.core.content.edit
import java.security.MessageDigest

class Mango9ChatModerationStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val sessions = Mango9SessionStore(context)

    fun isBlocked(userId: Int): Boolean = values(KEY_BLOCKED_USERS).contains(userId.toString())

    fun setBlocked(userId: Int, blocked: Boolean) = update(KEY_BLOCKED_USERS, userId.toString(), blocked)

    fun isMessageHidden(messageId: String): Boolean = values(KEY_HIDDEN_MESSAGES).contains(messageId)

    fun setMessageHidden(messageId: String, hidden: Boolean) = update(KEY_HIDDEN_MESSAGES, messageId, hidden)

    fun restoreMessages(messageIds: Collection<String>) {
        val key = key(KEY_HIDDEN_MESSAGES)
        val remaining = preferences.getStringSet(key, emptySet()).orEmpty() - messageIds.toSet()
        preferences.edit(commit = true) { putStringSet(key, remaining) }
    }

    fun isConversationDeleted(roomId: String): Boolean = values(KEY_DELETED_ROOMS).contains(roomId)

    fun setConversationDeleted(roomId: String, deleted: Boolean) = update(KEY_DELETED_ROOMS, roomId, deleted)

    fun isSmsMuted(phone: String): Boolean = values(KEY_MUTED_SMS).contains(normalizedPhone(phone))

    fun setSmsMuted(phone: String, muted: Boolean) = update(KEY_MUTED_SMS, normalizedPhone(phone), muted)

    fun isSmsMessageHidden(phone: String, messageId: String): Boolean =
        values("$KEY_HIDDEN_SMS.${normalizedPhone(phone)}").contains(messageId)

    fun setSmsMessageHidden(phone: String, messageId: String, hidden: Boolean) =
        update("$KEY_HIDDEN_SMS.${normalizedPhone(phone)}", messageId, hidden)

    private fun values(prefix: String): Set<String> = preferences.getStringSet(key(prefix), emptySet()).orEmpty()

    private fun update(prefix: String, value: String, enabled: Boolean) {
        val key = key(prefix)
        val updated = preferences.getStringSet(key, emptySet()).orEmpty().toMutableSet().apply {
            if (enabled) add(value) else remove(value)
        }
        preferences.edit(commit = true) { putStringSet(key, updated) }
    }

    private fun key(prefix: String): String = "$prefix.${identityHash(sessions.activeIdentity ?: "no-account")}"

    companion object {
        private const val FILE_NAME = "mango9_chat_moderation"
        private const val KEY_BLOCKED_USERS = "blocked_users"
        private const val KEY_HIDDEN_MESSAGES = "hidden_messages"
        private const val KEY_DELETED_ROOMS = "deleted_rooms"
        private const val KEY_MUTED_SMS = "muted_sms"
        private const val KEY_HIDDEN_SMS = "hidden_sms"

        private fun identityHash(identity: String): String = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray())
            .take(12)
            .joinToString("") { "%02x".format(it) }

        fun normalizedPhone(value: String): String {
            val digits = value.filter(Char::isDigit)
            return if (digits.length == 10) "1$digits" else digits
        }
    }
}
