// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

/** Public HTTPS profile links allow Android App Links, with the browser as fallback.
 * Do not invent profiles from display names or Facebook page-scoped messaging IDs.
 */
internal object Mango9SocialProfileLink {
    fun url(platform: String, handle: String?): String? {
        if (handle == null || handle.any { it.isISOControl() }) return null
        val value = handle.trim()
        if (!value.startsWith('@')) return null
        val name = value.drop(1)
        val host: String
        val pattern: String
        val reserved: Set<String>
        when (platform) {
            "instagram" -> {
                host = "www.instagram.com"; pattern = "[A-Za-z0-9_.]{1,30}"
                reserved = setOf("accounts", "about", "api", "developer", "developers", "direct", "explore", "legal", "p", "reel", "reels", "stories", "web")
            }
            "facebook" -> {
                host = "www.facebook.com"; pattern = "[A-Za-z0-9.]{5,50}"
                reserved = setOf("about", "ads", "business", "checkpoint", "dialog", "events", "friends", "gaming", "groups", "help", "home", "login", "logout", "marketplace", "messages", "notifications", "pages", "people", "plugins", "policies", "privacy", "profile.php", "recover", "reel", "reels", "search", "settings", "share", "sharer", "sharer.php", "watch")
                if (name.none { it in 'a'..'z' || it in 'A'..'Z' }) return null
            }
            "tiktok" -> { host = "www.tiktok.com"; pattern = "[A-Za-z0-9_.]{1,24}"; reserved = emptySet() }
            else -> return null
        }
        if (!name.matches(Regex(pattern)) || name.startsWith('.') || name.endsWith('.') || name.contains("..") || name.lowercase(java.util.Locale.ROOT) in reserved) return null
        return "https://$host/" + if (platform == "tiktok") "@$name" else "$name/"
    }

    fun url(thread: Mango9SocialThread?, outgoing: Boolean = false): String? = thread?.takeUnless { it.deleted }?.let {
        url(it.platform, if (outgoing) it.accountHandle else it.participantHandle)
    }
}
