// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.ui.main.crm.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.linphone.mango9.Mango9CalendarRepository
import org.linphone.mango9.Mango9ManagedTask
import org.linphone.mango9.Mango9SessionLease
import org.linphone.mango9.tasks

internal data class Mango9TasksState(
    val items: List<Mango9ManagedTask> = emptyList(), val page: Int = 0,
    val hasMore: Boolean = false, val loading: Boolean = false, val error: String? = null
)

internal class Mango9TasksViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = Mango9CalendarRepository(app)
    private var lease: Mango9SessionLease? = null
    private var query = ""
    private var work: Job? = null
    private var generation = 0L
    val state = MutableLiveData(Mango9TasksState())

    fun load(owner: Mango9SessionLease?, search: String, more: Boolean = false) {
        if (owner == null || !repository.isCurrent(owner)) {
            work?.cancel(); generation++; state.value = Mango9TasksState(); lease = null; return
        }
        val previous = state.value ?: Mango9TasksState()
        val clean = search.trim().take(100)
        if (more && (previous.loading || !previous.hasMore || lease != owner || query != clean)) return
        work?.cancel(); val request = ++generation
        lease = owner; query = clean
        state.value = if (more) previous.copy(loading = true, error = null) else Mango9TasksState(loading = true)
        work = viewModelScope.launch {
            try {
                if (clean.isNotEmpty() && !more) delay(250)
                val page = repository.tasks(owner, if (more) previous.page + 1 else 1, clean)
                if (request != generation || !repository.isCurrent(owner)) return@launch
                val items = ((if (more) previous.items else emptyList()) + page.items).associateBy { it.id }.values.toList()
                state.value = Mango9TasksState(items, page.page, page.hasMore)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (request == generation && repository.isCurrent(owner)) {
                    val status = (e as? org.linphone.mango9.Mango9CalendarFailure)?.status
                    val message = when {
                        e is org.linphone.mango9.Mango9ApiException.Unauthorized || e is org.linphone.mango9.Mango9ApiException.InvalidCredentials -> "Sign in again to load tasks."
                        status == 403 -> "Tasks are not available for this account’s permissions."
                        status == 404 -> "The Tasks service is unavailable on this server."
                        e is org.json.JSONException -> "The Tasks response could not be read. Please retry."
                        else -> "Tasks could not be loaded. Please retry."
                    }
                    state.value = if (more && status !in listOf(401, 403)) {
                        previous.copy(loading = false, error = message)
                    } else {
                        Mango9TasksState(error = message)
                    }
                }
            }
        }
    }
}
