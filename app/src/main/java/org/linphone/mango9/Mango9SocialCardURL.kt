// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import java.net.URI
import java.net.URLDecoder

object Mango9SocialCardURL {
    fun safe(value: String?): String? = runCatching {
        if (value == null || value.length > 8192 || value.any { it.code <= 32 || it.code == 127 || it == '\\' }) return null
        val uri = URI(value); val host = uri.host?.lowercase() ?: return null
        if (uri.scheme != "https" || uri.userInfo != null || uri.port != -1 || '.' !in host || host.endsWith(".local") || host.endsWith(".localhost") || host.matches(Regex("[0-9]+(\\.[0-9]+){3}"))) return null
        if (uri.rawQuery.orEmpty().split('&').any {
            URLDecoder.decode(it.substringBefore('='), "UTF-8").lowercase() in setOf("access_token", "api_key", "apikey", "authorization", "token")
        }
        ) {
            return null
        }
        value
    }.getOrNull()
}
