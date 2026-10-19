// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import java.io.Closeable

/** A decrypted SDK export belongs to all of its consumers, not to either screen. */
class Mango9NativeFileLease private constructor(private val owner: Owner) : Closeable {
    constructor(remove: () -> Unit) : this(Owner(remove))

    private var closed = false

    @Synchronized fun retain(): Mango9NativeFileLease {
        check(!closed)
        owner.retain()
        return Mango9NativeFileLease(owner)
    }

    @Synchronized override fun close() {
        if (!closed) { closed = true; owner.release() }
    }

    private class Owner(private val remove: () -> Unit) {
        private var references = 1

        @Synchronized fun retain() { check(references > 0); references++ }

        fun release() {
            val last = synchronized(this) { check(references > 0); --references == 0 }
            if (last) remove()
        }
    }
}
