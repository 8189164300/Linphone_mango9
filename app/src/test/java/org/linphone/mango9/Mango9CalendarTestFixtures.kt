/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import org.json.JSONObject

internal object Mango9CalendarTestFixtures {
    val eventJson: String = """
        {"id":91,"owner_id":42,"title":"Client consultation","description":"Discuss next steps",
        "start_at":"2026-09-11T09:00:00-07:00","end_at":"2026-09-11T09:30:00-07:00",
        "timezone":"America/Los_Angeles","activity":"appointment","priority":"medium",
        "status":{"id":3,"name":"Confirmed","color":"#008080"},
        "contact":{"id":101,"name":"Taylor Reed","kind":"lead"},
        "recurrence":{"frequency":"none","weekdays":[]},"reminders":[],"origin":"appointment",
        "shared_by_me_user_ids":[43],"permissions":{"can_edit":true,"can_delete":true,"can_assign":true,
        "can_share":true,"can_change_contact":true,"read_only_reason":null},"revision":"abc123"}
    """.trimIndent()
    val metadataJson: String = """
        {"timezone":"America/Los_Angeles","statuses":[{"id":3,"name":"Confirmed","color":"#008080"}],
        "activities":["appointment","call","meeting","task","follow_up"],"priorities":["low","medium","high"],
        "reminder_minutes":[0,15,30,60],"reminder_channels":["email","sms"],
        "share_recipients":[{"id":43,"name":"Jordan Lee"}],"assignees":[{"id":43,"name":"Jordan Lee"}],
        "capabilities":{"create":true,"assign":true,"push":false}}
    """.trimIndent()
    val settingsJson: String = """
        {"in_app_reminders":true,"reminder_minutes":15,"repeat_minutes":0,"snooze_minutes":10,
        "new_appointment_timezone":"Asia/Yerevan","first_weekday":2,"default_calendar_view":"appointments",
        "sms_push":false,"team_chat_push":true,"account_timezone":"America/Los_Angeles","revision":"settings-fixture"}
    """.trimIndent()

    fun event(): Mango9Appointment = Mango9CalendarJson.appointment(JSONObject(eventJson))

    fun metadata(): Mango9CalendarMetadata = Mango9CalendarJson.metadata(JSONObject(metadataJson))

    fun hours(configured: Boolean = true): Mango9BusinessHours {
        val days = (0..6).map {
            Mango9BusinessDay(it, if (it in 1..5) "09:00:00" else null, if (it in 1..5) "18:00:00" else null)
        }
        return Mango9BusinessHours(configured, "America/Los_Angeles", true, true, days, days, "hours-fixture")
    }

    fun envelope(json: String) = """{"success":true,"message":"success","data":$json}"""

    fun session(): Mango9Session = Mango9Session(
        "crm", "https://crm.example.invalid", "https://crm.example.invalid/api/v2", "42", "1", "client",
        "calendar@example.invalid", "Calendar Test", "test-access", "test-refresh", "", "", 1_900_000_000,
        "sip:42@crm.example.invalid",
    )

    class Sessions : Mango9SessionAccess {
        val generations = Mango9SessionGenerations()
        var session: Mango9Session? = session()
        var identity = session?.sipIdentity
        var writes = 0

        override fun captureSession() = session?.let(generations::capture)

        override fun currentSession(lease: Mango9SessionLease): Mango9Session? =
            session.takeIf { generations.matches(lease, identity) }

        override fun replaceTokens(lease: Mango9SessionLease, expected: Mango9Session, refreshed: Mango9Session): Boolean {
            if (currentSession(lease) != expected) return false
            session = refreshed; writes++; return true
        }

        fun awayAndBack() { generations.selectionChanged(); generations.selectionChanged() }
    }
}
