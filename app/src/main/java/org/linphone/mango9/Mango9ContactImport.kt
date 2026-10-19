/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import java.util.concurrent.atomic.AtomicLong

/** A newer read/reset retires all queued work from an earlier snapshot. */
class Mango9ContactImportGeneration {
    private val value = AtomicLong()

    fun begin(): Long = value.incrementAndGet()

    fun isCurrent(token: Long): Boolean = value.get() == token

    fun cancel(token: Long) { value.compareAndSet(token, token + 1) }
}

/** Schedule one bounded SDK batch at a time. Publish only a complete snapshot. */
object Mango9ContactBatchImport {
    const val BATCH_SIZE = 64

    fun <T, R> run(
        values: List<T>,
        schedule: (() -> Unit) -> Unit,
        isCurrent: () -> Boolean,
        convert: (T) -> R,
        commit: (List<R>) -> Unit,
        failed: (Exception) -> Unit,
        batchSize: Int = BATCH_SIZE,
    ) {
        val converted = ArrayList<R>(values.size)
        var offset = 0

        fun advance() {
            schedule {
                if (!isCurrent()) return@schedule
                try {
                    val end = minOf(values.size, offset + batchSize.coerceAtLeast(1))
                    while (offset < end) converted.add(convert(values[offset++]))
                    if (!isCurrent()) return@schedule
                    if (offset == values.size) commit(converted) else advance()
                } catch (error: Exception) {
                    if (isCurrent()) failed(error)
                }
            }
        }
        advance()
    }
}
