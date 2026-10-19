/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import androidx.annotation.MainThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class Mango9CrmSettingsState(
    val expanded: Boolean = false,
    val accountName: String? = null,
    val draft: Mango9CrmPreferences? = null,
    val baseline: Mango9CrmPreferences? = null,
    val busy: Boolean = false,
    val blocked: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
) {
    val canSave: Boolean get() = draft != null && draft != baseline && !busy && !blocked
}

/** Retained by a ViewModel. Collapsing/recreating views never discards a dirty draft. */
@MainThread
class Mango9CrmSettingsScreen(
    private val repository: Mango9CalendarRepository,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(Mango9CrmSettingsState())
    val state = mutableState.asStateFlow()
    private var lease: Mango9SessionLease? = null
    private var generation = 0L
    private var work: Job? = null

    init { synchronizeAccount() }

    /** Also checks at every interaction; an account notification is not our security fence. */
    private fun synchronizeAccount(): Boolean {
        val current = repository.capture()
        if (lease?.let(repository::isCurrent) == true || (lease == null && current == null)) return false
        ++generation
        work?.cancel()
        work = null
        lease = current
        mutableState.value = Mango9CrmSettingsState(
            expanded = state.value.expanded,
            accountName = current?.session?.let { it.displayName?.takeIf(String::isNotBlank) ?: it.loginId },
        )
        return true
    }

    fun accountChanged() {
        if (synchronizeAccount() && state.value.expanded) reload()
    }

    fun setExpanded(expanded: Boolean) {
        synchronizeAccount()
        val previous = state.value
        mutableState.value = previous.copy(expanded = expanded)
        if (expanded && !previous.expanded && !previous.busy &&
            (previous.draft == null || previous.draft == previous.baseline) && !previous.blocked
        ) {
            reload()
        }
    }

    fun capture(): Mango9SessionLease? = lease?.takeIf(repository::isCurrent)

    fun edit(original: Mango9SessionLease?, change: (Mango9CrmPreferences) -> Mango9CrmPreferences) {
        if (synchronizeAccount()) { if (state.value.expanded) reload(); return }
        if (original == null || original != lease || !repository.isCurrent(original) || state.value.busy) return
        val draft = state.value.draft ?: return
        val edited = change(draft).copy(revision = draft.revision, accountTimezone = draft.accountTimezone)
        if (!edited.isValid) return
        mutableState.value = state.value.copy(draft = edited, saved = false)
    }

    fun reload(): Job? {
        synchronizeAccount()
        if (state.value.busy) return null
        val current = lease ?: return null
        return request(current, false) { repository.preferences(current) }
    }

    fun save(): Job? {
        if (synchronizeAccount()) { if (state.value.expanded) reload(); return null }
        val current = capture() ?: return null
        if (!state.value.canSave) return null
        val draft = state.value.draft ?: return null
        return request(current, true) { repository.savePreferences(current, draft) }
    }

    private fun request(
        current: Mango9SessionLease, writing: Boolean, operation: suspend () -> Mango9CrmPreferences,
    ): Job {
        val request = ++generation
        mutableState.value = state.value.copy(busy = true, error = null, saved = false)
        return scope.launch {
            try {
                val accepted = operation()
                ensureActive()
                if (request != generation || !repository.isCurrent(current)) return@launch
                mutableState.value = state.value.copy(
                    draft = accepted, baseline = accepted, blocked = false, saved = writing,
                )
            } catch (cancelled: CancellationException) {
                // A cancelled PATCH might have reached the server. Never replay it.
                if (request == generation && repository.isCurrent(current) && writing) {
                    mutableState.value = state.value.copy(blocked = true, error = reloadMessage)
                }
                throw cancelled
            } catch (error: Exception) {
                if (request == generation && repository.isCurrent(current)) {
                    mutableState.value = state.value.copy(
                        blocked = true,
                        error = Mango9CalendarScreen.message(error) + if (writing) " $reloadMessage" else "",
                    )
                }
            } finally {
                if (request == generation) {
                    if (repository.isCurrent(current)) {
                        mutableState.value = state.value.copy(busy = false)
                    } else {
                        accountChanged()
                    }
                }
            }
        }.also { work = it }
    }

    private companion object {
        const val reloadMessage = "Reload saved CRM settings before trying again."
    }
}

/** In-process invalidation only: no credentials, account data or preferences persisted. */
object Mango9CalendarChanges {
    private val mutableVersion = MutableStateFlow(0L)
    val version = mutableVersion.asStateFlow()

    @Synchronized internal fun saved() { mutableVersion.value += 1 }
}
