/* Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later */
package org.linphone.mango9

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Presentation only: all dates remain stored as the server sent them. */
internal object Mango9TeamMessageLayout {
    private val serverFormats = listOf("yyyy-MM-dd HH:mm:ss.SSS", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss.SSS", "yyyy-MM-dd'T'HH:mm:ss").map(DateTimeFormatter::ofPattern)
    private fun instant(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return runCatching { Instant.parse(value) }.getOrNull() ?: serverFormats.firstNotNullOfOrNull {
            runCatching { LocalDateTime.parse(value, it).atZone(ZoneId.of("UTC")).toInstant() }.getOrNull()
        }
    }
    fun sameDay(value: String, previous: String?, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val current = instant(value) ?: return false
        val prior = instant(previous) ?: return false
        return current.atZone(zone).toLocalDate() == prior.atZone(zone).toLocalDate()
    }
    fun startsSender(message: Mango9ChatMessage, previous: Mango9ChatMessage?): Boolean {
        if (previous == null || message.fromUserId != previous.fromUserId || !sameDay(message.time, previous.time)) return true
        val current = instant(message.time) ?: return true
        val prior = instant(previous.time) ?: return true
        return Duration.between(prior, current).abs().seconds > 5 * 60
    }
    fun dayLabel(value: String): String = format(value, DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))
    fun timeLabel(value: String): String = format(value, DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    private fun format(value: String, formatter: DateTimeFormatter): String = instant(value)?.atZone(ZoneId.systemDefault())?.format(formatter.withLocale(Locale.getDefault())).orEmpty()
}
