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
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

@Suppress("DEPRECATION")
class Mango9SessionStore internal constructor(context: Context, private val scheduleCleanup: (Context) -> Unit) : Mango9SessionAccess, Mango9LoginAccess {
    constructor(context: Context) : this(context, Mango9PushCleanupJob::schedule)

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
        set(value) = synchronized(sessionLock) {
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
    ) = synchronized(sessionLock) {
        val identity = normalizedIdentity(sipIdentity ?: session.sipIdentity ?: activeIdentity)
            ?: throw IllegalArgumentException("A SIP identity is required")
        val associated = session.associatedWith(identity)
        invalidateNotificationLogin(identity)
        generations.loginChanged(identity)
        if (makeActive && activeIdentity != identity) generations.selectionChanged()
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
        contextVersion.value = contextVersion.value + 1
    }

    fun load(identityValue: String? = activeIdentity): Mango9Session? = synchronized(sessionLock) {
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

    fun resolvePushLogin(push: Mango9MessagePush): Mango9LoginLease? = synchronized(sessionLock) {
        val identity = normalizedIdentity(push.sipIdentity)
        val crm = push.crmId?.trim()?.takeIf(String::isNotEmpty)
        val session = if (identity != null) {
            load(identity)?.takeIf { crm == null || it.crmId == crm }
        } else {
            // A tenant ID alone cannot choose between two users or two servers on this device.
            if (crm == null) return null
            storedIdentities().mapNotNull(::load).filter { it.crmId == crm }.singleOrNull()
        }
        session?.takeIf { push.recipientUserId == null || push.recipientUserId == it.userId }?.let(generations::captureLogin)
    }

    /** Only receipt binds a remote payload; opening/replying must retain this exact login. */
    internal fun bindNotification(push: Mango9MessagePush, login: Mango9LoginLease): Mango9MessagePush? = synchronized(sessionLock) {
        val current = currentLogin(login) ?: return null
        val identity = current.sipIdentity ?: return null
        val key = notificationKey(identity)
        val nonce = metadata.getString(key, null) ?: UUID.randomUUID().toString().also {
            // Do not show an action whose ownership cannot survive process recreation.
            if (!metadata.edit().putString(key, it).commit()) return null
        }
        push.copy(sipIdentity = identity, notificationLogin = nonce)
    }

    internal fun resolveNotificationLogin(push: Mango9MessagePush): Mango9LoginLease? = synchronized(sessionLock) {
        val identity = normalizedIdentity(push.sipIdentity) ?: return null
        val nonce = push.notificationLogin ?: return null
        if (nonce != metadata.getString(notificationKey(identity), null)) return null
        resolvePushLogin(push)
    }

    /** Publication and logout are serialized; logout cancels only this login's notifications. */
    internal fun withNotificationLogin(push: Mango9MessagePush, action: () -> Unit): Boolean = synchronized(sessionLock) {
        if (resolveNotificationLogin(push) == null) return false
        action()
        true
    }

    private fun invalidateNotificationLogin(identity: String) {
        val key = notificationKey(identity)
        val previous = metadata.getString(key, null)
        check(metadata.edit().remove(key).commit()) { "Unable to retire notification ownership" }
        previous?.let { Mango9MessageNotifications.cancelLogin(appContext, it) }
    }

    private fun notificationKey(identity: String) = "notification_login.$identity"

    fun isActive(identityValue: String?): Boolean =
        normalizedIdentity(identityValue) != null && normalizedIdentity(identityValue) == activeIdentity

    fun activate(identityValue: String?) = synchronized(sessionLock) {
        val identity = normalizedIdentity(identityValue)
        val changed = activeIdentity != identity
        if (changed) generations.selectionChanged()
        metadata.edit {
            if (identity == null) remove(KEY_ACTIVE_IDENTITY) else putString(KEY_ACTIVE_IDENTITY, identity)
        }
        lineIdentities.activate(identity)
        if (identity != null) load(identity)
        if (changed) contextVersion.value = contextVersion.value + 1
    }

    fun remove(identityValue: String): Unit = synchronized(sessionLock) {
        val identity = normalizedIdentity(identityValue) ?: return
        invalidateNotificationLogin(identity)
        generations.loginChanged(identity)
        if (activeIdentity == identity) generations.selectionChanged()
        volatileSessions.remove(identity)
        encrypted.edit(commit = true) { remove(sessionKey(identity)) }
        lineIdentities.clear(identity)
        metadata.edit {
            putStringSet(KEY_IDENTITIES, storedIdentities() - identity)
            if (activeIdentity == identity) remove(KEY_ACTIVE_IDENTITY)
        }
        contextVersion.value = contextVersion.value + 1
    }

    override fun captureSession(): Mango9SessionLease? = synchronized(sessionLock) {
        load()?.let(generations::capture)
    }

    fun captureLogin(identity: String): Mango9LoginLease? = synchronized(sessionLock) {
        load(identity)?.let(generations::captureLogin)
    }

    override fun captureLogins(): List<Mango9LoginLease> = synchronized(sessionLock) {
        storedIdentities().sorted().sortedBy { it != activeIdentity }
            .mapNotNull(::load).map(generations::captureLogin)
    }

    override fun currentLogin(lease: Mango9LoginLease): Mango9Session? = synchronized(sessionLock) {
        if (generations.matchesLogin(lease)) load(lease.session.sipIdentity) else null
    }

    internal val selectionVersion: Long get() = synchronized(sessionLock) { generations.selectionVersion }

    internal fun selectionStamp(): Mango9SelectionStamp = synchronized(sessionLock) {
        generations.stamp(activeIdentity)
    }

    internal data class CallLease(val identity: String?, val login: Mango9LoginLease?, val selection: Long)

    internal fun captureCallLease(identity: String?): CallLease = synchronized(sessionLock) {
        CallLease(identity, identity?.let(::captureLogin), generations.selectionVersion)
    }

    /** Serialize the final, short SDK action with logout/re-login, not with a permission dialog. */
    internal fun <T> withCurrentCallLease(lease: CallLease, action: () -> T): T? = synchronized(sessionLock) {
        if (generations.selectionVersion != lease.selection) return null
        if (lease.login != null) {
            if (currentLogin(lease.login) == null) return null
        } else if (lease.identity?.let(::captureLogin) != null) {
            return null
        }
        action()
    }

    /** Caller holds its enrollment-intent guard before taking sessionLock, never the reverse. */
    internal fun commitLogin(
        session: Mango9Session,
        identity: String,
        remember: Boolean,
        expectedSelection: Long,
    ): Mango9SessionLease? = synchronized(sessionLock) {
        if (generations.selectionVersion != expectedSelection) return null
        rememberLogin = remember
        save(session, identity, persist = remember, makeActive = true)
        captureSession()
    }

    internal fun <T> withCurrentSession(lease: Mango9SessionLease, action: () -> T): T =
        synchronized(sessionLock) {
            if (currentSession(lease) == null) throw Mango9ApiException.Unauthorized
            action()
        }

    /** Called on the core thread; a manual selection or replacement login defeats queued work. */
    internal fun activateLoginIfCurrent(
        lease: Mango9LoginLease,
        expectedSelection: Long,
        onSelection: () -> Unit,
    ): Mango9SessionLease? = synchronized(sessionLock) {
        if (generations.selectionVersion != expectedSelection) return null
        val current = currentLogin(lease) ?: return null
        activate(current.sipIdentity)
        onSelection()
        captureSession()
    }

    fun hasPushOwner(owner: Mango9PushOwner): Boolean = synchronized(sessionLock) {
        storedIdentities().any { identity -> load(identity)?.let(Mango9PushOwner::from) == owner }
    }

    /**
     * Reserve remote cleanup and remove local account state atomically with respect to a new login.
     * The callback must only do local work/enqueue work; never wait for a network operation here.
     * A stale account screen is not allowed to remove a replacement login with the same SIP URI.
     */
    fun removeIfCurrent(
        lease: Mango9LoginLease, journalPushCleanup: Boolean = false,
        onRemoval: (Mango9Session) -> Unit = {
        }
    ): Boolean =
        synchronized(sessionLock) {
            val current = currentLogin(lease) ?: return false
            if (journalPushCleanup && Mango9PushOwner.from(current) != null) {
                // One encrypted commit records revocation before destroying its credentials.
                val id = UUID.randomUUID().toString()
                check(
                    encrypted.edit().putString("push_retirement.$id", current.toJson().toString())
                    .remove(sessionKey(requireNotNull(current.sipIdentity))).commit()
                ) { "Unable to save account cleanup" }
                // A scheduling failure must not interrupt local removal after its durable commit.
                // Startup/reconnect retries scheduling from the journal, which stays intact.
                runCatching { scheduleCleanup(appContext) }
            }
            remove(current.sipIdentity ?: return false)
            onRemoval(current)
            true
        }

    internal fun pendingPushRemovals(): List<Mango9PushRetirement> = synchronized(sessionLock) {
        encrypted.all.filterKeys { it.startsWith("push_retirement.") }.map { (key, raw) ->
            Mango9PushRetirement(key.removePrefix("push_retirement."), Mango9Session.fromJson(JSONObject(raw as String)))
        }
    }

    internal fun updatePushRemoval(id: String, before: Mango9Session, after: Mango9Session) = synchronized(sessionLock) {
        val key = "push_retirement.$id"
        val raw = encrypted.getString(key, null) ?: return@synchronized
        check(Mango9Session.fromJson(JSONObject(raw)) == before)
        check(after == before.copy(accessToken = after.accessToken, refreshToken = after.refreshToken))
        check(encrypted.edit().putString(key, after.toJson().toString()).commit())
    }

    internal fun acknowledgePushRemoval(id: String) = synchronized(sessionLock) {
        check(encrypted.edit().remove("push_retirement.$id").commit())
    }

    /** Finish/cancel a completed job before a simultaneous logout schedules the next one. */
    internal fun finishPushCleanup(action: (Boolean) -> Unit) = synchronized(sessionLock) {
        action(pendingPushRemovals().isEmpty())
    }

    override fun currentSession(lease: Mango9SessionLease): Mango9Session? = synchronized(sessionLock) {
        if (generations.matches(lease, activeIdentity)) load(lease.session.sipIdentity) else null
    }

    override fun replaceTokens(
        lease: Mango9SessionLease,
        expected: Mango9Session,
        refreshed: Mango9Session,
    ): Boolean = synchronized(sessionLock) {
        val current = currentSession(lease) ?: return false
        persistTokens(current, expected, refreshed)
    }

    override fun replaceLoginTokens(
        lease: Mango9LoginLease,
        expected: Mango9Session,
        refreshed: Mango9Session,
    ): Boolean = synchronized(sessionLock) {
        val current = currentLogin(lease) ?: return false
        persistTokens(current, expected, refreshed)
    }

    /** Only called under sessionLock; never changes account selection or login generation. */
    private fun persistTokens(current: Mango9Session, expected: Mango9Session, refreshed: Mango9Session): Boolean {
        if (current != expected) return false
        // A refresh endpoint may rotate tokens, but must never change tenant, user, or routing.
        val tokenUpdate = current.copy(accessToken = refreshed.accessToken, refreshToken = refreshed.refreshToken)
        if (tokenUpdate != refreshed || refreshed.accessToken.isBlank() || refreshed.refreshToken.isBlank()) return false
        val identity = current.sipIdentity ?: return false
        encrypted.edit(commit = true) {
            if (rememberLogin) {
                putString(sessionKey(identity), tokenUpdate.toJson().toString())
            } else {
                remove(sessionKey(identity))
            }
        }
        volatileSessions[identity] = tokenUpdate
        return true
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
        private val sessionLock = Any()
        private val generations = Mango9SessionGenerations()
        // Account selection/login changes, not access-token rotations. StateFlow
        // survives collectors stopping/restarting; leases still catch A → B → A.
        private val contextVersion = MutableStateFlow(0L)
        val contextChanges = contextVersion.asStateFlow()

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
