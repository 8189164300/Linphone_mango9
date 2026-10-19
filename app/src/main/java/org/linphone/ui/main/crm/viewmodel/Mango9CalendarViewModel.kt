/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.ui.main.crm.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.time.ZoneId
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.linphone.mango9.Mango9AppointmentContact
import org.linphone.mango9.Mango9CalendarRepository
import org.linphone.mango9.Mango9CalendarChanges
import org.linphone.mango9.Mango9CalendarEdits
import org.linphone.mango9.Mango9CalendarScreen
import org.linphone.mango9.Mango9SessionLease
import org.linphone.mango9.Mango9SessionStore
import org.linphone.mango9.Mango9CalendarViewport
import org.linphone.mango9.Mango9SelectionStamp

class Mango9CalendarViewModel internal constructor(
    application: Application,
    val repository: Mango9CalendarRepository,
    val contextChanges: Flow<Long>,
    private val calendarChanges: Flow<Long>,
    internal val contacts:
    suspend (Mango9SessionLease, org.linphone.mango9.Mango9RecordKind, String, Int) -> org.linphone.mango9.Mango9CrmRecordPage = { lease, kind, query, page ->
        org.linphone.mango9.Mango9CrmRepository(application).records(lease, kind, query, page)
    },
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application, Mango9CalendarRepository(application), Mango9SessionStore.contextChanges, Mango9CalendarChanges.version
    )

    lateinit var screen: Mango9CalendarScreen
        private set
    val edits = Mango9CalendarEdits(repository, viewModelScope) { lease, hours ->
        if (::screen.isInitialized) screen.acceptHours(lease, hours)
    }
    private var accountJob: Job? = null
    private var changesJob: Job? = null
    private var monitoredLease: Mango9SessionLease? = null
    private var viewport: Pair<Mango9SelectionStamp, Mango9CalendarViewport>? = null
    var zoom = 68f

    private fun stamp(lease: Mango9SessionLease) =
        Mango9SelectionStamp(lease.session.sipIdentity, lease.loginGeneration, lease.selectionGeneration)

    fun retainViewport(lease: Mango9SessionLease, value: Mango9CalendarViewport) {
        if (repository.isCurrent(lease)) viewport = stamp(lease) to value
    }

    fun retainedViewport(lease: Mango9SessionLease): Mango9CalendarViewport? =
        viewport?.takeIf { it.first == stamp(lease) && repository.isCurrent(lease) }?.second

    private fun checkViewportOwner() {
        if (viewport?.first != repository.capture()?.let(::stamp)) viewport = null
    }

    fun initialize(contact: Mango9AppointmentContact?, contactLease: Mango9SessionLease? = repository.capture()) {
        if (!::screen.isInitialized) screen = Mango9CalendarScreen(repository, viewModelScope, contact, linkedLease = contactLease)
    }

    fun start() {
        edits.checkAccount()
        checkViewportOwner()
        accountJob?.cancel()
        changesJob?.cancel()
        screen.updateDeviceZone(ZoneId.systemDefault())
        screen.refresh()
        monitoredLease = repository.capture()
        accountJob = viewModelScope.launch {
            contextChanges.collect {
                edits.checkAccount()
                checkViewportOwner()
                val current = repository.capture()
                if (monitoredLease?.let(repository::isCurrent) != true && (monitoredLease != null || current != null)) {
                    monitoredLease = current
                    screen.accountChanged()
                }
            }
        }
        changesJob = viewModelScope.launch {
            calendarChanges.drop(1).collect { screen.refresh() }
        }
    }

    fun stop() { accountJob?.cancel(); accountJob = null; changesJob?.cancel(); changesJob = null; screen.stop() }
}
