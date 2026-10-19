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
package org.linphone.ui.call.conference.model

import android.view.TextureView
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MutableLiveData
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.ParticipantDevice
import org.linphone.core.ParticipantDeviceListenerStub
import org.linphone.core.StreamType
import org.linphone.core.tools.Log
import org.linphone.utils.LinphoneUtils
import java.util.concurrent.atomic.AtomicLong

class ConferenceParticipantDeviceModel
    @WorkerThread
    constructor(
    val device: ParticipantDevice,
    val isMe: Boolean = false
) {
    companion object {
        private const val TAG = "[Conference Participant Device Model]"
    }

    val avatarModel = coreContext.contactsManager.getContactAvatarModelForAddress(device.participant.address)

    val name = avatarModel.contactName ?: device.name.orEmpty().ifEmpty {
        LinphoneUtils.getDisplayName(device.participant.address)
    }

    val isMuted = MutableLiveData<Boolean>()

    val isSpeaking = MutableLiveData<Boolean>()

    val isActiveSpeaker = MutableLiveData<Boolean>()

    val isScreenSharing = MutableLiveData<Boolean>()

    val isVideoAvailable = MutableLiveData<Boolean>()

    val isThumbnailAvailable = MutableLiveData<Boolean>()

    val isJoining = MutableLiveData<Boolean>()

    val isInConference = MutableLiveData<Boolean>()

    private var textureView: TextureView? = null
    private var boundWindow: TextureView? = null
    private var boundListener: TextureView.SurfaceTextureListener? = null
    private val windowGeneration = AtomicLong()

    @Volatile private var destroyed = false

    private val deviceListener = object : ParticipantDeviceListenerStub() {
        @WorkerThread
        override fun onStateChanged(
            participantDevice: ParticipantDevice,
            state: ParticipantDevice.State?
        ) {
            Log.i(
                "$TAG Participant device [${participantDevice.address.asStringUriOnly()}] state changed [$state]"
            )
            when (state) {
                ParticipantDevice.State.Joining, ParticipantDevice.State.Alerting -> {
                    isJoining.postValue(true)
                }
                ParticipantDevice.State.OnHold -> {
                    isInConference.postValue(false)
                }
                ParticipantDevice.State.Present -> {
                    isJoining.postValue(false)
                    isInConference.postValue(true)
                }
                else -> {}
            }
        }

        @WorkerThread
        override fun onIsMuted(participantDevice: ParticipantDevice, muted: Boolean) {
            Log.d(
                "$TAG Participant device [${participantDevice.address.asStringUriOnly()}] is ${if (participantDevice.isMuted) "muted" else "no longer muted"}"
            )
            isMuted.postValue(participantDevice.isMuted)
        }

        @WorkerThread
        override fun onIsSpeakingChanged(
            participantDevice: ParticipantDevice,
            speaking: Boolean
        ) {
            Log.d(
                "$TAG Participant device [${participantDevice.address.asStringUriOnly()}] is ${if (participantDevice.isSpeaking) "speaking" else "no longer speaking"}"
            )
            isSpeaking.postValue(speaking)
        }

        @WorkerThread
        override fun onScreenSharingChanged(
            participantDevice: ParticipantDevice,
            screenSharing: Boolean
        ) {
            Log.i(
                "$TAG Participant device [${participantDevice.address.asStringUriOnly()}] is ${if (screenSharing) "sharing its screen" else "no longer sharing its screen"}"
            )
            isScreenSharing.postValue(screenSharing)
        }

        @WorkerThread
        override fun onThumbnailStreamAvailabilityChanged(
            participantDevice: ParticipantDevice,
            available: Boolean
        ) {
            Log.i(
                "$TAG Participant device [${participantDevice.address.asStringUriOnly()}] thumbnail availability changed to ${if (available) "available" else "not available"}"
            )
            isThumbnailAvailable.postValue(available)
            if (available) updateWindowId()
        }

        @WorkerThread
        override fun onStreamAvailabilityChanged(
            participantDevice: ParticipantDevice,
            available: Boolean,
            streamType: StreamType?
        ) {
            if (streamType == StreamType.Video) {
                Log.i(
                    "$TAG Participant device [${participantDevice.address.asStringUriOnly()}] video stream availability changed to ${if (available) "available" else "not available"}"
                )
                isVideoAvailable.postValue(available)
                if (available) updateWindowId()
            }
        }
    }

    init {
        device.addListener(deviceListener)

        val state = device.state
        val joining = state == ParticipantDevice.State.Joining || state == ParticipantDevice.State.Alerting
        isJoining.postValue(joining)
        val inConference = device.isInConference
        isInConference.postValue(inConference)
        if (joining) {
            Log.i(
                "$TAG Participant [${device.address.asStringUriOnly()}] is joining the conference (state [$state])"
            )
        } else {
            Log.i(
                "$TAG Participant [${device.address.asStringUriOnly()}] is [${if (inConference) "inside" else "outside"}] the conference with state [${device.state}]"
            )
        }

        isMuted.postValue(device.isMuted)
        isSpeaking.postValue(device.isSpeaking)
        Log.i(
            "$TAG Participant [${device.address.asStringUriOnly()}] is in state [${device.state}]"
        )

        isActiveSpeaker.postValue(false)
        val screenSharing = device.isScreenSharingEnabled
        isScreenSharing.postValue(screenSharing)
        if (screenSharing) {
            Log.i("$TAG Participant [${device.address.asStringUriOnly()}] is sharing its screen")
        }

        val videoAvailability = device.getStreamAvailability(StreamType.Video)
        isVideoAvailable.postValue(videoAvailability)
        Log.i(
            "$TAG Participant device [${device.address.asStringUriOnly()}] video stream availability is ${if (videoAvailability) "available" else "not available"}"
        )

        // In case of joining conference without bundle mode, thumbnail stream availability will be false,
        // but we need to display our video preview for video stream to be sent
        val thumbnailVideoAvailability = if (isMe) videoAvailability else device.thumbnailStreamAvailability
        isThumbnailAvailable.postValue(thumbnailVideoAvailability)
        Log.i(
            "$TAG Participant device [${device.address.asStringUriOnly()}] thumbnail availability is ${if (thumbnailVideoAvailability) "available" else "not available"}"
        )
    }

    @WorkerThread
    fun destroy() {
        if (destroyed) return
        destroyed = true
        clearWindowId()
        device.removeListener(deviceListener)
    }

    @WorkerThread
    fun clearWindowId() {
        windowGeneration.incrementAndGet()
        textureView = null
        val owned = boundWindow ?: return
        boundWindow = null
        // Even setting null enters the SDK's thumbnail negotiation. Never touch
        // a window this model did not attach (audio-only/joining devices), or a
        // newer model's replacement surface during layout/Activity recreation.
        val listener = boundListener
        boundListener = null
        // Android's SDK registers the TextureView listener immediately, but
        // remote nativeWindowId becomes its SurfaceTexture only when available.
        // Comparing nativeWindowId to the TextureView alone would leak bindings.
        val stillOwned = if (listener != null) {
            owned.surfaceTextureListener === listener
        } else {
            val current = if (isMe) coreContext.core.nativePreviewWindowId else device.nativeVideoWindowId
            current === owned || (owned.surfaceTexture != null && current === owned.surfaceTexture)
        }
        if (stillOwned) {
            if (isMe) coreContext.core.nativePreviewWindowId = null else device.nativeVideoWindowId = null
        }
    }

    @UiThread
    fun setTextureView(view: TextureView) {
        val generation = windowGeneration.incrementAndGet()
        coreContext.postOnCoreThread {
            if (destroyed || generation != windowGeneration.get()) return@postOnCoreThread
            textureView = view
            updateWindowId()
        }
    }

    @WorkerThread
    private fun updateWindowId() {
        if (destroyed) return
        val view = textureView ?: return
        if (boundWindow === view) return
        // Retain the desired view while negotiation finishes. Availability
        // callbacks attach it later; audio-only participants need no video JNI.
        val available = device.getStreamAvailability(StreamType.Video) || (!isMe && device.thumbnailStreamAvailability)
        if (!available) return
        if (isMe) coreContext.core.nativePreviewWindowId = view else device.nativeVideoWindowId = view
        boundWindow = view
        boundListener = view.surfaceTextureListener
    }
}
