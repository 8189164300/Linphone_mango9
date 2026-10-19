// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.ui.main.chat.model.FileModel
import java.io.Closeable
import java.util.UUID

/** Pin before navigation, including the interval before the destination Activity exists. */
internal object Mango9NativeFileTransfers {
    const val TICKET = "mango9_native_file_ticket"
    const val MESSAGE = "mango9_native_message_id"
    private val pending = mutableMapOf<String, Transfer>()
    private val handler = Handler(Looper.getMainLooper())

    class Transfer(val path: String, val stamp: Mango9SelectionStamp, val lease: Mango9NativeFileLease?) : Closeable {
        fun isCurrent() = Mango9SessionStore(coreContext.context).selectionStamp() == stamp

        override fun close() { lease?.close() }
    }

    fun bind(args: Bundle, model: FileModel) {
        val token = UUID.randomUUID().toString()
        val transfer =
            Transfer(model.path, model.nativeOwner, model.retainFile())
        synchronized(pending) { pending[token] = transfer }
        args.putString(TICKET, token)
        args.putString(MESSAGE, model.relatedMessageId)
        handler.postDelayed({ take(token)?.close() }, 60_000)
    }

    fun take(token: String): Transfer? = synchronized(pending) { pending.remove(token) }
}
