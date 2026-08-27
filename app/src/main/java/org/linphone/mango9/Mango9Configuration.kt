/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.mango9

import java.net.URI
import java.net.URL

object Mango9Configuration {
    const val PROVISIONING_HOST = "provision.mango9.com"
    const val PROVISIONING_BASE_URL = "https://$PROVISIONING_HOST"
    const val SIP_PROXY_HOST = "proxy.mango9.com"
    const val SIP_PROXY_URI = "sip:$SIP_PROXY_HOST;transport=tls"
    const val MOBILE_REGISTRATION_EXPIRES_SECONDS = 30 * 24 * 60 * 60

    fun verifiedProvisioningUrl(rawValue: String?): URL? {
        val uri = verifiedHttpsUri(rawValue, requiredHost = PROVISIONING_HOST) ?: return null
        return uri.toURL()
    }

    fun verifiedHttpsUrl(rawValue: String?): URL? {
        return verifiedHttpsUri(rawValue, requiredHost = null)?.toURL()
    }

    private fun verifiedHttpsUri(rawValue: String?, requiredHost: String?): URI? {
        val value = rawValue?.trim().orEmpty()
        if (value.isEmpty()) return null

        val uri = try {
            URI(value)
        } catch (_: Exception) {
            return null
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.host.isNullOrBlank()) return null
        if (requiredHost != null && !uri.host.equals(requiredHost, ignoreCase = true)) return null
        if (uri.userInfo != null) return null
        if (uri.port != -1 && uri.port != 443) return null

        return try {
            val host = uri.host.lowercase().let { value ->
                if (value.contains(':') && !value.startsWith('[')) "[$value]" else value
            }
            val port = uri.port.takeIf { it >= 0 }?.let { ":$it" }.orEmpty()
            val path = uri.rawPath.ifBlank { "/" }
            val query = uri.rawQuery?.let { "?$it" }.orEmpty()
            URI("https://$host$port$path$query").normalize()
        } catch (_: Exception) {
            null
        }
    }
}
