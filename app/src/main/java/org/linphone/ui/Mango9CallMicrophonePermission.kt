/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.mango9.Mango9OutgoingCalls

/** The ActivityResult registry owns system dialogs; only an opaque call ticket is restored. */
internal class Mango9CallMicrophonePermission(private val activity: GenericActivity) {
    private var requestId: Long? = null
    private var rationaleVisible = false
    private var systemRequestInFlight = false
    private var dialog: AlertDialog? = null

    private val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        systemRequestInFlight = false
        val id = requestId
        requestId = null
        if (id != null && coreContext.isOutgoingCallPermissionPending(id)) {
            coreContext.completeOutgoingCallPermission(id, granted)
            if (!granted) showDenied()
        }
    }

    fun request(id: Long) {
        if (!systemRequestInFlight && requestId?.let(coreContext::isOutgoingCallPermissionPending) == false) {
            requestId = null
        }
        if (activity.isFinishing || activity.isDestroyed || requestId != null || systemRequestInFlight || dialog?.isShowing == true) {
            coreContext.completeOutgoingCallPermission(id, false)
            return
        }
        if (!coreContext.isOutgoingCallPermissionPending(id)) return
        requestId = id
        if (Mango9OutgoingCalls.hasMicrophone(activity)) {
            requestId = null
            coreContext.completeOutgoingCallPermission(id, true)
        } else if (ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)) {
            showRationale()
        } else {
            // false also means first request, not just "don't ask again". Let Android decide.
            launchSystemRequest()
        }
    }

    private fun showRationale() {
        rationaleVisible = true
        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.mango9_call_microphone_title)
            .setMessage(R.string.mango9_call_microphone_rationale)
            .setPositiveButton(R.string.mango9_call_microphone_continue) { _, _ ->
                rationaleVisible = false
                if (requestId?.let(coreContext::isOutgoingCallPermissionPending) == true) {
                    launchSystemRequest()
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancel() }
            .setOnCancelListener { cancel() }
            .show()
    }

    private fun showDenied() {
        if (activity.isFinishing || activity.isDestroyed) return
        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.mango9_call_microphone_off)
            .setMessage(R.string.mango9_call_microphone_denied)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.mango9_call_open_settings) { _, _ ->
                // The ticket was discarded before this action. onResume never dials.
                activity.goToAndroidPermissionSettings()
            }
            .show()
    }

    private fun launchSystemRequest() {
        systemRequestInFlight = true
        launcher.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun save(state: Bundle) {
        requestId?.let { state.putLong(KEY_ID, it) }
        state.putBoolean(KEY_RATIONALE, rationaleVisible)
        state.putBoolean(KEY_SYSTEM, systemRequestInFlight)
    }

    fun restore(state: Bundle?) {
        // Keep the old registry request reserved even if its call already timed out.
        // Its reply must not be delivered to a newer call's ticket.
        systemRequestInFlight = state?.getBoolean(KEY_SYSTEM) == true
        val id = state?.getLong(KEY_ID, 0L)?.takeIf { it != 0L } ?: return
        if (!coreContext.isOutgoingCallPermissionPending(id)) return // Never restore a dial after process death.
        requestId = id
        if (state.getBoolean(KEY_RATIONALE)) showRationale()
        // Otherwise the registered ActivityResult callback receives the in-flight system result.
    }

    fun stop() {
        if (!activity.isChangingConfigurations) {
            cancel()
            dialog?.dismiss()
        }
    }

    fun destroy() {
        if (!activity.isChangingConfigurations) cancel()
        dialog?.dismiss()
        dialog = null
    }

    private fun cancel() {
        requestId?.let { coreContext.completeOutgoingCallPermission(it, false) }
        requestId = null
        rationaleVisible = false
    }

    private companion object {
        const val KEY_ID = "mango9.outgoing.microphone.request"
        const val KEY_RATIONALE = "mango9.outgoing.microphone.rationale"
        const val KEY_SYSTEM = "mango9.outgoing.microphone.system"
    }
}
