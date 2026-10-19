// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

/** Provider/server types take precedence over filename hints, but never become raw HTTP headers. */
internal object Mango9MediaType {
    private val type = Regex("[a-z0-9!#$&^_.+\\-]+/[a-z0-9!#$&^_.+\\-]+")

    fun normalized(value: String?): String? = value?.substringBefore(';')?.trim()?.lowercase()
        ?.takeIf { type.matches(it) }

    fun explicit(value: String?): String? = normalized(value)?.takeUnless { it == "application/octet-stream" }

    // Keep the existing M4A upload contract, including lossless and multiple program tracks.
    // This does not claim that every receiving device has an ALAC decoder.
    fun canPreserveMp4Audio(tracks: List<String>): Boolean = tracks.isNotEmpty() && tracks.all {
        it == "audio/mp4a-latm" || it == "audio/alac"
    }

    fun fromExtension(extension: String): String = when (extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "heic", "heif" -> "image/heif"
        "avif" -> "image/avif"
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "3gp", "3gpp" -> "video/3gpp"
        "avi" -> "video/x-msvideo"
        "m4a", "m4b" -> "audio/mp4"
        "mka" -> "audio/x-matroska"
        "mp3" -> "audio/mpeg"
        "aac" -> "audio/aac"
        "ogg", "oga", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }
}
