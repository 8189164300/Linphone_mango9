/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.telecom

import android.telecom.DisconnectCause
import androidx.annotation.WorkerThread
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.concurrent.atomic.AtomicBoolean
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.core.Call
import org.linphone.core.CallListenerStub
import org.linphone.core.Reason
import org.linphone.core.tools.Log
import org.linphone.utils.AudioUtils
import org.linphone.utils.Event
import org.linphone.utils.LinphoneUtils

internal class TelecomCallControlCallback(
    private val call: Call,
    private val callControl: CallControlScope,
    parentScope: CoroutineScope,
    private val isCurrentCall: () -> Boolean,
    private val pendingTelecomAction: () -> TelecomCallActions.Action? = { null },
    private val isVideoEnabled: () -> Boolean = { LinphoneUtils.isVideoEnabled(call) },
    private val canReportActive: () -> Boolean = { true },
) {
    companion object {
        private const val TAG = "[Telecom Call Control Callback]"

        @WorkerThread
        fun disconnectCause(call: Call): Int = when (call.reason) {
            Reason.NotAnswered -> if (call.dir == Call.Dir.Incoming) DisconnectCause.MISSED else DisconnectCause.REMOTE
            Reason.Declined -> DisconnectCause.REJECTED
            // AndroidX only accepts LOCAL, REMOTE, MISSED and REJECTED. BUSY
            // throws on transactional Telecom and can leave the system call open.
            Reason.Busy -> if (call.dir == Call.Dir.Incoming) DisconnectCause.MISSED else DisconnectCause.REMOTE
            else -> if (call.state == Call.State.Error) DisconnectCause.REMOTE else DisconnectCause.LOCAL
        }
    }

    // Owned by this callback and explicitly closed on every terminal path. Do not
    // parent collectors to addCall's coroutine: addCall waits for its children
    // before returning, so those collectors would prevent the finally cleanup.
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob())
    private val installed = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val disconnecting = AtomicBoolean()
    private val controlOperations = Mutex()
    private var reportedActive: Boolean? = null // Core thread only.
    private var reportRevision = 0L // Fence state reports queued behind an older Android transaction.
    private val answered = AtomicBoolean()
    private var mutedByTelecomManager = false
    private val conferenceObservation = TelecomConferenceObservation { updateState(call.state) }

    private val callListener = object : CallListenerStub() {
        @WorkerThread
        override fun onStateChanged(call: Call, state: Call.State?, message: String) {
            updateState(state)
        }
    }

    fun onCallControlCallbackSet() {
        if (closed.get() || !installed.compareAndSet(false, true)) return
        Log.i(
            "$TAG Callback have been set for call, Telecom call ID is [${callControl.getCallId()}]"
        )

        coreContext.postOnCoreThread {
            if (!closed.get() && !disconnecting.get()) {
                call.addListener(callListener)
                updateState(call.state)
            }
        }

        callControl.availableEndpoints.onEach { list ->
            Log.i("$TAG New available audio endpoints list but ignoring it")
        }.launchIn(scope)

        callControl.currentCallEndpoint.onEach { endpoint ->
            Log.i("$TAG Android requests us to use [${endpoint.name}] audio endpoint with type [${endpointTypeToString(endpoint.type)}], ignoring it")
        }.launchIn(scope)

        callControl.isMuted.onEach { muted ->
            coreContext.postOnCoreThread {
                if (closed.get() || disconnecting.get() || !isCurrentCall()) return@postOnCoreThread
                val conference = call.conference
                if (conference?.isAvailableToTelecom() == false) return@postOnCoreThread
                val callState = call.state
                Log.i(
                    "$TAG We're asked to [${if (muted) "mute" else "unmute"}] the call in state [$callState]"
                )
                // Only follow un-mute requests for not outgoing calls (such as joining a conference muted)
                // and if connected to Android Auto that has a way to let user mute/unmute from the car directly
                // or if we muted the call previously following Telecom Manager request.
                if (muted || mutedByTelecomManager || (!LinphoneUtils.isCallOutgoing(callState, false) && coreContext.isConnectedToAndroidAuto)) {
                    mutedByTelecomManager = muted
                    // A local conference shares one mixer. Muting just its
                    // representative Call would leave the other legs audible.
                    if (conference != null) conference.microphoneMuted = muted else call.microphoneMuted = muted
                    coreContext.refreshMicrophoneMuteStateEvent.postValue(Event(true))
                } else {
                    if (coreContext.isConnectedToAndroidAuto) {
                        Log.w(
                            "$TAG Not following unmute request because call is in state [$callState]"
                        )
                    } else {
                        Log.w(
                            "$TAG Not following unmute request because user isn't connected to Android Auto and call is in state [$callState]"
                        )
                    }
                }
            }
        }.launchIn(scope)
    }

    @WorkerThread
    private fun updateState(state: Call.State?) {
        if (closed.get() || disconnecting.get()) return
        if (state == Call.State.End || state == Call.State.Error || state == Call.State.Released) {
            if (pendingTelecomAction() != TelecomCallActions.Action.DISCONNECT) disconnect(disconnectCause(call))
            return
        }
        if (!isCurrentCall()) {
            disconnect(DisconnectCause.LOCAL)
            return
        }
        val conference = conferenceObservation.observe(call.conference)
        if (conference?.isAvailableToTelecom() == false) {
            disconnect(DisconnectCause.LOCAL)
            return
        }
        if (pendingTelecomAction() != null) return // Android is already applying this change.
        when (state) {
            Call.State.Connected, Call.State.StreamsRunning, Call.State.PausedByRemote -> {
                if (conference != null && conference.call == null && !conference.isIn) {
                    reportInactive()
                } else if (reportedActive != true && canReportActive()) {
                    reportedActive = true
                    reportActive()
                }
            }
            Call.State.Pausing, Call.State.Paused -> {
                reportInactive()
            }
            else -> Unit // Resuming is only a request; streams are not active yet.
        }
    }

    @WorkerThread
    fun refreshFromCore() = updateState(call.state)

    @WorkerThread
    private fun reportInactive() {
        if (reportedActive == false) return
        reportedActive = false
        controlOperation(active = false) {
            answered.set(true)
            callControl.setInactive()
        }
    }

    @WorkerThread
    private fun reportActive() {
        val isVideo = isVideoEnabled()
        val incoming = call.dir == Call.Dir.Incoming
        val type = if (isVideo) {
            CallAttributesCompat.CALL_TYPE_VIDEO_CALL
        } else {
            CallAttributesCompat.CALL_TYPE_AUDIO_CALL
        }
        controlOperation(active = true) {
            // answer is only for the FIRST activation of an incoming call.
            // A late-attached outgoing/held call must use setActive instead.
            val result = if (incoming && !answered.get()) callControl.answer(type) else callControl.setActive()
            if (result !is CallControlResult.Error) answered.set(true)
            result
        }

        if (isVideo && corePreferences.routeAudioToSpeakerWhenVideoIsEnabled) {
            Log.i("$TAG Answering video call, routing audio to speaker")
            AudioUtils.routeAudioToSpeaker(call)
        }
    }

    // Check SDK ownership and media again at the head of the Android operation
    // queue. A conference may merge, split or end while an earlier report waits.
    private suspend fun mayReport(active: Boolean, revision: Long): Boolean = suspendCancellableCoroutine { continuation ->
        val ticket = coreContext.postOnCoreThreadCancellable {
            if (!continuation.isActive) return@postOnCoreThreadCancellable
            try {
                if (revision != reportRevision || closed.get() || disconnecting.get() ||
                    !isCurrentCall() || pendingTelecomAction() != null
                ) {
                    if (revision == reportRevision) reportedActive = null
                    continuation.resume(false)
                    return@postOnCoreThreadCancellable
                }
                val state = call.state
                val conference = call.conference
                val liveMedia = state in arrayOf(Call.State.Connected, Call.State.StreamsRunning, Call.State.PausedByRemote)
                val localHeld = conference != null && conference.call == null && !conference.isIn
                val stateMatches = if (active) {
                    liveMedia && !localHeld && canReportActive()
                } else {
                    state == Call.State.Pausing || state == Call.State.Paused || (liveMedia && localHeld)
                }
                val allowed = conference?.isAvailableToTelecom() != false && stateMatches
                if (!allowed && revision == reportRevision) reportedActive = null
                continuation.resume(allowed)
            } catch (error: Exception) { continuation.resumeWithException(error) }
        }
        continuation.invokeOnCancellation { ticket.cancel() }
    }

    @WorkerThread
    private fun controlOperation(active: Boolean, operation: suspend () -> CallControlResult) {
        val revision = ++reportRevision
        scope.launch {
            controlOperations.withLock {
                if (closed.get() || disconnecting.get()) return@withLock
                try {
                    val result = withTimeout(4_000) {
                        if (mayReport(active, revision)) operation() else null
                    }
                    if (result is CallControlResult.Error) {
                        Log.e("$TAG Call control state update failed: $result")
                        coreContext.postOnCoreThread { if (revision == reportRevision) reportedActive = null }
                    }
                } catch (cancelled: CancellationException) {
                    coreContext.postOnCoreThread { if (revision == reportRevision) reportedActive = null }
                    throw cancelled
                } catch (error: Exception) {
                    Log.e("$TAG Call control state update failed: $error")
                    coreContext.postOnCoreThread { if (revision == reportRevision) reportedActive = null }
                }
            }
        }
    }

    fun disconnect(cause: Int) {
        if (closed.get() || !disconnecting.compareAndSet(false, true)) return
        coreContext.postOnCoreThread { call.removeListener(callListener); conferenceObservation.close() }
        scope.launch {
            try {
                controlOperations.withLock {
                    val result = withTimeout(4_000) { callControl.disconnect(DisconnectCause(cause)) }
                    if (result is CallControlResult.Error) Log.e("$TAG Failed to disconnect: $result")
                }
            } catch (error: Exception) {
                Log.e("$TAG Could not disconnect call control: $error")
            } finally {
                close()
            }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        coreContext.postOnCoreThread { call.removeListener(callListener); conferenceObservation.close() }
    }

    @WorkerThread
    fun appliedByTelecom(action: TelecomCallActions.Action) {
        if (closed.get()) return
        ++reportRevision
        when (action) {
            TelecomCallActions.Action.ANSWER, TelecomCallActions.Action.ACTIVE -> {
                answered.set(true)
                reportedActive = true
            }
            TelecomCallActions.Action.INACTIVE -> {
                answered.set(true)
                reportedActive = false
            }
            TelecomCallActions.Action.DISCONNECT -> Unit
        }
    }

    private fun endpointTypeToString(type: Int): String {
        return when (type) {
            CallEndpointCompat.TYPE_UNKNOWN -> "UNKNOWN"
            CallEndpointCompat.TYPE_EARPIECE -> "EARPIECE"
            CallEndpointCompat.TYPE_BLUETOOTH -> "BLUETOOTH"
            CallEndpointCompat.TYPE_WIRED_HEADSET -> "WIRED HEADSET"
            CallEndpointCompat.TYPE_SPEAKER -> "SPEAKER"
            CallEndpointCompat.TYPE_STREAMING -> "STREAMING"
            else -> "UNEXPECTED: $type"
        }
    }
}
