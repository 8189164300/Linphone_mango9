/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import android.os.ParcelUuid
import android.net.Uri
import android.os.Build
import androidx.core.telecom.CallsManager
import androidx.core.telecom.CallAttributesCompat
import android.telecom.DisconnectCause
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.Proxy
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.Call
import org.linphone.core.CallListener
import org.linphone.core.Reason
import org.linphone.core.Conference
import org.linphone.core.ConferenceListener
import org.linphone.core.Participant
import org.linphone.core.Factory
import org.linphone.telecom.TelecomCallActions
import org.linphone.telecom.TelecomCallActions.Action
import org.linphone.telecom.TelecomCallControlCallback
import org.linphone.telecom.isAvailableToTelecom

/** Real Android/Core dispatch and production bridges, controlled non-network SDK call fixtures. */
@RunWith(AndroidJUnit4::class)
class Mango9TelecomLifecycleInstrumentedTest {
    @org.junit.After fun persistSdkFixtureCleanup() = Mango9SdkTestCleanup.flush()

    @Test fun realAndroidTelecomAcceptsTheBridgeTransitionsAndBusyCleanup() = runBlocking {
        assumeTrue("Synthetic system call is emulator-only", Build.MODEL.contains("sdk", true))
        coreContext.awaitReady()
        val native = NativeCall(Call.Dir.Outgoing)
        native.state = Call.State.OutgoingProgress
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = CallsManager(context)
        manager.registerAppWithTelecom(CallsManager.CAPABILITY_SUPPORTS_VIDEO_CALLING)
        val ready = CompletableDeferred<RecordingControl>()
        var callback: TelecomCallControlCallback? = null
        val session = launch(Dispatchers.Default) {
            manager.addCall(
                CallAttributesCompat(
                    "Mango9 local regression", Uri.parse("sip:fixture@example.invalid"),
                    CallAttributesCompat.DIRECTION_OUTGOING, CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
                    CallAttributesCompat.SUPPORTS_SET_INACTIVE, isLogExcluded = true
                ),
                {}, {}, {}, {},
            ) {
                val control = RecordingControl(this)
                callback = TelecomCallControlCallback(native.call, control, control, { true }, isVideoEnabled = { false })
                callback.onCallControlCallbackSet()
                ready.complete(control)
            }
        }
        var control: RecordingControl? = null
        try {
            val observed = withTimeout(8_000) { ready.await() }
            control = observed
            core { native.emit(Call.State.StreamsRunning) }
            eventually { observed.results.size == 1 }
            core { native.emit(Call.State.Paused) }
            eventually { observed.results.size == 2 }
            core { native.emit(Call.State.StreamsRunning) }
            eventually { observed.results.size == 3 }
            core { native.reason = Reason.Busy; native.emit(Call.State.End) }
            eventually { observed.results.size == 4 }
            withTimeout(5_000) { session.join() }
            assertEquals(listOf("active", "inactive", "active", "disconnect"), observed.results.map { it.first })
            assertTrue(observed.results.toString(), observed.results.all { it.second is CallControlResult.Success })
            core { assertTrue(native.listeners.isEmpty()) }
        } finally {
            callback?.close()
            withContext(NonCancellable) {
                if (session.isActive && control != null) {
                    runCatching { withTimeout(4_000) { control.disconnect(DisconnectCause(DisconnectCause.LOCAL)) } }
                }
                session.cancelAndJoin()
            }
        }
    }

    @Test fun lateOutgoingAttachmentUsesSetActiveNotIncomingAnswer() = fixture(Call.Dir.Outgoing) { f ->
        f.native.state = Call.State.StreamsRunning
        f.attach()
        f.callback.onCallControlCallbackSet()
        eventually { f.control.actions.size == 1 }
        assertEquals(listOf("active"), f.control.actions.toList())
        assertEquals(1, f.native.listeners.size)
    }

    @Test fun incomingAnswerIsOnceAndHeldCallUsesResume() = fixture { f ->
        f.native.state = Call.State.Connected
        f.attach()
        eventually { f.control.actions.size == 1 }
        core { f.native.emit(Call.State.StreamsRunning) }
        core { f.native.emit(Call.State.Paused) }
        eventually { f.control.actions.size == 2 }
        core { f.native.emit(Call.State.StreamsRunning) }
        eventually { f.control.actions.size == 3 }
        assertEquals(listOf("answer", "inactive", "active"), f.control.actions.toList())
    }

