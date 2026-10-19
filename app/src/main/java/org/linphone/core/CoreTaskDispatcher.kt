/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.core

/** Retains work until Core and its looper exist, without blocking the submitting thread. */
internal class CoreTaskDispatcher(private val uptimeMillis: () -> Long) {
    private val lock = Any()
    private val waiting = linkedSetOf<Ticket>()
    private var scheduler: ((Runnable, Long) -> Boolean)? = null

    internal inner class Ticket internal constructor(
        val dueAt: Long,
        private var action: (() -> Unit)?,
    ) : Runnable {
        fun cancel() = synchronized(lock) {
            action = null
            waiting.remove(this)
            Unit
        }

        override fun run() {
            val claimed = synchronized(lock) {
                waiting.remove(this)
                action.also { action = null }
            }
            claimed?.invoke()
        }
    }

    fun post(delayMillis: Long = 0, action: () -> Unit): Ticket = synchronized(lock) {
        val now = uptimeMillis()
        val delay = delayMillis.coerceIn(0, Long.MAX_VALUE - now)
        val ticket = Ticket(now + delay, action)
        val target = scheduler
        if (target == null) {
            waiting.add(ticket)
        } else if (!target(ticket, ticket.dueAt)) ticket.cancel()
        ticket
    }

    /** The scheduler must enqueue, not run inline (Handler.postAtTime satisfies this). */
    fun start(postAtTime: (Runnable, Long) -> Boolean) = synchronized(lock) {
        check(scheduler == null) { "Core task dispatcher has already started" }
        scheduler = postAtTime
        val pending = waiting.toList()
        waiting.clear()
        pending.forEach { if (!postAtTime(it, it.dueAt)) it.cancel() }
    }
}
