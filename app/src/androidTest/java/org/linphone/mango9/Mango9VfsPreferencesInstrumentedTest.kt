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
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.core.VfsPreferenceStore

/** Separate, disposable encrypted stores; never enables VFS or changes the app's keys. */
@RunWith(AndroidJUnit4::class)
class Mango9VfsPreferencesInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun freshInstallDoesNotCreateEncryptionKeysToReadAnAbsentFlag() = fixture { f ->
        val store = VfsPreferenceStore(f.raw) { fail("Unnecessary crypto initialization"); null }
        repeat(10) { assertFalse(store.isEnabled()) }
        assertTrue(f.raw.all.isEmpty())
        assertFalse(keyStore().containsAlias(f.alias))
    }

    @Test fun legacyKeysetsWithoutValuesUseTheSameEmptyStoreFastPath() = fixture { f ->
        assertTrue(f.open().all.isEmpty())
        assertEquals(2, f.raw.all.size) // Actual pinned AndroidX format, not a fabricated mock.
        val before = f.raw.all.toMap()
        val store = VfsPreferenceStore(f.raw) { fail("Keysets alone cannot enable VFS"); null }
        assertFalse(store.isEnabled())
        assertEquals(before, f.raw.all) // No migration/deletion/plaintext enabled flag.
    }

    @Test fun existingEncryptedEnabledFlagIsReadAndPreservedOnReopen() = fixture { f ->
        assertTrue(f.open().edit().putBoolean("vfs_enabled", true).putString("vfskey", "fixture-encrypted-token").commit())
        val before = f.raw.all.toMap()
        val opens = AtomicInteger()
        val store = VfsPreferenceStore(f.raw) { opens.incrementAndGet(); f.open() }
        assertTrue(store.isEnabled())
        assertTrue(store.isEnabled())
        assertEquals(1, opens.get())
        assertEquals("fixture-encrypted-token", store.open()!!.getString("vfskey", null))
        assertEquals(before, f.raw.all)
        assertFalse(f.raw.contains("vfs_enabled"))
        assertFalse(f.raw.all.values.contains("fixture-encrypted-token"))
    }

    @Test fun encryptedDisabledValueIsNotMistakenForAnUnusedStore() = fixture { f ->
        assertTrue(f.open().edit().putBoolean("vfs_enabled", false).commit())
        var opens = 0
        val store = VfsPreferenceStore(f.raw) { opens++; f.open() }
        assertFalse(store.isEnabled())
        assertEquals(1, opens)
    }

    @Test fun successfulHandleIsReusedButFlagChangesAreNeverCached() = fixture { f ->
        var opens = 0
        val store = VfsPreferenceStore(f.raw) { opens++; f.open() }
        assertFalse(store.isEnabled())
        val preferences = store.open()!!
        assertTrue(preferences.edit().putBoolean("vfs_enabled", true).commit())
        assertTrue(store.isEnabled())
        assertSame(preferences, store.open())
        assertTrue(preferences.edit().putBoolean("vfs_enabled", false).commit())
        assertFalse(store.isEnabled())
        assertEquals(1, opens)
    }

    @Test fun failedOpenRemainsRetryableWithoutResettingStoredData() = fixture { f ->
        assertTrue(f.open().edit().putBoolean("vfs_enabled", true).commit())
        val before = f.raw.all.toMap()
        var opens = 0
        val store = VfsPreferenceStore(f.raw) { if (++opens == 1) null else f.open() }
        assertNull(store.open())
        assertEquals(before, f.raw.all)
        assertTrue(store.isEnabled())
        assertEquals(2, opens)
        assertEquals(before, f.raw.all)
    }

    @Test fun unknownEntriesCannotSkipDecryptionAndExceptionsAreNotCached() = fixture { f ->
        assertTrue(f.raw.edit().putString("unrecognized-format", "fixture").commit())
        var attempts = 0
        val store = VfsPreferenceStore(f.raw) { attempts++; throw SecurityException("fixture key unavailable") }
        repeat(2) { assertThrows(SecurityException::class.java) { store.isEnabled() } }
        assertEquals(2, attempts)
        assertEquals("fixture", f.raw.getString("unrecognized-format", null))
    }

    @Test fun concurrentReadersAndEnablingShareOneCryptoHandle() = fixture { f ->
        val opens = AtomicInteger()
        val store = VfsPreferenceStore(f.raw) { opens.incrementAndGet(); f.open() }
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(6)
        try {
            val results = (1..6).map {
                executor.submit<SharedPreferences> {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    store.open()!!
                }
            }
            start.countDown()
            val handles = results.map { it.get(15, TimeUnit.SECONDS) }
            handles.forEach { assertSame(handles.first(), it) }
            assertEquals(1, opens.get())
            assertTrue(handles.first().edit().putBoolean("vfs_enabled", true).commit())
            assertTrue(store.isEnabled())
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    private inner class Fixture {
        val name = "mango9-vfs-test-${UUID.randomUUID()}"
        val alias = "$name-key"
        val raw = context.getSharedPreferences(name, Context.MODE_PRIVATE)

        fun open(): SharedPreferences = EncryptedSharedPreferences.create(
            context, name,
            MasterKey.Builder(context, alias).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val fixture = Fixture()
        try { block(fixture) } finally {
            check(context.deleteSharedPreferences(fixture.name))
            keyStore().deleteEntry(fixture.alias)
        }
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}
