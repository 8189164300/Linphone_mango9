/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.notifications

import android.content.Intent
import android.net.Uri

/** Intent extras do not distinguish PendingIntents; a private, per-call URI does. */
internal object CallNotificationIntent {
    const val OPEN = "open"
    const val ANSWER = "answer"
    const val HANGUP = "hangup"
    const val SPEAKER = "speaker"
    private const val SCHEME = "mango9-call"
    private const val AUTHORITY = "notification"

    data class Target(val token: String, val operation: String) {
        val identity: String get() = "$operation:$token"
    }

    fun target(intent: Intent, token: String?, operation: String): Intent = intent.apply {
        require(!token.isNullOrBlank())
        require(operation in listOf(OPEN, ANSWER, HANGUP, SPEAKER))
        data = Uri.Builder().scheme(SCHEME).authority(AUTHORITY)
            .appendPath(operation).appendPath(token).build()
    }

    fun read(intent: Intent): Target? {
        val uri = intent.data ?: return null
        if (uri.scheme != SCHEME || uri.authority != AUTHORITY ||
            uri.query != null || uri.fragment != null
        ) {
            return null
        }
        val parts = uri.pathSegments
        if (parts.size != 2 || parts[1].isBlank() ||
            parts[0] !in listOf(OPEN, ANSWER, HANGUP, SPEAKER)
        ) {
            return null
        }
        return Target(parts[1], parts[0])
    }

    fun consumeActivityTarget(intent: Intent, restoredIdentity: String? = null): Target? {
        val target = read(intent) ?: return null
        if (target.operation != OPEN && target.operation != ANSWER) return null
        intent.data = null
        // Restoring a handled action is not another tap; a NEW target still must be handled.
        return target.takeUnless { it.identity == restoredIdentity }
    }
}
