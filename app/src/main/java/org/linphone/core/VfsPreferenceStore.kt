/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.core

import android.content.SharedPreferences

/** Reuses the crypto handle, never a snapshot of its mutable settings. */
internal class VfsPreferenceStore(
    private val rawPreferences: SharedPreferences,
    private val openEncrypted: () -> SharedPreferences?,
) {
    private var encrypted: SharedPreferences? = null

    @Synchronized
    fun isEnabled(): Boolean {
        // AndroidX creates these two keysets even when no setting has ever been
        // written. They cannot contain a vfs_enabled value. Any other entry,
        // including an unknown/future key, must take the normal decryption path.
        // This is only a read: never clear/migrate keys or store a plaintext flag.
        if (encrypted == null && rawPreferences.all.keys.none { it !in KEYSET_KEYS }) return false
        return open()?.getBoolean("vfs_enabled", false) ?: false
    }

    @Synchronized
    fun open(): SharedPreferences? {
        encrypted?.let { return it }
        // A transient keystore failure is not cached as "disabled" or as a
        // permanent failure. The next operation can retry the original store.
        return openEncrypted()?.also { encrypted = it }
    }

    private companion object {
        // Reserved metadata in the pinned AndroidX security-crypto 1.1.0 format.
        // Real encrypted-preference fixtures verify this compatibility boundary.
        val KEYSET_KEYS = setOf(
            "__androidx_security_crypto_encrypted_prefs_key_keyset__",
            "__androidx_security_crypto_encrypted_prefs_value_keyset__",
        )
    }
}
