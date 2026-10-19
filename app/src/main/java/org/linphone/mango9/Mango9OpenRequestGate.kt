/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

/** A newer navigation intent fences both queued account selection and the eventual UI callback. */
class Mango9OpenRequestGate {
    private var generation = 0L

    @Synchronized fun begin(): Ticket = Ticket(++generation)

    @Synchronized fun cancel() { generation++ }

    inner class Ticket internal constructor(private val version: Long) {
        fun <T> ifCurrent(action: () -> T): T? = synchronized(this@Mango9OpenRequestGate) {
            if (generation == version) action() else null
        }
    }
}
