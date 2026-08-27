/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.mango9

internal object Mango9CallForwardingPolicy {
    fun normalizedDestination(value: String): String = value.trim()

    fun isValidDestination(value: String): Boolean {
        val destination = normalizedDestination(value)
        return destination.any { it in '0'..'9' } && destination.all {
            it in '0'..'9' || it.isWhitespace() || it in "+()- ."
        }
    }

    fun validatedDestination(enabled: Boolean, value: String): String {
        val destination = normalizedDestination(value)
        if ((enabled || destination.isNotEmpty()) && !isValidDestination(destination)) {
            throw Mango9ApiException.InvalidForwardingDestination
        }
        return destination
    }
}
