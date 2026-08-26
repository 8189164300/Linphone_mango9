/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.mango9

/** A monotonically increasing token gate used to prevent stale async results from winning. */
internal class Mango9LatestRequestGate {
    private var latest = 0L

    val current: Long get() = latest

    fun next(): Long = ++latest

    fun invalidate() {
        latest++
    }

    fun isLatest(request: Long): Boolean = request == latest
}
