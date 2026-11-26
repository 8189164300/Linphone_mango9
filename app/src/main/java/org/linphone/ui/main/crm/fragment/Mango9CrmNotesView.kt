// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.ui.main.crm.fragment

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.linphone.mango9.*

/** Previous notes live inside the normal Notes card, without granting record-edit permissions. */
internal class Mango9CrmNotesView(
    context: Context, private val lifecycleOwner: LifecycleOwner, private val lease: Mango9SessionLease,
    private val contactId: Int, private val ownerCurrent: () -> Boolean
) : LinearLayout(context) {
    private val api = Mango9Opportunities(context)
    private val sessions = Mango9SessionStore(context)
    private val notes = linkedMapOf<Long, Mango9CrmNote>()
    private var page = 0
    private var generation = 0
    private var job: Job? = null
    private var more = false
    private var loaded = false
    private var error = ""
    private var busy = false
    private val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME && isAttachedToWindow) load(true)
    }

    init { orientation = VERTICAL; setPadding(0, dp(12), 0, dp(8)) }

    private fun current() = ownerCurrent() && sessions.currentSession(lease) != null

    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()

    private fun label(value: String, small: Boolean = false, bold: Boolean = false, selectable: Boolean = false) = TextView(context).apply {
        text = value; textSize = if (small) Mango9CrmText.label else Mango9CrmText.value
        setTextColor(Color.rgb(38, 58, 82)); setPadding(0, dp(4), 0, dp(4))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setTextIsSelectable(selectable)
    }

    private fun button(title: String, action: () -> Unit) = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
        text = title; isAllCaps = false; textSize = Mango9CrmText.value; minimumHeight = dp(44)
        setTextColor(Color.rgb(66, 83, 199)); setOnClickListener { if (current()) action() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lifecycleOwner.lifecycle.addObserver(observer)
        if (!busy && !loaded) load(true)
    }

    override fun onDetachedFromWindow() {
        lifecycleOwner.lifecycle.removeObserver(observer)
        generation++; job?.cancel(); job = null; notes.clear(); page = 0; loaded = false; busy = false
        removeAllViews()
        super.onDetachedFromWindow()
    }

    private fun render() {
        removeAllViews()
        if (!current()) return
        addView(label("Previous notes", small = true, bold = true))
        notes.values.forEach { note ->
            addView(label(note.author, bold = true, selectable = true))
            if (note.created.isNotEmpty()) addView(label(note.dateLabel, small = true))
            addView(label(note.text, selectable = true).apply { setPadding(0, dp(4), 0, dp(16)) })
        }
        if (busy) addView(ProgressBar(context).apply { contentDescription = "Loading notes" }, LayoutParams(dp(32), dp(32)))
        if (error.isNotEmpty()) {
            addView(label(error).apply { setTextColor(Color.rgb(170, 52, 76)) })
            addView(button("Retry notes") { load(true) })
        } else if (loaded && !busy && notes.isEmpty()) addView(label("No previous notes."))
        if (more && !busy) addView(button("Load earlier notes") { load(false) })
    }

    private fun load(reset: Boolean) {
        if (!current() || (!reset && busy)) return
        val seq = ++generation; job?.cancel()
        if (reset) { notes.clear(); page = 0; more = false; loaded = false }
        val requestedPage = page + 1
        busy = true; error = ""; render()
        job = lifecycleOwner.lifecycleScope.launch {
            try {
                val raw = api.request(lease, query = mapOf("page" to "$requestedPage", "limit" to "50"), mobilePath = "contacts/$contactId/notes")
                if (seq != generation || !current() || !isAttachedToWindow) return@launch
                val next = Mango9CrmNotesPage.parse(raw, contactId, requestedPage)
                val size = notes.size
                next.notes.forEach { notes[it.id] = it }
                require(!next.more || notes.size > size)
                page = requestedPage; more = next.more; loaded = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (seq == generation) {
                    // Do not retain notes when permission or the account has changed.
                    notes.clear(); page = 0; more = false; loaded = false
                    error = "Notes could not be loaded. Please retry."
                }
            } finally {
                if (seq == generation) { busy = false; render() }
            }
        }
    }
}
