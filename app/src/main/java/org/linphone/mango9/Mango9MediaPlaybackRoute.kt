// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper

/** Route this media player only; never change the phone's call mode or global speaker state. */
internal class Mango9MediaPlaybackRoute(context: Context, private val player: MediaPlayer) : AutoCloseable {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private var closed = false
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = update()

        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = update()
    }

    init {
        audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        try { update() } catch (failure: RuntimeException) { audio.unregisterAudioDeviceCallback(callback); throw failure }
    }

    private fun update() {
        if (closed) return
        val devices = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        // Let Android choose among connected headphones, hearing aids, Bluetooth, USB and HDMI.
        // Pin only the built-in loudspeaker when no external output exists and no call is active.
        val speaker = if (preferSpeaker(audio.mode, devices.map { it.type })) {
            devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        } else {
            null
        }
        runCatching { player.setPreferredDevice(speaker) }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { audio.unregisterAudioDeviceCallback(callback) }
        runCatching { player.setPreferredDevice(null) }
    }

    companion object {
        private val externalOutputs = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC,
            AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_DOCK,
            AudioDeviceInfo.TYPE_HEARING_AID, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST, AudioDeviceInfo.TYPE_BUS, AudioDeviceInfo.TYPE_IP,
        )

        internal fun preferSpeaker(mode: Int, outputTypes: List<Int>): Boolean =
            mode == AudioManager.MODE_NORMAL && outputTypes.none { it in externalOutputs }
    }
}
