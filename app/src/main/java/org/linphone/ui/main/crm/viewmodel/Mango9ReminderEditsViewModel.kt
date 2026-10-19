/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.ui.main.crm.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import org.linphone.mango9.Mango9CalendarEdits
import org.linphone.mango9.Mango9CalendarRepository

/** The reminder host is Activity-scoped; its editor survives replacement of that Activity. */
class Mango9ReminderEditsViewModel internal constructor(application: Application, val repository: Mango9CalendarRepository) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, Mango9CalendarRepository(application))

    val edits = Mango9CalendarEdits(repository, viewModelScope)
}
