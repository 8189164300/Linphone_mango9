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
class Mango9SessionStore internal constructor(context: Context, private val scheduleCleanup: (Context) -> Unit, private val secretsOverride: SharedPreferences?) : Mango9SessionAccess, Mango9LoginAccess, Mango9RenewalOwner {
    constructor(context: Context) : this(context, Mango9PushCleanupJob::schedule, null)
    internal constructor(context: Context, scheduleCleanup: (Context) -> Unit) : this(context, scheduleCleanup, null)

    override val renewalOwner: Any get() = sessionLock
    private val appContext = context.applicationContext
    private val metadata = appContext.getSharedPreferences(METADATA_FILE, Context.MODE_PRIVATE)
    private val lineIdentities = Mango9LineIdentityStore(appContext)
    private val encrypted: SharedPreferences by lazy {
        secretsOverride ?: createEncryptedStore()
    }

    private fun createEncryptedStore(): SharedPreferences {
        val masterKey = MasterKey.Builder(appContext, MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
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
            if (!value) commitSecrets { storedIdentities().forEach { putString(sessionKey(it), tombstone()) } }
            checkMetadata { putBoolean(KEY_REMEMBER_LOGIN, value) }
        }

    val lastStorageError: Mango9ApiException?
        get() = synchronized(sessionLock) { storageErrors[activeIdentity] }

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
        val previous = load(identity)
        val associated = session.associatedWith(identity).copy(loginEpoch = UUID.randomUUID().toString())
        commitSecrets {
            putString(sessionKey(identity), if (persist) record(associated) else tombstone())
            if (previous?.logoutToken != null && previous.sessionFamilyId != associated.sessionFamilyId) {
                putString("push_retirement.${UUID.randomUUID()}", previous.toJson().toString())
            }
        }
        // The secure record is authoritative even when the later UI metadata commit fails.
        // Fence the previous login immediately so its renewal cannot overwrite this generation.
        generations.loginChanged(identity)
        volatileSessions.remove(identity)
        if (makeActive) generations.selectionChanged()
        try {
            invalidateNotificationLogin(identity, previous?.loginEpoch)
            checkMetadata {
                putStringSet(KEY_IDENTITIES, storedIdentities() + identity)
                if (makeActive) putString(KEY_ACTIVE_IDENTITY, identity)
            }
            volatileSessions[identity] = associated
            if (makeActive) lineIdentities.activate(identity)
        } finally {
            if (previous?.logoutToken != null) runCatching { scheduleCleanup(appContext) }
            contextVersion.value = contextVersion.value + 1
        }
    }

