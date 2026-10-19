// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.media.AudioDeviceInfo
import android.media.AudioManager
import org.junit.Assert.*
import org.junit.Test

class Mango9MediaPlaybackRouteTest {
    @Test fun builtInPlaybackUsesLoudspeakerAndIgnoresVirtualTelephonySink() {
        assertTrue(
            Mango9MediaPlaybackRoute.preferSpeaker(
                AudioManager.MODE_NORMAL,
                listOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_TELEPHONY,
        )
            )
        )
    }

    @Test fun externalOutputsStayUnderAndroidRouteControl() {
        for (type in listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_HDMI
        )) {
            assertFalse(Mango9MediaPlaybackRoute.preferSpeaker(AudioManager.MODE_NORMAL, listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, type)))
        }
    }

    @Test fun activeCallsNeverGetSpeakerOverrides() {
        for (mode in listOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_RINGTONE)) {
            assertFalse(Mango9MediaPlaybackRoute.preferSpeaker(mode, listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)))
        }
    }
}
