/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.mango9

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.RestrictionsManager
import android.content.SharedPreferences
import android.os.UserManager
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.StringReader
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.xml.parsers.SAXParserFactory
import org.linphone.core.Config
import org.linphone.core.Core
import org.linphone.core.CoreContext
import org.linphone.core.GlobalState
import org.linphone.core.tools.Log
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

internal data class Mango9ManagedConfigurationSnapshot(
    val xmlConfig: String?,
    val rootCa: String?,
    val configUri: String?,
) {
    val signature: String = hashFields(
        "xmlConfig" to xmlConfig,
        "rootCa" to rootCa,
        "configUri" to configUri,
    )

    val xmlSignature: String?
        get() = xmlConfig?.let { hashFields("xmlConfig" to it) }

    val coreSignature: String?
        get() = if (rootCa == null && configUri == null) {
            null
        } else {
            hashFields("rootCa" to rootCa, "configUri" to configUri)
        }

    companion object {
        const val XML_CONFIG_KEY = "xmlConfig"
        const val ROOT_CA_KEY = "rootCa"
        const val CONFIG_URI_KEY = "configUri"

        val EMPTY = Mango9ManagedConfigurationSnapshot(null, null, null)

        fun from(values: Map<String, Any?>): Mango9ManagedConfigurationSnapshot =
            Mango9ManagedConfigurationSnapshot(
                xmlConfig = values[XML_CONFIG_KEY].managedString(MAX_XML_LENGTH),
                rootCa = values[ROOT_CA_KEY].managedString(MAX_ROOT_CA_LENGTH),
                configUri = values[CONFIG_URI_KEY].managedString(MAX_CONFIG_URI_LENGTH),
            )

        private fun Any?.managedString(maxLength: Int): String? {
            val value = this as? String ?: return null
            return value.takeIf { it.isNotBlank() && it.length <= maxLength }
        }

        private fun hashFields(vararg fields: Pair<String, String?>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            fields.forEach { (name, value) ->
                digest.update(name.toByteArray(Charsets.UTF_8))
                digest.update(0.toByte())
                if (value == null) {
                    digest.update(0.toByte())
                } else {
                    digest.update(1.toByte())
                    digest.update(value.length.toString().toByteArray(Charsets.UTF_8))
                    digest.update(0.toByte())
                    digest.update(value.toByteArray(Charsets.UTF_8))
                }
            }
            return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }

        private const val MAX_XML_LENGTH = 1_048_576
        private const val MAX_ROOT_CA_LENGTH = 1_048_576
        private const val MAX_CONFIG_URI_LENGTH = 8_192
    }
}

internal data class Mango9ManagedConfigEntryKey(
    val section: String,
    val key: String,
)

internal object Mango9ManagedConfigXmlParser {
    fun entryKeys(xml: String): Set<Mango9ManagedConfigEntryKey> {
        require(xml.length <= MAX_XML_LENGTH) { "Managed XML is too large" }
        require(!xml.contains("<!DOCTYPE", ignoreCase = true)) { "DOCTYPE is not allowed" }
        require(!xml.contains("<!ENTITY", ignoreCase = true)) { "ENTITY is not allowed" }

        val keys = linkedSetOf<Mango9ManagedConfigEntryKey>()
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            isValidating = false
        }
        setFeatureIfSupported(factory, "http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeatureIfSupported(factory, "http://xml.org/sax/features/external-general-entities", false)
        setFeatureIfSupported(factory, "http://xml.org/sax/features/external-parameter-entities", false)
        setFeatureIfSupported(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false)

        val handler = object : DefaultHandler() {
            private var section: String? = null

            override fun resolveEntity(publicId: String?, systemId: String?): InputSource =
                InputSource(StringReader(""))

            override fun startElement(
                uri: String?,
                localName: String?,
                qName: String,
                attributes: Attributes,
            ) {
                when (elementName(localName, qName)) {
                    "section" -> section = attributes.getValue("name")?.takeIf { it.isNotBlank() }
                    "entry" -> {
                        val currentSection = section
                        val entry = attributes.getValue("name")?.takeIf { it.isNotBlank() }
                        if (currentSection != null && entry != null) {
                            keys += Mango9ManagedConfigEntryKey(currentSection, entry)
                        }
                    }
                }
            }

            override fun endElement(uri: String?, localName: String?, qName: String) {
                if (elementName(localName, qName) == "section") section = null
            }
        }

        factory.newSAXParser().parse(InputSource(StringReader(xml)), handler)
        return keys
    }

