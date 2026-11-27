// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.ui.main.crm.fragment

import android.content.Context
import android.content.ContextWrapper
import android.content.DialogInterface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.linphone.R

/** A page in the activity's content hierarchy, with a toolbar and system Back support. */
internal class Mango9FullPage(private val context: Context) : DialogInterface {
    private val activity: ComponentActivity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<ComponentActivity>().first()
    private val root = LinearLayout(context).apply {
        tag = "mango9.fullpage"
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(context.getColor(R.color.mango9_calendar_background))
        isClickable = true; isFocusableInTouchMode = true
    }
    private val toolbar = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(context.getColor(R.color.mango9_calendar_surface))
    }
    private val title = TextView(context).apply {
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 14, 19, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        textSize = 19f; setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(context.getColor(R.color.mango9_calendar_ink))
        setPadding(dp(8), dp(12), dp(8), dp(12))
    }
    private val buttons = mutableMapOf<Int, Button>()
    private var pageHost: ViewGroup? = null

    fun setHost(host: ViewGroup) = apply { pageHost = host }

    private var shown: ((DialogInterface) -> Unit)? = null
    private var dismissed: ((DialogInterface) -> Unit)? = null
    private val dismissActions = mutableListOf<() -> Unit>()

    fun whenDismissed(action: () -> Unit) = apply { dismissActions.add(action) }

    private var cancelled: ((DialogInterface) -> Unit)? = null
    private var cancelable = true
    private var dirty: () -> Boolean = { false }

    fun protectChanges(check: () -> Boolean) = apply { dirty = check }

    fun requestLeave(action: () -> Unit) {
        if (!cancelable) return
        if (dirty()) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(context).setTitle("Discard changes?").setMessage("Your unsaved changes will be lost.").setNegativeButton("Keep editing", null).setPositiveButton("Discard changes") { _, _ -> action() }.show()
        } else {
            action()
        }
    }

    private val siblings = mutableMapOf<View, Int>()
    private val previousPageVisibility = mutableMapOf<View, Int>()
    private var opportunityPage = false
    val isOpportunityList get() = title.text.toString() == "Opportunities"
    var isShowing = false
        private set
    private val back = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() { cancel() }
    }

    init {
        toolbar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(toolbar, LinearLayout.LayoutParams(-1, -2))
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            if (pageHost == null) v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()

    fun setTitle(value: CharSequence) = apply { title.text = value }

    fun setTitle(value: Int) = setTitle(context.getString(value))

    /** Opportunity pages own their blue header; other CRM pages keep their existing toolbar. */
    fun opportunityChrome() = apply {
        opportunityPage = true
        val list = isOpportunityList
        toolbar.setBackgroundColor(if (list) android.graphics.Color.WHITE else android.graphics.Color.rgb(66, 83, 199))
        title.setTextColor(if (list) android.graphics.Color.rgb(66, 83, 199) else android.graphics.Color.WHITE); title.textSize = if (list) 18f else 22f
        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(title, 18, 22, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        root.setBackgroundColor(android.graphics.Color.rgb(246, 247, 251))
        buttons[DialogInterface.BUTTON_NEGATIVE]?.let { old ->
            toolbar.removeView(old)
            toolbar.addView(
                android.widget.ImageButton(context).apply {
                setImageResource(R.drawable.caret_left); imageTintList = android.content.res.ColorStateList.valueOf(if (list) android.graphics.Color.rgb(66, 83, 199) else android.graphics.Color.WHITE)
                background = null; contentDescription = "Back"; setPadding(dp(12), dp(12), dp(12), dp(12)); setOnClickListener {
                    cancel()
                }
            },
                0, LinearLayout.LayoutParams(dp(48), dp(56))
            )
        }
    }

    fun headerAction(view: View) = apply {
        toolbar.addView(view, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
    }

    fun setView(view: View) = apply { root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f)) }

    fun setNegativeButton(label: Int, action: ((DialogInterface, Int) -> Unit)?) = button(DialogInterface.BUTTON_NEGATIVE, label, action)

    fun setPositiveButton(label: Int, action: ((DialogInterface, Int) -> Unit)?) = button(DialogInterface.BUTTON_POSITIVE, label, action)

    fun setNeutralButton(label: Int, action: ((DialogInterface, Int) -> Unit)?) = button(DialogInterface.BUTTON_NEUTRAL, label, action)

    private fun button(id: Int, label: Int, action: ((DialogInterface, Int) -> Unit)?) = apply {
        val button = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            this.id = when (id) {
                DialogInterface.BUTTON_POSITIVE -> android.R.id.button1
                DialogInterface.BUTTON_NEGATIVE -> android.R.id.button2
                else -> android.R.id.button3
            }
            setText(label); isAllCaps = false; minHeight = dp(48); minimumWidth = dp(48)
            setTextColor(context.getColor(if (id == DialogInterface.BUTTON_NEUTRAL) R.color.mango9_calendar_error else R.color.mango9_calendar_accent))
            setOnClickListener {
                if (id == DialogInterface.BUTTON_NEGATIVE) {
                requestLeave {action?.invoke(this@Mango9FullPage, id); cancelled?.invoke(this@Mango9FullPage); dismiss()}
            } else { action?.invoke(this@Mango9FullPage, id); dismiss() }
            }
        }
        buttons[id] = button
        if (id == DialogInterface.BUTTON_NEGATIVE) {
            toolbar.addView(button, 0)
        } else if (id == DialogInterface.BUTTON_NEUTRAL) {
            root.addView(button, LinearLayout.LayoutParams(-1, -2))
        } else {
            toolbar.addView(button)
        }
    }

    fun create() = this

    fun getButton(id: Int): Button = buttons[id] ?: Button(context).apply { visibility = View.GONE }

    fun setOnShowListener(listener: (DialogInterface) -> Unit) { shown = listener }

    fun setOnDismissListener(listener: (DialogInterface) -> Unit) { dismissed = listener }

    fun setOnCancelListener(listener: (DialogInterface) -> Unit) { cancelled = listener }

    fun setCancelable(value: Boolean) { cancelable = value }

    fun show() {
        if (isShowing) return
        val host = pageHost ?: activity.findViewById<ViewGroup>(android.R.id.content)
        for (i in 0 until host.childCount) host.getChildAt(i).let {
            if (opportunityPage && it.tag == "mango9.fullpage") {
                previousPageVisibility[it] = it.visibility; it.visibility = View.INVISIBLE
            }
            siblings[it] = it.importantForAccessibility
            it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        host.addView(root, FrameLayout.LayoutParams(-1, -1))
        isShowing = true
        activity.onBackPressedDispatcher.addCallback(back)
        ViewCompat.requestApplyInsets(root)
        root.requestFocus(); shown?.invoke(this)
    }

    override fun cancel() { requestLeave { cancelled?.invoke(this); dismiss() } }

    override fun dismiss() {
        if (!isShowing) return
        isShowing = false; back.remove()
        (root.parent as? ViewGroup)?.removeView(root)
        siblings.forEach { (view, mode) -> view.importantForAccessibility = mode }; siblings.clear()
        previousPageVisibility.forEach { (view, visibility) -> view.visibility = visibility }; previousPageVisibility.clear()
        dismissed?.invoke(this); dismissActions.forEach { it() }
    }
}
