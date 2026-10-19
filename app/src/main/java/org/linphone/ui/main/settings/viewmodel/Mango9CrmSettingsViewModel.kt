/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.ui.main.settings.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import org.linphone.mango9.Mango9CalendarRepository
import org.linphone.mango9.Mango9CrmSettingsScreen
import org.linphone.mango9.Mango9SessionStore

class Mango9CrmSettingsViewModel internal constructor(
    application: Application, repository: Mango9CalendarRepository, changes: Flow<Long>,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, Mango9CalendarRepository(application), Mango9SessionStore.contextChanges)

    val screen = Mango9CrmSettingsScreen(repository, viewModelScope)

    init {
        viewModelScope.launch { changes.collect { screen.accountChanged() } }
    }
}
