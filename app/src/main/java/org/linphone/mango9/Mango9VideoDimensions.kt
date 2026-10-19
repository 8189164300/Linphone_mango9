// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

internal object Mango9VideoDimensions {
    data class Size(val width: Int, val height: Int)

    /** Bound both display axes and align both, including tall screen recordings. */
    fun fit(width: Int, height: Int, rotation: Int = 0, longEdge: Int = 1280, shortEdge: Int = 720): Size {
        require(width > 0 && height > 0)
        val rotated = rotation in setOf(90, 270)
        val displayWidth = if (rotated) height else width
        val displayHeight = if (rotated) width else height
        val scale =
            minOf(1.0, longEdge.toDouble() / maxOf(displayWidth, displayHeight), shortEdge.toDouble() / minOf(displayWidth, displayHeight))

        fun aligned(value: Int): Int = (value * scale + 0.000001).toInt().coerceAtLeast(2).let { it - it % 2 }
        return Size(aligned(displayWidth), aligned(displayHeight))
    }
}
