/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async

/**
 * Reserves wire order synchronously, before dispatching onto the application-owned scope.
 * An old POST finishes before logout's DELETE; a new login's POST follows that DELETE.
 * Different accounts do not wait for one another. Cancelling a screen waiting for a result
 * must not cancel cleanup or release the queue while an HTTP call is still running.
 */
internal class Mango9PushOperationQueue<K>(private val scope: CoroutineScope) {
    private val lock = Any()
    private val tails = mutableMapOf<K, Job>()

    fun <T> enqueue(identity: K, action: suspend () -> T): Result<T> = synchronized(lock) {
        val previous = tails[identity]
        val operation = scope.async(start = CoroutineStart.LAZY) {
            previous?.join()
            action()
        }
        tails[identity] = operation
        operation.invokeOnCompletion {
            synchronized(lock) {
                if (tails[identity] === operation) tails.remove(identity)
            }
        }
        operation.start()
        Result(operation)
    }

    class Result<T> internal constructor(private val operation: Deferred<T>) {
        suspend fun await(): T = operation.await()
    }
}