    private fun elementName(localName: String?, qName: String): String =
        localName?.takeIf { it.isNotBlank() } ?: qName.substringAfter(':')

    private fun setFeatureIfSupported(factory: SAXParserFactory, feature: String, enabled: Boolean) {
        try {
            factory.setFeature(feature, enabled)
        } catch (_: Exception) {
            // Explicit declaration rejection and the empty entity resolver remain active.
        }
    }

    private const val MAX_XML_LENGTH = 1_048_576
}

internal data class Mango9ManagedConfigEntryBackup(
    val section: String,
    val key: String,
    val existed: Boolean,
    val value: String,
    val overwrite: Boolean,
    val skip: Boolean,
) {
    val entryKey = Mango9ManagedConfigEntryKey(section, key)
}

internal object Mango9ManagedConfigBackupCodec {
    fun encode(backup: Mango9ManagedConfigEntryBackup): String = listOf(
        encodeText(backup.section),
        encodeText(backup.key),
        backup.existed.flag(),
        encodeText(backup.value),
        backup.overwrite.flag(),
        backup.skip.flag(),
    ).joinToString(".")

    fun decode(rawValue: String): Mango9ManagedConfigEntryBackup? {
        val fields = rawValue.split('.', limit = 6)
        if (fields.size != 6 || listOf(2, 4, 5).any { fields[it] !in setOf("0", "1") }) return null
        return try {
            val section = decodeText(fields[0])
            val key = decodeText(fields[1])
            if (section.isBlank() || key.isBlank()) return null
            Mango9ManagedConfigEntryBackup(
                section = section,
                key = key,
                existed = fields[2] == "1",
                value = decodeText(fields[3]),
                overwrite = fields[4] == "1",
                skip = fields[5] == "1",
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun encodeKey(key: Mango9ManagedConfigEntryKey): String =
        "${encodeText(key.section)}.${encodeText(key.key)}"

    fun decodeKey(rawValue: String): Mango9ManagedConfigEntryKey? {
        val fields = rawValue.split('.', limit = 2)
        if (fields.size != 2) return null
        return try {
            val section = decodeText(fields[0])
            val key = decodeText(fields[1])
            if (section.isBlank() || key.isBlank()) null else Mango9ManagedConfigEntryKey(section, key)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun encodeText(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decodeText(value: String): String =
        Base64.getUrlDecoder().decode(value).toString(Charsets.UTF_8)

    private fun Boolean.flag(): String = if (this) "1" else "0"
}

/** Cache successful crypto opens only; never treat an unavailable key as an empty store. */
internal class Mango9ManagedPreferenceStore(
    private val raw: SharedPreferences,
    private val openEncrypted: () -> SharedPreferences?,
) {
    private var opened: SharedPreferences? = null

    @Synchronized
    fun isEmpty(): Boolean = opened?.all?.isEmpty()
        ?: raw.all.keys.none { it !in KEYSETS }

    @Synchronized
    fun open(): SharedPreferences? = opened ?: openEncrypted()?.also { opened = it }

    private companion object {
        // Pinned AndroidX security-crypto metadata, not user settings. Do not delete it.
        val KEYSETS = setOf(
            "__androidx_security_crypto_encrypted_prefs_key_keyset__",
            "__androidx_security_crypto_encrypted_prefs_value_keyset__",
        )
    }
}

private enum class ManagedTransition { Unchanged, Changed, Failed }

class Mango9ManagedConfigurationManager internal constructor(
    context: Context,
    private val preferenceStore: Mango9ManagedPreferenceStore,
    private val snapshotSource: () -> Mango9ManagedConfigurationSnapshot?,
) {
    constructor(context: Context) : this(
        context,
        createPreferenceStore(context.applicationContext),
        { readSnapshot(context.applicationContext) },
    )

    companion object {
        private const val TAG = "[Mango9 Managed Configuration]"
        private const val PREFERENCES_FILE = "mango9_managed_configuration_secrets"
        private const val MASTER_KEY_ALIAS = "mango9_managed_configuration_master_key"
        private const val KEY_XML_BACKUPS = "xml.backups"
        private const val KEY_XML_ACTIVE_ENTRIES = "xml.active_entries"
        private const val KEY_LAST_XML_SIGNATURE = "xml.last_signature"
        private const val KEY_LAST_CORE_SIGNATURE = "core.last_signature"
        private const val KEY_CORE_ACTIVE = "core.active"
        private const val KEY_BASELINE_ROOT_CA_PRESENT = "core.baseline_root_ca_present"
        private const val KEY_BASELINE_ROOT_CA = "core.baseline_root_ca"
        private const val KEY_BASELINE_CONFIG_URI_PRESENT = "core.baseline_config_uri_present"
        private const val KEY_BASELINE_CONFIG_URI = "core.baseline_config_uri"

        private fun createPreferenceStore(context: Context) = Mango9ManagedPreferenceStore(
            context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE),
        ) {
            val masterKey = MasterKey.Builder(context, MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context, PREFERENCES_FILE, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

        private fun readSnapshot(context: Context): Mango9ManagedConfigurationSnapshot? {
            val restrictions = (context.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager)
                .applicationRestrictions
            if (restrictions.getBoolean(UserManager.KEY_RESTRICTIONS_PENDING, false)) {
                Log.w("$TAG Managed restrictions are pending; keeping the last known configuration")
                return null
            }
            return Mango9ManagedConfigurationSnapshot.from(
                mapOf(
                    Mango9ManagedConfigurationSnapshot.XML_CONFIG_KEY to
                        restrictions.getString(Mango9ManagedConfigurationSnapshot.XML_CONFIG_KEY),
                    Mango9ManagedConfigurationSnapshot.ROOT_CA_KEY to
                        restrictions.getString(Mango9ManagedConfigurationSnapshot.ROOT_CA_KEY),
                    Mango9ManagedConfigurationSnapshot.CONFIG_URI_KEY to
                        restrictions.getString(Mango9ManagedConfigurationSnapshot.CONFIG_URI_KEY),
                ),
            )
        }
    }

    private val appContext = context.applicationContext
    private val lock = Any()
    private val receiverLock = Any()
    private val refreshQueued = AtomicBoolean(false)
    private val refreshRevision = AtomicLong()
    private var restartRequired = false
    private var appliedCore: Core? = null
    private var appliedCoreSignature: String? = null
    private var xmlNeedsRetry = false

    @Volatile
    private var coreContext: CoreContext? = null

    @Volatile
    private var currentSnapshotSignature: String? = null

    @Volatile
    private var receiverRegistered = false

    private val restrictionsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED) {
                refreshFromRestrictions()
            }
        }
    }

    fun attach(coreContext: CoreContext) {
        this.coreContext = coreContext
    }

    fun prepareConfiguration(config: Config) {
        val snapshot = readCurrentSnapshot() ?: return
        synchronized(lock) {
            if (transition { applyXmlTransition(config, snapshot) } == ManagedTransition.Failed) {
                currentSnapshotSignature = null
            }
            // Preparing XML does not mean rootCa/configUri were applied to a Core.
        }
    }

    fun applyBeforeCoreStart(core: Core) {
        val snapshot = readCurrentSnapshot() ?: return
        synchronized(lock) {
            val xml = transition { applyXmlTransition(core.config, snapshot) }
            val settings = transition { applyCoreTransition(core, snapshot) }
            if (xml != ManagedTransition.Failed && settings != ManagedTransition.Failed) {
                currentSnapshotSignature = snapshot.signature
            } else {
                currentSnapshotSignature = null
            }
        }
    }

    fun onForeground() {
        refreshFromRestrictions()
        if (receiverRegistered) return
        synchronized(receiverLock) {
            if (receiverRegistered) return
            ContextCompat.registerReceiver(
                appContext,
                restrictionsReceiver,
                IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED),
                ContextCompat.RECEIVER_EXPORTED,
            )
            receiverRegistered = true
            Log.i("$TAG Listening for managed configuration changes")
        }
    }

    fun onBackground() {
        if (!receiverRegistered) return
        synchronized(receiverLock) {
            if (!receiverRegistered) return
            try {
                appContext.unregisterReceiver(restrictionsReceiver)
            } catch (_: IllegalArgumentException) {
                // The receiver was already detached by the system or another lifecycle transition.
            }
            receiverRegistered = false
            Log.i("$TAG Stopped listening for managed configuration changes")
        }
    }

    private fun refreshFromRestrictions() {
        val context = coreContext ?: return
        requestRefresh { action ->
            context.postOnCoreThreadWhenAvailableForHeavyTask(action, "apply Mango9 managed configuration")
        }
    }

    internal fun requestRefresh(enqueue: ((Core) -> Unit) -> Unit) {
        // Foreground/broadcast callbacks must never wait behind crypto or SDK work.
        refreshRevision.incrementAndGet()
        if (!refreshQueued.compareAndSet(false, true)) return
        try {
            enqueue { core ->
                val revision = refreshRevision.get()
                try {
                synchronized(lock) {
                    try {
                        // Calls can defer this task. Read restrictions now, not the stale queued payload.
                        val snapshot = readCurrentSnapshot() ?: return@synchronized
                        if (snapshot.signature == currentSnapshotSignature && !restartRequired) return@synchronized
                        val xml = transition { applyXmlTransition(core.config, snapshot) }
                        val settings = transition { applyCoreTransition(core, snapshot) }
                        if ((xml == ManagedTransition.Changed || settings == ManagedTransition.Changed) &&
                            core.globalState == GlobalState.On
                        ) {
                            restartRequired = true
                        }
                        if (restartRequired) {
                            Log.i("$TAG Restarting Core after a managed configuration change")
                            if (core.globalState == GlobalState.On) core.stop()
                            restartRequired = core.start() != 0
                            if (restartRequired) Log.e("$TAG Core restart failed; retry remains pending")
                        }
                        if (xml != ManagedTransition.Failed && settings != ManagedTransition.Failed && !restartRequired) {
                            currentSnapshotSignature = snapshot.signature
                        } else {
                            currentSnapshotSignature = null
                        }
                    } catch (error: Exception) {
                        currentSnapshotSignature = null
                        Log.e("$TAG Managed refresh failed: ${error.javaClass.simpleName}")
                    }
                }
                } finally {
                    refreshQueued.set(false)
                    // A restriction notification arriving during the transaction
                    // must get another latest-state read after that transaction.
                    if (refreshRevision.get() != revision) requestRefresh(enqueue)
                }
            }
        } catch (error: Exception) {
            refreshQueued.set(false)
            Log.e("$TAG Managed refresh could not be queued: ${error.javaClass.simpleName}")
        }
    }

    private fun readCurrentSnapshot(): Mango9ManagedConfigurationSnapshot? = try {
        snapshotSource()
    } catch (error: Exception) {
        Log.e("$TAG Restrictions unavailable; retaining prior policy: ${error.javaClass.simpleName}")
        null
    }

    private fun transition(action: () -> ManagedTransition): ManagedTransition = try {
        action()
    } catch (error: Exception) {
        // No payloads or credentials in errors; failed opens and writes must remain retryable.
        Log.e("$TAG Managed state transition failed: ${error.javaClass.simpleName}")
        ManagedTransition.Failed
    }

    private fun commit(preferences: SharedPreferences, change: SharedPreferences.Editor.() -> Unit) {
        check(preferences.edit().apply(change).commit()) { "Managed state could not be persisted" }
    }

    private fun sync(config: Config) {
        check(config.sync() == 0) { "Managed configuration could not be persisted" }
    }

    private fun securePreferences(): SharedPreferences {
        return checkNotNull(preferenceStore.open()) { "Secure managed state is unavailable" }
    }

    private fun requireCompleteBackups(
        keys: Set<Mango9ManagedConfigEntryKey>,
        backups: Map<Mango9ManagedConfigEntryKey, Mango9ManagedConfigEntryBackup>,
    ) {
        check(keys.all { it in backups }) { "Managed rollback state is incomplete" }
    }

    private fun applyXmlTransition(
        config: Config,
        snapshot: Mango9ManagedConfigurationSnapshot,
    ): ManagedTransition {
        if (snapshot.xmlConfig == null && !xmlNeedsRetry && preferenceStore.isEmpty()) return ManagedTransition.Unchanged
        val preferences = securePreferences()
        val xml = snapshot.xmlConfig
        val lastSignature = preferences.getString(KEY_LAST_XML_SIGNATURE, null)
        val nextSignature = snapshot.xmlSignature
        if (!xmlNeedsRetry && nextSignature != null && nextSignature == lastSignature) return ManagedTransition.Unchanged

        val activeKeys = loadActiveKeys(preferences)
        if (xml == null) {
            if (!xmlNeedsRetry && activeKeys.isEmpty() && lastSignature == null) return ManagedTransition.Unchanged
            val backups = loadBackups(preferences)
            requireCompleteBackups(activeKeys, backups)
            xmlNeedsRetry = true
            activeKeys.forEach { key -> restoreEntry(config, backups[key], key) }
            sync(config)
            commit(preferences) {
                remove(KEY_XML_BACKUPS)
                remove(KEY_XML_ACTIVE_ENTRIES)
                remove(KEY_LAST_XML_SIGNATURE)
            }
            xmlNeedsRetry = false
            Log.i("$TAG Removed managed XML overlay and restored [${activeKeys.size}] entries")
            return if (activeKeys.isNotEmpty()) ManagedTransition.Changed else ManagedTransition.Unchanged
        }

        val nextKeys = try {
            Mango9ManagedConfigXmlParser.entryKeys(xml)
        } catch (error: Exception) {
            Log.e("$TAG Rejected invalid managed xmlConfig: ${error.javaClass.simpleName}")
            return ManagedTransition.Failed
        }

        val backups = loadBackups(preferences).toMutableMap()
        requireCompleteBackups(activeKeys, backups)
        nextKeys.forEach { key ->
            if (key !in backups) backups[key] = captureEntry(config, key)
        }
        val before = (activeKeys + nextKeys).associateWith { captureEntry(config, it) }
        xmlNeedsRetry = true
        // Write rollback coverage before native mutations. If interrupted, removal
        // must restore newly introduced entries too, not just the previous overlay.
        commit(preferences) {
            putStringSet(KEY_XML_BACKUPS, backups.values.mapTo(linkedSetOf(), Mango9ManagedConfigBackupCodec::encode))
            putStringSet(KEY_XML_ACTIVE_ENTRIES, (activeKeys + nextKeys).mapTo(linkedSetOf(), Mango9ManagedConfigBackupCodec::encodeKey))
            remove(KEY_LAST_XML_SIGNATURE)
        }

        try {
            check(config.loadFromXmlString(xml) == 0) { "SDK rejected managed XML" }
            (activeKeys - nextKeys).forEach { key -> restoreEntry(config, backups[key], key) }
            sync(config)
        } catch (error: Exception) {
            // SDK rejection (including a thrown error) or failed sync must not
            // leave a partially modified in-memory configuration in use.
            before.forEach { (key, backup) -> restoreEntry(config, backup, key) }
            try { sync(config) } catch (_: Exception) { /* Durable rollback coverage remains for retry. */ }
            throw error
        }
        commit(preferences) {
            putStringSet(
                KEY_XML_ACTIVE_ENTRIES,
                nextKeys.mapTo(linkedSetOf(), Mango9ManagedConfigBackupCodec::encodeKey),
            )
            putString(KEY_LAST_XML_SIGNATURE, nextSignature)
            if (nextKeys.isEmpty()) {
                remove(KEY_XML_BACKUPS)
            } else {
                putStringSet(
                    KEY_XML_BACKUPS,
                    backups.filterKeys {
                    it in nextKeys
                }.values.mapTo(linkedSetOf(), Mango9ManagedConfigBackupCodec::encode)
                )
            }
        }
        xmlNeedsRetry = false
        Log.i("$TAG Applied managed xmlConfig containing [${nextKeys.size}] entries")
        return ManagedTransition.Changed
    }

    private fun applyCoreTransition(
        core: Core,
        snapshot: Mango9ManagedConfigurationSnapshot,
    ): ManagedTransition {
        if (snapshot.coreSignature == null && preferenceStore.isEmpty()) return ManagedTransition.Unchanged
        val preferences = securePreferences()
        val nextSignature = snapshot.coreSignature
        val lastSignature = preferences.getString(KEY_LAST_CORE_SIGNATURE, null)
        val coreWasManaged = preferences.getBoolean(KEY_CORE_ACTIVE, false)
        if (coreWasManaged || lastSignature != null) {
            check(preferences.contains(KEY_BASELINE_ROOT_CA_PRESENT) && preferences.contains(KEY_BASELINE_CONFIG_URI_PRESENT)) {
                "Managed Core rollback state is incomplete"
            }
            check(!preferences.getBoolean(KEY_BASELINE_ROOT_CA_PRESENT, false) || preferences.contains(KEY_BASELINE_ROOT_CA))
            check(!preferences.getBoolean(KEY_BASELINE_CONFIG_URI_PRESENT, false) || preferences.contains(KEY_BASELINE_CONFIG_URI))
        }

        if (nextSignature == null) {
            if (!coreWasManaged && lastSignature == null) return ManagedTransition.Unchanged
            restoreCoreBaseline(core, preferences)
            sync(core.config)
            clearCoreState(preferences)
            appliedCore = null
            appliedCoreSignature = null
            Log.i("$TAG Removed managed rootCa/configUri and restored the prior Core settings")
            return ManagedTransition.Changed
        }
        if (nextSignature == lastSignature && coreWasManaged &&
            appliedCore === core && appliedCoreSignature == nextSignature
        ) {
            return ManagedTransition.Unchanged
        }

        val verifiedConfigUri = snapshot.configUri?.let { rawValue ->
            Mango9Configuration.verifiedProvisioningUrl(rawValue)?.toString().also { verified ->
                if (verified == null) Log.w("$TAG Rejected configUri outside the Mango9 allowlist")
            }
        }
        if (snapshot.configUri != null && verifiedConfigUri == null) return ManagedTransition.Failed

        if (!coreWasManaged) captureCoreBaseline(core, preferences)
        appliedCoreSignature = null
        check(core.setProvisioningUri(verifiedConfigUri ?: baselineConfigUri(preferences)) == 0) {
            "SDK rejected managed provisioning URI"
        }
        if (snapshot.rootCa == null) {
            restoreRootCaBaseline(core, preferences)
        } else {
            core.setRootCaData(snapshot.rootCa)
        }
        sync(core.config)
        commit(preferences) {
            putBoolean(KEY_CORE_ACTIVE, true)
            putString(KEY_LAST_CORE_SIGNATURE, nextSignature)
        }
        appliedCore = core
        appliedCoreSignature = nextSignature
        Log.i(
            "$TAG Applied managed Core settings " +
                "[rootCa=${snapshot.rootCa != null}, configUri=${verifiedConfigUri != null}]",
        )
        return ManagedTransition.Changed
    }

    private fun captureEntry(config: Config, key: Mango9ManagedConfigEntryKey): Mango9ManagedConfigEntryBackup {
        val existed = config.hasEntry(key.section, key.key) != 0
        return Mango9ManagedConfigEntryBackup(
            section = key.section,
            key = key.key,
            existed = existed,
            value = if (existed) config.getString(key.section, key.key, "").orEmpty() else "",
            overwrite = existed && config.getOverwriteFlagForEntry(key.section, key.key),
            skip = existed && config.getSkipFlagForEntry(key.section, key.key),
        )
    }

    private fun restoreEntry(
        config: Config,
        backup: Mango9ManagedConfigEntryBackup?,
        key: Mango9ManagedConfigEntryKey,
    ) {
        if (backup?.existed == true) {
            config.setString(key.section, key.key, backup.value)
            config.setOverwriteFlagForEntry(key.section, key.key, backup.overwrite)
            config.setSkipFlagForEntry(key.section, key.key, backup.skip)
        } else {
            config.cleanEntry(key.section, key.key)
        }
    }

    private fun loadBackups(
        preferences: SharedPreferences,
    ): Map<Mango9ManagedConfigEntryKey, Mango9ManagedConfigEntryBackup> {
        val entries = preferences.getStringSet(KEY_XML_BACKUPS, emptySet()).orEmpty().map { encoded ->
            checkNotNull(Mango9ManagedConfigBackupCodec.decode(encoded)) { "Invalid managed rollback entry" }
        }
        return entries.associateBy(Mango9ManagedConfigEntryBackup::entryKey).also {
            check(it.size == entries.size) { "Duplicate managed rollback entries" }
        }
    }

    private fun loadActiveKeys(preferences: SharedPreferences): Set<Mango9ManagedConfigEntryKey> =
        preferences.getStringSet(KEY_XML_ACTIVE_ENTRIES, emptySet()).orEmpty().mapTo(linkedSetOf()) {
            checkNotNull(Mango9ManagedConfigBackupCodec.decodeKey(it)) { "Invalid managed entry key" }
        }

    private fun captureCoreBaseline(core: Core, preferences: SharedPreferences) {
        val rootCa = core.rootCa
        val configUri = core.provisioningUri
        commit(preferences) {
            // Durable before any native setter, so retry never captures an overlay as baseline.
            putBoolean(KEY_CORE_ACTIVE, true)
            putBoolean(KEY_BASELINE_ROOT_CA_PRESENT, rootCa != null)
            if (rootCa == null) remove(KEY_BASELINE_ROOT_CA) else putString(KEY_BASELINE_ROOT_CA, rootCa)
            putBoolean(KEY_BASELINE_CONFIG_URI_PRESENT, configUri != null)
            if (configUri == null) {
                remove(KEY_BASELINE_CONFIG_URI)
            } else {
                putString(KEY_BASELINE_CONFIG_URI, configUri)
            }
        }
    }

    private fun restoreCoreBaseline(core: Core, preferences: SharedPreferences) {
        restoreRootCaBaseline(core, preferences)
        check(core.setProvisioningUri(baselineConfigUri(preferences)) == 0) {
            "SDK rejected baseline provisioning URI"
        }
    }

    private fun restoreRootCaBaseline(core: Core, preferences: SharedPreferences) {
        core.setRootCaData("")
        core.rootCa = if (preferences.getBoolean(KEY_BASELINE_ROOT_CA_PRESENT, false)) {
            preferences.getString(KEY_BASELINE_ROOT_CA, "").orEmpty()
        } else {
            ""
        }
    }

    private fun baselineConfigUri(preferences: SharedPreferences): String? =
        if (preferences.getBoolean(KEY_BASELINE_CONFIG_URI_PRESENT, false)) {
            preferences.getString(KEY_BASELINE_CONFIG_URI, null)
        } else {
            null
        }

    private fun clearCoreState(preferences: SharedPreferences) {
        commit(preferences) {
            remove(KEY_CORE_ACTIVE)
            remove(KEY_LAST_CORE_SIGNATURE)
            remove(KEY_BASELINE_ROOT_CA_PRESENT)
            remove(KEY_BASELINE_ROOT_CA)
            remove(KEY_BASELINE_CONFIG_URI_PRESENT)
            remove(KEY_BASELINE_CONFIG_URI)
        }
    }
}