    @Test fun holdAndResumeAreReportedOnlyAfterMediaTransitionsComplete() = fixture(Call.Dir.Outgoing) { f ->
        f.native.state = Call.State.StreamsRunning
        f.attach()
        eventually { f.control.actions.size == 1 }
        core { f.native.emit(Call.State.Pausing) }
        eventually { f.control.actions.size == 2 }
        core { f.native.emit(Call.State.Paused) }
        core { f.native.emit(Call.State.Resuming) }
        delay(50)
        assertEquals(2, f.control.actions.size)
        core { f.native.emit(Call.State.StreamsRunning) }
        eventually { f.control.actions.size == 3 }
    }

    @Test fun outgoingBusyEndsExactlyOnceWithPermittedCauseAndNoCollectors() = fixture(Call.Dir.Outgoing) { f ->
        f.attach()
        eventually { f.control.mute.subscriptionCount.value == 1 }
        core {
            f.native.reason = Reason.Busy
            f.native.emit(Call.State.End)
            f.native.emit(Call.State.Error)
            f.native.emit(Call.State.Released)
        }
        eventually { f.control.causes.size == 1 && f.native.listeners.isEmpty() && f.control.subscribers() == 0 }
        assertEquals(listOf(DisconnectCause.REMOTE), f.control.causes.toList())
    }

    @Test fun incomingUnansweredAndBusyUseMissedInsteadOfInvalidBusy() = fixture { f ->
        core {
            f.native.reason = Reason.NotAnswered
            assertEquals(DisconnectCause.MISSED, TelecomCallControlCallback.disconnectCause(f.native.call))
            f.native.reason = Reason.Busy
            assertEquals(DisconnectCause.MISSED, TelecomCallControlCallback.disconnectCause(f.native.call))
            f.native.reason = Reason.Declined
            assertEquals(DisconnectCause.REJECTED, TelecomCallControlCallback.disconnectCause(f.native.call))
        }
    }

    @Test fun closingBeforeQueuedAttachmentCannotLeakAListener() = fixture { f ->
        val release = blockCore()
        try {
            f.callback.onCallControlCallbackSet()
            f.callback.close()
        } finally { release.countDown() }
        core { assertTrue(f.native.listeners.isEmpty()) }
        eventually { f.control.subscribers() == 0 }
        assertTrue(f.control.actions.isEmpty())
    }

    @Test fun aCloseRequestBeforeSetupIsRetainedAndPreventsAttachment() = fixture { f ->
        val release = blockCore()
        try {
            f.callback.disconnect(DisconnectCause.LOCAL)
            f.callback.onCallControlCallbackSet()
        } finally { release.countDown() }
        eventually { f.control.causes.size == 1 && f.control.subscribers() == 0 }
        core { assertTrue(f.native.listeners.isEmpty()) }
    }

    @Test fun staleOwnerAndClosedFlowsCannotMuteAnotherCall() = fixture { f ->
        f.attach()
        eventually { f.control.mute.subscriptionCount.value == 1 }
        f.control.mute.emit(true)
        eventually { f.native.muteWrites == 1 }
        f.current.set(false)
        f.control.mute.emit(false)
        core { assertEquals(1, f.native.muteWrites) }
        f.callback.close()
        eventually { f.control.subscribers() == 0 }
        f.control.mute.emit(true)
        core { assertEquals(1, f.native.muteWrites) }
    }

    @Test fun nativeCallEndedBeforeSetupDisconnectsWithoutAnswer() = fixture { f ->
        f.native.state = Call.State.End
        f.attach()
        eventually { f.control.causes.size == 1 && f.native.listeners.isEmpty() }
        assertFalse(f.control.actions.contains("answer"))
    }

    @Test fun systemHoldWaitsForStoppedMediaButNotTheRemoteSipAck() = fixture(Call.Dir.Outgoing) { f ->
        f.native.state = Call.State.StreamsRunning
        f.native.pauseStopsImmediately = false
        f.attach()
        eventually { f.control.actions.size == 1 }
        val holding = async { f.operations.perform(Action.INACTIVE) }
        eventually { f.native.pauseCalls == 1 }
        assertFalse(holding.isCompleted)
        assertEquals(Call.State.StreamsRunning, f.native.state)
        core { f.native.emit(Call.State.Pausing) }
        holding.await()
        assertEquals(listOf("active"), f.control.actions.toList())
        assertEquals(listOf(Action.INACTIVE), f.applied.toList())
        core { f.native.emit(Call.State.StreamsRunning) }
        eventually { f.control.actions.size == 2 }
        assertEquals(listOf("active", "active"), f.control.actions.toList())
    }

