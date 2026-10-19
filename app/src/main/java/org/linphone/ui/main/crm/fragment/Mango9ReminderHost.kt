/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.ui.main.crm.fragment

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.findNavController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.linphone.R
import org.linphone.mango9.Mango9CalendarChanges
import org.linphone.mango9.Mango9CalendarEdits
import org.linphone.mango9.Mango9CalendarRepository
import org.linphone.mango9.Mango9ForegroundReminders
import org.linphone.mango9.Mango9ReminderCallGate
import org.linphone.mango9.Mango9ReminderCalls
import org.linphone.mango9.Mango9ReminderFileHistory
import org.linphone.mango9.Mango9ReminderHistory
import org.linphone.mango9.Mango9ReminderSelection
import org.linphone.mango9.Mango9SessionStore
import org.linphone.ui.call.CallActivity

/** Only the active MainActivity presents reminders; never selects an account or posts OS notifications. */
internal class Mango9ReminderHost(
    private val activity: AppCompatActivity,
    private val banner: Mango9ReminderBanner,
    private val repository: Mango9CalendarRepository = Mango9CalendarRepository(activity),
    history: Mango9ReminderHistory = Mango9ReminderFileHistory.get(activity),
    calls: ((() -> Unit) -> Mango9ReminderCalls) = { Mango9ReminderCallGate(it) },
    accountChanges: Flow<Long> = Mango9SessionStore.contextChanges,
    changes: Flow<Long> = Mango9CalendarChanges.version,
    retainedEdits: Mango9CalendarEdits? = null,
) : DefaultLifecycleObserver, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var resumed = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    private var closed = false
    private val displayedCalls = linkedSetOf<Activity>()
    private var gate: Mango9ReminderCalls? = null
    private var shown: Mango9ReminderSelection? = null
    internal val reminders = Mango9ForegroundReminders(repository, scope, history, ::eligible)
    private val dialogs = Mango9AppointmentDialogs(activity, scope, repository, {}, { lease, contact ->
        if (eligible() && repository.isCurrent(lease)) {
            reminders.dismissSelection()
            activity.findNavController(R.id.main_nav_container).navigate(R.id.action_global_mango9CrmFragment, Mango9CrmFragment.recordArguments(contact, lease))
        }
    }, onAllClosed = { if (!closed) reminders.dismissSelection() }, retainedEdits = retainedEdits)
    private val windows = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            if (activity is CallActivity) { displayedCalls.add(activity); updateActive() }
        }

        override fun onActivityStopped(activity: Activity) {
            if (displayedCalls.remove(activity)) updateActive()
        }

        override fun onActivityDestroyed(activity: Activity) {
            if (displayedCalls.remove(activity)) updateActive()
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

        override fun onActivityResumed(activity: Activity) {}

        override fun onActivityPaused(activity: Activity) {}

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    }

    init {
        gate = calls(::updateActive)
        banner.onOpen = { if (eligible() && !dialogs.hasEditor) reminders.open() }
        activity.application.registerActivityLifecycleCallbacks(windows)
        scope.launch {
            reminders.state.collect { state ->
                banner.render(state)
                if (shown != state.selection) {
                    if (!dialogs.hasEditor) dialogs.close()
                    shown = state.selection
                    state.selection?.takeIf { !dialogs.hasEditor && eligible() && repository.isCurrent(it.lease) }?.let {
                        dialogs.detail(it.lease, it.event, it.metadata)
                    }
                }
            }
        }
        scope.launch { accountChanges.collect { reminders.accountChanged(); dialogs.closeExpired() } }
        scope.launch { changes.drop(1).collect { reminders.changed() } }
        activity.lifecycle.addObserver(this)
        updateActive()
    }

    private fun eligible() = !closed && resumed && !activity.isFinishing && !activity.isDestroyed && displayedCalls.isEmpty() && gate?.blocked == false

    private fun updateActive() {
        if (closed) return
        val allowed = eligible()
        if (!allowed) dialogs.detach() else dialogs.resumeEdits()
        reminders.setActive(allowed)
    }

    override fun onResume(owner: LifecycleOwner) { resumed = true; updateActive() }

    override fun onPause(owner: LifecycleOwner) { resumed = false; updateActive() }

    override fun onDestroy(owner: LifecycleOwner) = close()

    override fun close() {
        if (closed) return
        closed = true; gate?.close(); gate = null; dialogs.detach(); reminders.setActive(false)
        banner.onOpen = {}; banner.render(reminders.state.value)
        activity.lifecycle.removeObserver(this); activity.application.unregisterActivityLifecycleCallbacks(windows)
        displayedCalls.clear(); scope.cancel()
    }
}
