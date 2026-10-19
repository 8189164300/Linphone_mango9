/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import androidx.annotation.MainThread
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class Mango9CalendarScreenState(
    val date: LocalDate,
    val displayZone: ZoneId,
    val firstWeekday: DayOfWeek,
    val calendar: Boolean = false,
    val mode: Mango9CalendarMode = Mango9CalendarMode.Month,
    val metadata: Mango9CalendarMetadata? = null,
    val preferences: Mango9CrmPreferences? = null,
    val events: List<Mango9Appointment> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val preferencesError: Boolean = false,
    val capacityExceeded: Boolean = false,
    val contextExpired: Boolean = false,
    val query: String = "",
    /** null = all, 0 = no status, otherwise the server status identifier. */
    val selectedStatus: Int? = null,
    val agendaDay: LocalDate? = null,
    val contentRevision: Long = 0,
) {
    val range: Mango9CalendarRange get() = if (calendar) {
        Mango9CalendarPresentation.visibleRange(date, mode, displayZone, firstWeekday)
    } else {
        Mango9CalendarMode.Month.range(date, displayZone, firstWeekday)
    }
    val hours: Mango9BusinessHours? get() = metadata?.businessHoursSettings
}

/** A form holds its original login/permissions/timezone, never a newly selected account. */
data class Mango9AppointmentCreationContext(
    val lease: Mango9SessionLease, val metadata: Mango9CalendarMetadata,
    val contact: Mango9AppointmentContact?, val start: Instant, val zone: ZoneId,
    val activity: String? = null,
)

/**
 * Owned by a ViewModel's main-thread scope. Account and query generations fence all
 * callbacks, even if a transport ignores cancellation. Reads never replace an open
 * form or reset timeline zoom/scroll. No second local appointment database.
 */
