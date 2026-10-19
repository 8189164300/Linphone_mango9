// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

/** A local file must advance while playing; retain a real position and permit one decoder recovery. */
internal class Mango9PlaybackProgress(private val stallMillis: Long = 3_000) {
    var resumePosition = 0
        private set
    private var previousNativePosition = 0
    private var lastProgressAt = 0L
    private var recoveryUsed = false

    fun resetForUserAction(position: Int) {
        resumePosition = position.coerceAtLeast(0)
        recoveryUsed = false
    }

    fun start(now: Long, nativePosition: Int) {
        previousNativePosition = nativePosition.coerceAtLeast(0)
        lastProgressAt = now
    }

    fun sample(now: Long, nativePosition: Int): Int {
        val current = nativePosition.coerceAtLeast(0)
        if (current > previousNativePosition) {
            // A decoder that resets to zero must not erase the requested seek or
            // last good bookmark. Explicit seeks reset this anchor separately.
            resumePosition = maxOf(resumePosition, current)
            lastProgressAt = now
        }
        previousNativePosition = current
        return resumePosition
    }

    fun isStalled(now: Long): Boolean = now - lastProgressAt >= stallMillis

    fun claimRecovery(): Boolean {
        if (recoveryUsed) return false
        recoveryUsed = true
        return true
    }
}
