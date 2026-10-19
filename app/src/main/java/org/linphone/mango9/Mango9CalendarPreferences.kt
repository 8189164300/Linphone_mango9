/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.json.JSONObject

data class Mango9CrmPreferences(
    val inAppReminders: Boolean, val reminderMinutes: Int, val repeatMinutes: Int, val snoozeMinutes: Int,
    val newAppointmentTimezone: String, val firstWeekday: Int, val defaultCalendarView: String,
    val smsPush: Boolean, val teamChatPush: Boolean, val accountTimezone: String, val revision: String,
    val socialPush: Boolean? = null, val leadPush: Boolean? = null, val eventsPush: Boolean? = null,
) {
    val isValid: Boolean get() = reminderMinutes in Mango9AppointmentActions.reminderChoices &&
        repeatMinutes in listOf(0, 5, 10, 15, 30, 60) && snoozeMinutes in Mango9AppointmentActions.minuteChoices &&
        firstWeekday in 0..2 && defaultCalendarView in setOf("appointments", "calendar") &&
        (newAppointmentTimezone in setOf("account", "device") || runCatching { ZoneId.of(newAppointmentTimezone) }.isSuccess)

    fun body(): JSONObject = JSONObject().put("in_app_reminders", inAppReminders).put("reminder_minutes", reminderMinutes)
        .put("repeat_minutes", repeatMinutes).put("snooze_minutes", snoozeMinutes)
        .put("new_appointment_timezone", newAppointmentTimezone).put("first_weekday", firstWeekday)
        .put("default_calendar_view", defaultCalendarView).put("sms_push", smsPush).put("team_chat_push", teamChatPush)
        .apply {
            socialPush?.let { put("social_push", it) }
            leadPush?.let { put("lead_push", it) }
            eventsPush?.let { put("events_push", it) }
        }

    fun appointmentZone(deviceZone: ZoneId, latestAccountZone: String = accountTimezone): ZoneId = when (newAppointmentTimezone) {
        "device" -> deviceZone; "account" -> ZoneId.of(latestAccountZone); else -> ZoneId.of(newAppointmentTimezone)
    }

    fun weekStartsOn(deviceDefault: DayOfWeek): DayOfWeek = when (firstWeekday) { 1 -> DayOfWeek.SUNDAY; 2 -> DayOfWeek.MONDAY; else -> deviceDefault }
}

data class Mango9PersonalReminder(
    val enabled: Boolean, val minutesBefore: Int?, val dueAt: Instant?,
    val dismissed: Boolean, val needsReenable: Boolean, val revision: String,
)

data class Mango9ReminderItem(val event: Mango9Appointment, val reminder: Mango9PersonalReminder)

data class Mango9ReminderFeed(val items: List<Mango9ReminderItem>, val serverTime: Instant, val settings: Mango9CrmPreferences?) {
    fun due(): List<Mango9ReminderItem> = items.filter {
        it.reminder.enabled && !it.reminder.dismissed && it.reminder.dueAt?.let { due -> due <= serverTime } == true &&
            it.event.endAt > serverTime && !it.event.isRecurring
    }.sortedWith(compareBy({ it.reminder.dueAt }, { it.event.id }))
}

/** Bounded hashes/timestamps only, never appointment content or credentials. Persist outside backups. */
class Mango9ReminderCadence(lastShown: Map<String, Long> = emptyMap()) {
    private val shown = lastShown.entries.sortedByDescending { it.value }.take(256)
        .associate { it.key to it.value }.toMutableMap()

    fun allows(key: String, now: Instant, repeatMinutes: Int): Boolean = shown[key]?.let {
        repeatMinutes > 0 && now.epochSecond - it >= repeatMinutes.toLong() * 60
    } ?: true

    fun record(key: String, now: Instant) {
        shown[key] = now.epochSecond
        if (shown.size > 256) {
            val retained = shown.entries.sortedByDescending { it.value }.take(256).associate { it.key to it.value }
            shown.clear(); shown.putAll(retained)
        }
    }

    fun snapshot(): Map<String, Long> = shown.toMap()

    companion object {
        fun key(session: Mango9Session, item: Mango9ReminderItem): String {
            val value = listOf(
                session.crmApiBaseUrl, session.crmId, session.userId, session.sipIdentity.orEmpty(),
                item.event.id.toString(), item.reminder.revision
            ).joinToString("|")
            return MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") {
                "%02x".format(it)
            }
        }
    }
}

object Mango9AppointmentActions {
    val minuteChoices = listOf(5, 10, 15, 30, 60)
    val reminderChoices = listOf(0, 5, 10, 15, 30, 60, 120, 1440)

    fun pushBackBody(event: Mango9Appointment, minutes: Int): JSONObject {
        require(minutes in minuteChoices)
        val offset = Duration.ofMinutes(minutes.toLong())
        return JSONObject().put("start_at", event.startAt.plus(offset).toString()).put("end_at", event.endAt.plus(offset).toString())
    }
}

sealed class Mango9ReminderAction {
    data class Configure(val minutes: Int) : Mango9ReminderAction()

    data class Snooze(val until: Instant) : Mango9ReminderAction()

    data object Dismiss : Mango9ReminderAction()

    fun body(reminder: Mango9PersonalReminder): JSONObject = JSONObject().put("reminder_revision", reminder.revision).apply {
        when (val action = this@Mango9ReminderAction) {
            is Configure -> {
                require(action.minutes == -1 || action.minutes in Mango9AppointmentActions.reminderChoices)
                put("action", "configure"); put("minutes_before", action.minutes)
            }
            is Snooze -> { put("action", "snooze"); put("until", action.until.toString()) }
            Dismiss -> put("action", "dismiss")
        }
    }
}
