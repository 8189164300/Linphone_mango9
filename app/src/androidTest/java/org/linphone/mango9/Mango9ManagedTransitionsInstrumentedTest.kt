/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.Config
import org.linphone.core.Core
import org.linphone.core.Factory
import org.linphone.core.GlobalState

/** Real isolated SDK Config and encrypted storage; simulated Core setters/restarts, no network. */
@RunWith(AndroidJUnit4::class)
class Mango9ManagedTransitionsInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun emptyAndKeysetsOnlyStoresDoNotOpenCrypto() = fixture { f ->
        for (legacyKeysets in listOf(false, true)) {
            if (legacyKeysets) assertTrue(f.open().all.isEmpty())
            val before = f.raw.all.toMap()
            val manager = f.manager { fail("Unmanaged install must not open crypto"); null }
            manager.prepareConfiguration(f.config)
            manager.applyBeforeCoreStart(f.core)
            f.refresh(manager)
            assertEquals(before, f.raw.all)
            assertEquals(0, f.xmlLoads)
            assertEquals(0, f.uriWrites)
        }
    }

    @Test fun xmlApplyReplaceAndRemoveRestoreOnlyOwnedEntriesAcrossReopen() = fixture { f ->
        f.seed()
        f.snapshot = xml("one" to "managed-one", "new" to "managed-new")
        val manager = f.manager()
        manager.prepareConfiguration(f.config)
        manager.applyBeforeCoreStart(f.core)
        assertEquals("managed-one", f.value("one"))
        assertEquals("managed-new", f.value("new"))
        assertEquals("untouched", f.value("other"))
        assertEquals(1, f.xmlLoads)
        f.snapshot = xml("two" to "managed-two")
        f.refresh(manager)
        assertEquals("original-one", f.value("one"))
        assertNull(f.value("new"))
        assertEquals("managed-two", f.value("two"))
        // A removed entry may subsequently be changed by the user. Its next baseline is fresh.
        f.native.setString("mango9_fixture", "one", "user-updated")
        f.snapshot = xml("one" to "managed-again")
        f.refresh(manager)
        assertEquals("original-two", f.value("two"))
        f.snapshot = Mango9ManagedConfigurationSnapshot.EMPTY
        f.manager().applyBeforeCoreStart(f.core)
        assertEquals("user-updated", f.value("one"))
        assertTrue(f.native.getOverwriteFlagForEntry("mango9_fixture", "one"))
        assertTrue(f.native.getSkipFlagForEntry("mango9_fixture", "one"))
        assertEquals("untouched", f.value("other"))
        assertTrue(f.open().all.isEmpty())
    }

    @Test fun failedSecureOpenDoesNotAcknowledgeTheSnapshotOrCacheFailure() = fixture { f ->
        f.seed()
        f.snapshot = xml("one" to "managed")
        var unavailable = true
        var attempts = 0
        val manager = f.manager { attempts++; if (unavailable) null else f.open() }
        f.refresh(manager)
        assertEquals("original-one", f.value("one"))
        assertEquals(0, f.xmlLoads)
        val failedAttempts = attempts
        unavailable = false
        f.refresh(manager)
        assertTrue(attempts > failedAttempts)
        assertEquals("managed", f.value("one"))
        val openedAttempts = attempts
        manager.applyBeforeCoreStart(f.core)
        assertEquals(openedAttempts, attempts)
    }

    @Test fun failedBackupCommitPreventsNativeMutationAndCanRetry() = fixture { f ->
        f.seed()
        f.snapshot = xml("one" to "managed")
        var failCommit = true
        val preferences = interceptCommits(f.open()) { editor -> if (failCommit) false else editor.commit() }
        val manager = f.manager { preferences }
        f.refresh(manager)
        assertEquals(0, f.xmlLoads)
        assertEquals("original-one", f.value("one"))
        failCommit = false
        f.refresh(manager)
        assertEquals("managed", f.value("one"))
        f.snapshot = Mango9ManagedConfigurationSnapshot.EMPTY
        f.refresh(manager)
        assertEquals("original-one", f.value("one"))
    }

    @Test fun failedFinalCommitCannotMakeInMemorySignatureSuppressRetry() = fixture { f ->
        f.seed()
        f.snapshot = xml("one" to "managed")
        var commits = 0
        val preferences = interceptCommits(f.open()) { editor ->
            val result = editor.commit()
            if (++commits == 2) false else result // Android may update memory before a disk failure.
        }
        val manager = f.manager { preferences }
        f.refresh(manager)
        assertEquals(1, f.xmlLoads)
        f.refresh(manager)
        assertEquals(2, f.xmlLoads)
        assertEquals("managed", f.value("one"))
        f.snapshot = Mango9ManagedConfigurationSnapshot.EMPTY
        f.manager().applyBeforeCoreStart(f.core)
        assertEquals("original-one", f.value("one"))
    }

    @Test fun rejectedPartialXmlRollsBackAndDoesNotLoseRemovalCoverage() = fixture { f ->
        f.seed()
        f.snapshot = xml("one" to "first")
        val manager = f.manager()
        f.refresh(manager)
        f.snapshot = xml("one" to "second", "new" to "partial")
        f.rejectXml = true
        f.refresh(manager)
        assertEquals("first", f.value("one"))
        assertNull(f.value("new"))
        val attempts = f.xmlLoads
        f.throwXml = true
        f.refresh(manager)
        assertEquals(attempts + 1, f.xmlLoads)
        f.rejectXml = false
        f.snapshot = Mango9ManagedConfigurationSnapshot.EMPTY
        f.manager().applyBeforeCoreStart(f.core)
        assertEquals("original-one", f.value("one"))
        assertNull(f.value("new"))
    }

    @Test fun failedConfigSyncRetainsRollbackJournalAndDoesNotAcknowledgeSuccess() = fixture { f ->
        f.seed()
        f.snapshot = xml("one" to "managed", "new" to "new")
        f.rejectSync = true
        val manager = f.manager()
        f.refresh(manager)
        assertFalse(f.open().contains("xml.last_signature"))
        assertFalse(f.open().getStringSet("xml.active_entries", emptySet())!!.isEmpty())
        f.rejectSync = false
        f.refresh(manager)
        assertEquals(2, f.xmlLoads)
        f.snapshot = Mango9ManagedConfigurationSnapshot.EMPTY
        f.manager().applyBeforeCoreStart(f.core)
        assertEquals("original-one", f.value("one"))
        assertNull(f.value("new"))
    }

    @Test fun incompleteRollbackStateNeverDeletesTheCurrentEntry() = fixture { f ->
        f.seed()
        f.snapshot = xml("one" to "managed")
        val manager = f.manager()
        f.refresh(manager)
        val preferences = f.open()
        val original = preferences.getStringSet("xml.backups", emptySet())!!.toSet()
        assertTrue(preferences.edit().remove("xml.backups").commit())
        f.snapshot = Mango9ManagedConfigurationSnapshot.EMPTY
        f.refresh(manager)
        assertEquals("managed", f.value("one"))
        assertTrue(preferences.contains("xml.active_entries"))
        assertTrue(preferences.edit().putStringSet("xml.backups", original).commit())
        f.refresh(manager)
        assertEquals("original-one", f.value("one"))
    }

    @Test fun queuedRefreshUsesLatestRestrictionsAndPendingDoesNotEraseState() = fixture { f ->
        f.seed()
        val manager = f.manager()
        f.snapshot = xml("one" to "old-queued")
        val queue = mutableListOf<(Core) -> Unit>()
        manager.requestRefresh { queue += it }
        f.snapshot = xml("one" to "latest")
        repeat(5) { manager.requestRefresh { queue += it } }
        assertEquals(1, queue.size)
        queue.removeAt(0)(f.core)
        assertEquals("latest", f.value("one"))
        f.failRead = true
        f.refresh(manager)
        assertEquals("latest", f.value("one"))
        f.failRead = false
        f.snapshot = xml("one" to "not-applied")
        manager.requestRefresh { queue += it }
        f.snapshot = null // Restrictions are being replaced by Android; not an empty policy.
        queue.removeAt(0)(f.core)
        assertEquals("latest", f.value("one"))
        f.snapshot = Mango9ManagedConfigurationSnapshot.EMPTY
        f.refresh(manager)
        assertEquals("original-one", f.value("one"))
    }

    @Test fun coreSetterFailureRetriesWithoutRecapturingTheManagedBaseline() = fixture { f ->
        f.snapshot = Mango9ManagedConfigurationSnapshot(null, "fixture-certificate", "https://provision.mango9.com/v1/device")
        f.rejectUri = true
        val manager = f.manager()
        f.refresh(manager)
        assertEquals(0, f.caWrites)
        assertFalse(f.open().contains("core.last_signature"))
        f.rejectUri = false
        f.refresh(manager)
        assertEquals(1, f.caWrites)
        assertEquals(f.snapshot!!.configUri, f.uri)
        f.snapshot = Mango9ManagedConfigurationSnapshot.EMPTY
        f.manager().applyBeforeCoreStart(f.core)
        assertEquals("https://provision.mango9.com/v1/original", f.uri)
        assertEquals("fixture-original-ca.pem", f.caPath)
        assertTrue(f.open().all.isEmpty())
    }

    @Test fun runtimeRootCaIsReappliedToANewCoreButNotRepeatedOnTheSameCore() = fixture { f ->
        f.snapshot = Mango9ManagedConfigurationSnapshot(null, "fixture-certificate", null)
        val manager = f.manager()
        manager.prepareConfiguration(f.config)
        assertEquals(0, f.caWrites)
        manager.applyBeforeCoreStart(f.core)
        assertEquals(1, f.caWrites)
        manager.applyBeforeCoreStart(f.core)
        assertEquals(1, f.caWrites)
        manager.applyBeforeCoreStart(f.newCore())
        assertEquals(2, f.caWrites)
        f.snapshot = Mango9ManagedConfigurationSnapshot(null, "do-not-apply", "https://invalid.example/device")
        f.refresh(manager)
        assertEquals(2, f.caWrites)
    }

    @Test fun failedRestartRemainsPendingAfterTransitionSignaturesArePersisted() = fixture { f ->
        f.snapshot = xml("one" to "managed")
        f.state = GlobalState.On
        f.rejectStart = true
        val manager = f.manager()
        f.refresh(manager)
        assertEquals(1, f.stops)
        assertEquals(1, f.starts)
        assertEquals(GlobalState.Off, f.state)
        f.rejectStart = false
        f.refresh(manager)
        assertEquals(1, f.stops)
        assertEquals(2, f.starts)
        assertEquals(GlobalState.On, f.state)
        assertEquals(1, f.xmlLoads)
        f.refresh(manager)
        assertEquals(2, f.starts)
        assertEquals(1, f.xmlLoads)
    }

    @Test fun refreshDoesNotBlockMainBehindCryptoAndPreservesChangesArrivingDuringApply() = fixture { f ->
        f.snapshot = xml("one" to "first")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CompletableFuture<Unit>()
        var first = true
        val manager = f.manager {
            if (first) {
                first = false
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
            f.open()
        }
        val queue = CopyOnWriteArrayList<(Core) -> Unit>()
        manager.requestRefresh { queue += it }
        val action = queue.removeAt(0)
        val worker = Thread {
            try { action(f.core); finished.complete(Unit) } catch (error: Throwable) { finished.completeExceptionally(error) }
        }
        worker.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            f.snapshot = xml("one" to "latest")
            val mainReturned = CountDownLatch(1)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                manager.requestRefresh { queue += it }
                mainReturned.countDown()
            }
            assertTrue("Refresh blocked the main looper behind secure storage", mainReturned.await(2, TimeUnit.SECONDS))
        } finally { release.countDown() }
        finished.get(15, TimeUnit.SECONDS)
        worker.join(1000)
        assertEquals(1, queue.size)
        queue.removeAt(0)(f.core)
        assertEquals("latest", f.value("one"))
    }

    private fun xml(vararg entries: Pair<String, String>) = Mango9ManagedConfigurationSnapshot(
        "<config xmlns=\"http://www.linphone.org/xsds/lpconfig.xsd\"><section name=\"mango9_fixture\">" +
            entries.joinToString("") { (key, value) -> "<entry name=\"$key\" overwrite=\"true\">$value</entry>" } +
            "</section></config>",
                null, null,
    )

    private inner class Fixture {
        val name = "mango9-managed-test-${UUID.randomUUID()}"
        val alias = "$name-key"
        val raw = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val file = context.cacheDir.resolve("$name.rc")
        val native = Factory.instance().createConfig(file.absolutePath)

        @Volatile var snapshot: Mango9ManagedConfigurationSnapshot? = Mango9ManagedConfigurationSnapshot.EMPTY
        var failRead = false
        var xmlLoads = 0
        var rejectXml = false
        var throwXml = false
        var rejectSync = false
        var rejectUri = false
        var rejectStart = false
        var state = GlobalState.Ready
        var uri: String? = "https://provision.mango9.com/v1/original"
        var caPath = "fixture-original-ca.pem"
        var uriWrites = 0
        var caWrites = 0
        var starts = 0
        var stops = 0
        val config = Proxy.newProxyInstance(Config::class.java.classLoader, arrayOf(Config::class.java)) { _, method, args ->
            if (method.name == "loadFromXmlString") {
                xmlLoads++
                if (rejectXml) {
                    native.setString("mango9_fixture", "one", "partial")
                    native.setString("mango9_fixture", "new", "partial")
                    if (throwXml) throw IllegalArgumentException("Fixture partial SDK error")
                    return@newProxyInstance -1
                }
            }
            if (method.name == "sync" && rejectSync) return@newProxyInstance -1
            try {
                method.invoke(native, *(args ?: emptyArray()))
            } catch (error: InvocationTargetException) { throw error.targetException }
        } as Config
        val core = newCore()

        fun newCore(): Core = Proxy.newProxyInstance(Core::class.java.classLoader, arrayOf(Core::class.java)) { _, method, args ->
            when (method.name) {
                "getConfig" -> config
                "getGlobalState" -> state
                "getProvisioningUri" -> uri
                "getRootCa" -> caPath
                "setProvisioningUri" -> {
                    uriWrites++; if (rejectUri) {
                    -1
                } else { uri = args!![0] as String?; 0 }
                }
                "setRootCa" -> { caPath = args!![0] as String; null }
                "setRootCaData" -> { caWrites++; null }
                "start" -> {
                    starts++; if (rejectStart) {
                    -1
                } else { state = GlobalState.On; 0 }
                }
                "stop" -> { stops++; state = GlobalState.Off; null }
                else -> error("Unexpected Core method: ${method.name}")
            }
        } as Core

        fun seed() {
            native.setString("mango9_fixture", "one", "original-one")
            native.setOverwriteFlagForEntry("mango9_fixture", "one", true)
            native.setSkipFlagForEntry("mango9_fixture", "one", true)
            native.setString("mango9_fixture", "two", "original-two")
            native.setString("mango9_fixture", "other", "untouched")
            assertEquals(0, native.sync())
        }

        fun value(key: String): String? = native.getString("mango9_fixture", key, null)

        fun open(): SharedPreferences = EncryptedSharedPreferences.create(
            context, name, MasterKey.Builder(context, alias).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

        fun manager(open: () -> SharedPreferences? = { this.open() }) = Mango9ManagedConfigurationManager(
            context, Mango9ManagedPreferenceStore(raw, open), {
                if (failRead) throw IllegalStateException("Fixture restrictions unavailable")
                snapshot
            },
        )

        fun refresh(manager: Mango9ManagedConfigurationManager) = manager.requestRefresh { it(core) }
    }

    private fun interceptCommits(
        preferences: SharedPreferences,
        commit: (SharedPreferences.Editor) -> Boolean,
    ): SharedPreferences = object : SharedPreferences by preferences {
        override fun edit(): SharedPreferences.Editor {
            val editor = preferences.edit()
            return object : SharedPreferences.Editor by editor {
                override fun commit(): Boolean = commit(editor)
            }
        }
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val done = CompletableFuture<Unit>()
        coreContext.postOnCoreThread {
            try {
                val fixture = Fixture()
                try { block(fixture) } finally {
                    context.deleteSharedPreferences(fixture.name)
                    KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(fixture.alias) }
                    fixture.file.delete()
                }
                done.complete(Unit)
            } catch (error: Throwable) { done.completeExceptionally(error) }
        }
        done.get(45, TimeUnit.SECONDS)
    }
}
