/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.mango9

internal object Mango9AccountCompany {
    fun displayName(sessionDisplayName: String?, domain: String?): String {
        val tenant = domain.orEmpty().trim().substringBefore('.').trim()
        val firstSessionNamePart = sessionDisplayName
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.firstOrNull()
            .orEmpty()

        if (
            tenant.isNotEmpty() &&
            firstSessionNamePart.isNotEmpty() &&
            normalizedCompanyToken(firstSessionNamePart) == normalizedCompanyToken(tenant)
        ) {
            return firstSessionNamePart
        }

        val formattedTenant = tenant
            .split(Regex("[-_]+"))
            .filter(String::isNotBlank)
            .joinToString(" ") { part ->
                part.lowercase().replaceFirstChar { character -> character.titlecase() }
            }
        if (formattedTenant.isNotEmpty()) return formattedTenant

        val fallbackDomain = domain.orEmpty().trim()
        return fallbackDomain.ifEmpty { "Mango9 PBX" }
    }

    private fun normalizedCompanyToken(value: String): String =
        value.lowercase().filter(Char::isLetterOrDigit)
}
