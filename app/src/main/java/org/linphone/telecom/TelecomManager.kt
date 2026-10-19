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

import android.content.Context
import android.telecom.DisconnectCause
import androidx.annotation.WorkerThread
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallException
import androidx.core.telecom.CallsManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Conference
import org.linphone.core.tools.Log
import org.linphone.mango9.Mango9PushCallerIdentityCache
import org.linphone.utils.LinphoneUtils
import androidx.core.net.toUri
import org.linphone.compatibility.Compatibility

class TelecomManager
    @WorkerThread
    constructor(context: Context) {
    companion object {
        private const val TAG = "[Telecom Manager]"

        private val SAMSUNG_S23s =
            arrayListOf("dm3q", "dm1q", "dm2q", "r11s", "r11q", "SC-52D", "SCG19", "SC-51D", "SCG24", "SCG20")
    }

    private val callsManager = CallsManager(context)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val calls = TelecomCallRegistry<Registration>()
    private val grouping = TelecomCallGrouping()
    private var representatives = emptySet<Long>() // Core queue only.
    private var localConferenceKeys = emptyMap<Long, Long>()
    private val conferences = linkedMapOf<Long, TelecomConferenceObservation>()
    private var coreRunning = false
    private var reconciling = false

    /** Keep a close request even if Android has not returned its CallControl yet. */
    private class Registration {
        private var callback: TelecomCallControlCallback? = null
        private var closeCause: Int? = null
        lateinit var actions: TelecomCallActions

        @Volatile var regroupAfterClose = false

        @Volatile var groupedConferenceKey: Long? = null

        @Synchronized fun attach(value: TelecomCallControlCallback) {
            callback = value
            value.onCallControlCallbackSet()
            closeCause?.let(value::disconnect)
        }

        @Synchronized fun end(cause: Int) {
            if (closeCause != null) return
            closeCause = cause
            // Telecom completes its own disconnect callback; do not echo it.
            if (actions.pending != TelecomCallActions.Action.DISCONNECT) callback?.disconnect(cause)
        }

        @Synchronized fun applied(action: TelecomCallActions.Action) { callback?.appliedByTelecom(action) }

        @Synchronized fun refresh() { callback?.refreshFromCore() }

        @Synchronized fun isOpen(): Boolean = closeCause == null

        @Synchronized fun close() {
            callback?.close()
            callback = null
            closeCause = closeCause ?: DisconnectCause.LOCAL
        }
    }

    private val coreListener = object : CoreListenerStub() {
        @WorkerThread
        override fun onCallStateChanged(
            core: Core,
            call: Call,
            state: Call.State?,
            message: String
        ) {
            if (state == Call.State.End || state == Call.State.Error || state == Call.State.Released) {
                calls[call.nativePointer]?.value?.end(TelecomCallControlCallback.disconnectCause(call))
            }
            reconcileCalls(core)
        }

        override fun onConferenceStateChanged(core: Core, conference: Conference, state: Conference.State) {
            reconcileCalls(core)
        }
    }

    private val hasTelecomFeature = Compatibility.hasTelecomManagerFeature(context)

    private var registeredWithTelecom = false

    init {
        Log.i(
            "$TAG Feature is [${if (hasTelecomFeature) "available" else "not available"}]"
        )
        if (hasTelecomFeature) {
            try {
                callsManager.registerAppWithTelecom(CallsManager.CAPABILITY_SUPPORTS_VIDEO_CALLING)
                registeredWithTelecom = true
                Log.i("$TAG App has been registered with Telecom")
            } catch (e: Exception) {
                Log.e("$TAG Can't init TelecomManager: $e")
            }
        }
    }

    @WorkerThread
    private fun onCallCreated(call: Call) {
        if (!registeredWithTelecom || call.nativePointer == 0L) return
        if (calls[call.nativePointer] != null) return
        Log.i("$TAG Call to [${call.remoteAddress.asStringUriOnly()}] created in state [${call.state}]")

        val address = call.callLog.remoteAddress
        val pushedIdentity = if (call.state == Call.State.PushIncomingReceived) {
            Mango9PushCallerIdentityCache.get(call.callLog.callId)
        } else {
            null
        }
        val uri = if (pushedIdentity?.handle?.startsWith('+') == true) {
            "tel:${pushedIdentity.handle}".toUri()
        } else {
            address.asStringUriOnly().toUri()
        }

        val direction = if (call.dir == Call.Dir.Outgoing) {
            CallAttributesCompat.DIRECTION_OUTGOING
        } else {
            CallAttributesCompat.DIRECTION_INCOMING
        }

        val capabilities = CallAttributesCompat.SUPPORTS_SET_INACTIVE or CallAttributesCompat.SUPPORTS_TRANSFER

        val conferenceInfo = LinphoneUtils.getConferenceInfoIfAny(call)
        val displayName = if (pushedIdentity != null) {
            pushedIdentity.displayName
        } else if (call.conference != null || conferenceInfo != null) {
            conferenceInfo?.subject ?: call.conference?.subject ?: LinphoneUtils.getDisplayName(address)
        } else {
            val friend = coreContext.contactsManager.findContactByAddress(address)
            friend?.name ?: LinphoneUtils.getDisplayName(address)
        }

        // CAPABILITY_SUPPORTS_VIDEO_CALLING declares app capability when registering with
        // Telecom. The per-call attribute must describe the media state of this call so
        // Android can activate the voice audio path without treating an audio call as video.
        val isVideoCall = LinphoneUtils.isVideoEnabled(call)
        val type = if (isVideoCall && Build.DEVICE in SAMSUNG_S23s) {
            Log.w("$TAG Samsung S23, S23 FE, S23+ or S23 Ultra detected [${Build.MODEL}], applying workaround to prevent no audio on earpiece issue")
            CallAttributesCompat.CALL_TYPE_AUDIO_CALL
        } else if (isVideoCall) {
            CallAttributesCompat.CALL_TYPE_VIDEO_CALL
        } else {
            CallAttributesCompat.CALL_TYPE_AUDIO_CALL
        }

        val registration = Registration()
        val entry = calls.claim(call.nativePointer, registration) ?: return
        registration.actions = TelecomCallActions(call, { ownsLiveCall(entry, it) }, registration::applied)
        scope.launch {
            try {
                val callAttributes = CallAttributesCompat(
                    displayName,
                    uri,
                    direction,
                    type,
                    capabilities,
                    isLogExcluded = true
                )
                Log.i("$TAG Adding call to Telecom's CallsManager with attributes [$callAttributes]")

                callsManager.addCall(
                    callAttributes,
                    { callType -> // onAnswer
                        Log.i("$TAG We're asked to answer the call with type [$callType]")
                        registration.actions.perform(TelecomCallActions.Action.ANSWER, callType == CallAttributesCompat.CALL_TYPE_VIDEO_CALL)
                    },
                    { disconnectCause -> // onDisconnect
                        Log.i(
                            "$TAG We're asked to terminate the call with reason [$disconnectCause]"
                        )
                        if (registration.groupedConferenceKey == null) {
                            registration.actions.perform(TelecomCallActions.Action.DISCONNECT)
                        } // This redundant system session no longer owns a SIP participant.
                    },
                    { // onSetActive
                        Log.i("$TAG We're asked to resume the call")
                        registration.actions.perform(TelecomCallActions.Action.ACTIVE)
                    },
                    { // onSetInactive
                        Log.i("$TAG We're asked to pause the call")
                        if (registration.groupedConferenceKey == null) {
                            registration.actions.perform(TelecomCallActions.Action.INACTIVE)
                        }
                    }
                ) {
                    val callbacks = TelecomCallControlCallback(
                        call, this, CoroutineScope(coroutineContext),
                        isCurrentCall = { ownsLiveCall(entry, coreContext.core) },
                        pendingTelecomAction = { registration.actions.pending },
                        canReportActive = { canActivateRepresentative(entry.callKey) },
                    )
                    registration.attach(callbacks)
                    Log.i("$TAG Call added to Telecom's CallsManager")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (ce: CallException) {
                Log.e("$TAG Failed to add call to Telecom's CallsManager: $ce")
            } catch (se: SecurityException) {
                Log.e("$TAG Security exception trying to add call to Telecom's CallsManager: $se")
            } catch (ise: IllegalArgumentException) {
                Log.e("$TAG Illegal argument exception trying to add call to Telecom's CallsManager: $ise")
            } catch (e: Exception) {
                Log.e("$TAG Exception trying to add call to Telecom's CallsManager: $e")
            } finally {
                registration.close()
                calls.release(entry)
                // A conference can split while its redundant system session is
                // still closing. Re-enroll only this deliberate regrouping, not
                // every failed addCall (which would create an unbounded retry).
                if (registration.regroupAfterClose || registration.groupedConferenceKey != null) {
                    coreContext.postOnCoreThread { reconcileCalls(it) }
                }
            }
        }
    }

    @WorkerThread
    private fun ownsLiveCall(entry: TelecomCallRegistry.Entry<Registration>, core: Core): Boolean =
        calls.isCurrent(entry) && entry.value.isOpen() && entry.callKey in representatives &&
            core.calls.any {
                it.nativePointer == entry.callKey && it.state !in arrayOf(Call.State.End, Call.State.Error, Call.State.Released)
            }

    @WorkerThread
    private fun reconcileCalls(core: Core) {
        if (!coreRunning || !registeredWithTelecom || reconciling) return
        reconciling = true
        try {
            val live = core.calls.filter {
                it.state !in arrayOf(
                    Call.State.Idle, Call.State.OutgoingInit,
                    Call.State.End, Call.State.Error, Call.State.Released
                )
            }
            val localConferences = linkedMapOf<Long, Conference>()
            val members = live.map { call ->
                val conference = call.conference
                val local = conference?.takeIf { it.call == null }
                local?.let { localConferences[it.nativePointer] = it }
                TelecomCallGrouping.Member(call.nativePointer, local?.nativePointer, conference?.isAvailableToTelecom() != false)
            }
            localConferenceKeys = members.mapNotNull { member -> member.localConferenceKey?.let { member.callKey to it } }.toMap()
            representatives = grouping.representatives(members)
            conferences.keys.filter { it !in localConferences }.forEach { key -> conferences.remove(key)?.close() }
            localConferences.forEach { (key, conference) ->
                conferences.getOrPut(key) { TelecomConferenceObservation { reconcileCalls(core) } }.observe(conference)
            }
            calls.snapshot().forEach { entry ->
                if (entry.callKey !in representatives) {
                    localConferenceKeys[entry.callKey]?.let { entry.value.groupedConferenceKey = it }
                    entry.value.end(DisconnectCause.LOCAL)
                }
            }
            live.filter { it.nativePointer in representatives }.forEach { call ->
                val registration = calls[call.nativePointer]?.value
                if (registration == null) {
                    onCallCreated(call)
                } else if (!registration.isOpen() && registration.groupedConferenceKey != null) {
                    registration.regroupAfterClose = true
                } else if (registration.isOpen()) registration.refresh()
            }
        } finally { reconciling = false }
    }

    @WorkerThread
    private fun canActivateRepresentative(callKey: Long): Boolean {
        val conferenceKey = localConferenceKeys[callKey] ?: return true
        return calls.snapshot().none {
            it.callKey != callKey && it.value.groupedConferenceKey != null &&
                (it.value.groupedConferenceKey == conferenceKey || localConferenceKeys[it.callKey] == conferenceKey)
        }
    }

    @WorkerThread
    fun onCoreStarted(core: Core) {
        Log.i("$TAG Core has been started")
        if (registeredWithTelecom) {
            coreRunning = true
            core.addListener(coreListener)
            reconcileCalls(core)
        } else {
            Log.w(
                "$TAG Telecom registration unavailable, enable audio focus requests in Linphone SDK"
            )
            coreContext.core.config.setBool("audio", "android_disable_audio_focus_requests", false)
        }
    }

    @WorkerThread
    fun onCoreStopped(core: Core) {
        Log.i("$TAG Core is being stopped")
        coreRunning = false
        representatives = emptySet()
        localConferenceKeys = emptyMap()
        grouping.clear()
        conferences.values.forEach { it.close() }
        conferences.clear()
        if (hasTelecomFeature) {
            core.removeListener(coreListener)
        }
        // Invalidate commands before disconnecting. An addCall still opening must
        // receive this close request when its CallControl becomes available.
        calls.clear().forEach { it.value.end(DisconnectCause.LOCAL) }
    }
}
