// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Source-thread paging. Source positions include skipped contents; publication never drives the cursor. */
internal class Mango9ContentPager<C, T : Any>(
    private val pageSize: Int,
    private val post: (() -> Unit) -> Unit,
    private val current: () -> Boolean,
    private val release: (T) -> Unit,
    private val publish: (Snapshot<T>) -> Unit,
) {
    data class Snapshot<T>(val generation: Long, val items: List<T>, val loading: Boolean, val exhausted: Boolean, val failed: Boolean)

    private val closed = AtomicBoolean(false)
    private val owned = ConcurrentHashMap.newKeySet<T>()

    @Volatile var generation = 0L
        private set
    private var total = 0
    private var offset = 0
    private var loading = false
    private var failed = false
    private var target = 0
    private var batch = emptyList<C>()
    private var batchPosition = 0
    private val rows = arrayListOf<T>()
    private var fetch: ((Int, Int) -> List<C>)? = null
    private var transform: ((C) -> T?)? = null

    init { require(pageSize > 0) }

    fun reset(count: Int, fetch: (Int, Int) -> List<C>, transform: (C) -> T?) {
        if (!allowed()) return
        generation++; owned.toList().forEach(::discard); rows.clear(); batch = emptyList(); batchPosition = 0
        total = count.coerceAtLeast(0); offset = 0; loading = false; failed = false
        this.fetch = fetch; this.transform = transform
        more()
    }

    fun more() {
        if (!allowed() || loading) return
        if (offset >= total) { emit(); return }
        loading = true; failed = false; target = rows.size + pageSize; emit()
        val request = generation
        post { step(request) }
    }

    private fun allowed() = !closed.get() && current()

    private fun step(request: Long) {
        if (generation != request || !allowed()) return
        try {
            if (offset >= total || rows.size >= target) { finish(); return }
            if (batchPosition >= batch.size) {
                val fetched = requireNotNull(fetch)(offset, minOf(total, offset + pageSize))
                // A source mutation may restart paging while the old query is executing.
                if (generation != request || !allowed()) return
                batch = fetched
                batchPosition = 0
                if (batch.isEmpty()) { offset = total; finish(); return }
            }
            // One metadata record per queue turn; SIP callbacks can run between records.
            val row = requireNotNull(transform)(batch[batchPosition])
            if (row != null) {
                owned.add(row)
                if (!allowed() || generation != request) { discard(row); return }
                rows.add(row)
            }
            offset++; batchPosition++
            post { step(request) }
        } catch (_: Exception) {
            if (generation == request && allowed()) { failed = true; finish() }
        }
    }

    private fun finish() { loading = false; emit() }

    private fun emit() { if (allowed()) publish(Snapshot(generation, rows.toList(), loading, offset >= total, failed)) }

    private fun discard(row: T) { if (owned.remove(row)) release(row) }

    /** May be called from UI while an export is running; the late export disposes itself. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        owned.toList().forEach(::discard)
    }
}
