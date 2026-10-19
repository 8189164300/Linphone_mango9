/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatViewInflater
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.widget.ImageViewCompat
import androidx.core.widget.NestedScrollView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import androidx.databinding.DataBindingUtil
import androidx.databinding.ViewDataBinding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.BR
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.core.GlobalState
import org.linphone.ui.call.model.AudioDeviceModel
import org.linphone.ui.call.model.CallMediaEncryptionModel
import org.linphone.ui.main.history.model.NumpadModel
import org.linphone.ui.call.viewmodel.CurrentCallViewModel
import org.linphone.utils.TouchListener
import org.linphone.utils.setTouchListener

/** Real XML, AppCompat widgets and LiveData bindings; no SIP calls or account changes. */
@RunWith(AndroidJUnit4::class)
class Mango9CallStyleInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun controlBindingsRenderCameraMuteAndEveryNonEarpieceRoute() = fixture { model ->
        for (landscape in listOf(false, true)) {
            val binding = inflate(R.layout.call_actions_generic, model, landscape)
            model.isPaused.value = false
            model.isPausedByRemote.value = false
            model.videoUpdateInProgress.value = false
            model.hideVideo.value = false
            model.isSendingVideo.value = false
            model.isMicrophoneMuted.value = false
            val routes =
                listOf(model.isSpeakerEnabled, model.isBluetoothEnabled, model.isHeadsetEnabled, model.isHearingAidEnabled, model.isHdmiEnabled)
            routes.forEach { it.value = false }
            binding.executePendingBindings()
            control(binding, R.id.toggle_video, R.id.toggle_video, false)
            control(binding, R.id.toggle_mute_mic, R.id.record_volume_vu_meter, false)
            control(binding, R.id.change_audio_output, R.id.playback_volume_vu_meter, false)
            model.isSendingVideo.value = true
            model.isMicrophoneMuted.value = true
            binding.executePendingBindings()
            control(binding, R.id.toggle_video, R.id.toggle_video, true)
            control(binding, R.id.toggle_mute_mic, R.id.record_volume_vu_meter, true)
            assertEquals(context.getString(R.string.mango9_call_unmute), binding.root.findViewById<View>(R.id.toggle_mute_mic).contentDescription)
            assertEquals(context.getString(R.string.mango9_call_camera_off), binding.root.findViewById<View>(R.id.toggle_video).contentDescription)
            for (route in routes) {
                routes.forEach { it.value = false }
                route.value = true
                binding.executePendingBindings()
                control(binding, R.id.change_audio_output, R.id.playback_volume_vu_meter, true)
            }
            for (id in listOf(R.id.toggle_mute_mic, R.id.change_audio_output)) assertTrue(binding.root.findViewById<View>(id).hasOnClickListeners())
            for (id in listOf(R.id.record_volume_vu_meter, R.id.playback_volume_vu_meter)) {
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, binding.root.findViewById<View>(id).importantForAccessibility)
                assertFalse(binding.root.findViewById<View>(id).hasOnClickListeners())
            }
            model.isPausedByRemote.value = true
            binding.executePendingBindings()
            assertFalse(binding.root.findViewById<View>(R.id.toggle_video).isEnabled)
            snapshot(binding.root, "controls-${if (landscape) "land" else "portrait"}", landscape)
            binding.unbind()
        }
    }

    @Test fun outgoingOutputRemainsDisabledUntilRingingWithNoDuplicateStateFill() = fixture { model ->
        val binding = inflate(R.layout.call_outgoing_actions, model)
        model.hideVideo.value = false
        model.isMicrophoneMuted.value = true
        model.isSpeakerEnabled.value = true
        model.isOutgoingRinging.value = false
        binding.executePendingBindings()
        val output = binding.root.findViewById<ImageView>(R.id.change_audio_output)
        val meter = binding.root.findViewById<View>(R.id.playback_volume_vu_meter)
        assertFalse(output.isEnabled); assertFalse(meter.isEnabled)
        assertEquals(color(R.color.mango9_call_disabled), tint(output))
        assertEquals(color(R.color.mango9_call_control), background(meter))
        model.isOutgoingRinging.value = true
        binding.executePendingBindings()
        control(binding, R.id.change_audio_output, R.id.playback_volume_vu_meter, true)
        control(binding, R.id.toggle_mute_mic, R.id.record_volume_vu_meter, true)
        assertFalse(binding.root.findViewById<View>(R.id.toggle_video).isEnabled)
        binding.unbind()
    }

    @Test fun holdIsBlueAndRecordingIsRedInBothExpandedLayouts() = fixture { model ->
        for (landscape in listOf(false, true)) {
            val binding = inflate(R.layout.call_actions_bottom_sheet, model, landscape, 1.8f)
            model.canBePaused.value = true
            model.isPaused.value = true
            model.isPausedByRemote.value = false
            model.isRecordingEnabled.value = true
            model.isRecording.value = true
            binding.executePendingBindings()
            val hold = binding.root.findViewById<ImageView>(R.id.pause_call)
            assertTrue(hold.isSelected)
            assertEquals(color(R.color.mango9_call_accent), background(hold))
            model.isPaused.value = false
            binding.executePendingBindings()
            val record = binding.root.findViewById<ImageView>(R.id.record_call)
            assertTrue(record.isSelected); assertTrue(record.isEnabled)
            assertEquals(color(R.color.mango9_call_destructive), background(record))
            assertEquals(Color.WHITE, tint(record))
            record.isPressed = true
            assertEquals(color(R.color.mango9_call_destructive), background(record))
            assertEquals(Color.WHITE, tint(record))
            snapshot(binding.root, "expanded-${if (landscape) "land" else "portrait"}", landscape)
            binding.unbind()
        }
    }

    @Test fun audioCanvasAndHeadersStayLightAndVideoHeadersHideInFullscreen() = fixture { model ->
        model.displayedName.value = "Mango9 sample caller"
        model.displayedAddress.value = "+1 202 555 0100"
        model.isIncomingEarlyMedia.value = false
        model.isVideoEnabled.value = false
        model.isReceivingVideo.value = false
        for (landscape in listOf(false, true)) {
            for (layout in listOf(R.layout.call_incoming_fragment, R.layout.call_outgoing_fragment, R.layout.call_ended_fragment, R.layout.call_active_fragment)) {
                model.fullScreenMode.value = false; model.pipMode.value = false
                val binding = inflate(layout, model, landscape, 1.3f)
                binding.executePendingBindings()
                val canvas = if (layout == R.layout.call_outgoing_fragment) {
                    (binding.root as ViewGroup).getChildAt(0)
                } else {
                    binding.root.findViewById<View>(R.id.constraint_layout) ?: binding.root
                }
                assertEquals(color(R.color.mango9_call_canvas), background(canvas))
                val name = binding.root.findViewById<TextView>(R.id.display_name) ?: binding.root.findViewById<TextView>(R.id.name)
                assertNotNull(name); assertEquals(color(R.color.mango9_call_ink), name.currentTextColor)
                if (layout == R.layout.call_incoming_fragment) {
                    assertEquals(View.GONE, binding.root.findViewById<View>(R.id.remote_video_surface).visibility)
                    model.isIncomingEarlyMedia.value = true; model.isVideoEnabled.value = true
                    binding.executePendingBindings()
                    assertEquals(View.VISIBLE, binding.root.findViewById<View>(R.id.remote_video_surface).visibility)
                    assertEquals(Color.WHITE, binding.root.findViewById<TextView>(R.id.early_media_name).currentTextColor)
                    model.isIncomingEarlyMedia.value = false; model.isVideoEnabled.value = false
                }
                if (layout == R.layout.call_active_fragment) {
                    val header = binding.root.findViewById<View>(R.id.call_header_surface)
                    assertEquals(View.VISIBLE, header.visibility)
                    model.fullScreenMode.value = true
                    binding.executePendingBindings()
                    assertEquals(View.INVISIBLE, header.visibility)
                    model.fullScreenMode.value = false; model.pipMode.value = true
                    binding.executePendingBindings()
                    assertEquals(View.INVISIBLE, header.visibility)
                    model.pipMode.value = false
                    binding.executePendingBindings()
                }
                snapshot(binding.root, "screen-${context.resources.getResourceEntryName(layout)}-$landscape", landscape)
                binding.unbind()
            }
        }
    }

    @Test fun expandedActionsReflowWithoutClippingAndRemainScrollable() = fixture { model ->
        model.conferenceModel.participantsLabel.value = "Participants (12)"
        for (conference in listOf(false, true)) for (landscape in listOf(false, true)) {
            val layout = if (conference) R.layout.call_conference_actions_bottom_sheet else R.layout.call_actions_bottom_sheet
            for (font in listOf(1f, 1.8f, 2f)) {
                val binding = inflate(layout, model, landscape, font)
                snapshot(binding.root, "adaptive-$conference-$landscape-$font", landscape)
                val grid = binding.root.findViewById<android.widget.GridLayout>(R.id.extra_actions_grid)
                val scroll = binding.root.findViewById<androidx.core.widget.NestedScrollView>(R.id.extra_actions_scroll)
                assertEquals(7, grid.childCount)
                if (!landscape && font >= 1.8f) assertTrue("Enlarged labels need wider columns", grid.columnCount <= 3)
                for (label in descendants(grid).filterIsInstance<TextView>().filter { it.labelFor != View.NO_ID }) {
                    assertTrue("Zero-width label: ${label.text}", label.width > 0)
                    assertTrue("Clipped label: ${label.text}", label.layout.height <= label.height - label.compoundPaddingTop - label.compoundPaddingBottom)
                    for (line in 0 until label.lineCount) assertEquals("Truncated ${label.text}", 0, label.layout.getEllipsisCount(line))
                    val icon = (label.parent as View).findViewById<View>(label.labelFor)
                    assertTrue("Label overlaps its icon", label.top >= icon.bottom)
                    assertTrue("Cell clips its label", label.bottom <= (label.parent as View).height)
                }
                val expectedScroll = (grid.height - scroll.height + scroll.paddingTop + scroll.paddingBottom).coerceAtLeast(0)
                scroll.scrollTo(0, grid.height)
                assertEquals("Last actions must be reachable", expectedScroll, scroll.scrollY)
                assertTrue(binding.root.findViewById<View>(R.id.main_actions).height > 0)
                binding.unbind()
            }
        }
    }

    @Test fun expandedActionsPreserveTheirBoundNavigationAndDisabledConferenceActions() = fixture { model ->
        for (landscape in listOf(false, true)) {
            var transfers = 0
            val binding = inflate(R.layout.call_actions_bottom_sheet, model, landscape, 2f)
            binding.setVariable(BR.transferCallClickListener, View.OnClickListener { transfers++ })
            binding.executePendingBindings()
            binding.root.findViewById<View>(R.id.transfer).performClick()
            binding.root.findViewById<View>(R.id.transfer_label).performClick()
            assertEquals(2, transfers)
            binding.unbind()
            val conference = inflate(R.layout.call_conference_actions_bottom_sheet, model, landscape, 2f)
            assertFalse(conference.root.findViewById<View>(R.id.record_call).isEnabled)
            assertFalse(conference.root.findViewById<View>(R.id.record_call_label).isEnabled)
            assertFalse(conference.root.findViewById<View>(R.id.screen_sharing).isEnabled)
            assertFalse(conference.root.findViewById<View>(R.id.screen_sharing).hasOnClickListeners())
            conference.unbind()
        }
    }

    @Test fun audioPickerUsesReadableSelectedAndDisabledRows() = fixture { model ->
        val device = onCore { it.audioDevices.first() }
        val binding = inflate(R.layout.call_audio_device_list_cell, model)
        for (enabled in listOf(false, true)) for (selected in listOf(false, true)) {
            binding.setVariable(BR.model, AudioDeviceModel(device, "Speaker", org.linphone.core.AudioDevice.Type.Speaker, selected, enabled))
            binding.executePendingBindings()
            val row = binding.root as android.widget.CompoundButton
            assertEquals(enabled, row.isEnabled); assertEquals(selected, row.isChecked)
            assertEquals(color(if (!enabled) R.color.mango9_call_disabled else if (selected) R.color.mango9_call_accent else R.color.mango9_call_ink), row.currentTextColor)
        }
        binding.unbind()
    }

    @Test fun lightSheetsAndKeypadKeepReadableTextAtLargeFont() = fixture { model ->
        for (landscape in listOf(false, true)) {
            for (layout in listOf(R.layout.call_numpad_bottom_sheet, R.layout.call_stats_bottom_sheet, R.layout.call_media_encryption_stats_bottom_sheet, R.layout.call_conference_layout_bottom_sheet)) {
                val binding = inflate(layout, model, landscape, 1.8f)
                when (layout) {
                    R.layout.call_numpad_bottom_sheet -> binding.setVariable(BR.model, model.numpadModel)
                    R.layout.call_stats_bottom_sheet -> binding.setVariable(BR.model, model.callStatsModel)
                    R.layout.call_media_encryption_stats_bottom_sheet -> binding.setVariable(BR.model, model.callMediaEncryptionModel)
                }
                if (layout == R.layout.call_conference_layout_bottom_sheet) binding.setVariable(BR.viewModel, model.conferenceModel)
                binding.executePendingBindings()
                assertEquals(color(R.color.mango9_call_tray), background(binding.root))
                descendants(binding.root).filterIsInstance<TextView>().forEach { text ->
                    assertTrue("Unreadable ${context.resources.getResourceEntryName(layout)}: ${text.text}", ColorUtils.calculateContrast(text.currentTextColor, effectiveBackground(text)) >= 3.0)
                }
                snapshot(binding.root, "sheet-${context.resources.getResourceEntryName(layout)}-$landscape", landscape)
                binding.unbind()
            }
        }
    }

    @Test fun nullableKeypadBindingCanAttachReleaseAndClearItsListener() {
        instrumentation.runOnMainSync {
            val view = View(context)
            var presses = 0
            var releases = 0

            fun touch(action: Int) {
                val event = android.view.MotionEvent.obtain(0, 0, action, 0f, 0f, 0)
                try { view.dispatchTouchEvent(event) } finally { event.recycle() }
            }
            view.setTouchListener(null)
            touch(android.view.MotionEvent.ACTION_DOWN)
            view.setTouchListener(object : TouchListener {
                override fun onPressed(view: View): Boolean { presses++; return true }

                override fun onReleased(view: View): Boolean { releases++; return true }
            })
            touch(android.view.MotionEvent.ACTION_DOWN)
            touch(android.view.MotionEvent.ACTION_CANCEL)
            assertEquals(1, presses); assertEquals(1, releases)
            view.setTouchListener(null)
            touch(android.view.MotionEvent.ACTION_DOWN)
            touch(android.view.MotionEvent.ACTION_UP)
            assertEquals(1, presses); assertEquals(1, releases)
        }
    }

    @Test fun populatedStatisticsWrapAndScrollWithoutLosingOptionalFields() = fixture { model ->
        val stats = model.callStatsModel
        listOf(
            stats.audioCodec to "Codec: opus / 48 kHz",
            stats.audioBandwidth to "Bandwidth: ↑ 148 kbits/s ↓ 127 kbits/s",
            stats.audioIce to "ICE: HostConnection",
            stats.audioIpFamily to "IP family: IPv6",
            stats.lossRate to "Loss rate: ↑ 0% ↓ 1%",
            stats.jitterBuffer to "Jitter buffer: 42 ms",
            stats.videoCodec to "Codec: H.264 / 90 kHz",
            stats.videoBandwidth to "Bandwidth: ↑ 1200 kbits/s ↓ 2400 kbits/s",
            stats.videoIce to "ICE: RelayConnection",
            stats.videoIpFamily to "IP family: IPv4",
            stats.videoLossRate to "Loss rate: ↑ 1% ↓ 0%",
            stats.videoResolution to "Resolution: ↑ 1920x1080 ↓ 1280x720",
            stats.videoFps to "Frames per second: ↑ 30 ↓ 25",
            stats.lostPackets to "Lost packets: 128",
            stats.repairedPackets to "Repaired packets: 127",
            stats.fecBandwidth to "Recovery bandwidth: ↑ 87 kbits/s ↓ 98 kbits/s"
        ).forEach { (field, value) -> field.value = value }
        for (landscape in listOf(false, true)) for (font in listOf(1f, 2f)) {
            stats.showAdvancedStats.value = true; stats.isVideoEnabled.value = true; stats.fecEnabled.value = true
            val binding = inflate(R.layout.call_stats_bottom_sheet, model, landscape, font, coordinator = true)
            renderSheet(binding, "populated-stats-$landscape-$font", landscape)
            assertTextFits(binding.root)
            assertEquals(19, visibleTexts(binding.root).size)
            assertScrollableToEnd(binding.root.findViewById(R.id.call_stats_scroll))
            stats.showAdvancedStats.value = false; stats.isVideoEnabled.value = false; stats.fecEnabled.value = false
            binding.executePendingBindings()
            renderSheet(binding, "audio-stats-$landscape-$font", landscape)
            assertEquals(View.GONE, binding.root.findViewById<View>(R.id.video_stats).visibility)
            assertEquals(View.GONE, binding.root.findViewById<View>(R.id.fec_stats).visibility)
            assertEquals(5, visibleTexts(binding.root).size)
            assertTextFits(binding.root)
            binding.unbind()
        }
    }

    @Test fun securityDetailsAndValidationRemainReachableAndRespectConferenceState() = fixture { model ->
        var validations = 0
        val security = onCore { CallMediaEncryptionModel { validations++ } }
        security.mediaEncryption.value = "Media encryption: Post Quantum ZRTP"
        security.zrtpCipher.value = "Cipher: AES-256"
        security.zrtpKeyAgreement.value = "Key agreement: X25519 + ML-KEM-768"
        security.zrtpHash.value = "Hash: SHA-384"
        security.zrtpAuthTag.value = "Authentication tag: HS80"
        security.zrtpAuthSas.value = "Authentication string: B32"
        for (landscape in listOf(false, true)) {
            security.isMediaEncryptionZrtp.value = true
            model.conferenceModel.isCurrentCallInConference.value = false
            val binding =
                inflate(R.layout.call_media_encryption_stats_bottom_sheet, model, landscape, 2f, coordinator = true)
            binding.setVariable(BR.model, security); binding.executePendingBindings()
            renderSheet(binding, "populated-security-$landscape", landscape)
            assertTextFits(binding.root)
            val button = visibleTexts(binding.root).single { it.hasOnClickListeners() }
            assertScrollableToEnd(binding.root.findViewById(R.id.call_encryption_scroll))
            val before = validations
            button.performClick(); assertEquals(before + 1, validations)
            model.conferenceModel.isCurrentCallInConference.value = true
            binding.executePendingBindings(); assertEquals(View.GONE, button.visibility)
            security.isMediaEncryptionZrtp.value = false
            binding.executePendingBindings()
            assertEquals(2, visibleTexts(binding.root).size)
            assertEquals(View.GONE, button.visibility)
            binding.unbind()
        }
    }

    @Test fun newCallTransferAndInCallKeypadsRetainActionsAndLargeText() = fixture { model ->
        var digits = ""
        var calls = 0; var transfers = 0; var voicemail = 0; var erases = 0; var clears = 0
        val keypad =
            NumpadModel(false, { digits += it }, { voicemail++ }, { erases++ }, { calls++ }, { transfers++ }, { clears++ })
        keypad.showLetters.value = true; keypad.voicemailAvailable.value = true
        for (landscape in listOf(false, true)) for (start in listOf(false, true)) {
            val binding =
                inflate(if (start) R.layout.start_call_numpad_bottom_sheet else R.layout.call_numpad_bottom_sheet, model, landscape, 2f, coordinator = true)
            binding.setVariable(BR.model, keypad); binding.setVariable(BR.showCallTransferIcon, false); binding.executePendingBindings()
            renderSheet(binding, "keypad-$start-$landscape", landscape)
            assertTextFits(binding.root)
            assertScrollableToEnd(binding.root.findViewById(if (start) R.id.start_call_numpad_scroll else R.id.call_numpad_scroll))
            val backspace = binding.root.findViewById<ImageView>(R.id.backspace)
            assertEquals(color(R.color.mango9_call_ink), tint(backspace))
            val prior = digits
            binding.root.findViewById<View>(R.id.digit_2).performClick()
            assertEquals(prior + "2", digits)
            val oldErases = erases; backspace.performClick(); assertEquals(oldErases + 1, erases)
            val oldClears = clears; assertTrue(backspace.performLongClick()); assertEquals(oldClears + 1, clears)
            if (start) {
                val oldCalls = calls; binding.root.findViewById<View>(R.id.call).performClick(); assertEquals(oldCalls + 1, calls)
                binding.setVariable(BR.showCallTransferIcon, true); binding.executePendingBindings()
                assertEquals(View.GONE, binding.root.findViewById<View>(R.id.call).visibility)
                assertEquals(View.VISIBLE, binding.root.findViewById<View>(R.id.transfer).visibility)
                val oldTransfers = transfers; binding.root.findViewById<View>(R.id.transfer).performClick(); assertEquals(oldTransfers + 1, transfers)
                assertTrue(binding.root.findViewById<View>(R.id.digit_0).performLongClick())
                assertEquals(prior + "2+", digits)
                val oldVoicemail = voicemail; assertTrue(binding.root.findViewById<View>(R.id.digit_1).performLongClick()); assertEquals(oldVoicemail + 1, voicemail)
            }
            binding.unbind()
        }
    }

    @Test fun scrollingKeypadCancelsThePressedKeyBeforeReleasing() = fixture { model ->
        val binding =
            inflate(R.layout.call_numpad_bottom_sheet, model, landscape = true, fontScale = 2f, coordinator = true)
        renderSheet(binding, "keypad-scroll-gesture", true, 220)
        val scroll = binding.root.findViewById<NestedScrollView>(R.id.call_numpad_scroll)
        assertTrue(scroll.canScrollVertically(1))
        val digit = binding.root.findViewById<View>(R.id.digit_2)
        var presses = 0; var releases = 0; var clicks = 0
        digit.setTouchListener(object : TouchListener {
            override fun onPressed(view: View): Boolean { presses++; return false }

            override fun onReleased(view: View): Boolean { releases++; return false }
        })
        digit.setOnClickListener { clicks++ }
        val rect = android.graphics.Rect(0, 0, digit.width, digit.height)
        scroll.offsetDescendantRectToMyCoords(digit, rect)
        scroll.scrollTo(0, rect.top)
        val x = rect.exactCenterX(); val y = (rect.centerY() - scroll.scrollY).toFloat()
        val down = android.os.SystemClock.uptimeMillis()

        fun touch(action: Int, offset: Float, time: Long) {
            val event = MotionEvent.obtain(down, down + time, action, x, y + offset, 0)
            try { scroll.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        touch(MotionEvent.ACTION_DOWN, 0f, 0)
        touch(MotionEvent.ACTION_MOVE, -80f, 40)
        touch(MotionEvent.ACTION_MOVE, -120f, 80)
        touch(MotionEvent.ACTION_UP, -120f, 120)
        assertEquals(1, presses); assertEquals(1, releases); assertEquals(0, clicks)
        binding.unbind()
    }

    @Test fun nightModeKeepsLightCallMenusAndReadableSecurityWarnings() = fixture { model ->
        val binding = inflate(R.layout.call_media_encryption_info, model, night = true)
        model.isZrtpSasValidationRequired.value = true
        binding.executePendingBindings()
        for (id in listOf(R.id.sas_validation_required_label, R.id.media_encryption_label, R.id.wait_for_encryption_info_label)) {
            val label = binding.root.findViewById<TextView>(id)
            assertTrue(ColorUtils.calculateContrast(label.currentTextColor, Color.WHITE) >= 4.5)
        }
        val attrs = binding.root.context.obtainStyledAttributes(intArrayOf(R.attr.color_main2_000, R.attr.color_main2_100, R.attr.color_main2_500, R.attr.color_bottom_sheet_background))
        try {
            assertEquals(Color.WHITE, attrs.getColor(0, 0))
            assertTrue(ColorUtils.calculateLuminance(attrs.getColor(1, 0)) > .85)
            assertTrue(ColorUtils.calculateContrast(attrs.getColor(2, 0), Color.WHITE) >= 4.5)
            assertEquals(color(R.color.mango9_call_tray), attrs.getColor(3, 0))
        } finally { attrs.recycle() }
        binding.unbind()
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private lateinit var owner: Owner

    private fun fixture(block: (CurrentCallViewModel) -> Unit) {
        runBlocking { coreContext.awaitReady() }
        val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
        while (onCore { it.globalState } != GlobalState.On) {
            check(android.os.SystemClock.elapsedRealtime() < deadline) { "App Core did not start" }
            Thread.sleep(25)
        }
        check(onCore { it.callsNb == 0 && it.accountList.isEmpty() }) { "Use an account-free emulator" }
        val store = ViewModelStore()
        lateinit var model: CurrentCallViewModel
        instrumentation.runOnMainSync {
            owner = Owner().also { it.registry.currentState = Lifecycle.State.RESUMED }
            model = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[CurrentCallViewModel::class.java]
        }
        onCore { Unit }
        instrumentation.waitForIdleSync()
        try { instrumentation.runOnMainSync { block(model) } } finally {
            instrumentation.runOnMainSync { owner.registry.currentState = Lifecycle.State.DESTROYED; store.clear() }
            onCore { Unit }
        }
    }

    private fun inflate(layout: Int, model: CurrentCallViewModel, landscape: Boolean = false, fontScale: Float = 1f, night: Boolean = false, coordinator: Boolean = false): ViewDataBinding {
        val config = Configuration(context.resources.configuration).apply {
            this.fontScale = fontScale
            orientation = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
            screenWidthDp = if (landscape) 800 else 360
            screenHeightDp = if (landscape) 360 else 800
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val themed = ContextThemeWrapper(context.createConfigurationContext(config), R.style.Theme_LinphoneInCall)
        themed.theme.applyStyle(R.style.Mango9CallPalette, true)
        val inflater = LayoutInflater.from(themed).cloneInContext(themed)
        val appCompat = AppCompatViewInflater()
        inflater.factory2 = object : LayoutInflater.Factory2 {
            override fun onCreateView(parent: View?, name: String, context: Context, attrs: AttributeSet): View? =
                appCompat.createView(parent, name, context, attrs, false, true, true, true)

            override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? = onCreateView(null, name, context, attrs)
        }
        val parent = if (coordinator) CoordinatorLayout(themed) else null
        return DataBindingUtil.inflate<ViewDataBinding>(inflater, layout, parent, false).also {
            parent?.addView(it.root)
            // Layout picker has a different viewModel type.
            if (layout != R.layout.call_conference_layout_bottom_sheet) it.setVariable(BR.viewModel, model)
            it.setVariable(BR.conferenceViewModel, model.conferenceModel)
            it.setVariable(BR.numpadModel, model.numpadModel)
            when (layout) {
                R.layout.call_numpad_bottom_sheet, R.layout.start_call_numpad_bottom_sheet -> it.setVariable(BR.model, model.numpadModel)
                R.layout.call_stats_bottom_sheet -> it.setVariable(BR.model, model.callStatsModel)
                R.layout.call_media_encryption_stats_bottom_sheet -> it.setVariable(BR.model, model.callMediaEncryptionModel)
            }
            it.lifecycleOwner = owner
            it.executePendingBindings()
        }
    }

    private fun control(binding: ViewDataBinding, iconId: Int, backgroundId: Int, selected: Boolean) {
        val icon = binding.root.findViewById<ImageView>(iconId)
        val surface = binding.root.findViewById<View>(backgroundId)
        assertEquals(selected, icon.isSelected); assertEquals(selected, surface.isSelected)
        assertEquals(color(if (selected) R.color.mango9_call_accent else R.color.mango9_call_control), background(surface))
        assertEquals(if (selected) Color.WHITE else color(R.color.mango9_call_ink), tint(icon))
        icon.isPressed = true
        assertEquals(if (selected) Color.WHITE else color(R.color.mango9_call_ink), tint(icon))
        icon.isPressed = false
    }

    private fun background(view: View): Int {
        val bitmap = Bitmap.createBitmap(60, 60, Bitmap.Config.ARGB_8888)
        return try {
            val drawable = requireNotNull(view.background)
            val bounds = android.graphics.Rect(drawable.bounds)
            drawable.setBounds(0, 0, 60, 60); drawable.draw(Canvas(bitmap))
            drawable.bounds = bounds
            bitmap.getPixel(30, 30)
        } finally { bitmap.recycle() }
    }

    private fun effectiveBackground(view: View): Int {
        val surfaces = mutableListOf<View>()
        var ancestor: View? = view
        while (ancestor != null) {
            if (ancestor.background != null) surfaces.add(ancestor)
            ancestor = ancestor.parent as? View
        }
        return surfaces.asReversed().fold(Color.WHITE) { under, surface -> ColorUtils.compositeColors(background(surface), under) }
    }

    private fun tint(view: ImageView): Int {
        val tint = requireNotNull(ImageViewCompat.getImageTintList(view))
        return tint.getColorForState(view.drawableState, tint.defaultColor)
    }

    private fun visibleTexts(root: View): List<TextView> = descendants(root).filterIsInstance<TextView>().filter { text ->
        var node: View? = text
        var visible = text.text.isNotEmpty()
        while (node != null) {
            visible = visible && node.visibility == View.VISIBLE
            if (node == root) break
            node = node.parent as? View
        }
        visible
    }

    private fun assertTextFits(root: View) {
        for (label in visibleTexts(root).filterNot { it is android.widget.EditText }) {
            assertTrue("Zero-width text: ${label.text}", label.width > label.compoundPaddingLeft + label.compoundPaddingRight)
            assertTrue("Clipped text: ${label.text}", label.layout.height <= label.height - label.compoundPaddingTop - label.compoundPaddingBottom)
            val container = label.parent as ViewGroup
            assertTrue("Text overflows its container: ${label.text}", label.left >= container.paddingLeft && label.right <= container.width - container.paddingRight)
            for (line in 0 until label.lineCount) assertEquals("Ellipsized: ${label.text}", 0, label.layout.getEllipsisCount(line))
        }
    }

    private fun assertScrollableToEnd(scroll: NestedScrollView) {
        val content = scroll.getChildAt(0)
        val expected = (content.height - scroll.height + scroll.paddingTop + scroll.paddingBottom).coerceAtLeast(0)
        scroll.scrollTo(0, content.height)
        assertEquals(expected, scroll.scrollY)
        assertFalse(scroll.canScrollVertically(1))
    }

    private fun renderSheet(binding: ViewDataBinding, name: String, landscape: Boolean, heightDp: Int? = null) {
        val parent = binding.root.parent as CoordinatorLayout
        BottomSheetBehavior.from(binding.root).state = BottomSheetBehavior.STATE_EXPANDED
        snapshot(parent, name, landscape, exactHeight = true, heightDp = heightDp)
        assertTrue("Sheet must fit its actual parent", binding.root.height <= parent.height)
        assertTrue("Expanded sheet must be on-screen", binding.root.top >= 0)
    }

    private fun snapshot(view: View, name: String, landscape: Boolean, exactHeight: Boolean = false, heightDp: Int? = null) {
        val density = view.resources.displayMetrics.density
        val width = ((if (landscape) 800 else 360) * density).toInt()
        val height = ((heightDp ?: if (landscape) 360 else 800) * density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, if (exactHeight) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST))
        view.layout(0, 0, width, view.measuredHeight)
        val bitmap = Bitmap.createBitmap(width, view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE); view.draw(Canvas(bitmap))
            val directory = File(context.cacheDir, "call-style-fixtures").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) {
        (0 until view.childCount).flatMap {
        descendants(view.getChildAt(it))
    }
    } else {
        emptyList()
    }

    private fun color(id: Int) = ContextCompat.getColor(context, id)

    private fun <T> onCore(block: (org.linphone.core.Core) -> T): T {
        val latch = CountDownLatch(1)
        var result: Result<T>? = null
        coreContext.postOnCoreThread { core -> result = runCatching { block(core) }; latch.countDown() }
        check(latch.await(15, TimeUnit.SECONDS)) { "Core queue timed out" }
        return requireNotNull(result).getOrThrow()
    }
}
