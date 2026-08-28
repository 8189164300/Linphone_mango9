/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.mango9

import android.content.Context
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.Call.Dir
import org.linphone.core.Call.Status
import org.linphone.utils.LinphoneUtils

data class Mango9SmsConversationStats(
    val total: Int,
    val sent: Int,
    val received: Int,
    val attachments: Int,
    val failed: Int,
    val firstAvailableMessage: ZonedDateTime?,
    val latestMessage: ZonedDateTime?,
    val senderIds: List<String>,
) {
    companion object {
        fun build(messages: List<Mango9SmsMessage>): Mango9SmsConversationStats {
            val dates = messages.mapNotNull { Mango9ConversationTime.parse(it.time) }
            return Mango9SmsConversationStats(
                total = messages.size,
                sent = messages.count { !it.isIncoming },
                received = messages.count(Mango9SmsMessage::isIncoming),
                attachments = messages.sumOf { Mango9ChatMedia.parse(it.files).size },
                failed = messages.count { !it.isIncoming && it.status == 99 },
                firstAvailableMessage = dates.minOrNull(),
                latestMessage = dates.maxOrNull(),
                senderIds = messages.asSequence()
                    .filterNot(Mango9SmsMessage::isIncoming)
                    .map(Mango9SmsMessage::senderId)
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .distinct()
                    .sorted()
                    .toList(),
            )
        }
    }
}

data class Mango9SmsCrmMatch(
    val id: Int,
    val kind: Mango9RecordKind,
    val name: String,
    val ownerName: String,
    val status: String,
    val createdAt: String,
) {
    companion object {
        fun exactMatch(
            phone: String,
            clients: List<Mango9CrmRecord>,
            leads: List<Mango9CrmRecord>,
        ): Mango9SmsCrmMatch? {
            val target = Mango9PhoneNumber.normalized(phone)
            if (target.isEmpty()) return null
            clients.firstOrNull { Mango9PhoneNumber.normalized(it.phone) == target }?.let {
                return from(it, Mango9RecordKind.Client)
            }
            leads.firstOrNull { Mango9PhoneNumber.normalized(it.phone) == target }?.let {
                return from(it, Mango9RecordKind.Lead)
            }
            return null
        }

        private fun from(record: Mango9CrmRecord, kind: Mango9RecordKind) = Mango9SmsCrmMatch(
            record.id,
            kind,
            record.name,
            record.ownerName,
            record.status,
            record.createdAt,
        )
    }
}

data class Mango9LocalCallFact(
    val phone: String,
    val outgoing: Boolean,
    val missed: Boolean,
    val connected: Boolean,
    val startEpochSeconds: Long,
    val durationSeconds: Int,
)

data class Mango9LocalCallStats(
    val total: Int,
    val inbound: Int,
    val outbound: Int,
    val missed: Int,
    val connectedDurationSeconds: Int,
    val lastCallEpochSeconds: Long?,
) {
    companion object {
        fun build(phone: String, facts: List<Mango9LocalCallFact>): Mango9LocalCallStats {
            val target = Mango9PhoneNumber.normalized(phone)
            val matches = facts.filter { Mango9PhoneNumber.normalized(it.phone) == target }
            return Mango9LocalCallStats(
                total = matches.size,
                inbound = matches.count { !it.outgoing },
                outbound = matches.count(Mango9LocalCallFact::outgoing),
                missed = matches.count(Mango9LocalCallFact::missed),
                connectedDurationSeconds = matches.filter(Mango9LocalCallFact::connected)
                    .sumOf { it.durationSeconds.coerceAtLeast(0) },
                lastCallEpochSeconds = matches.map(Mango9LocalCallFact::startEpochSeconds)
                    .filter { it > 0 }
                    .maxOrNull(),
            )
        }
    }
}

data class Mango9SmsConversationInsights(
    val stats: Mango9SmsConversationStats,
    val crmMatch: Mango9SmsCrmMatch?,
    val crmLookupSucceeded: Boolean,
    val localCalls: Mango9LocalCallStats,
)

class Mango9ConversationInsightsRepository(context: Context) {
    private val crm = Mango9CrmRepository(context.applicationContext)

    suspend fun load(phone: String, messages: List<Mango9SmsMessage>): Mango9SmsConversationInsights {
        val crmLookup = loadCrmMatch(phone)
        return Mango9SmsConversationInsights(
            stats = Mango9SmsConversationStats.build(messages),
            crmMatch = crmLookup.first,
            crmLookupSucceeded = crmLookup.second,
            localCalls = loadLocalCalls(phone),
        )
    }

    private suspend fun loadCrmMatch(phone: String): Pair<Mango9SmsCrmMatch?, Boolean> {
        val search = Mango9PhoneNumber.normalized(phone)
        return runCatching {
            val clients = crm.records(Mango9RecordKind.Client, search = search).records
            val exactClient = Mango9SmsCrmMatch.exactMatch(phone, clients, emptyList())
            if (exactClient != null) return@runCatching exactClient
            val leads = crm.records(Mango9RecordKind.Lead, search = search).records
            Mango9SmsCrmMatch.exactMatch(phone, emptyList(), leads)
        }.fold(
            onSuccess = { it to true },
            onFailure = { null to false },
        )
    }

    private suspend fun loadLocalCalls(phone: String): Mango9LocalCallStats =
        suspendCancellableCoroutine { continuation ->
            coreContext.postOnCoreThread { core ->
                val facts = runCatching {
                    val logs = if (core.accountList.size > 1) {
                        LinphoneUtils.getDefaultAccount()?.callLogs ?: core.callLogs
                    } else {
                        core.callLogs
                    }
                    logs.mapNotNull { log ->
                        val remote = Mango9CallerIdentity.externalPhoneNumber(log.remoteAddress.asStringUriOnly())
                            ?: return@mapNotNull null
                        Mango9LocalCallFact(
                            phone = remote,
                            outgoing = log.dir == Dir.Outgoing,
                            missed = log.dir != Dir.Outgoing && log.status in setOf(
                                Status.Missed,
                                Status.Aborted,
                                Status.EarlyAborted,
                            ),
                            connected = log.status == Status.Success,
                            startEpochSeconds = log.startDate,
                            durationSeconds = log.duration,
                        )
                    }
                }.getOrDefault(emptyList())
                if (continuation.isActive) continuation.resume(Mango9LocalCallStats.build(phone, facts))
            }
        }
}

internal object Mango9ConversationTime {
    private val localFormats = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
    )

    fun parse(value: String): ZonedDateTime? {
        if (value.isBlank()) return null
        return try {
            Instant.parse(value).atZone(ZoneId.systemDefault())
        } catch (_: DateTimeParseException) {
            localFormats.firstNotNullOfOrNull { formatter ->
                runCatching {
                    LocalDateTime.parse(value, formatter)
                        .atZone(ZoneId.of("UTC"))
                        .withZoneSameInstant(ZoneId.systemDefault())
                }.getOrNull()
            }
        }
    }
}
