/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import androidx.annotation.MainThread
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class Mango9CalendarEditProgress(
    val busy: Boolean = false, val writing: Boolean = false, val blocked: Boolean = false,
    val completed: Boolean = false, val closed: Boolean = false, val error: String? = null, val revision: Long = 0,
)

/** In-memory, ViewModel-owned editing. Never retains a View, Activity, or UI callback. */
@MainThread
sealed class Mango9CalendarEdit(
    val lease: Mango9SessionLease,
    protected val repository: Mango9CalendarRepository,
    private val scope: CoroutineScope,
) {
    protected val mutableProgress = MutableStateFlow(Mango9CalendarEditProgress())
    val progress = mutableProgress.asStateFlow()
    private var work: Job? = null
    private var generation = 0L
    internal val presentation = Mango9CalendarFormState()
    abstract val canSave: Boolean

    abstract fun save(): Job?

    protected val editable: Boolean get() = repository.isCurrent(lease) && !progress.value.busy &&
        !progress.value.blocked && !progress.value.completed && !progress.value.closed

    protected fun changed() {
        mutableProgress.value = progress.value.copy(error = null, revision = progress.value.revision + 1)
    }

    protected open fun onClosed() {}

    internal fun cancel() {
        generation++; work?.cancel(); work = null
        presentation.clear()
        onClosed()
        mutableProgress.value = progress.value.copy(closed = true, busy = false, writing = false)
    }

    protected fun request(
        writing: Boolean, complete: Boolean = writing,
        retryCodes: Set<String> =
        setOf("overlap", "out_of_calendar", "invalid_event", "invalid_hours", "timezone_legacy_events"),
        action: suspend () -> Unit,
    ): Job? {
        if (!repository.isCurrent(lease) || progress.value.closed || progress.value.completed || progress.value.busy || (writing && !canSave)) return null
        val request = ++generation
        if (writing) presentation.child = null
        mutableProgress.value = progress.value.copy(busy = true, writing = writing, error = null)
        return scope.launch {
            try {
                action()
                ensureActive()
                if (request == generation && repository.isCurrent(lease)) {
                    mutableProgress.value = progress.value.copy(completed = complete, blocked = false, busy = false, writing = false)
                }
            } catch (cancelled: CancellationException) {
                if (request == generation && writing) {
                    mutableProgress.value = progress.value.copy(blocked = true, error = uncertain)
                }
                throw cancelled
            } catch (error: Exception) {
                if (request == generation && repository.isCurrent(lease)) {
                    // Only an explicit validation rejection proves a write did not
                    // happen. Unknown/conflict/network outcomes require a fresh read.
                    val rejected = error is Mango9CalendarFailure && error.status in 400..499 &&
                        error.code in retryCodes
                    val blocked = if (writing) !rejected else progress.value.blocked
                    mutableProgress.value = progress.value.copy(
                        blocked = blocked,
                        error = Mango9CalendarScreen.message(error) + if (blocked) " $uncertain" else "",
                    )
                }
            } finally {
                if (request == generation) mutableProgress.value = progress.value.copy(busy = false, writing = false)
            }
        }.also { work = it }
    }

    private companion object {
        const val uncertain = "Check the saved appointment or reload business hours before trying again. The save may have reached the server."
    }
}

class Mango9AppointmentEdit internal constructor(
    lease: Mango9SessionLease, repository: Mango9CalendarRepository, scope: CoroutineScope,
    val metadata: Mango9CalendarMetadata, val event: Mango9Appointment?,
    val zone: ZoneId, initial: Mango9AppointmentDraft,
) : Mango9CalendarEdit(lease, repository, scope) {
    var draft: Mango9AppointmentDraft = initial
        set(value) { if (editable && value != field) { field = value; changed() } }
    val owner: Boolean get() = event == null || event.ownerId.toString() == lease.session.userId
    val validation: String? get() = draft.validationMessage(event, owner)
    override val canSave: Boolean get() = editable && validation == null &&
        draft.payload(event, owner, zone.id).length() > 0

    override fun save(): Job? {
        val submitted = draft
        return request(true) { repository.save(lease, submitted, metadata, event, zone.id) }
    }
}

