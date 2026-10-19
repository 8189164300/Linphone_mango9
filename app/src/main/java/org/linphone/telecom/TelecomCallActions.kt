/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.telecom

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.Call
import org.linphone.core.CallListenerStub
import org.linphone.core.Conference
import org.linphone.core.Core
import org.linphone.utils.LinphoneUtils

/** A Telecom callback succeeds only after the requested SDK/media transition. */
internal class TelecomCallActions(
    private val call: Call,
    private val ownsCall: (Core) -> Boolean,
    private val onApplied: (Action) -> Unit,
    private val answer: (Call, Boolean) -> Unit = { target, video -> coreContext.answerCall(target, requestedVideo = video) },
    private val terminate: (Call) -> Unit = { coreContext.terminateCall(it) },
    private val timeoutMillis: Long = 4_000, // Leave room inside Telecom's 5-second deadline.
) {
    enum class Action { ANSWER, DISCONNECT, ACTIVE, INACTIVE }

    private val operations = Mutex()

    @Volatile var pending: Action? = null
        private set

    suspend fun perform(action: Action, video: Boolean = false) = withTimeout(timeoutMillis) {
        operations.withLock {
            pending = action
            try {
                suspendCancellableCoroutine<Unit> { continuation ->
                    val completed = AtomicBoolean()
                    var listener: CallListenerStub? = null // Only accessed on the Core queue.
                    var conference: Conference? = null
                    var conferenceObservation: TelecomConferenceObservation? = null
                    var capturesConference = false

                    fun finish(error: Throwable? = null) {
                        if (!completed.compareAndSet(false, true)) return
                        listener?.let(call::removeListener)
                        listener = null
                        conferenceObservation?.close()
                        if (error == null) {
                            try {
                                if (continuation.isActive) onApplied(action)
                                continuation.resume(Unit)
                            } catch (failure: Exception) { continuation.resumeWithException(failure) }
                        } else {
                            continuation.resumeWithException(error)
                        }
                    }

                    fun observe(state: Call.State): Boolean {
                        if (!continuation.isActive) return true
                        val ended = state == Call.State.End || state == Call.State.Error || state == Call.State.Released
                        if (ended) {
                            finish(if (action == Action.DISCONNECT) null else IllegalStateException("Call ended during Telecom action"))
                            return true
                        }
                        if (!ownsCall(coreContext.core)) {
                            finish(IllegalStateException("Telecom action lost its call owner"))
                            return true
                        }
                        val target = conference
                        if (capturesConference && call.conference?.nativePointer != target?.nativePointer) {
                            finish(IllegalStateException("Conference membership changed during Telecom action"))
                            return true
                        }
                        if (target != null && !target.isAvailableToTelecom()) {
                            finish(IllegalStateException("Conference ended during Telecom action"))
                            return true
                        }
                        val ready = when (action) {
                            Action.ANSWER -> state == Call.State.Connected || state == Call.State.StreamsRunning || state == Call.State.PausedByRemote
                            Action.ACTIVE -> if (target != null) {
                                target.isIn
                            } else {
                                state == Call.State.Connected || state == Call.State.StreamsRunning || state == Call.State.PausedByRemote
                            }
                            // The pinned SDK stops streams BEFORE publishing Pausing.
                            // Waiting for the remote SIP ACK would needlessly block a SIM call.
                            Action.INACTIVE -> if (target != null && target.call == null) {
                                !target.isIn
                            } else {
                                state == Call.State.Pausing || state == Call.State.Paused
                            }
                            Action.DISCONNECT -> false
                        }
                        if (ready) finish()
                        return ready
                    }
                    val ticket = coreContext.postOnCoreThreadCancellable { core ->
                        if (!continuation.isActive) return@postOnCoreThreadCancellable
                        try {
                            if (action == Action.DISCONNECT && call.state in arrayOf(Call.State.End, Call.State.Error, Call.State.Released)) {
                                observe(call.state)
                                return@postOnCoreThreadCancellable
                            }
                            check(ownsCall(core)) { "Telecom action no longer owns this live call" }
                            capturesConference = action == Action.ACTIVE || action == Action.INACTIVE
                            conference = if (capturesConference) call.conference else null
                            if (observe(call.state)) return@postOnCoreThreadCancellable
                            var resumeIssued = false
                            var conferenceCommandIssued = false

                            fun applyConferenceCommand() {
                                val target = conference ?: return
                                if (conferenceCommandIssued || !continuation.isActive || completed.get()) return
                                check(ownsCall(core) && call.conference?.nativePointer == target.nativePointer) {
                                    "Telecom conference action lost its owner"
                                }
                                check(target.isAvailableToTelecom()) {
                                    "Conference is no longer available"
                                }
                                // A remote conference's enter() calls resume internally. Do not
                                // call it while Pausing or let a void leave() no-op in Updating.
                                val canIssue = target.call == null || when (action) {
                                    Action.ACTIVE -> call.state == Call.State.Paused
                                    Action.INACTIVE -> call.state == Call.State.StreamsRunning
                                    else -> false
                                }
                                if (canIssue) {
                                    conferenceCommandIssued = true
                                    val result = if (action == Action.ACTIVE) target.enter() else target.leave()
                                    check(result == 0) { "SDK rejected conference media action" }
                                }
                            }

                            fun resumeWhenPaused() {
                                if (!resumeIssued && continuation.isActive && call.state == Call.State.Paused) {
                                    check(ownsCall(core)) { "Telecom resume lost its call owner" }
                                    resumeIssued = true
                                    check(call.resume() == 0) { "SDK rejected resume" }
                                }
                            }
                            listener = object : CallListenerStub() {
                                override fun onStateChanged(call: Call, state: Call.State?, message: String) {
                                    try {
                                        if (conference != null) {
                                            applyConferenceCommand()
                                        } else if (action == Action.ACTIVE) resumeWhenPaused()
                                        if (state != null) observe(state)
                                    } catch (error: Exception) { finish(error) }
                                }
                            }.also(call::addListener)
                            conferenceObservation = TelecomConferenceObservation {
                                try {
                                    if (!observe(call.state)) applyConferenceCommand()
                                } catch (error: Exception) { finish(error) }
                            }.also { it.observe(conference) }
                            // A cancellation while queued must never become a late answer/hold.
                            if (!continuation.isActive) return@postOnCoreThreadCancellable
                            when (action) {
                                Action.ANSWER -> {
                                    check(LinphoneUtils.isCallIncoming(call.state)) { "Call is not ringing" }
                                    answer(call, video)
                                }
                                Action.DISCONNECT -> terminate(call)
                                Action.ACTIVE -> {
                                    if (conference != null) {
                                        applyConferenceCommand()
                                    } else {
                                        check(call.state in arrayOf(Call.State.Paused, Call.State.Pausing, Call.State.Resuming)) {
                                            "Call cannot be resumed in this state"
                                        }
                                        // resume(Pausing) queues an SDK-internal future action and
                                        // returns -1. Wait here instead, where cancellation can
                                        // remove our listener and prevent a late resume.
                                        resumeWhenPaused()
                                    }
                                }
                                Action.INACTIVE -> if (conference != null) {
                                    applyConferenceCommand()
                                } else if (call.state != Call.State.Pausing) {
                                    check(call.pause() == 0) { "SDK rejected pause" }
                                }
                            }
                            observe(call.state)
                        } catch (error: Exception) { finish(error) }
                    }
                    continuation.invokeOnCancellation {
                        ticket.cancel()
                        coreContext.postOnCoreThread {
                            completed.set(true)
                            listener?.let(call::removeListener)
                            listener = null
                            conferenceObservation?.close()
                        }
                    }
                }
            } finally { pending = null }
        }
    }
}
