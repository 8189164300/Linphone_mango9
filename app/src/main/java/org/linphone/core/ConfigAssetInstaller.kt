/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.core

import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStream

/** Installs defaults before native Config opens them; never replaces a user's configuration. */
internal class ConfigAssetInstaller(
    private val directory: File,
    private val openAsset: (String) -> InputStream,
) {
    private companion object {
        // AtomicFile requires caller-owned locking, including across installer instances.
        val lock = Any()
        val packaged = linkedMapOf(
            "linphonerc_factory" to "linphonerc",
            "assistant_linphone_default_values" to "assistant_linphone_default_values",
            "assistant_third_party_default_values" to "assistant_third_party_default_values",
        )
    }

    fun install(packageRevision: String) = synchronized(lock) {
        val userConfig = File(directory, CorePreferences.CONFIG_FILE_NAME)
        if (!recoverExists(userConfig)) writeAsset("linphonerc_default", userConfig)

        val marker = AtomicFile(File(directory, ".mango9-packaged-configs"))
        val previous = try {
            marker.openRead().bufferedReader().use { it.readText() }
        } catch (_: FileNotFoundException) { null }
        val complete = packaged.values.all { recoverExists(File(directory, it)) }
        if (complete && previous == signature(packageRevision)) return@synchronized

        // Publish the marker only after every file commits. An interrupted upgrade
        // is retried as a complete set before the native Config can read it.
        packaged.forEach { (asset, name) -> writeAsset(asset, File(directory, name)) }
        write(marker) { signature(packageRevision).byteInputStream() }
    }

    private fun signature(revision: String): String = buildString {
        appendLine(revision)
        packaged.values.forEach { name ->
            val file = File(directory, name)
            appendLine("$name:${file.length()}:${file.lastModified()}")
        }
    }

    private fun recoverExists(file: File): Boolean = try {
        AtomicFile(file).openRead().use { }
        true
    } catch (error: FileNotFoundException) {
        // Unreadable existing user data is not an invitation to replace it.
        if (file.exists() || File(file.path + ".bak").exists()) throw error
        false
    }

    private fun writeAsset(asset: String, target: File) =
        write(AtomicFile(target)) { openAsset(asset) }

    private fun write(target: AtomicFile, source: () -> InputStream) {
        // Open the asset before touching the destination, then roll back any
        // read/write/close failure. finishWrite syncs and commits the full file.
        val input = source()
        var output: FileOutputStream? = null
        try {
            input.use {
                val stream = target.startWrite()
                output = stream
                it.copyTo(stream)
            }
            target.finishWrite(checkNotNull(output))
        } catch (error: Throwable) {
            output?.let(target::failWrite)
            throw error
        }
    }
}