class Mango9BusinessHoursEdit internal constructor(
    lease: Mango9SessionLease, repository: Mango9CalendarRepository, scope: CoroutineScope,
    private val saved: (Mango9SessionLease, Mango9BusinessHours) -> Unit,
) : Mango9CalendarEdit(lease, repository, scope) {
    var original: Mango9BusinessHours? = null
        private set
    var accepted: Mango9BusinessHours? = null
        private set
    private var currentDraft: Mango9BusinessHours? = null
    var draft: Mango9BusinessHours?
        get() = currentDraft
        set(value) {
            if (editable && value != null && original != null) {
                val baseline = original!!
                currentDraft = value.copy(
                    timezone = if (baseline.timezoneEditable) value.timezone else baseline.timezone,
                    timezoneEditable = baseline.timezoneEditable, defaults = baseline.defaults, revision = baseline.revision,
                )
                changed()
            }
        }
    val value: Mango9BusinessHours? get() = draft
    val timezoneChanged: Boolean get() = value?.timezone != original?.timezone
    override val canSave: Boolean get() = editable && value != null && value != original && value?.validationMessage == null

    fun reload(): Job? = request(false) {
        val loaded = repository.businessHours(lease)
        // The repository checks cancellation and the original selection before returning.
        original = loaded; currentDraft = loaded
    }

    override fun save(): Job? {
        val submitted = value ?: return null
        val baseline = original ?: return null
        return request(true) {
            val result = repository.saveBusinessHours(lease, submitted, baseline)
            accepted = result
            if (repository.isCurrent(lease)) saved(lease, result)
        }
    }
}

/** One editor per host. Rotation detaches its UI, not its operation or captured account. */
@MainThread
class Mango9CalendarEdits(
    private val repository: Mango9CalendarRepository,
    private val scope: CoroutineScope,
    private val hoursSaved: (Mango9SessionLease, Mango9BusinessHours) -> Unit = { _, _ -> },
) {
    var active: Mango9CalendarEdit? = null
        private set

    fun checkAccount() {
        active?.takeUnless { repository.isCurrent(it.lease) }?.let { active = null; it.cancel() }
    }

    fun dismiss(edit: Mango9CalendarEdit): Boolean {
        if (active !== edit || edit.progress.value.writing) return false
        active = null; edit.cancel()
        return true
    }

    fun appointment(
        lease: Mango9SessionLease, metadata: Mango9CalendarMetadata, event: Mango9Appointment?, creation: Mango9AppointmentCreationContext?,
        editingZone: ZoneId = ZoneId.systemDefault(),
    ): Mango9AppointmentEdit? {
        checkAccount()
        if (active != null || !repository.isCurrent(lease)) return null
        if (event == null && (creation == null || !metadata.capabilities.create)) return null
        // Match the existing-appointment detail view and iOS editor. Capture the
        // display zone once; editing must not rewrite the event's stored zone.
        val zone = if (event == null) creation!!.zone else editingZone
        val draft = event?.let(Mango9AppointmentDraft::from) ?: Mango9AppointmentDraft(
            "", "", creation!!.start, creation.start.plusSeconds(1800),
            creation.activity?.takeIf {
                it in metadata.activities
            } ?: metadata.activities.firstOrNull { it == "appointment" } ?: metadata.activities.firstOrNull().orEmpty(),
            metadata.priorities.firstOrNull { it == "medium" } ?: metadata.priorities.firstOrNull().orEmpty(),
            null, creation.contact, emptySet(),
        )
        return Mango9AppointmentEdit(lease, repository, scope, metadata, event, zone, draft).also { active = it }
    }

    fun businessHours(lease: Mango9SessionLease): Mango9BusinessHoursEdit? {
        checkAccount()
        if (active != null || !repository.isCurrent(lease)) return null
        return Mango9BusinessHoursEdit(lease, repository, scope, hoursSaved).also { active = it; it.reload() }
    }

    fun detail(
        lease: Mango9SessionLease, displayed: Mango9Appointment, metadata: Mango9CalendarMetadata,
        now: () -> java.time.Instant = java.time.Instant::now,
    ): Mango9AppointmentDetail? {
        checkAccount()
        if (active != null || !repository.isCurrent(lease)) return null
        return Mango9AppointmentDetail(lease, repository, scope, displayed, metadata, now).also { active = it; it.reload() }
    }
}
