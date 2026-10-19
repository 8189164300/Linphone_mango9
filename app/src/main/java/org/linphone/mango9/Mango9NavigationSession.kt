/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import android.os.Bundle
import java.util.UUID

/** Route arguments contain only an identity and generation numbers, never session credentials. */
object Mango9NavigationSession {
    private val process = UUID.randomUUID().toString()
    private const val PROCESS = "mango9_route_process"
    private const val IDENTITY = "mango9_route_identity"
    private const val LOGIN = "mango9_route_login"
    private const val SELECTION = "mango9_route_selection"

    fun bind(arguments: Bundle, lease: Mango9SessionLease?) {
        arguments.putString(PROCESS, process)
        arguments.putString(IDENTITY, lease?.session?.sipIdentity)
        arguments.putLong(LOGIN, lease?.loginGeneration ?: -1L)
        arguments.putLong(SELECTION, lease?.selectionGeneration ?: -1L)
    }

    /** Bind legacy/local entry points once; restored routes must never silently retarget an ID. */
    fun resolve(arguments: Bundle, sessions: Mango9SessionStore): Mango9SessionLease? {
        val current = sessions.captureSession()
        if (!arguments.containsKey(PROCESS)) bind(arguments, current)
        return matching(arguments, current)
    }

    fun matching(arguments: Bundle, current: Mango9SessionLease?): Mango9SessionLease? =
        current?.takeIf {
            arguments.getString(PROCESS) == process &&
                arguments.getString(IDENTITY) == it.session.sipIdentity &&
                arguments.getLong(LOGIN) == it.loginGeneration &&
                arguments.getLong(SELECTION) == it.selectionGeneration
        }
}
