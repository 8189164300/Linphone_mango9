/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import java.time.Instant
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

data class Mango9AppointmentStatus(val id: Int, val name: String, val color: String?)

data class Mango9AppointmentContact(val id: Int, val name: String?, val kind: String) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: "CRM contact"
}

data class Mango9CalendarPerson(val id: Int, val name: String)

data class Mango9CalendarCapabilities(val create: Boolean, val assign: Boolean, val push: Boolean, val inAppReminders: Boolean)

data class Mango9CalendarMetadata(
    val timezone: String,
    val statuses: List<Mango9AppointmentStatus>,
    val activities: List<String>,
    val priorities: List<String>,
    val reminderMinutes: List<Int>,
    val reminderChannels: List<String>,
    val shareRecipients: List<Mango9CalendarPerson>,
    val assignees: List<Mango9CalendarPerson>,
    val capabilities: Mango9CalendarCapabilities,
    val businessHoursSettings: Mango9BusinessHours?,
)

data class Mango9AppointmentPermissions(
    val canEdit: Boolean, val canDelete: Boolean, val canAssign: Boolean,
    val canShare: Boolean, val canChangeContact: Boolean, val readOnlyReason: String?,
)

data class Mango9AppointmentRecurrence(val frequency: String, val weekdays: List<Int>)

data class Mango9AppointmentReminder(val minutesBefore: Int?, val channels: List<String>)

data class Mango9Appointment(
    val id: Int,
    val ownerId: Int,
    val title: String,
    val description: String,
    val startAt: Instant,
    val endAt: Instant,
    val timezone: String,
    val activity: String,
    val priority: String,
    val status: Mango9AppointmentStatus?,
    val contact: Mango9AppointmentContact?,
    val recurrence: Mango9AppointmentRecurrence,
    val reminders: List<Mango9AppointmentReminder>,
    val origin: String,
    val sharedByMeUserIds: List<Int>,
    val permissions: Mango9AppointmentPermissions,
    val revision: String,
    val occurrenceKey: String? = null,
) {
    val isRecurring: Boolean get() = recurrence.frequency != "none"
    val displayId: String get() = occurrenceKey ?: id.toString()

    fun retainingOccurrence(displayed: Mango9Appointment): Mango9Appointment =
        if (isRecurring && id == displayed.id && revision == displayed.revision && displayed.occurrenceKey != null) {
            copy(startAt = displayed.startAt, endAt = displayed.endAt, occurrenceKey = displayed.occurrenceKey)
        } else {
            this
        }
}

data class Mango9AppointmentPage(
    val events: List<Mango9Appointment>, val page: Int, val limit: Int,
    val total: Int, val hasMore: Boolean, val snapshot: String,
)

/** Sparse writes preserve server-owned fields and capabilities, including shared appointments. */
data class Mango9AppointmentDraft(
    val title: String, val notes: String, val start: Instant, val end: Instant,
    val activity: String, val priority: String, val status: Int?,
    val contact: Mango9AppointmentContact?, val shares: Set<Int>,
    val assignee: Int? = null, val reminder: Int = -1, val channels: List<String> = emptyList(),
) {
    fun validationMessage(event: Mango9Appointment?, owner: Boolean): String? {
        if (title.isBlank()) return "Enter an appointment title."
        if (end <= start) return "End time must be after the start time."
        val old = event?.reminders?.firstOrNull()
        val reminderChanged = event == null || (old?.minutesBefore ?: -1) != reminder || old?.channels.orEmpty().toSet() != channels.toSet()
        if (owner && event?.permissions?.canEdit != false && reminderChanged && reminder >= 0 && channels.isEmpty()) {
            return "Choose a reminder channel or turn the reminder off."
        }
        return null
    }

    /** A restricted recipient picker cannot remove existing shares it cannot display. */
    fun selectingShares(visible: Set<Int>, selected: Set<Int>) = copy(shares = (shares - visible) + selected.intersect(visible))

    fun payload(event: Mango9Appointment?, owner: Boolean, timezone: String): JSONObject = JSONObject().apply {
        if (event?.permissions?.canEdit != false) {
            if (event?.title != title.trim()) put("title", title.trim())
            if (event?.description != notes) put("description", notes)
            if (event?.startAt != start) put("start_at", start.toString())
            if (event?.endAt != end) put("end_at", end.toString())
            if (event?.activity != activity) put("activity", activity)
            if (event?.priority != priority) put("priority", priority)
            if (event == null || event.status?.id != status) put("status_id", status ?: JSONObject.NULL)
        }
        if (event?.permissions?.canChangeContact != false && (event == null || event.contact?.id != contact?.id)) {
            put("contact_id", contact?.id ?: JSONObject.NULL)
        }
        if (owner && event?.permissions?.canEdit != false) {
            if (event == null) put("timezone", timezone)
            val old = event?.reminders?.firstOrNull()
            if (event == null || (old?.minutesBefore ?: -1) != reminder ||
                (reminder >= 0 && old?.channels.orEmpty().toSet() != channels.toSet())
            ) {
                put(
                    "reminders",
                    JSONArray().apply {
                    if (reminder >= 0) put(JSONObject().put("minutes_before", reminder).put("channels", JSONArray(channels)))
                }
                )
            }
        }
        if (event?.permissions?.canShare != false && (event == null || event.sharedByMeUserIds.toSet() != shares)) {
            put("share_user_ids", JSONArray(shares.sorted()))
        }
        if (assignee != null && (event?.permissions?.canAssign ?: owner)) put("assign_to_user_id", assignee)
    }

    companion object {
        fun from(event: Mango9Appointment) = Mango9AppointmentDraft(
            event.title, event.description, event.startAt, event.endAt, event.activity, event.priority,
            event.status?.id, event.contact, event.sharedByMeUserIds.toSet(),
            reminder = event.reminders.firstOrNull()?.minutesBefore ?: -1,
            channels = event.reminders.firstOrNull()?.channels.orEmpty(),
        )
    }
}

