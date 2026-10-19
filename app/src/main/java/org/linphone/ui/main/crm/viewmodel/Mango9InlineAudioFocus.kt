/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.ui.main.crm.viewmodel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import org.linphone.core.tools.Log

internal interface Mango9InlineAudioFocus {
    fun request(onLost: () -> Unit): Boolean

    fun abandon()
}

/** Foreground voice clips must pause for calls, focus loss and unplugged headphones. */
internal class Mango9PlatformInlineAudioFocus(context: Context) : Mango9InlineAudioFocus {
    private val context = context.applicationContext
    private val manager = context.getSystemService(AudioManager::class.java)
    private var current: AudioFocusRequest? = null
    private var noisy: BroadcastReceiver? = null

    override fun request(onLost: () -> Unit): Boolean {
        if (current != null) return true
        try {
            if (manager.mode != AudioManager.MODE_NORMAL) return false
        } catch (_: RuntimeException) {
            return false
        }
        lateinit var request: AudioFocusRequest
        request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                if (current === request && change != AudioManager.AUDIOFOCUS_GAIN) onLost()
            }, Handler(Looper.getMainLooper()))
            .build()
        current = request
        try {
            if (manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                abandon()
                return false
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && current === request) onLost()
                }
            }
            ContextCompat.registerReceiver(context, receiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
            noisy = receiver
            return true
        } catch (_: RuntimeException) {
            // Fail closed if the OS rejects focus or receiver registration.
            // Never play anyway or retain focus after this partial setup.
            Log.w("[Inline Audio] Audio focus unavailable")
            abandon()
            return false
        }
    }

    override fun abandon() {
        val request = current
        current = null
        val receiver = noisy
        noisy = null
        try {
            if (receiver != null) context.unregisterReceiver(receiver)
        } catch (_: RuntimeException) {
            Log.w("[Inline Audio] Audio route listener cleanup unavailable")
        } finally {
            try {
                if (request != null) manager.abandonAudioFocusRequest(request)
            } catch (_: RuntimeException) {
                Log.w("[Inline Audio] Audio focus cleanup unavailable")
            }
        }
    }
}
