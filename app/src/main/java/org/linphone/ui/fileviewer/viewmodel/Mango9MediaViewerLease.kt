/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.ui.fileviewer.viewmodel

import android.os.Bundle
import androidx.lifecycle.ViewModel
import org.linphone.mango9.Mango9MediaAsset
import org.linphone.mango9.Mango9MediaCache
import org.linphone.mango9.Mango9SessionLease

/** Keep the pinned file through rotation, but never restore a ticket into a different login. */
internal class Mango9MediaViewerLease : ViewModel() {
    private var asset: Mango9MediaAsset? = null

    fun accept(args: Bundle, session: Mango9SessionLease?): Boolean {
        if (session == null) {
            Mango9MediaCache.takeViewer(args, null)?.close()
            release()
            return false
        }
        if (asset == null) asset = Mango9MediaCache.takeViewer(args, session)
        return asset?.file?.absolutePath == args.getString("path")
    }

    fun release() { asset?.close(); asset = null }

    override fun onCleared() { release(); super.onCleared() }
}
