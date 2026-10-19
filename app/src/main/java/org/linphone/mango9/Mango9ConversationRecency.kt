// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Compare instants, not wire formats (SQL UTC, ISO offsets and fractional seconds). */
internal object Mango9ConversationRecency {
    fun time(value: String): Instant {
        val text = value.trim()
        return runCatching { Instant.parse(text.replace(' ', 'T')) }.getOrNull()
            ?: runCatching { LocalDateTime.parse(text.replace(' ', 'T')).toInstant(ZoneOffset.UTC) }.getOrNull()
            ?: runCatching { LocalDate.parse(text).atStartOfDay().toInstant(ZoneOffset.UTC) }.getOrNull()
            ?: Instant.MIN
    }

    fun newer(candidate: String, existing: String) = time(candidate) > time(existing)

    fun rooms(values: List<Mango9ChatRoom>) = values.sortedWith(
        compareByDescending<Mango9ChatRoom> { time(it.latest) }.thenBy { it.id }
    )

    fun sms(values: List<Mango9SmsParty>) = values.sortedWith(
        compareByDescending<Mango9SmsParty> { time(it.latest) }.thenBy { it.phone }
    )

    fun roomActivity(values: List<Mango9ChatRoom>, id: String, at: String, preview: String) = rooms(
        values.map {
        if (it.id == id && time(at) >= time(it.latest)) it.copy(latest = at, lastMessage = preview) else it
    }
    )

    fun smsActivity(values: List<Mango9SmsParty>, phone: String, at: String, preview: String): List<Mango9SmsParty> {
        val old = values.firstOrNull { it.phone == phone }
        val updated = when {
            old == null -> Mango9SmsParty(phone, at, preview, 0, "")
            time(at) >= time(old.latest) -> old.copy(latest = at, lastMessage = preview)
            else -> old
        }
        return sms(values.filterNot { it.phone == phone } + updated)
    }

    // Directory metadata remains authoritative; only a newer observed message survives a stale snapshot.
    fun roomSnapshot(server: Mango9ChatRoom, observed: Mango9ChatRoom?): Mango9ChatRoom =
        if (observed != null && newer(observed.latest, server.latest)) {
            server.copy(latest = observed.latest, lastMessage = observed.lastMessage)
        } else {
            server
        }

    fun smsSnapshot(server: Mango9SmsParty, observed: Mango9SmsParty?): Mango9SmsParty =
        if (observed != null && newer(observed.latest, server.latest)) {
            server.copy(latest = observed.latest, lastMessage = observed.lastMessage)
        } else {
            server
        }

    fun socialSnapshot(server: Mango9SocialThread, observed: Mango9SocialThread?): Mango9SocialThread =
        if (observed != null && newer(observed.activityAt, server.activityAt)) {
            server.copy(activityAt = observed.activityAt, summary = observed.summary)
        } else {
            server
        }
}
