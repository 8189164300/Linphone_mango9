/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.mango9

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

@Suppress("DEPRECATION")
class Mango9SessionStore(context: Context) {
    private val appContext = context.applicationContext
    private val metadata = appContext.getSharedPreferences(METADATA_FILE, Context.MODE_PRIVATE)
    private val lineIdentities = Mango9LineIdentityStore(appContext)
    private val encrypted: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext, MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            SECRETS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var rememberLogin: Boolean
        get() = metadata.getBoolean(KEY_REMEMBER_LOGIN, true)
        set(value) {
            metadata.edit { putBoolean(KEY_REMEMBER_LOGIN, value) }
            if (!value) {
                encrypted.edit { storedIdentities().forEach { remove(sessionKey(it)) } }
            }
        }

    val activeIdentity: String?
        get() = normalizedIdentity(metadata.getString(KEY_ACTIVE_IDENTITY, null))

    fun save(
        session: Mango9Session,
        sipIdentity: String? = null,
        persist: Boolean = rememberLogin,
        makeActive: Boolean = false,
    ) {
        val identity = normalizedIdentity(sipIdentity ?: session.sipIdentity ?: activeIdentity)
            ?: throw IllegalArgumentException("A SIP identity is required")
        val associated = session.associatedWith(identity)
        volatileSessions[identity] = associated
        metadata.edit {
            putStringSet(KEY_IDENTITIES, storedIdentities() + identity)
            if (makeActive) putString(KEY_ACTIVE_IDENTITY, identity)
        }
        if (makeActive) lineIdentities.activate(identity)
        if (persist) {
            encrypted.edit(commit = true) { putString(sessionKey(identity), associated.toJson().toString()) }
        } else {
            encrypted.edit(commit = true) { remove(sessionKey(identity)) }
        }
    }

    fun load(identityValue: String? = activeIdentity): Mango9Session? {
        val identity = normalizedIdentity(identityValue) ?: return null
        volatileSessions[identity]?.let { return it }
        if (!rememberLogin) return null
        val rawValue = encrypted.getString(sessionKey(identity), null) ?: return null
        return try {
            Mango9Session.fromJson(JSONObject(rawValue)).associatedWith(identity).also {
                volatileSessions[identity] = it
            }
        } catch (_: Exception) {
            encrypted.edit { remove(sessionKey(identity)) }
            null
        }
    }

    fun hasSession(identityValue: String): Boolean = load(identityValue) != null

    fun identityForCrmId(crmId: String): String? {
        val requested = crmId.trim()
        if (requested.isEmpty()) return null
        return storedIdentities().firstOrNull { identity -> load(identity)?.crmId == requested }
    }

    fun isActive(identityValue: String?): Boolean =
        normalizedIdentity(identityValue) != null && normalizedIdentity(identityValue) == activeIdentity

    fun activate(identityValue: String?) {
        val identity = normalizedIdentity(identityValue)
        metadata.edit {
            if (identity == null) remove(KEY_ACTIVE_IDENTITY) else putString(KEY_ACTIVE_IDENTITY, identity)
        }
        lineIdentities.activate(identity)
        if (identity != null) load(identity)
    }

    fun remove(identityValue: String) {
        val identity = normalizedIdentity(identityValue) ?: return
        volatileSessions.remove(identity)
        encrypted.edit(commit = true) { remove(sessionKey(identity)) }
        lineIdentities.clear(identity)
        metadata.edit {
            putStringSet(KEY_IDENTITIES, storedIdentities() - identity)
            if (activeIdentity == identity) remove(KEY_ACTIVE_IDENTITY)
        }
    }

    private fun storedIdentities(): Set<String> =
        metadata.getStringSet(KEY_IDENTITIES, emptySet()).orEmpty().mapNotNull(::normalizedIdentity).toSet()

    private fun sessionKey(identity: String) = "session.$identity"

    companion object {
        const val METADATA_FILE = "mango9_session_metadata"
        const val SECRETS_FILE = "mango9_session_secrets"
        private const val MASTER_KEY_ALIAS = "mango9_crm_session_master_key"
        private const val KEY_REMEMBER_LOGIN = "remember_login"
        private const val KEY_ACTIVE_IDENTITY = "active_sip_identity"
        private const val KEY_IDENTITIES = "crm_session_identities"
        private val volatileSessions = ConcurrentHashMap<String, Mango9Session>()

        fun normalizedIdentity(rawValue: String?): String? {
            var value = rawValue?.trim()?.lowercase().orEmpty()
            if (value.isEmpty()) return null
            val openingBracket = value.indexOf('<')
            val closingBracket = value.indexOf('>', startIndex = openingBracket + 1)
            if (openingBracket >= 0 && closingBracket > openingBracket) {
                value = value.substring(openingBracket + 1, closingBracket)
            }
            value = value.substringBefore(';').trim()
            return value.takeIf { it.isNotEmpty() }
        }
    }
}
