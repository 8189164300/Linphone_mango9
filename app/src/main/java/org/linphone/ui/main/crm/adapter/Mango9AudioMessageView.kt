/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.ui.main.crm.adapter

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import org.linphone.R
import androidx.core.content.ContextCompat
import org.linphone.ui.main.crm.viewmodel.Mango9AudioClip
import org.linphone.ui.main.crm.viewmodel.Mango9AudioState
import org.linphone.ui.main.crm.viewmodel.Mango9InlineAudioController

/** Lightweight recycled controls: binding does not prepare or download audio. */
internal class Mango9AudioMessageView(context: Context, private val cardStyle: Boolean = false) : LinearLayout(context) {
    val play = ImageButton(context)
    val seek = SeekBar(context)
    val name = TextView(context)
    val elapsed = TextView(context)
    val duration = TextView(context)
    val status = TextView(context)
    private var clip: Mango9AudioClip? = null
    private var controller: Mango9InlineAudioController? = null
    private var subscription: AutoCloseable? = null
    private var dragging = false
    private var resumeAfterSeek = false
    private var selectionAction: (() -> Unit)? = null

    fun selection(action: (() -> Unit)?) {
        selectionAction = action
        importantForAccessibility = if (action == null) IMPORTANT_FOR_ACCESSIBILITY_AUTO else IMPORTANT_FOR_ACCESSIBILITY_YES
        descendantFocusability = if (action == null) FOCUS_AFTER_DESCENDANTS else FOCUS_BLOCK_DESCENDANTS
        setOnClickListener(if (action == null) null else OnClickListener { action() })
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean =
        selectionAction != null || super.onInterceptTouchEvent(event)

    fun setInk(color: Int) {
        if (cardStyle) {
            name.setTextColor(ContextCompat.getColor(context, R.color.mango9_team_ink))
            listOf(elapsed, duration, status).forEach { it.setTextColor(ContextCompat.getColor(context, R.color.mango9_team_secondary)) }
            play.imageTintList = ColorStateList.valueOf(Color.WHITE)
            seek.progressTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.orange_main_500))
            seek.thumbTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.orange_main_500))
            return
        }
        listOf(name, elapsed, duration, status).forEach { it.setTextColor(color) }
        play.imageTintList = ColorStateList.valueOf(color)
        seek.progressTintList = ColorStateList.valueOf(color)
        seek.thumbTintList = ColorStateList.valueOf(color)
    }

    init {
        orientation = VERTICAL
        val heading = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        play.apply {
            setImageResource(R.drawable.play_fill)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x304343D5) }
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { clip?.let { controller?.toggle(it) } }
        }
        heading.addView(play, LayoutParams(dp(48), dp(48)))
        name.apply {
            setTextColor(Color.WHITE); textSize = 13f; setTypeface(typeface, Typeface.BOLD)
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(8), 0, 0, 0)
        }
        heading.addView(name, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(heading, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        seek.apply {
            progressTintList = ColorStateList.valueOf(Color.WHITE)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            contentDescription = context.getString(R.string.mango9_audio_seek)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) {
                    dragging = true
                    resumeAfterSeek = clip?.let { controller?.beginSeek(it) } == true
                }

                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        elapsed.text = time(progress)
                        // Keyboard/TalkBack progress actions don't send touch tracking callbacks.
                        if (!dragging) clip?.let { controller?.seek(it, progress, controller?.state?.requested == true) }
                    }
                }

                override fun onStopTrackingTouch(bar: SeekBar) {
                    dragging = false
                    clip?.let { controller?.seek(it, bar.progress, resumeAfterSeek) }
                }
            })
        }
        addView(seek, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))
        val times = LinearLayout(context)
        listOf(elapsed, duration).forEach { it.setTextColor(Color.WHITE); it.textSize = 11f; it.typeface = Typeface.MONOSPACE }
        duration.gravity = Gravity.END
        times.addView(elapsed, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        times.addView(duration, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(times)
        status.apply { setTextColor(Color.WHITE); textSize = 12f; visibility = GONE }
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        if (cardStyle) {
            heading.removeAllViews(); removeAllViews()
            heading.addView(play, LayoutParams(dp(46), dp(46)))
            val controls = LinearLayout(context).apply {
                orientation = VERTICAL
                addView(name, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                addView(seek, LayoutParams(LayoutParams.MATCH_PARENT, dp(36)))
                addView(times, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }
            name.setPadding(0, 0, 0, 0); name.textSize = 15f
            seek.setPadding(dp(2), 0, dp(2), 0)
            play.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ContextCompat.getColor(context, R.color.orange_main_500)) }
            heading.addView(controls, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
            addView(heading, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            setPadding(dp(11), dp(11), dp(11), dp(11))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat(); setColor(ContextCompat.getColor(context, R.color.mango9_team_card))
                setStroke(dp(1), ContextCompat.getColor(context, R.color.mango9_team_border))
            }
            setInk(Color.WHITE)
        }
    }

    fun bind(value: Mango9AudioClip, owner: Mango9InlineAudioController) {
        if (clip == value && controller === owner) return
        subscription?.close(); subscription = null
        dragging = false
        clip = value; controller = owner
        name.text = if (cardStyle && (value.media.name.isBlank() || value.media.name.startsWith("mango9-voice-", true))) {
            context.getString(R.string.mango9_voice_message)
        } else value.media.name.ifBlank { context.getString(R.string.mango9_audio_message) }
        render(Mango9AudioState())
        if (isAttachedToWindow) subscribe()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); subscribe() }

    override fun onDetachedFromWindow() {
        subscription?.close(); subscription = null
        dragging = false
        super.onDetachedFromWindow()
    }

    private fun subscribe() {
        val value = clip ?: return
        if (subscription == null) subscription = controller?.observe(value, ::render)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(MeasureSpec.makeMeasureSpec(resolveSize(dp(if (cardStyle) minOf(264, resources.configuration.screenWidthDp - 80) else 250), widthMeasureSpec), MeasureSpec.EXACTLY), heightMeasureSpec)
    }

    private fun render(state: Mango9AudioState) {
        play.setImageResource(when { state.error -> R.drawable.arrow_clockwise; state.requested || state.loading -> R.drawable.pause_fill; else -> R.drawable.play_fill })
        play.contentDescription = context.getString(
            when {
            state.error -> R.string.mango9_audio_retry
            state.loading -> R.string.mango9_audio_cancel
            state.requested -> R.string.mango9_audio_pause
            else -> R.string.mango9_audio_play
        }
        )
        seek.isEnabled = state.prepared && !state.loading && !state.error
        if (!dragging) {
            seek.max = state.duration.coerceAtLeast(1); seek.progress = state.position; elapsed.text = time(state.position)
        }
        duration.text = if (state.duration > 0) time(state.duration) else "–:––"
        status.visibility = if (state.error || state.loading || state.focusDenied) VISIBLE else GONE
        status.text = when {
            state.error -> context.getString(R.string.mango9_audio_error)
            state.focusDenied -> context.getString(R.string.mango9_audio_focus_denied)
            state.loading -> context.getString(R.string.mango9_audio_loading)
            else -> ""
        }
    }

    private fun time(milliseconds: Int): String {
        val seconds = milliseconds.coerceAtLeast(0) / 1000
        return "%d:%02d".format(java.util.Locale.ROOT, seconds / 60, seconds % 60)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
