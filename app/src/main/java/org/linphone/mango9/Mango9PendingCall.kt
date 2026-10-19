/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import java.util.UUID

/** One user intent, not a queue of calls waiting behind a permission sheet. */
internal class Mango9PendingCall<T> {
    // Saved Activity state can outlive the process. A fresh manager must not issue
    // the old process's ticket 1 and accidentally accept its permission reply.
    private var sequence = UUID.randomUUID().mostSignificantBits
    private var pending: Pair<Long, T>? = null

    @Synchronized fun begin(value: T): Long? {
        if (pending != null) return null
        var id = ++sequence
        if (id == 0L) id = ++sequence // 0 is the Activity saved-state sentinel.
        pending = id to value
        return id
    }

    @Synchronized fun hasPending(): Boolean = pending != null

    @Synchronized fun matches(id: Long): Boolean = pending?.first == id

    @Synchronized fun take(id: Long): T? {
        if (!matches(id)) return null
        return pending?.second.also { pending = null }
    }

    @Synchronized fun clear(): T? = pending?.second.also { pending = null }
}
