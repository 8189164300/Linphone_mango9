/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import java.text.Normalizer
import java.util.Locale

object Mango9ContactSearchLimits {
    // SDK search results and their JNI wrappers are allocated before UI paging.
    // Keep each query bounded; refining a query still searches the full directory.
    const val SDK_RESULTS = 2_000
}

/** Keep the entire search directory, but construct/publish only the revealed rows. */
class Mango9ContactPage<T>(private val size: Int = 100) {
    private var values: List<T> = emptyList()
    var limit: Int = 0
        private set
    val total: Int get() = values.size

    fun replace(snapshot: List<T>): List<T> {
        values = snapshot.toList()
        limit = minOf(size.coerceAtLeast(1), values.size)
        return values.subList(0, limit)
    }

    fun reveal(near: Int): List<T> {
        if (near < maxOf(0, limit - 10) || limit >= values.size) return emptyList()
        val old = limit
        limit = minOf(values.size, limit + size.coerceAtLeast(1))
        return values.subList(old, limit)
    }

    fun clear() { values = emptyList(); limit = 0 }
}

object Mango9ContactHeading {
    fun of(name: String?): String {
        val normalized = Normalizer.normalize(name.orEmpty().trim(), Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "").uppercase(Locale.getDefault())
        if (normalized.isEmpty()) return "#"
        val point = normalized.codePointAt(0)
        return if (Character.isLetter(point)) String(Character.toChars(point)) else "#"
    }
}
