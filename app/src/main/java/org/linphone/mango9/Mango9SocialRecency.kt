// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Shares confirmed activity between the separate inbox and conversation view models. */
internal object Mango9SocialRecency {
    data class Snapshot(val lease: Mango9SessionLease? = null, val threads: Map<String, Mango9SocialThread> = emptyMap())

    private val mutable = MutableStateFlow(Snapshot())
    val state = mutable.asStateFlow()

    fun record(lease: Mango9SessionLease, thread: Mango9SocialThread) {
        mutable.update { old ->
            val rows = if (old.lease == lease) old.threads else emptyMap()
            val latest = Mango9ConversationRecency.socialSnapshot(thread, rows[thread.id])
            Snapshot(lease, (rows - thread.id + (thread.id to latest)).entries.toList().takeLast(100).associate { it.toPair() })
        }
    }

    fun overlay(lease: Mango9SessionLease, rows: List<Mango9SocialThread>): List<Mango9SocialThread> {
        val observed = state.value.takeIf { it.lease == lease }?.threads.orEmpty()
        return rows.map { Mango9ConversationRecency.socialSnapshot(it, observed[it.id]) }
            .sortedWith(compareByDescending<Mango9SocialThread> { Mango9ConversationRecency.time(it.activityAt) }.thenBy { it.id })
    }
}