@MainThread
class Mango9CalendarScreen(
    private val repository: Mango9CalendarRepository,
    private val scope: CoroutineScope,
    val contact: Mango9AppointmentContact? = null,
    date: LocalDate = LocalDate.now(),
    deviceZone: ZoneId = ZoneId.systemDefault(),
    private val deviceFirstWeekday: DayOfWeek = java.time.temporal.WeekFields.of(java.util.Locale.getDefault()).firstDayOfWeek,
    private val linkedLease: Mango9SessionLease? = if (contact == null) null else repository.capture(),
) {
    private val mutableState = MutableStateFlow(Mango9CalendarScreenState(date, deviceZone, deviceFirstWeekday))
    val state = mutableState.asStateFlow()
    private var lease: Mango9SessionLease? = null
    private var loadJob: Job? = null
    private var generation = 0L
    private var hoursGeneration = 0L
    private var loadedRange: Mango9CalendarRange? = null
    private var initializedDefaults = false
    private var userChoseView = false

    fun refresh(): Job? {
        loadJob?.cancel()
        val request = ++generation
        if (contact != null && linkedLease?.let(repository::isCurrent) != true) {
            expireLinkedContext()
            return null
        }
        val current = repository.capture()
        if (current == null) {
            clearAccount()
            mutableState.value = mutableState.value.copy(error = "Connect your CRM account to view appointments.")
            return null
        }
        if (lease != null && lease?.let(repository::isCurrent) != true) clearAccount()
        lease = current
        val hoursVersion = hoursGeneration
        mutableState.value = mutableState.value.copy(
            loading = true, error = null, capacityExceeded = false,
            events = if (loadedRange == mutableState.value.range) mutableState.value.events else emptyList()
        )
        return scope.launch {
            // Optional settings must not hold the required appointment read hostage.
            // Both children still belong to this account/query generation and stop
            // with the screen. Fast settings can select the first range immediately.
            val preferencesRequest = async(start = CoroutineStart.UNDISPATCHED) {
                try {
                    repository.preferences(current) to false
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    null to true
                }
            }
            var confirmedMetadata: Mango9CalendarMetadata? = null
            try {
                val metadata = repository.metadata(current)
                confirmedMetadata = metadata
                ensureActive()
                if (!owns(request, current)) return@launch
                var preferencesApplied = preferencesRequest.isCompleted
                if (preferencesApplied) {
                    val (preferences, failed) = preferencesRequest.await()
                    applyPreferences(preferences, failed)
                }
                do {
                    val range = mutableState.value.range
                    val events = repository.displayEvents(current, range, contact?.id)
                    ensureActive()
                    if (!owns(request, current)) return@launch
                    val latestHours = mutableState.value.hours
                    val acceptedMetadata = if (hoursVersion == hoursGeneration) {
                        metadata
                    } else {
                        metadata.copy(
                        businessHoursSettings = latestHours, timezone = latestHours?.timezone ?: metadata.timezone
                    )
                    }
                    loadedRange = range
                    mutableState.value = mutableState.value.copy(
                        metadata = acceptedMetadata, events = events, loading = false, error = null,
                        selectedStatus = mutableState.value.selectedStatus?.takeIf { status ->
                            status == 0 || acceptedMetadata.statuses.any { it.id == status }
                        },
                        contentRevision = mutableState.value.contentRevision + 1
                    )
                    if (!preferencesApplied) {
                        val (preferences, failed) = preferencesRequest.await()
                        ensureActive()
                        if (!owns(request, current)) return@launch
                        applyPreferences(preferences, failed)
                        preferencesApplied = true
                    }
                    // A late first-weekday/default-view preference can change the
                    // month grid's spillover range. Never label the old data as that
                    // new range; fetch it without reloading metadata or settings.
                    val rangeChanged = mutableState.value.range != range
                    if (rangeChanged) mutableState.value = mutableState.value.copy(events = emptyList(), loading = true)
                } while (rangeChanged)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (!owns(request, current)) {
                    // An old account may finish without a host account-change callback.
                    if (generation == request) {
                        if (contact != null) expireLinkedContext() else clearAccount()
                    }
                    return@launch
                }
                val capacity = error is Mango9CalendarFailure && error.code in setOf("too_many_occurrences", "too_many_events")
                loadedRange = null
                val latestHours = mutableState.value.hours
                val acceptedMetadata = if (hoursVersion == hoursGeneration) {
                    confirmedMetadata
                } else {
                    confirmedMetadata?.copy(
                    businessHoursSettings = latestHours, timezone = latestHours?.timezone ?: confirmedMetadata.timezone
                    )
                }
                mutableState.value = mutableState.value.copy(
                    metadata = if (capacity) acceptedMetadata else null,
                    events = emptyList(), loading = false, error = message(error), capacityExceeded = capacity,
                    contentRevision = mutableState.value.contentRevision + 1
                )
            } finally {
                preferencesRequest.cancel()
                if (owns(request, current)) mutableState.value = mutableState.value.copy(loading = false)
            }
        }.also { loadJob = it }
    }

    /** Call on the host's account-change signal, not only on the next network response. */
    fun accountChanged() {
        loadJob?.cancel(); generation++
        clearAccount()
        refresh()
    }

    fun stop() {
        generation++; loadJob?.cancel(); loadJob = null
        mutableState.value = mutableState.value.copy(loading = false)
    }

    fun search(query: String) { mutableState.value = mutableState.value.copy(query = query) }

    fun filterStatus(status: Int?) {
        mutableState.value = mutableState.value.copy(selectedStatus = status?.takeIf { it >= 0 })
    }

    fun showAgendaDay(day: LocalDate) {
        userChoseView = true
        mutableState.value = mutableState.value.copy(date = day, calendar = false, agendaDay = day)
        refresh()
    }

    fun chooseCalendar(calendar: Boolean) {
        userChoseView = true
        mutableState.value = mutableState.value.copy(agendaDay = null)
        if (mutableState.value.calendar == calendar) return
        mutableState.value = mutableState.value.copy(calendar = calendar)
        refresh()
    }

    fun chooseMode(mode: Mango9CalendarMode) {
        userChoseView = true
        mutableState.value = mutableState.value.copy(agendaDay = null)
        if (mutableState.value.mode == mode && mutableState.value.calendar) return
        mutableState.value = mutableState.value.copy(calendar = true, mode = mode)
        refresh()
    }

    fun chooseDate(date: LocalDate, openDay: Boolean = false) {
        userChoseView = true
        val next = mutableState.value.copy(
            date = date,
            agendaDay = null,
            mode = if (openDay) Mango9CalendarMode.Day else mutableState.value.mode,
            calendar = openDay || mutableState.value.calendar
        )
        if (next == mutableState.value) return
        mutableState.value = next
        refresh()
    }

    fun move(direction: Long) {
        val value = mutableState.value
        val mode = if (value.calendar) value.mode else Mango9CalendarMode.Month
        val anchor = if (mode == Mango9CalendarMode.Week) {
            Mango9CalendarPresentation.days(value.date, mode, value.firstWeekday).first()
        } else {
            value.date
        }
        chooseDate(mode.move(anchor, direction))
    }

    fun updateDeviceZone(zone: ZoneId) {
        if (mutableState.value.displayZone == zone) return
        mutableState.value = mutableState.value.copy(displayZone = zone)
        refresh()
    }

    fun canCreate(): Boolean {
        val value = mutableState.value
        return lease?.let(repository::isCurrent) == true && value.metadata?.capabilities?.create == true &&
            !value.loading && (value.error == null || value.capacityExceeded)
    }

    fun creation(start: Instant, calendarSelection: Boolean): Mango9AppointmentCreationContext? {
        if (!canCreate()) return null
        val value = mutableState.value
        val current = lease ?: return null
        val metadata = value.metadata ?: return null
        val zone = if (calendarSelection) {
            value.displayZone
        } else {
            value.preferences?.appointmentZone(value.displayZone, metadata.timezone) ?: ZoneId.of(metadata.timezone)
        }
        if (calendarSelection && value.hours?.allows(start, start.plusSeconds(1800)) == false) return null
        return Mango9AppointmentCreationContext(current, metadata, contact, start, zone)
    }

    fun capture(): Mango9SessionLease? = lease?.takeIf(repository::isCurrent)

    /** Hours save is independent of a slow metadata read and must win over it. */
    fun acceptHours(context: Mango9SessionLease, hours: Mango9BusinessHours) {
        if (!repository.isCurrent(context) || lease?.let(repository::isCurrent) != true) return
        hoursGeneration++
        mutableState.value = mutableState.value.copy(
            metadata = mutableState.value.metadata?.copy(
            businessHoursSettings = hours, timezone = hours.timezone
            )
        )
    }

    private fun owns(request: Long, context: Mango9SessionLease) = generation == request && repository.isCurrent(context)

    private fun applyPreferences(value: Mango9CrmPreferences?, failed: Boolean) {
        // An older CRM may not expose this endpoint. Retain only previously read
        // settings for this login; do not invent a saved notification preference.
        val preferences = value ?: mutableState.value.preferences
        var next = mutableState.value.copy(
            preferences = preferences, preferencesError = failed,
            firstWeekday = preferences?.weekStartsOn(deviceFirstWeekday) ?: deviceFirstWeekday
        )
        if (!initializedDefaults && !userChoseView && preferences != null) {
            next = next.copy(calendar = preferences.defaultCalendarView == "calendar")
        }
        initializedDefaults = initializedDefaults || preferences != null
        mutableState.value = next
    }

    private fun clearAccount() {
        lease = null; loadedRange = null; initializedDefaults = false; userChoseView = false; hoursGeneration++
        val value = mutableState.value
        mutableState.value = Mango9CalendarScreenState(
            value.date, value.displayZone, deviceFirstWeekday,
            calendar = value.calendar, mode = value.mode, contentRevision = value.contentRevision + 1
        )
    }

    private fun expireLinkedContext() {
        clearAccount()
        mutableState.value = mutableState.value.copy(
            contextExpired = true,
            error = "The CRM account changed. Reopen appointments from the lead or client."
        )
    }

    companion object {
        fun message(error: Exception): String = when (error) {
            is Mango9CalendarFailure -> error.message.orEmpty()
            is Mango9ApiException.Unauthorized, is Mango9ApiException.InvalidCredentials -> "Reconnect your CRM account to sync appointments."
            else -> "Appointments could not be loaded. Check your connection and refresh."
        }
    }
}