class Mango9CalendarFailure(val code: String, val status: Int = 0) : Exception(
    when (code) {
        "event_changed" -> "This appointment changed on another device. Refresh it before saving."
        "hours_changed" -> "Business hours changed on another device. Reload them before saving."
        "settings_changed" -> "CRM settings changed on another device. Reload them before saving."
        "reminder_changed" -> "Your reminder changed on another device. Refresh it before trying again."
        "overlap" -> "That time overlaps another appointment. Nothing was changed."
        "out_of_calendar" -> "That time is outside the appointment owner's business hours. Nothing was changed."
        "timezone_legacy_events" -> "Correct older appointments without a saved time zone on the web before changing the CRM time zone."
        "calendar_endpoint_unavailable" -> "Appointment sync is unavailable on this CRM server. You can still browse the calendar."
        "snapshot_changed" -> "The calendar changed while loading. Refresh to load the latest appointments."
        "too_many_events", "too_many_occurrences", "invalid_range" -> "Choose Day, Week or a smaller date range to view appointments."
        "unsupported_recurrence" -> "This repeat rule requires an app update. View it in the web calendar for now."
        "invalid_stored_event" -> "An appointment has an invalid date or time zone. Correct it on the web, then retry."
        "uncertain_write" -> "The save could not be confirmed. Close this form and refresh before trying again."
        "event_save_unconfirmed" -> "The server did not confirm the new event dates. Close and refresh Events to check the saved details before trying again."
        else -> if (status == 403) {
            "Your CRM account does not have permission to do this."
        } else {
            "Appointment data could not be loaded safely. Please refresh and try again."
        }
    },
)

/** Strict required fields: malformed permissions or partial arrays must never become editable data. */
internal object Mango9CalendarJson {
    fun appointment(json: JSONObject): Mango9Appointment = checked {
        val permissions = json.getJSONObject("permissions")
        val recurrence = json.getJSONObject("recurrence")
        Mango9Appointment(
            json.getInt("id"), json.getInt("owner_id"), json.getString("title"), json.getString("description"),
            Instant.parse(json.getString("start_at")), Instant.parse(json.getString("end_at")),
            json.getString("timezone"), json.getString("activity"), json.getString("priority"),
            json.optionalObject("status")?.let(::status),
                json.optionalObject("contact")?.let {
                Mango9AppointmentContact(it.getInt("id"), it.optionalString("name"), it.getString("kind"))
            },
            Mango9AppointmentRecurrence(recurrence.getString("frequency"), recurrence.getJSONArray("weekdays").ints()),
            json.getJSONArray("reminders").objects {
                Mango9AppointmentReminder(if (it.isNull("minutes_before")) null else it.getInt("minutes_before"), it.getJSONArray("channels").strings())
            },
            json.getString("origin"), json.getJSONArray("shared_by_me_user_ids").ints(),
            Mango9AppointmentPermissions(
                permissions.getBoolean("can_edit"), permissions.getBoolean("can_delete"), permissions.getBoolean("can_assign"),
                permissions.getBoolean("can_share"), permissions.getBoolean("can_change_contact"), permissions.optionalString("read_only_reason"),
            ),
                json.getString("revision").also { require(it.isNotBlank()) },
        )
    }

