/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import java.io.Closeable

/** A private draft file must outlive both the draft and a queued SDK handoff. */
internal class Mango9DraftFileLease private constructor(private val owner: Owner) : Closeable {
    constructor(delete: () -> Unit) : this(Owner(delete))

    private var closed = false

    @Synchronized fun retain(): Mango9DraftFileLease {
        check(!closed)
        owner.retain()
        return Mango9DraftFileLease(owner)
    }

    /** The SDK keeps this durable path for asynchronous upload, retry and sent history. */
    @Synchronized fun keepForHistory() {
        check(!closed)
        owner.keep()
    }

    @Synchronized override fun close() {
        if (!closed) { closed = true; owner.release() }
    }

    private class Owner(private val delete: () -> Unit) {
        private var references = 1
        private var retainedBySdk = false

        @Synchronized fun retain() { check(references > 0); references++ }

        @Synchronized fun keep() { check(references > 0); retainedBySdk = true }

        fun release() {
            val remove = synchronized(this) {
                check(references > 0)
                --references == 0 && !retainedBySdk
            }
            if (remove) delete()
        }
    }
}
