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
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.StringReader
import java.security.MessageDigest
import java.util.Base64
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
        if (fields.size != 6) return null
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

class Mango9ManagedConfigurationManager(context: Context) {
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
    }

    private val appContext = context.applicationContext
    private val restrictionsManager =
        appContext.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
    private val lock = Any()

    private val securePreferences: SharedPreferences? by lazy {
        try {
            val masterKey = MasterKey.Builder(appContext, MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                PREFERENCES_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (error: Exception) {
            Log.e("$TAG Secure state unavailable; managed configuration will not be applied: $error")
            null
        }
    }

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
        val snapshot = readSnapshot() ?: return
        synchronized(lock) {
            applyXmlTransition(config, snapshot)
            currentSnapshotSignature = snapshot.signature
        }
    }

    fun applyBeforeCoreStart(core: Core) {
        val snapshot = readSnapshot() ?: return
        synchronized(lock) {
            if (currentSnapshotSignature != snapshot.signature) {
                applyXmlTransition(core.config, snapshot)
            }
            applyCoreTransition(core, snapshot)
            currentSnapshotSignature = snapshot.signature
        }
    }

    fun onForeground() {
        refreshFromRestrictions()
        if (receiverRegistered) return
        synchronized(lock) {
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
        synchronized(lock) {
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
        val snapshot = readSnapshot() ?: return
        if (snapshot.signature == currentSnapshotSignature) return
        val context = coreContext ?: return
        context.postOnCoreThreadWhenAvailableForHeavyTask(
            { core ->
                synchronized(lock) {
                    if (snapshot.signature == currentSnapshotSignature) return@synchronized
                    val xmlChanged = applyXmlTransition(core.config, snapshot)
                    val coreChanged = applyCoreTransition(core, snapshot)
                    currentSnapshotSignature = snapshot.signature
                    if ((xmlChanged || coreChanged) && core.globalState == GlobalState.On) {
                        Log.i("$TAG Restarting Core after a managed configuration change")
                        core.stop()
                        val result = core.start()
                        if (result != 0) {
                            Log.e("$TAG Core restart failed with status [$result]")
                        }
                    }
                }
            },
            "apply Mango9 managed configuration",
        )
    }

    private fun readSnapshot(): Mango9ManagedConfigurationSnapshot? {
        val restrictions = restrictionsManager.applicationRestrictions
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

    private fun applyXmlTransition(
        config: Config,
        snapshot: Mango9ManagedConfigurationSnapshot,
    ): Boolean {
        val preferences = securePreferences ?: return false
        val xml = snapshot.xmlConfig
        val lastSignature = preferences.getString(KEY_LAST_XML_SIGNATURE, null)
        val nextSignature = snapshot.xmlSignature
        if (nextSignature != null && nextSignature == lastSignature) return false

        val activeKeys = loadActiveKeys(preferences)
        if (xml == null) {
            if (activeKeys.isEmpty() && lastSignature == null) return false
            val backups = loadBackups(preferences)
            activeKeys.forEach { key -> restoreEntry(config, backups[key], key) }
            config.sync()
            preferences.edit(commit = true) {
                remove(KEY_XML_BACKUPS)
                remove(KEY_XML_ACTIVE_ENTRIES)
                remove(KEY_LAST_XML_SIGNATURE)
            }
            Log.i("$TAG Removed managed XML overlay and restored [${activeKeys.size}] entries")
            return activeKeys.isNotEmpty()
        }

        val nextKeys = try {
            Mango9ManagedConfigXmlParser.entryKeys(xml)
        } catch (error: Exception) {
            Log.e("$TAG Rejected invalid managed xmlConfig: ${error.javaClass.simpleName}")
            return false
        }

        val backups = loadBackups(preferences).toMutableMap()
        nextKeys.forEach { key ->
            if (key !in backups) backups[key] = captureEntry(config, key)
        }
        saveBackups(preferences, backups.values)

        val result = config.loadFromXmlString(xml)
        if (result != 0) {
            Log.e("$TAG Linphone rejected managed xmlConfig with status [$result]")
            return false
        }

        (activeKeys - nextKeys).forEach { key -> restoreEntry(config, backups[key], key) }
        config.sync()
        preferences.edit(commit = true) {
            putStringSet(
                KEY_XML_ACTIVE_ENTRIES,
                nextKeys.mapTo(linkedSetOf(), Mango9ManagedConfigBackupCodec::encodeKey),
            )
            putString(KEY_LAST_XML_SIGNATURE, nextSignature)
            if (nextKeys.isEmpty()) remove(KEY_XML_BACKUPS)
        }
        Log.i("$TAG Applied managed xmlConfig containing [${nextKeys.size}] entries")
        return true
    }

    private fun applyCoreTransition(
        core: Core,
        snapshot: Mango9ManagedConfigurationSnapshot,
    ): Boolean {
        val preferences = securePreferences ?: return false
        val nextSignature = snapshot.coreSignature
        val lastSignature = preferences.getString(KEY_LAST_CORE_SIGNATURE, null)
        val coreWasManaged = preferences.getBoolean(KEY_CORE_ACTIVE, false)

        if (nextSignature == null) {
            if (!coreWasManaged && lastSignature == null) return false
            restoreCoreBaseline(core, preferences)
            clearCoreState(preferences)
            Log.i("$TAG Removed managed rootCa/configUri and restored the prior Core settings")
            return true
        }
        if (nextSignature == lastSignature && coreWasManaged) return false

        val verifiedConfigUri = snapshot.configUri?.let { rawValue ->
            Mango9Configuration.verifiedProvisioningUrl(rawValue)?.toString().also { verified ->
                if (verified == null) Log.w("$TAG Rejected configUri outside the Mango9 allowlist")
            }
        }
        if (snapshot.configUri != null && verifiedConfigUri == null) return false

        if (!coreWasManaged) captureCoreBaseline(core, preferences)
        if (snapshot.rootCa == null) {
            restoreRootCaBaseline(core, preferences)
        } else {
            core.setRootCaData(snapshot.rootCa)
        }
        core.provisioningUri = verifiedConfigUri ?: baselineConfigUri(preferences)
        preferences.edit(commit = true) {
            putBoolean(KEY_CORE_ACTIVE, true)
            putString(KEY_LAST_CORE_SIGNATURE, nextSignature)
        }
        Log.i(
            "$TAG Applied managed Core settings " +
                "[rootCa=${snapshot.rootCa != null}, configUri=${verifiedConfigUri != null}]",
        )
        return true
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
    ): Map<Mango9ManagedConfigEntryKey, Mango9ManagedConfigEntryBackup> =
        preferences.getStringSet(KEY_XML_BACKUPS, emptySet()).orEmpty().mapNotNull { encoded ->
            Mango9ManagedConfigBackupCodec.decode(encoded)
        }.associateBy(Mango9ManagedConfigEntryBackup::entryKey)

    private fun saveBackups(
        preferences: SharedPreferences,
        backups: Collection<Mango9ManagedConfigEntryBackup>,
    ) {
        preferences.edit(commit = true) {
            putStringSet(
                KEY_XML_BACKUPS,
                backups.mapTo(linkedSetOf(), Mango9ManagedConfigBackupCodec::encode),
            )
        }
    }

    private fun loadActiveKeys(preferences: SharedPreferences): Set<Mango9ManagedConfigEntryKey> =
        preferences.getStringSet(KEY_XML_ACTIVE_ENTRIES, emptySet()).orEmpty().mapNotNullTo(linkedSetOf()) {
            Mango9ManagedConfigBackupCodec.decodeKey(it)
        }

    private fun captureCoreBaseline(core: Core, preferences: SharedPreferences) {
        val rootCa = core.rootCa
        val configUri = core.provisioningUri
        preferences.edit(commit = true) {
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
        core.provisioningUri = baselineConfigUri(preferences)
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
        preferences.edit(commit = true) {
            remove(KEY_CORE_ACTIVE)
            remove(KEY_LAST_CORE_SIGNATURE)
            remove(KEY_BASELINE_ROOT_CA_PRESENT)
            remove(KEY_BASELINE_ROOT_CA)
            remove(KEY_BASELINE_CONFIG_URI_PRESENT)
            remove(KEY_BASELINE_CONFIG_URI)
        }
    }
}
