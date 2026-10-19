/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.contacts

import java.util.Locale

/** Display-only initials. Never use this to normalize a contact identity or dial address. */
object ContactInitials {
    fun fromName(name: String, limit: Int = 2, locale: Locale = Locale.getDefault()): String {
        if (limit <= 0) return ""
        // Bound work and output even for malformed remote display names. Do not
        // turn a SIP scheme, numeric extension or email domain into a person's initials.
        val bounded = name.take(512).trim()
        val value = when {
            bounded.startsWith("sips:", true) -> bounded.drop(5)
            bounded.startsWith("sip:", true) || bounded.startsWith("tel:", true) -> bounded.drop(4)
            else -> bounded
        }.substringBefore('@').substringBefore(';')
        val result = StringBuilder()
        var index = 0
        var count = 0
        var foundInWord = false
        while (index < value.length && count < limit.coerceAtMost(2)) {
            val codePoint = value.codePointAt(index)
            val width = Character.charCount(codePoint)
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                foundInWord = false
            } else if (!foundInWord && Character.isLetter(codePoint)) {
                val start = index
                index += width
                // Retain combining marks instead of splitting accented initials
                // or UTF-16 surrogate pairs (including non-BMP alphabets).
                while (index < value.length && index - start < 8) {
                    val mark = value.codePointAt(index)
                    if (Character.getType(mark) !in markTypes) break
                    index += Character.charCount(mark)
                }
                result.append(value.substring(start, index).uppercase(locale))
                count++
                foundInWord = true
                continue
            }
            index += width
        }
        return result.toString()
    }

    private val markTypes = setOf(
        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(),
    )
}