    fun metadata(json: JSONObject): Mango9CalendarMetadata = checked {
        val capabilities = json.getJSONObject("capabilities")
        Mango9CalendarMetadata(
            json.getString("timezone").also { ZoneId.of(it) }, json.getJSONArray("statuses").objects(::status),
            json.getJSONArray("activities").strings(), json.getJSONArray("priorities").strings(),
            json.getJSONArray("reminder_minutes").ints(), json.getJSONArray("reminder_channels").strings(),
            json.getJSONArray("share_recipients").objects(::person), json.getJSONArray("assignees").objects(::person),
            Mango9CalendarCapabilities(
                capabilities.getBoolean("create"), capabilities.getBoolean("assign"),
                capabilities.getBoolean("push"), capabilities.optionalBoolean("in_app_reminders") == true
            ),
            json.optionalObject("business_hours_settings")?.let(::businessHours),
        )
    }

    fun page(json: JSONObject): Mango9AppointmentPage = checked {
        val pagination = json.getJSONObject("pagination")
        Mango9AppointmentPage(
            json.getJSONArray("events").objects(::appointment), pagination.getInt("page"),
            pagination.getInt("limit"), pagination.getInt("total"), pagination.getBoolean("has_more"), json.getString("snapshot")
        )
    }

    fun businessHours(json: JSONObject): Mango9BusinessHours = checked {
        fun days(array: JSONArray) = array.objects {
            Mango9BusinessDay(it.getInt("weekday"), it.optionalString("open"), it.optionalString("close"))
        }
        Mango9BusinessHours(
            json.getBoolean("configured"), json.getString("timezone"),
            json.optionalBoolean("timezone_editable") == true, json.getBoolean("quick_enable"),
            days(json.getJSONArray("days")), days(json.getJSONArray("defaults")), json.getString("revision")
        )
            .also { require(it.validationMessage == null && it.revision.isNotBlank()) }
    }

    fun preferences(json: JSONObject): Mango9CrmPreferences = checked {
        Mango9CrmPreferences(
            json.getBoolean("in_app_reminders"), json.getInt("reminder_minutes"),
            json.getInt("repeat_minutes"), json.getInt("snooze_minutes"), json.getString("new_appointment_timezone"),
            json.getInt("first_weekday"), json.getString("default_calendar_view"), json.getBoolean("sms_push"),
            json.getBoolean("team_chat_push"), json.getString("account_timezone"), json.getString("revision"),
            json.strictOptionalBoolean("social_push"), json.strictOptionalBoolean("lead_push"), json.strictOptionalBoolean("events_push")
        )
            .also { require(it.revision.isNotBlank() && it.isValid); ZoneId.of(it.accountTimezone) }
    }

    fun personalReminder(json: JSONObject): Mango9PersonalReminder = checked {
        Mango9PersonalReminder(
            json.getBoolean("enabled"), if (json.isNull("minutes_before")) null else json.getInt("minutes_before"),
            json.optionalString("due_at")?.let(Instant::parse), json.getBoolean("dismissed"), json.getBoolean("needs_reenable"),
            json.getString("revision").also { require(it.isNotBlank()) }
        )
    }

    fun reminderFeed(json: JSONObject): Mango9ReminderFeed = checked {
        Mango9ReminderFeed(
            json.getJSONArray("items").objects {
            Mango9ReminderItem(appointment(it.getJSONObject("event")), personalReminder(it.getJSONObject("reminder")))
        },
            Instant.parse(json.getString("server_time")), json.optionalObject("settings")?.let(::preferences)
        )
    }

    private fun person(json: JSONObject) = Mango9CalendarPerson(json.getInt("id"), json.getString("name"))

    private fun status(json: JSONObject) = Mango9AppointmentStatus(json.getInt("id"), json.getString("name"), json.optionalString("color"))

    private fun JSONObject.optionalObject(key: String): JSONObject? = if (isNull(key)) null else getJSONObject(key)

    private fun JSONObject.optionalString(key: String): String? = if (isNull(key)) null else getString(key)

    private fun JSONObject.optionalBoolean(key: String): Boolean? = if (isNull(key)) null else getBoolean(key)

    private fun JSONObject.strictOptionalBoolean(key: String): Boolean? = if (isNull(key)) {
        null
    } else {
        require(get(key) is Boolean) { "Invalid notification preference" }
        getBoolean(key)
    }

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    private fun JSONArray.ints(): List<Int> = (0 until length()).map { getInt(it) }

    private fun <T> JSONArray.objects(parse: (JSONObject) -> T): List<T> = (0 until length()).map {
        parse(getJSONObject(it))
    }

    private inline fun <T> checked(block: () -> T): T = try {
        block()
    } catch (error: Mango9CalendarFailure) {
        throw error
    } catch (_: Exception) { throw Mango9CalendarFailure("invalid_response") }
}
