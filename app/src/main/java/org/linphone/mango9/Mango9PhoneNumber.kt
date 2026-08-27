/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.mango9

/** Normalizes manually entered and contact-list phone values through one path. */
object Mango9PhoneNumber {
    fun normalized(value: String): String {
        val address = value.trim().let(::unwrapDisplayAddress)
        val phonePart = when (address.substringBefore(':').lowercase()) {
            "sip", "sips" -> address.substringAfter(':').substringBefore('@').substringBefore(';')
            "tel" -> address.substringAfter(':').substringBefore(';')
            else -> address
        }
        val digits = phonePart.filter(Char::isDigit)
        return if (digits.length == 10) "1$digits" else digits
    }

    private fun unwrapDisplayAddress(value: String): String {
        val start = value.indexOf('<')
        if (start < 0) return value
        val end = value.indexOf('>', start + 1)
        return if (end > start + 1) value.substring(start + 1, end).trim() else value
    }
}
