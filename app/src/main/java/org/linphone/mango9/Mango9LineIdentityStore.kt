/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.mango9

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

data class Mango9LineIdentity(
    val extensionNumber: String,
    val activeNumber: String?,
)

/** Keeps the non-secret, user-visible line label isolated by normalized SIP identity. */
class Mango9LineIdentityStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_FILE,
        Context.MODE_PRIVATE,
    )

    fun save(identity: Mango9LineIdentity, sipIdentity: String?) {
        val account = Mango9SessionStore.normalizedIdentity(sipIdentity)
        val extension = identity.extensionNumber.trim()
        val activeNumber = formatPhoneNumber(identity.activeNumber.orEmpty())
        if (account != null) {
            preferences.edit(commit = true) {
                putString(
                    storageKey(account),
                    JSONObject()
                        .put("extension", extension)
                        .put("active_number", activeNumber)
                        .toString(),
                )
            }
            if (account == activeIdentity) setActiveValues(extension, activeNumber)
        } else {
            setActiveValues(extension, activeNumber)
        }
    }

    fun load(sipIdentity: String?): Mango9LineIdentity? {
        val account = Mango9SessionStore.normalizedIdentity(sipIdentity) ?: return null
        val rawValue = preferences.getString(storageKey(account), null) ?: return null
        return runCatching {
            val json = JSONObject(rawValue)
            Mango9LineIdentity(
                extensionNumber = json.optString("extension"),
                activeNumber = json.optString("active_number").takeIf(String::isNotBlank),
            )
        }.getOrNull()
    }

    fun activate(sipIdentity: String?) {
        val account = Mango9SessionStore.normalizedIdentity(sipIdentity)
        preferences.edit(commit = true) {
            if (account == null) remove(KEY_ACTIVE_IDENTITY) else putString(KEY_ACTIVE_IDENTITY, account)
        }
        val identity = load(account)
        if (identity == null) {
            clearActiveValues()
        } else {
            setActiveValues(identity.extensionNumber, identity.activeNumber.orEmpty())
        }
    }

    fun clear(sipIdentity: String?) {
        val account = Mango9SessionStore.normalizedIdentity(sipIdentity)
        if (account == null) {
            clearActiveValues()
            return
        }
        preferences.edit(commit = true) { remove(storageKey(account)) }
        if (account == activeIdentity) clearActiveValues(clearIdentity = true)
    }

    private val activeIdentity: String?
        get() = Mango9SessionStore.normalizedIdentity(
            preferences.getString(KEY_ACTIVE_IDENTITY, null),
        )

    private fun setActiveValues(extension: String, activeNumber: String) {
        preferences.edit(commit = true) {
            putString(KEY_ACTIVE_EXTENSION, extension)
            putString(KEY_ACTIVE_NUMBER, activeNumber)
        }
    }

    private fun clearActiveValues(clearIdentity: Boolean = false) {
        preferences.edit(commit = true) {
            remove(KEY_ACTIVE_EXTENSION)
            remove(KEY_ACTIVE_NUMBER)
            if (clearIdentity) remove(KEY_ACTIVE_IDENTITY)
        }
    }

    private fun storageKey(identity: String) = "$PER_ACCOUNT_PREFIX$identity"

    companion object {
        const val PREFERENCES_FILE = "mango9_line_identity"
        const val KEY_ACTIVE_EXTENSION = "mango9_active_extension"
        const val KEY_ACTIVE_NUMBER = "mango9_active_number"
        private const val KEY_ACTIVE_IDENTITY = "mango9_active_line_identity"
        private const val PER_ACCOUNT_PREFIX = "mango9_line_identity."

        fun formatPhoneNumber(rawValue: String): String {
            val trimmed = rawValue.trim()
            val digits = trimmed.filter(Char::isDigit).let {
                if (it.length == 11 && it.startsWith('1')) it.drop(1) else it
            }
            return if (digits.length == 10) {
                "${digits.take(3)}-${digits.substring(3, 6)}-${digits.takeLast(4)}"
            } else {
                trimmed
            }
        }

        fun accountLineLabel(
            extensionNumber: String,
            activeNumber: String,
            fallback: String,
        ): String {
            val extension = extensionNumber.trim()
            val number = activeNumber.trim()
            return when {
                extension.isNotEmpty() && number.isNotEmpty() -> "$number · Ext $extension"
                extension.isNotEmpty() -> "Ext $extension"
                number.isNotEmpty() -> number
                else -> fallback
            }
        }
    }
}