    fun load(identityValue: String? = activeIdentity): Mango9Session? = synchronized(sessionLock) {
        val identity = normalizedIdentity(identityValue) ?: return null
        storageErrors.remove(identity)
        volatileSessions[identity]?.let { return it }
        if (!rememberLogin) return null
        return try {
            val rawValue = encrypted.getString(sessionKey(identity), null) ?: return null
            val json = JSONObject(rawValue)
            if (json.optBoolean("revoked", false)) return null
            val payload = if (json.has("version")) {
                if (json.getInt("version") != 1) throw Mango9ApiException.SecureStorage
                json.getJSONObject("session")
            } else {
                json
            }
            Mango9Session.fromJson(payload).also {
                if (normalizedIdentity(it.sipIdentity) != identity) throw Mango9ApiException.SecureStorage
                volatileSessions[identity] = it
            }
        } catch (error: Exception) {
            storageErrors[identity] = Mango9ApiException.SecureStorage
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
        val nonce = current.loginEpoch ?: metadata.getString(key, null) ?: UUID.randomUUID().toString().also {
            // Do not show an action whose ownership cannot survive process recreation.
            if (!metadata.edit().putString(key, it).commit()) return null
        }
        push.copy(sipIdentity = identity, notificationLogin = nonce)
    }

    internal fun resolveNotificationLogin(push: Mango9MessagePush): Mango9LoginLease? = synchronized(sessionLock) {
        val identity = normalizedIdentity(push.sipIdentity) ?: return null
        val nonce = push.notificationLogin ?: return null
        val login = resolvePushLogin(push) ?: return null
        if (nonce != (login.session.loginEpoch ?: metadata.getString(notificationKey(identity), null))) return null
        login
    }

    /** Publication and logout are serialized; logout cancels only this login's notifications. */
    internal fun withNotificationLogin(push: Mango9MessagePush, action: () -> Unit): Boolean = synchronized(sessionLock) {
        if (resolveNotificationLogin(push) == null) return false
        action()
        true
    }

    private fun invalidateNotificationLogin(identity: String, epoch: String? = volatileSessions[identity]?.loginEpoch) {
        val key = notificationKey(identity)
        val previous = epoch ?: metadata.getString(key, null)
        if (!metadata.edit().remove(key).commit()) throw Mango9ApiException.SecureStorage
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
        commitSecrets { putString(sessionKey(identity), tombstone()) }
        removeLocalState(identity)
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
        if (generations.matchesLogin(lease)) load(lease.session.sipIdentity)?.takeIf { sameLogin(it, lease.session) } else null
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
            val identity = current.sipIdentity ?: return false
            // Journal and tombstone commit together, before any SDK account is removed.
            commitSecrets {
                if (journalPushCleanup && Mango9PushOwner.from(current) != null) {
                    putString("push_retirement.${UUID.randomUUID()}", current.toJson().toString())
                }
                putString("local_logout.$identity", current.loginEpoch ?: "legacy")
                putString(sessionKey(identity), tombstone())
            }
            val metadataFailure = runCatching { removeLocalState(identity) }.exceptionOrNull()
            runCatching { Mango9SupervisorStore(appContext).clearDisplay(current) }
            contextVersion.value = contextVersion.value + 1
            runCatching { scheduleCleanup(appContext) }
            onRemoval(current)
            acknowledgeLocalRemoval(identity)
            if (metadataFailure != null) throw metadataFailure
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
        check(validTokenUpdate(before, after))
        commitSecrets { putString(key, after.toJson().toString()) }
    }

    internal fun acknowledgePushRemoval(id: String) = synchronized(sessionLock) {
        commitSecrets { remove("push_retirement.$id") }
    }

    /** Finish/cancel a completed job before a simultaneous logout schedules the next one. */
    internal fun finishPushCleanup(action: (Boolean) -> Unit) = synchronized(sessionLock) {
        action(pendingPushRemovals().isEmpty())
    }

    override fun currentSession(lease: Mango9SessionLease): Mango9Session? = synchronized(sessionLock) {
        if (generations.matches(lease, activeIdentity)) {
            load(lease.session.sipIdentity)?.takeIf {
            sameLogin(it, lease.session)
        }
        } else {
            null
        }
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
        if (!validTokenUpdate(current, refreshed)) return false
        val identity = current.sipIdentity ?: return false
        commitSecrets { putString(sessionKey(identity), if (rememberLogin) record(refreshed) else tombstone()) }
        volatileSessions[identity] = refreshed
        return true
    }

    internal fun retainsProofKey(id: String): Boolean = synchronized(sessionLock) {
        if (volatileSessions.values.any { it.proofKeyId == id }) return true
        try {
            encrypted.all.filterKeys { it.startsWith("session.") || it.startsWith("push_retirement.") }.values.any { raw ->
                val json = JSONObject(raw as String)
                (json.optJSONObject("session") ?: json).optionalString("proof_key_id") == id
            }
        } catch (_: Exception) { true }
    }

    private fun sameLogin(current: Mango9Session, captured: Mango9Session): Boolean =
        current.loginEpoch == captured.loginEpoch && current.crmId == captured.crmId &&
            current.crmApiBaseUrl == captured.crmApiBaseUrl && current.userId == captured.userId

    private fun validTokenUpdate(before: Mango9Session, after: Mango9Session): Boolean =
        before.copy(
            accessToken = after.accessToken, refreshToken = after.refreshToken,
            sessionFamilyId = after.sessionFamilyId, logoutToken = after.logoutToken
        ) == after &&
            (before.sessionFamilyId == null || before.sessionFamilyId == after.sessionFamilyId) &&
            after.accessToken.isNotBlank() && after.refreshToken.isNotBlank()

    private fun record(session: Mango9Session) = JSONObject().put("version", 1).put("revoked", false).put("session", session.toJson()).toString()

    private fun tombstone() = JSONObject().put("version", 1).put("revoked", true).toString()

    /** SharedPreferences changes its memory cache even when commit returns false. Restore it too. */
    private fun commitSecrets(change: SharedPreferences.Editor.() -> Unit) {
        try {
            val before = encrypted.all
            try {
                if (!encrypted.edit().apply(change).commit()) throw Mango9ApiException.SecureStorage
            } catch (error: Exception) {
                runCatching {
                    encrypted.edit().clear().apply { before.forEach { (key, value) -> putString(key, value as String) } }.commit()
                }
                throw error
            }
        } catch (error: Exception) { throw Mango9ApiException.SecureStorage }
    }

    private fun checkMetadata(change: SharedPreferences.Editor.() -> Unit) {
        if (!metadata.edit().apply(change).commit()) throw Mango9ApiException.SecureStorage
    }

    private fun removeLocalState(identity: String) {
        // Once the secure tombstone exists, even a metadata failure cannot revive this login.
        val epoch = volatileSessions[identity]?.loginEpoch
        volatileSessions.remove(identity)
        generations.loginChanged(identity)
        if (activeIdentity == identity) generations.selectionChanged()
        invalidateNotificationLogin(identity, epoch)
        lineIdentities.clear(identity)
        checkMetadata {
            putStringSet(KEY_IDENTITIES, storedIdentities() - identity)
            if (activeIdentity == identity) remove(KEY_ACTIVE_IDENTITY)
        }
    }

    internal fun recoverLocalRemovals(removeAccount: (String) -> Unit) = synchronized(sessionLock) {
        encrypted.all.keys.filter { it.startsWith("local_logout.") }.forEach { key ->
            val identity = key.removePrefix("local_logout.")
            val current = load(identity)
            storageErrors[identity]?.let { throw it }
            if (current == null) removeAccount(identity)
            acknowledgeLocalRemoval(identity)
        }
    }

    private fun acknowledgeLocalRemoval(identity: String) { commitSecrets { remove("local_logout.$identity") } }

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

        private val storageErrors = mutableMapOf<String, Mango9ApiException>()
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
