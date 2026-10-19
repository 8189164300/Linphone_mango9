/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.telecom

import org.linphone.core.Conference
import org.linphone.core.ConferenceListenerStub
import org.linphone.core.Participant
import org.linphone.core.ParticipantDevice

/** Pending termination is not a split back into ordinary calls. Core queue only. */
internal fun Conference.isAvailableToTelecom(): Boolean = state !in arrayOf(
    Conference.State.CreationFailed, Conference.State.TerminationPending,
    Conference.State.Terminated, Conference.State.Deleted,
)

/** Core-queue-only observation. Local mixer membership need not change Call.state. */
internal class TelecomConferenceObservation(private val changed: () -> Unit) {
    private var conference: Conference? = null
    private val listener = object : ConferenceListenerStub() {
        private fun update(source: Conference) {
            if (conference?.nativePointer == source.nativePointer) changed()
        }

        override fun onStateChanged(conference: Conference, state: Conference.State) = update(conference)

        override fun onParticipantAdded(conference: Conference, participant: Participant) = update(conference)

        override fun onParticipantRemoved(conference: Conference, participant: Participant) = update(conference)

        override fun onParticipantDeviceAdded(conference: Conference, device: ParticipantDevice) = update(conference)

        override fun onParticipantDeviceRemoved(conference: Conference, device: ParticipantDevice) = update(conference)

        override fun onParticipantDeviceStateChanged(conference: Conference, device: ParticipantDevice, state: ParticipantDevice.State) = update(conference)
    }

    fun observe(value: Conference?): Conference? {
        if (conference?.nativePointer == value?.nativePointer) return conference
        close()
        conference = value
        value?.addListener(listener)
        return value
    }

    fun close() {
        val previous = conference
        conference = null // A queued old notification must not act on a new owner.
        previous?.removeListener(listener)
    }
}
