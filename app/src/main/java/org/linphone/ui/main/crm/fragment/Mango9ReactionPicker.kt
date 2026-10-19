// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.ui.main.crm.fragment

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import android.icu.text.BreakIterator
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.linphone.R
import java.util.Locale

/** Shared by SMS, Team and Social. More emoji stays visible while the strip scrolls. */
internal class Mango9ReactionStrip(context: Context) : LinearLayout(context) {
    val choices = LinearLayout(context).apply { orientation = HORIZONTAL }
    val more = ImageButton(context).apply {
        setImageResource(R.drawable.smiley)
        imageTintList = ColorStateList.valueOf(Color.GRAY)
        contentDescription = context.getString(R.string.mango9_reaction_more)
        background = rounded(Color.argb(24, 128, 128, 128), dp(24).toFloat())
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(choices)
        },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )
        addView(more, LayoutParams(dp(48), dp(48)))
    }

    fun bind(
        emoji: List<String>, selected: Set<String>, canRemove: Boolean, canAdd: Boolean,
             choose: (String) -> Unit, moreEmoji: () -> Unit
    ) {
        choices.removeAllViews()
        emoji.forEach { value ->
            val active = value in selected
            choices.addView(
                TextView(context).apply {
                text = value; textSize = 26f; gravity = Gravity.CENTER
                isSelected = active
                isEnabled = if (active) canRemove else canAdd
                alpha = if (isEnabled) 1f else 0.4f
                contentDescription = context.getString(if (active) R.string.mango9_reaction_remove else R.string.mango9_reaction_add, value)
                background = rounded(if (active) Color.argb(45, 70, 80, 210) else Color.TRANSPARENT, dp(24).toFloat())
                setOnClickListener { choose(value) }
            },
                LayoutParams(dp(48), dp(52))
            )
        }
        more.visibility = if (canAdd) View.VISIBLE else View.GONE
        more.setOnClickListener { moreEmoji() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

internal object Mango9EmojiInput {
    // ICU uses extended grapheme boundaries: modifiers, flags and ZWJ families stay intact.
    fun isSingleEmoji(value: String): Boolean {
        if (value.isEmpty() || value.length > 64) return false
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(value) }
        if (iterator.first() != 0 || iterator.next() != value.length || iterator.next() != BreakIterator.DONE) return false
        val points = value.codePoints().toArray()
        return points.any { UCharacter.hasBinaryProperty(it, UProperty.EMOJI_PRESENTATION) } ||
            (points.any { UCharacter.hasBinaryProperty(it, UProperty.EMOJI) } && points.any { it == 0xFE0F || it == 0x20E3 })
    }

    fun dialog(context: Context, current: () -> Boolean, choose: (String) -> Unit): AlertDialog {
        val input = EditText(context).apply {
            hint = context.getString(R.string.mango9_reaction_choose)
            setSingleLine(); textSize = 28f; gravity = Gravity.CENTER
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.mango9_reaction_choose)
            .setMessage(R.string.mango9_reaction_keyboard)
            .setView(input).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null).create()
        dialog.setOnShowListener {
            val confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            confirm.isEnabled = false
            input.doAfterTextChanged { confirm.isEnabled = current() && isSingleEmoji(it?.toString().orEmpty()) }
            confirm.setOnClickListener {
                val emoji = input.text.toString()
                if (current() && isSingleEmoji(emoji)) { dialog.dismiss(); choose(emoji) }
            }
            input.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            input.post {
                if (dialog.isShowing && current()) {
                    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
        return dialog
    }
}

/** Position against the visible part of a bubble, not its off-screen bottom on long messages. */
internal class Mango9MessageActionPopup(private val anchor: View) {
    private val context = anchor.context

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(6), dp(8), dp(6))
    }
    private val scroll = ScrollView(context).apply { addView(panel); isFillViewport = false }
    private val popup = PopupWindow(scroll, dp(330), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
        val color = TypedValue().also { context.theme.resolveAttribute(android.R.attr.colorBackground, it, true) }.data
        setBackgroundDrawable(rounded(color, dp(20).toFloat()))
        elevation = dp(8).toFloat(); isOutsideTouchable = true
        inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
    }
    val isShowing get() = popup.isShowing

    fun dismiss() = popup.dismiss()

    fun onDismiss(action: () -> Unit) = popup.setOnDismissListener(action)

    fun action(label: String, icon: Int? = null, destructive: Boolean = false, run: () -> Unit) {
        panel.addView(
            TextView(context).apply {
            text = label; textSize = 16f; gravity = Gravity.CENTER_VERTICAL
            val ink = context.getColor(if (destructive) org.linphone.R.color.mango9_calendar_error else org.linphone.R.color.mango9_calendar_ink)
            setTextColor(ink)
            icon?.let { resource ->
                val drawable = androidx.appcompat.content.res.AppCompatResources.getDrawable(context, resource)?.mutate()
                drawable?.setBounds(0, 0, dp(22), dp(22)); drawable?.setTint(ink)
                setCompoundDrawablesRelative(drawable, null, null, null); compoundDrawablePadding = dp(14)
            }
            minHeight = dp(48); setPadding(dp(12), dp(8), dp(12), dp(8))
            val background = TypedValue().also { context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }
            setBackgroundResource(background.resourceId)
            setOnClickListener { run() }
        },
            LinearLayout.LayoutParams(-1, -2)
        )
    }

    fun separator() {
        panel.addView(
            View(context).apply { setBackgroundColor(context.getColor(org.linphone.R.color.mango9_calendar_ink)); alpha = 0.12f },
            LinearLayout.LayoutParams(-1, dp(1)).apply {
                topMargin = dp(5); bottomMargin = dp(5); marginStart = dp(12); marginEnd = dp(12)
            }
        )
    }

    fun position() {
        val visible = Rect(); val frame = Rect()
        if (!anchor.isAttachedToWindow || !anchor.getLocalVisibleRect(visible)) { dismiss(); return }
        val screen = IntArray(2); val window = IntArray(2)
        anchor.getLocationOnScreen(screen); anchor.getLocationInWindow(window)
        visible.offset(screen[0], screen[1])
        anchor.getWindowVisibleDisplayFrame(frame)
        val width = minOf(dp(330), frame.width() - dp(16)).coerceAtLeast(1)
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val height = minOf(panel.measuredHeight, frame.height() - dp(16)).coerceAtLeast(1)
        val x = visible.centerX().minus(width / 2).coerceIn(frame.left + dp(8), maxOf(frame.left + dp(8), frame.right - width - dp(8)))
        val idealY = if (visible.top - frame.top >= height + dp(8)) visible.top - height - dp(4) else visible.bottom + dp(4)
        val y = idealY.coerceIn(frame.top + dp(8), maxOf(frame.top + dp(8), frame.bottom - height - dp(8)))
        val wx = x - screen[0] + window[0]; val wy = y - screen[1] + window[1]
        if (popup.isShowing) {
            popup.update(wx, wy, width, height)
        } else {
            popup.width = width; popup.height = height
            popup.showAtLocation(anchor, Gravity.TOP or Gravity.LEFT, wx, wy)
        }
    }
}

private fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
