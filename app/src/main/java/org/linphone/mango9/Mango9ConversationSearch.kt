/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

/** Literal, case-insensitive matching preserves UTF-16 offsets used by Android text spans. */
internal object Mango9ConversationSearch {
    fun matchingIds(entries: List<Pair<String, String>>, query: String, checkActive: () -> Unit = {}): List<String> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()
        return buildList {
            entries.forEachIndexed { index, (id, text) ->
                if (index % 128 == 0) checkActive()
                if (text.contains(term, ignoreCase = true)) add(id)
            }
        }
    }

    fun ranges(text: String, query: String): List<IntRange> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()
        return buildList {
            var start = text.indexOf(term, ignoreCase = true)
            while (start >= 0) {
                add(start until start + term.length)
                start = text.indexOf(term, start + term.length, ignoreCase = true)
            }
        }
    }

    fun select(matches: List<String>, current: String?, step: Int = 0): String? {
        if (matches.isEmpty()) return null
        val index = matches.indexOf(current)
        return if (index < 0) matches.last() else matches[Math.floorMod(index + step, matches.size)]
    }
}
