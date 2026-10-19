/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.core

/** Service callbacks never touch the SDK on main, including stop-before-start races.
 * enqueue must dispatch serially to the Core worker and must not run inline.
 */
internal class CoreServiceSubscription<T : Any>(
    private val enqueue: ((T) -> Unit) -> Unit,
    private val attach: (T) -> Unit,
    private val detach: (T) -> Unit,
) {
    @Volatile var isClosed = false
        private set

    // Owned exclusively by the serial Core queue.
    private var attached: T? = null

    fun start() {
        if (isClosed) return
        enqueue { core ->
            if (!isClosed && attached !== core) {
                attached?.let(detach)
                attached = null
                attach(core)
                attached = core
            }
        }
    }

    // Android invokes service start/destroy on main. Mark closed immediately
    // so already queued notification callbacks cannot republish after teardown.
    fun close() {
        if (isClosed) return
        isClosed = true
        enqueue {
            attached?.let(detach)
            attached = null
        }
    }
}