    @Test fun systemDisconnectDoesNotEchoAndCleansItsStateListener() = fixture { f ->
        f.attach()
        f.operations.perform(Action.DISCONNECT)
        assertEquals(1, f.native.terminateCalls)
        assertTrue(f.control.causes.isEmpty())
        assertEquals(listOf(Action.DISCONNECT), f.applied.toList())
        f.callback.close() // Mirrors addCall finally after Telecom acknowledges its own request.
        core { assertTrue(f.native.listeners.isEmpty()) }
    }

    @Test fun rejectedResumeFailsCallbackInsteadOfClaimingItIsActive() = fixture { f ->
        f.native.state = Call.State.Paused
        f.native.resumeResult = -1
        val error = runCatching { f.operations.perform(Action.ACTIVE) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(f.applied.isEmpty())
        core { assertTrue(f.native.listeners.isEmpty()) }
    }

    @Test fun staleSystemAnswerCannotActOnANewerAccountOrCall() = fixture { f ->
        f.current.set(false)
        val error = runCatching { f.operations.perform(Action.ANSWER) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals(0, f.native.answerCalls)
        assertTrue(f.applied.isEmpty())
    }

    @Test fun aQueuedCallbackThatExpiresNeverPausesLater() = fixture { f ->
        f.native.state = Call.State.StreamsRunning
        val operations = TelecomCallActions(f.native.call, { true }, {}, timeoutMillis = 100)
        val release = blockCore()
        val error = try {
            runCatching { operations.perform(Action.INACTIVE) }.exceptionOrNull()
        } finally { release.countDown() }
        assertTrue(error is TimeoutCancellationException)
        core {
            assertEquals(0, f.native.pauseCalls)
            assertTrue(f.native.listeners.isEmpty())
        }
    }

    @Test fun consecutiveSystemHoldAndResumeWaitForTheirOwnTransitions() = fixture(Call.Dir.Outgoing) { f ->
        f.native.state = Call.State.StreamsRunning
        val hold = async { f.operations.perform(Action.INACTIVE) }
        eventually { f.native.pauseCalls == 1 }
        val resume = async { f.operations.perform(Action.ACTIVE) }
        hold.await()
        eventually { f.operations.pending == Action.ACTIVE }
        assertEquals(0, f.native.resumeCalls)
        core { f.native.emit(Call.State.Paused) }
        eventually { f.native.resumeCalls == 1 }
        assertFalse(resume.isCompleted)
        core { f.native.emit(Call.State.StreamsRunning) }
        resume.await()
        assertEquals(listOf(Action.INACTIVE, Action.ACTIVE), f.applied.toList())
        core { assertTrue(f.native.listeners.isEmpty()) }
    }

    @Test fun systemAudioAnswerKeepsTheRequestedMediaAndAwaitsConnected() = fixture { f ->
        val answering = async { f.operations.perform(Action.ANSWER, video = false) }
        eventually { f.native.answerCalls == 1 }
        assertFalse(answering.isCompleted)
        assertEquals(false, f.native.requestedVideo)
        core { f.native.emit(Call.State.Connected) }
        answering.await()
        assertEquals(listOf(Action.ANSWER), f.applied.toList())
    }

    @Test fun expiredResumeDuringPausingNeverQueuesAnSdkResume() = fixture { f ->
        f.native.state = Call.State.Pausing
        val operations = TelecomCallActions(f.native.call, { true }, {}, timeoutMillis = 100)
        val error = runCatching { operations.perform(Action.ACTIVE) }.exceptionOrNull()
        assertTrue(error is TimeoutCancellationException)
        core {
            f.native.emit(Call.State.Paused)
            assertEquals(0, f.native.resumeCalls)
            assertTrue(f.native.listeners.isEmpty())
        }
    }

    @Test fun localConferenceHoldLeavesOnlyTheLocalMixer() = fixture(Call.Dir.Outgoing) { f ->
        f.native.state = Call.State.StreamsRunning
        val conference = NativeConference(f.native).also { f.native.conference = it }
        f.attach()
        eventually { f.control.actions.size == 1 }
        f.operations.perform(Action.INACTIVE)
        assertFalse(conference.inside)
        assertEquals(1, conference.leaveCalls)
        assertEquals(0, f.native.pauseCalls)
        assertEquals(Call.State.StreamsRunning, f.native.state)
        assertEquals(listOf(Action.INACTIVE), f.applied.toList())
        assertEquals(listOf("active"), f.control.actions.toList())
    }

    @Test fun localConferenceResumeEntersEvenWhileParticipantCallIsStreamsRunning() = fixture { f ->
        f.native.state = Call.State.StreamsRunning
        val conference = NativeConference(f.native).also { it.inside = false; f.native.conference = it }
        f.operations.perform(Action.ACTIVE)
        assertTrue(conference.inside)
        assertEquals(1, conference.enterCalls)
        assertEquals(0, f.native.resumeCalls)
        assertEquals(listOf(Action.ACTIVE), f.applied.toList())
    }

    @Test fun localConferenceUiMembershipChangesReachTelecomWithoutCallStateChanges() = fixture(Call.Dir.Outgoing) { f ->
        f.native.state = Call.State.StreamsRunning
        val conference = NativeConference(f.native).also { f.native.conference = it }
        f.attach()
        eventually { f.control.actions.size == 1 }
        core { conference.membership(false) }
        eventually { f.control.actions.size == 2 }
        core { conference.membership(true) }
        eventually { f.control.actions.size == 3 }
        assertEquals(listOf("active", "inactive", "active"), f.control.actions.toList())
        assertEquals(Call.State.StreamsRunning, f.native.state)
    }

    @Test fun remoteConferenceHoldWaitsForAnUpdateBeforeLeaving() = fixture { f ->
        f.native.state = Call.State.Updating
        val conference = NativeConference(f.native, local = false).also { f.native.conference = it }
        val holding = async { f.operations.perform(Action.INACTIVE) }
        eventually { f.operations.pending == Action.INACTIVE }
        assertEquals(0, conference.leaveCalls)
        core { f.native.emit(Call.State.StreamsRunning) }
        holding.await()
        assertEquals(1, conference.leaveCalls)
        assertEquals(0, f.native.pauseCalls)
        assertEquals(Call.State.Pausing, f.native.state)
    }

    @Test fun remoteConferenceResumeWaitsForPausedThenForLiveMedia() = fixture { f ->
        f.native.state = Call.State.Pausing
        val conference = NativeConference(f.native, local = false).also { it.inside = false; f.native.conference = it }
        val resuming = async { f.operations.perform(Action.ACTIVE) }
        eventually { f.operations.pending == Action.ACTIVE }
        assertEquals(0, conference.enterCalls)
        core { f.native.emit(Call.State.Paused) }
        eventually { conference.enterCalls == 1 }
        assertFalse(resuming.isCompleted)
        core { f.native.emit(Call.State.StreamsRunning) }
        resuming.await()
        assertEquals(0, f.native.resumeCalls)
    }

    @Test fun expiredRemoteConferenceResumeCannotEnterLater() = fixture { f ->
        f.native.state = Call.State.Pausing
        val conference = NativeConference(f.native, local = false).also { it.inside = false; f.native.conference = it }
        val operations = TelecomCallActions(f.native.call, { true }, {}, timeoutMillis = 100)
        assertTrue(runCatching { operations.perform(Action.ACTIVE) }.exceptionOrNull() is TimeoutCancellationException)
        core { f.native.emit(Call.State.Paused) }
        assertEquals(0, conference.enterCalls)
        assertTrue(conference.listeners.isEmpty())
    }

    @Test fun aNoOpConferenceLeaveIsNotAcknowledgedAsStoppedMedia() = fixture { f ->
        f.native.state = Call.State.StreamsRunning
        val conference = NativeConference(f.native).also { it.changeOnLeave = false; f.native.conference = it }
        val operations = TelecomCallActions(f.native.call, { true }, {}, timeoutMillis = 100)
        assertTrue(runCatching { operations.perform(Action.INACTIVE) }.exceptionOrNull() is TimeoutCancellationException)
        core { assertTrue(conference.listeners.isEmpty()); assertTrue(f.native.listeners.isEmpty()) }
        assertTrue(conference.inside)
        assertEquals(0, f.native.pauseCalls)
    }

    @Test fun rejectedConferenceEnterDoesNotFallBackToParticipantResume() = fixture { f ->
        f.native.state = Call.State.StreamsRunning
        val conference = NativeConference(f.native).also { it.inside = false; it.enterResult = -1; f.native.conference = it }
        assertTrue(runCatching { f.operations.perform(Action.ACTIVE) }.exceptionOrNull() is IllegalStateException)
        assertEquals(0, f.native.resumeCalls)
        assertTrue(f.applied.isEmpty())
        core { assertTrue(conference.listeners.isEmpty()) }
    }

    @Test fun changingConferenceMembershipInvalidatesTheWaitingOperation() = fixture { f ->
        f.native.state = Call.State.Updating
        val original = NativeConference(f.native, local = false).also { f.native.conference = it }
        val holding = async { runCatching { f.operations.perform(Action.INACTIVE) }.exceptionOrNull() }
        eventually { f.operations.pending == Action.INACTIVE }
        val replacement = NativeConference(f.native, local = false, key = 201L)
        core { f.native.conference = replacement; f.native.emit(Call.State.StreamsRunning) }
        assertTrue(holding.await() is IllegalStateException)
        assertEquals(0, original.leaveCalls); assertEquals(0, replacement.leaveCalls)
        core { assertTrue(original.listeners.isEmpty()) }
    }

    @Test fun terminatedConferenceFailsWaitingResumeAndDisposesObservers() = fixture { f ->
        f.native.state = Call.State.Pausing
        val conference = NativeConference(f.native, local = false).also { it.inside = false; f.native.conference = it }
        val resuming = async { runCatching { f.operations.perform(Action.ACTIVE) }.exceptionOrNull() }
        eventually { f.operations.pending == Action.ACTIVE }
        core { conference.state = Conference.State.Terminated; conference.notifyState() }
        assertTrue(resuming.await() is IllegalStateException)
        assertEquals(0, conference.enterCalls)
        core { assertTrue(conference.listeners.isEmpty()) }
    }

    @Test fun conferenceRepresentativeWaitsUntilRedundantSystemSessionsClose() = fixture(Call.Dir.Outgoing) { f ->
        f.native.state = Call.State.StreamsRunning
        f.canActivate.set(false)
        f.attach()
        assertTrue(f.control.actions.isEmpty())
        f.canActivate.set(true)
        core { f.callback.refreshFromCore() }
        eventually { f.control.actions.size == 1 }
        assertEquals(listOf("active"), f.control.actions.toList())
    }

    @Test fun queuedActivationRechecksConferenceGroupingAfterAnOlderTransaction() = fixture(Call.Dir.Outgoing) { f ->
        f.native.state = Call.State.Paused
        val release = CompletableDeferred<Unit>()
        f.control.holdInactive = release
        f.attach()
        eventually { f.control.actions.size == 1 }
        try {
            core { f.native.emit(Call.State.StreamsRunning) }
            val checks = f.activationChecks.get()
            f.canActivate.set(false)
            release.complete(Unit)
            eventually { f.activationChecks.get() > checks }
            core {} // The gated report has reset its optimistic state before retry.
            assertEquals(listOf("inactive"), f.control.actions.toList())
            f.canActivate.set(true)
            core { f.callback.refreshFromCore() }
            eventually { f.control.actions.size == 2 }
            assertEquals(listOf("inactive", "active"), f.control.actions.toList())
        } finally { release.complete(Unit) }
    }

    @Test fun newerHoldSupersedesAResumeQueuedBehindAnOlderTransaction() = fixture(Call.Dir.Outgoing) { f ->
        f.native.state = Call.State.Paused
        val release = CompletableDeferred<Unit>()
        f.control.holdInactive = release
        f.attach()
        eventually { f.control.actions.size == 1 }
        try {
            core { f.native.emit(Call.State.StreamsRunning); f.native.emit(Call.State.Pausing) }
            release.complete(Unit)
            eventually { f.control.actions.size == 2 }
            assertEquals(listOf("inactive", "inactive"), f.control.actions.toList())
        } finally { release.complete(Unit) }
    }

    @Test fun endingConferenceIsUnavailableAndNeverReportsItsParticipantAsActive() {
        listOf(Conference.State.CreationFailed, Conference.State.TerminationPending, Conference.State.Terminated, Conference.State.Deleted).forEach { state ->
            fixture(Call.Dir.Outgoing) { f ->
                f.native.state = Call.State.StreamsRunning
                val conference = NativeConference(f.native).also { it.state = state; f.native.conference = it }
                core { assertFalse(conference.value.isAvailableToTelecom()) }
                f.attach()
                eventually { f.control.causes.size == 1 }
                assertTrue(f.control.actions.isEmpty())
                assertEquals(0, f.native.terminateCalls)
            }
        }
    }

    @Test fun systemMuteControlsTheConferenceNotOnlyItsRepresentativeAndRejectsStaleOwners() {
        listOf(true, false).forEach { local ->
            fixture(Call.Dir.Outgoing) { f ->
                f.native.state = Call.State.StreamsRunning
                val conference = NativeConference(f.native, local).also { f.native.conference = it }
                f.attach()
                eventually { f.control.mute.subscriptionCount.value == 1 }
                f.control.mute.emit(true)
                eventually { conference.muteWrites == 1 }
                assertTrue(conference.muted)
                assertEquals(0, f.native.muteWrites)
                f.control.mute.emit(false)
                eventually { conference.muteWrites == 2 }
                assertFalse(conference.muted)
                f.current.set(false)
                f.control.mute.emit(true)
                core { assertEquals(2, conference.muteWrites) }
                assertEquals(0, f.native.muteWrites)
            }
        }
    }

    @Test fun realSdkLocalMixerUsesConferenceEnterAndLeaveWithoutAParticipantCallPause() = fixture(Call.Dir.Outgoing) { f ->
        assumeTrue("Loopback call is emulator-only", Build.MODEL.contains("sdk", true))
        // Core construction permits configuration work; INVITE also requires
        // startup to finish and the SIP listening points to be available.
        eventually {
            var started = false
            core { started = coreContext.core.globalState == org.linphone.core.GlobalState.On }
            started
        }
        var conference: Conference? = null
        var participant: Call? = null
        var restore: () -> Unit = {}
        var callback: TelecomCallControlCallback? = null
        var operations: TelecomCallActions? = null
        // Own the loopback destination. Exercise the app's real Core/service
        // lifecycle without an external peer, registration or microphone capture.
        val peer = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        try {
            core {
                val sdk = coreContext.core
                assertTrue("Loopback fixture requires no configured accounts", sdk.accountList.isEmpty())
                assertEquals("Never disturb an existing call", 0, sdk.callsNb)
                val previousDefault = sdk.defaultAccount
                val previousFiles = sdk.useFiles
                val previousType = sdk.config.getString("misc", "conference_type", null)
                val previousNetwork = sdk.isNetworkReachable
                val sessions = Mango9SessionStore(InstrumentationRegistry.getInstrumentation().targetContext)
                val previousIdentity = sessions.activeIdentity
                var mixerAccount: org.linphone.core.Account? = null
                restore = {
                    sdk.defaultAccount = previousDefault
                    mixerAccount?.let { account ->
                        account.params.identityAddress?.asStringUriOnly()?.let(sessions::remove)
                        sdk.removeAccount(account)
                    }
                    sessions.activate(previousIdentity)
                    sdk.useFiles = previousFiles
                    sdk.config.setString("misc", "conference_type", previousType)
                    sdk.setNetworkReachable(previousNetwork)
                    participant?.callLog?.let(sdk::removeCallLog)
                }
                sdk.useFiles = true
                sdk.config.setString("misc", "conference_type", "local")
                sdk.setNetworkReachable(true)
                val account = sdk.createAccount(
                    sdk.createAccountParams().apply {
                    identityAddress = Factory.instance().createAddress("sip:mixer@127.0.0.1")
                    serverAddress = Factory.instance().createAddress("sip:127.0.0.1:${peer.localPort};transport=udp")
                    isRegisterEnabled = false
                    pushNotificationAllowed = false
                }
                )
                mixerAccount = account
                assertEquals(0, sdk.addAccount(account))
                sdk.defaultAccount = account
                val params = sdk.createConferenceParams(null).apply {
                    this.account = account
                    isLocalParticipantEnabled = true
                    isAudioEnabled = true
                    isVideoEnabled = false
                    isChatEnabled = false
                    conferenceFactoryAddress = null
                    subject = "Isolated loopback regression"
                }
                val created = requireNotNull(sdk.createConferenceWithParams(params))
                conference = created
                assertNull(created.call)
                // The SDK creates the mixer when adding its first real call, not
                // when constructing an empty Conference. Never enter it early.
                val callParams = requireNotNull(sdk.createCallParams(null)).apply { this.account = account; isVideoEnabled = false }
                val target =
                    requireNotNull(Factory.instance().createAddress("sip:peer@127.0.0.1:${peer.localPort};transport=udp"))
                val member = requireNotNull(sdk.inviteAddressWithParams(target, callParams))
                participant = member
                assertEquals(0, created.addParticipant(member))
                assertEquals(Conference.State.Created, created.state)
                // Audio-only, still joining: no remote SDP exists. Teardown must
                // not enter the SDK's video-thumbnail negotiation with null params.
                val device = created.participantList.flatMap { it.devices.toList() }.first()
                val displayModel = org.linphone.ui.call.conference.model.ConferenceParticipantDeviceModel(device)
                displayModel.clearWindowId()
                displayModel.destroy()
                assertEquals(0, created.enter())
                assertTrue(created.isIn)
                val ownsCall = { sdk.calls.any { it.nativePointer == member.nativePointer } }
                val actions = TelecomCallActions(member, { ownsCall() }, {
                    f.applied.add(it); callback?.appliedByTelecom(it)
                })
                operations = actions
                callback = TelecomCallControlCallback(
                    member, f.control, f.control, ownsCall,
                    pendingTelecomAction = { actions.pending }, isVideoEnabled = { false },
                ).also { it.onCallControlCallbackSet() }
            }
            eventually { f.control.mute.subscriptionCount.value == 1 }
            f.control.mute.emit(true)
            eventually {
                var muted = false
                core { muted = conference!!.microphoneMuted }
                muted
            }
            f.control.mute.emit(false)
            eventually {
                var muted = true
                core { muted = conference!!.microphoneMuted }
                !muted
            }
            operations!!.perform(Action.INACTIVE)
            core { assertFalse(conference!!.isIn) }
            operations!!.perform(Action.ACTIVE)
            core { assertTrue(conference!!.isIn) }
            assertEquals(listOf(Action.INACTIVE, Action.ACTIVE), f.applied.toList())
        } finally {
            callback?.close()
            try {
                core {
                    try { conference?.terminate(); participant?.terminate() } finally { restore() }
                }
            } finally { peer.close() }
        }
    }

    private class NativeCall(val direction: Call.Dir) {
        @Volatile var state = Call.State.IncomingReceived

        @Volatile var reason = Reason.None

        @Volatile var muteWrites = 0

        @Volatile var pauseCalls = 0

        @Volatile var pauseStopsImmediately = true

        @Volatile var resumeCalls = 0

        @Volatile var answerCalls = 0

        @Volatile var terminateCalls = 0

        @Volatile var resumeResult = 0

        @Volatile var requestedVideo: Boolean? = null

        @Volatile var conference: NativeConference? = null

        @Volatile var externalConference: Conference? = null
        val listeners = CopyOnWriteArrayList<CallListener>()
        val call = Proxy.newProxyInstance(Call::class.java.classLoader, arrayOf(Call::class.java)) { proxy, method, args ->
            when (method.name) {
                "getState" -> state
                "getDir" -> direction
                "getReason" -> reason
                "getNativePointer" -> 100L
                "getConference" -> externalConference ?: conference?.value
                "addListener" -> { listeners.add(args!![0] as CallListener); null }
                "removeListener" -> { listeners.remove(args!![0] as CallListener); null }
                "setMicrophoneMuted" -> { muteWrites++; null }
                "pause" -> { pauseCalls++; if (pauseStopsImmediately) emit(Call.State.Pausing); 0 }
                "resume" -> { resumeCalls++; if (resumeResult == 0) emit(Call.State.Resuming); resumeResult }
                "toString" -> "Synthetic non-network call"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                else -> error("Unexpected SDK access: ${method.name}")
            }
        } as Call

        fun emit(value: Call.State) {
            state = value
            conference?.takeUnless {
                it.local
            }?.inside = value in arrayOf(Call.State.StreamsRunning, Call.State.Updating, Call.State.UpdatedByRemote)
            listeners.toList().forEach { it.onStateChanged(call, value, "fixture") }
        }
    }

    private class NativeConference(val call: NativeCall, val local: Boolean = true, val key: Long = 200L) {
        @Volatile var inside = true

        @Volatile var state = Conference.State.Created

        @Volatile var enterCalls = 0

        @Volatile var leaveCalls = 0

        @Volatile var enterResult = 0

        @Volatile var changeOnLeave = true

        @Volatile var muted = false

        @Volatile var muteWrites = 0
        val listeners = CopyOnWriteArrayList<ConferenceListener>()
        private val participant = Proxy.newProxyInstance(Participant::class.java.classLoader, arrayOf(Participant::class.java)) { _, method, _ ->
            error("Unexpected participant access: ${method.name}")
        } as Participant
        val value: Conference = Proxy.newProxyInstance(Conference::class.java.classLoader, arrayOf(Conference::class.java)) { _, method, args ->
            when (method.name) {
                "getNativePointer" -> key
                "getState" -> state
                "isIn" -> inside
                "getCall" -> if (local) null else call.call
                "getMicrophoneMuted" -> muted
                "setMicrophoneMuted" -> { muted = args!![0] as Boolean; muteWrites++; null }
                "addListener" -> { listeners.add(args!![0] as ConferenceListener); null }
                "removeListener" -> { listeners.remove(args!![0] as ConferenceListener); null }
                "leave" -> { leaveCalls++; if (changeOnLeave) { if (local) membership(false) else call.emit(Call.State.Pausing) }; 0 }
                "enter" -> {
                    enterCalls++; if (enterResult == 0) { if (local) membership(true) else call.emit(Call.State.Resuming) }; enterResult
                }
                else -> error("Unexpected conference access: ${method.name}")
            }
        } as Conference

        fun membership(joined: Boolean) {
            inside = joined
            listeners.toList().forEach {
                if (joined) it.onParticipantAdded(value, participant) else it.onParticipantRemoved(value, participant)
            }
        }

        fun notifyState() = listeners.toList().forEach { it.onStateChanged(value, state) }
    }

    private class Control : CallControlScope {
        override val coroutineContext = Dispatchers.Default + SupervisorJob()
        val actions = Collections.synchronizedList(mutableListOf<String>())
        val causes = Collections.synchronizedList(mutableListOf<Int>())
        val mute = MutableSharedFlow<Boolean>()

        @Volatile var holdInactive: CompletableDeferred<Unit>? = null
        private val endpoints = MutableSharedFlow<List<CallEndpointCompat>>()
        private val endpoint = MutableSharedFlow<CallEndpointCompat>()
        override val isMuted = mute
        override val availableEndpoints = endpoints
        override val currentCallEndpoint = endpoint

        override fun getCallId() = ParcelUuid(UUID.randomUUID())

        override suspend fun setActive(): CallControlResult { actions.add("active"); return CallControlResult.Success() }

        override suspend fun setInactive(): CallControlResult {
            actions.add("inactive"); holdInactive?.await(); return CallControlResult.Success()
        }

        override suspend fun answer(callType: Int): CallControlResult {
            actions.add("answer"); return CallControlResult.Success()
        }

        override suspend fun disconnect(disconnectCause: DisconnectCause): CallControlResult {
            causes.add(disconnectCause.code); return CallControlResult.Success()
        }

        override suspend fun requestEndpointChange(endpoint: CallEndpointCompat): CallControlResult = CallControlResult.Success()

        fun subscribers() = mute.subscriptionCount.value + endpoints.subscriptionCount.value + endpoint.subscriptionCount.value
    }

    private class RecordingControl(private val delegate: CallControlScope) : CallControlScope by delegate {
        val results = Collections.synchronizedList(mutableListOf<Pair<String, CallControlResult>>())

        override suspend fun setActive() = delegate.setActive().also { results.add("active" to it) }

        override suspend fun setInactive() = delegate.setInactive().also { results.add("inactive" to it) }

        override suspend fun answer(callType: Int) = delegate.answer(callType).also { results.add("answer" to it) }

        override suspend fun disconnect(disconnectCause: DisconnectCause) = delegate.disconnect(disconnectCause).also {
            results.add("disconnect" to it)
        }
    }

    private inner class Fixture(direction: Call.Dir) {
        val native = NativeCall(direction)
        val current = AtomicBoolean(true)
        val canActivate = AtomicBoolean(true)
        val activationChecks = AtomicInteger()
        val control = Control()
        val applied = Collections.synchronizedList(mutableListOf<Action>())
        val operations: TelecomCallActions = TelecomCallActions(
            native.call, { current.get() }, { applied.add(it); callback.appliedByTelecom(it) },
            answer = { _, video -> native.answerCalls++; native.requestedVideo = video },
            terminate = { native.terminateCalls++; native.emit(Call.State.End) },
        )
        val callback: TelecomCallControlCallback = TelecomCallControlCallback(
            native.call, control, control, current::get,
            pendingTelecomAction = { operations.pending }, isVideoEnabled = { false },
            canReportActive = { activationChecks.incrementAndGet(); canActivate.get() },
        )

        fun attach() { callback.onCallControlCallbackSet(); core {} }

        fun close() {
            callback.close(); control.cancel()
            core { assertTrue(native.listeners.isEmpty()); assertTrue(native.conference?.listeners.isNullOrEmpty()) }
        }
    }

    private fun fixture(direction: Call.Dir = Call.Dir.Incoming, block: suspend CoroutineScope.(Fixture) -> Unit) = runBlocking {
        coreContext.awaitReady()
        val fixture = Fixture(direction)
        try { block(fixture) } finally { fixture.close() }
    }

    private fun core(action: () -> Unit) {
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        coreContext.postOnCoreThread {
            try { action() } catch (error: Throwable) { failure = error } finally { done.countDown() }
        }
        assertTrue("Core queue timeout", done.await(10, TimeUnit.SECONDS))
        failure?.let { throw AssertionError(it) }
    }

    private fun blockCore(): CountDownLatch {
        val ready = CountDownLatch(1)
        val release = CountDownLatch(1)
        coreContext.postOnCoreThread { ready.countDown(); release.await(5, TimeUnit.SECONDS) }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        return release
    }

    private suspend fun eventually(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(10)
    }
}
