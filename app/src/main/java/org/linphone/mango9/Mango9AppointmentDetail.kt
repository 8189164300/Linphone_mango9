/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class Mango9AppointmentReminderState(
    val reminder: Mango9PersonalReminder? = null,
    val preferences: Mango9CrmPreferences? = null,
    val loading: Boolean = false, val settingsLoading: Boolean = false,
    val error: String? = null,
)

/** Retains authoritative revisions and write outcomes, never a window or its callbacks. */
class Mango9AppointmentDetail internal constructor(
    lease: Mango9SessionLease, repository: Mango9CalendarRepository, private val scope: CoroutineScope,
    val displayed: Mango9Appointment, val metadata: Mango9CalendarMetadata, private val now: () -> Instant,
) : Mango9CalendarEdit(lease, repository, scope) {
    var event: Mango9Appointment? = null
        private set
    val shown: Mango9Appointment? get() = event?.retainingOccurrence(displayed)
    private val mutableReminder = MutableStateFlow(Mango9AppointmentReminderState())
    val personal = mutableReminder.asStateFlow()
    private var reminderJob: Job? = null
    private var settingsJob: Job? = null
    private var readGeneration = 0L
    private var needsReload = false

    override val canSave: Boolean get() = editable && event != null && !needsReload

    override fun save(): Job? = null // Detail actions have explicit intents, never a generic replay.

    override fun onClosed() { cancelReads() }

    private fun cancelReads() {
        readGeneration++; reminderJob?.cancel(); settingsJob?.cancel()
        reminderJob = null; settingsJob = null
    }

    fun reload(): Job? = request(false) {
        needsReload = true
        val loaded = repository.appointment(lease, displayed.id)
        event = loaded; needsReload = false
        startReminderReads()
    }

    fun changeStatus(id: Int?): Job? {
        val original = event ?: return null
        if (!original.permissions.canEdit || id == original.status?.id || (
            id != null && metadata.statuses.none {
            it.id == id
        }
        )
        ) {
            return null
        }
        return request(true, complete = false, retryCodes = emptySet()) {
            event = repository.changeStatus(lease, original, id)
            startReminderReads()
        }
    }

    fun pushBack(minutes: Int): Job? {
        val original = event ?: return null
        if (!original.permissions.canEdit || minutes !in Mango9AppointmentActions.minuteChoices || original.startAt.plusSeconds(minutes * 60L) <= now()) return null
        return request(true, complete = false, retryCodes = setOf("overlap", "out_of_calendar")) {
            event = repository.pushBack(lease, original, minutes)
            startReminderReads()
        }
    }

    fun delete(): Job? {
        val original = event ?: return null
        if (!original.permissions.canDelete) return null
        return request(true, retryCodes = emptySet()) { repository.delete(lease, original); cancelReads() }
    }

    fun updateReminder(action: Mango9ReminderAction): Job? {
        val original = event ?: return null
        val state = personal.value.reminder ?: return null
        if (!metadata.capabilities.inAppReminders || original.isRecurring || personal.value.loading) return null
        if (action !is Mango9ReminderAction.Dismiss && original.endAt <= now()) return null
        if (action !is Mango9ReminderAction.Configure && !state.enabled) return null
        if (action is Mango9ReminderAction.Configure && action.minutes != -1 && action.minutes !in Mango9AppointmentActions.reminderChoices) return null
        if (action is Mango9ReminderAction.Dismiss && state.dismissed) return null
        if (action is Mango9ReminderAction.Snooze && action.until <= now()) return null
        return request(true, complete = false, retryCodes = emptySet()) {
            val saved = repository.updateReminder(lease, original, state, action)
            mutableReminder.value = personal.value.copy(reminder = saved, error = null)
        }
    }

    fun refreshReminder() {
        if (canSave) startReminderReads()
    }

    private fun startReminderReads() {
        val original = event ?: return
        cancelReads()
        if (!metadata.capabilities.inAppReminders || original.isRecurring) {
            mutableReminder.value = Mango9AppointmentReminderState(); return
        }
        val generation = readGeneration

        fun current() = generation == readGeneration && !progress.value.closed && repository.isCurrent(lease) && event?.revision == original.revision
        mutableReminder.value = Mango9AppointmentReminderState(loading = true, settingsLoading = true)
        reminderJob = scope.launch {
            try {
                val loaded = repository.reminder(lease, original)
                if (current()) mutableReminder.value = personal.value.copy(reminder = loaded)
            } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) {
                if (current()) mutableReminder.value = personal.value.copy(error = Mango9CalendarScreen.message(error))
            } finally {
                if (current()) mutableReminder.value = personal.value.copy(loading = false)
            }
        }
        settingsJob = scope.launch {
            try {
                val loaded = repository.preferences(lease)
                if (current()) mutableReminder.value = personal.value.copy(preferences = loaded)
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {
                // Optional defaults must not hold up an available personal reminder.
            } finally {
                if (current()) mutableReminder.value = personal.value.copy(settingsLoading = false)
            }
        }
    }
}
