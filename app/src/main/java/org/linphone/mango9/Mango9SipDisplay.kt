/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.mango9

internal object Mango9SipDisplay {
    fun friendlyUsername(username: String?, uri: String): String {
        val value = username?.trim().orEmpty()
        if (value.isNotEmpty()) return value

        val fallback = uri.trim().removePrefixIgnoringCase("sip:")
        return fallback.substringBefore('@').substringBefore(';').trim()
    }

    private fun String.removePrefixIgnoringCase(prefix: String): String =
        if (startsWith(prefix, ignoreCase = true)) drop(prefix.length) else this
}
