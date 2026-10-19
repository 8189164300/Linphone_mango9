/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import androidx.annotation.MainThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class Mango9ReminderSelection(val lease: Mango9SessionLease, val event: Mango9Appointment, val metadata: Mango9CalendarMetadata)

internal data class Mango9ForegroundReminderState(
    val item: Mango9ReminderItem? = null,
    val opening: Boolean = false,
    val error: Boolean = false,
    val selection: Mango9ReminderSelection? = null,
)

/** All UI state is main-thread owned. Eligibility is also checked after every suspension. */
@MainThread
internal class Mango9ForegroundReminders(
    private val repository: Mango9CalendarRepository,
    private val scope: CoroutineScope,
    private val history: Mango9ReminderHistory,
    private val canPresent: () -> Boolean,
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val pollMillis: Long = 15_000,
    private val visibleMillis: Long = 20_000,
) {
    private val mutableState = MutableStateFlow(Mango9ForegroundReminderState())
    val state = mutableState.asStateFlow()
    private var active = false
    private var lease: Mango9SessionLease? = null
    private var generation = 0L
    private var polling: Job? = null
    private var expiry: Job? = null
    private var opening: Job? = null
    private var visibleUntil = 0L

    fun setActive(value: Boolean) {
        if (!value || !canPresent()) {
            active = false; clear(); return
        }
        if (active) { accountChanged(); return }
        active = true; restart()
    }

    fun accountChanged() {
        if (lease?.let(repository::isCurrent) == true) return
        clear()
        if (active && canPresent()) restart()
    }

    /** A save refreshes server truth, but never dismisses an already-open appointment. */
    fun changed() {
        if (!active || !canPresent()) { clear(); return }
        val selected = state.value.selection?.takeIf { repository.isCurrent(it.lease) }
        clear(selected); restart()
    }

    fun dismissSelection() {
        mutableState.value = state.value.copy(selection = null)
        changed()
    }

    private fun clear(selection: Mango9ReminderSelection? = null) {
        generation++
        polling?.cancel(); polling = null; expiry?.cancel(); expiry = null; opening?.cancel(); opening = null
        lease = null; visibleUntil = 0L; mutableState.value = Mango9ForegroundReminderState(selection = selection)
    }

    private fun valid(request: Long, original: Mango9SessionLease) = active && canPresent() &&
        generation == request && repository.isCurrent(original)

    private fun restart() {
        val original = repository.capture() ?: return
        lease = original
        val request = ++generation
        polling?.cancel()
        polling = scope.launch {
            while (isActive && valid(request, original)) {
                try {
                    history.ready()
                    ensureActive()
                    if (!valid(request, original)) { if (generation == request) clear(); return@launch }
                    val feed = repository.reminderFeed(original)
                    ensureActive()
                    if (!valid(request, original)) { if (generation == request) clear(); return@launch }
                    // Never derive due reminders from device time or stale/offline data.
                    val candidates = if (feed.settings?.inAppReminders != false) feed.due() else emptyList()
                    val visible = state.value.item
                    val retained = visible?.takeIf { elapsedMillis() < visibleUntil }?.let { shown ->
                        candidates.firstOrNull { it.event.id == shown.event.id && it.reminder.revision == shown.reminder.revision }
                    }
                    if (retained != null) {
                        mutableState.value = state.value.copy(item = retained)
                    } else {
                        expiry?.cancel()
                        val next = candidates.firstOrNull {
                            history.allows(Mango9ReminderCadence.key(original.session, it), feed.serverTime, feed.settings?.repeatMinutes ?: 0)
                        }
                        mutableState.value = state.value.copy(item = next, error = false)
                        if (next != null) {
                            history.record(Mango9ReminderCadence.key(original.session, next), feed.serverTime)
                            visibleUntil = elapsedMillis() + visibleMillis
                            expiry = scope.launch {
                                delay(visibleMillis)
                                if (valid(request, original) && state.value.item?.let {
                                    it.event.id == next.event.id && it.reminder.revision == next.reminder.revision
                                } == true
                                ) {
                                    mutableState.value = state.value.copy(item = null, error = false)
                                }
                            }
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {
                    if (valid(request, original)) {
                        expiry?.cancel(); mutableState.value = state.value.copy(item = null, error = false)
                    } else if (generation == request) {
                        clear(); return@launch
                    }
                }
                delay(pollMillis)
            }
            // No late account or call can leave a stale banner visible.
            if (generation == request) clear()
        }
    }

    fun open() {
        val original = lease ?: return
        val item = state.value.item ?: return
        val request = generation
        if (state.value.opening || !valid(request, original)) return
        mutableState.value = state.value.copy(opening = true, error = false)
        opening = scope.launch {
            try {
                val metadata = repository.metadata(original)
                ensureActive()
                if (!valid(request, original)) { if (generation == request) clear(); return@launch }
                val event = repository.appointment(original, item.event.id)
                ensureActive()
                if (valid(request, original)) {
                    mutableState.value = state.value.copy(selection = Mango9ReminderSelection(original, event, metadata))
                } else if (generation == request) clear()
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {
                if (valid(request, original)) {
                    mutableState.value = state.value.copy(error = true)
                } else if (generation == request) clear()
            } finally {
                if (generation == request) mutableState.value = state.value.copy(opening = false)
            }
        }
    }
}
