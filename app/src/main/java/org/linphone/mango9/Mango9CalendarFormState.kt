/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import android.os.Bundle
import kotlin.math.roundToInt

/** Host-owned presentation values only: never retains a dialog, context or callback.
 * Native widget snapshots survive configuration changes, not process death.
 * Confirmation prompts are deliberately excluded: resuming must never authorize a write.
 */
internal class Mango9CalendarFormState {
    var scrollY = 0
    var inputTag: String? = null
    var selectionStart = 0
    var selectionEnd = 0
    var child: Child? = null
    private var width = 0
    private var contentHeight = 0
    private var fontScale = 0f
    internal var anchor: Anchor? = null
        private set

    data class Region(val tag: String, val top: Int, val bottom: Int)

    data class Anchor(val tag: String, val fraction: Float)

    fun captureScroll(position: Int, width: Int, height: Int, scale: Float, regions: List<Region>) {
        scrollY = position.coerceAtLeast(0)
        this.width = width; contentHeight = height; fontScale = scale
        val valid = regions.filter { it.bottom > it.top && it.bottom > scrollY }
        val region = valid.filter { it.top <= scrollY }.minByOrNull { it.bottom - it.top }
            ?: valid.minByOrNull { it.top }
        anchor = region?.let { Anchor(it.tag, (scrollY - it.top).toFloat() / (it.bottom - it.top)) }
    }

    fun restoredScroll(width: Int, height: Int, scale: Float, regions: List<Region>): Int {
        if (this.width == width && contentHeight == height && fontScale == scale) return scrollY
        val saved = anchor ?: return scrollY
        val region = regions.firstOrNull { it.tag == saved.tag && it.bottom > it.top } ?: return scrollY
        return (region.top + saved.fraction * (region.bottom - region.top)).roundToInt().coerceAtLeast(0)
    }

    class Child(val action: String, val stage: String, val values: Bundle = Bundle()) {
        var snapshot: Bundle? = null
        val form = Mango9CalendarFormState()
    }

    fun clear() {
        scrollY = 0; inputTag = null; child = null; anchor = null
        width = 0; contentHeight = 0; fontScale = 0f
    }
}
