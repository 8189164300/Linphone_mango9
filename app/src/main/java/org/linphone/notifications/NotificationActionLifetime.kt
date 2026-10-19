/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.notifications

/** A broadcast action cannot start late or finish its PendingResult twice. */
internal class NotificationActionLifetime(
    private val elapsedMillis: () -> Long,
    timeoutMillis: Long,
    private val onFinished: () -> Unit,
) {
    private val deadline = elapsedMillis() + timeoutMillis
    private val lock = Any()

    private enum class State { Pending, Running, Finished }

    private var state = State.Pending

    fun run(action: () -> Unit) {
        val canRun = synchronized(lock) {
            if (state != State.Pending) return
            (elapsedMillis() < deadline).also { if (it) state = State.Running }
        }
        try {
            if (canRun) action()
        } finally {
            finish()
        }
    }

    fun finish() {
        val notify = synchronized(lock) {
            if (state == State.Finished) {
                false
            } else { state = State.Finished; true }
        }
        if (notify) onFinished()
    }
}
