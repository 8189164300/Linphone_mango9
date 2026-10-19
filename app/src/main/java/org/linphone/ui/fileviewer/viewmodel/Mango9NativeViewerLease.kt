// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.ui.fileviewer.viewmodel

import android.os.Bundle
import androidx.lifecycle.ViewModel
import org.linphone.mango9.Mango9NativeFileTransfers

/** Single-use handoff, retained across configuration changes, never restored into another login. */
internal class Mango9NativeViewerLease : ViewModel() {
    private var token: String? = null
    private var transfer: Mango9NativeFileTransfers.Transfer? = null

    fun accept(args: Bundle): Boolean {
        val incoming = args.getString(Mango9NativeFileTransfers.TICKET)
            ?: return !args.getBoolean("isEncrypted", false)
        if (token == null) {
            token = incoming
            transfer = Mango9NativeFileTransfers.take(incoming)
        }
        return token == incoming && transfer?.path == args.getString("path") && isCurrent()
    }

    fun isCurrent() = token == null || transfer?.isCurrent() == true

    fun retainFile() = transfer?.lease?.retain()

    fun release() { transfer?.close(); transfer = null }

    override fun onCleared() { release(); super.onCleared() }
}
