// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.ui.main.crm.fragment

import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.linphone.R

/** Selection is an account/filter snapshot. Newly arriving conversations are never selected implicitly. */
internal class Mango9ChatListSelection(
    private val fragment: Fragment,
    private val scope: () -> String,
    private val current: () -> Boolean,
    private val all: suspend () -> List<String>,
    private val work: suspend (String, String) -> Unit,
    private val refresh: () -> Unit,
    private val changed: () -> Unit,
) {
    private val ui = Mango9LiveViews(fragment.requireContext())
    val view = ui.row()
    var selecting = false; private set
    var busy = false; private set
    private var owner = ""
    private var epoch = 0
    private var failed = false
    private var allPagesSelected = false
    private var preserveSnapshot = false
    private val selected = linkedSetOf<String>()
    private var rows = emptySet<String>()
    private val toggle = ui.icon(R.drawable.check_square_offset, "Select chats") {
        if (!busy) {
            selecting = !selecting; selected.clear(); failed = false; preserveSnapshot = false; allPagesSelected = false; render(); changed()
        }
    }
    private val every = CheckBox(ui.context).apply {
        contentDescription = "Select all chats in this list"; minWidth = ui.dp(44); minHeight = ui.dp(44)
    }
    private val count = ui.text(size = 13f)
    private val progress = ProgressBar(ui.context)
    private val read = ui.icon(R.drawable.envelope_simple_open, "Mark selected chats read") { run("read") }
    private val unread = ui.icon(R.drawable.envelope_simple, "Mark selected chats unread") { run("unread") }
    private val delete = ui.icon(R.drawable.trash, "Delete selected chats") {
        MaterialAlertDialogBuilder(ui.context).setTitle("Delete selected chats?")
            .setMessage("These chats will be removed for you. Other participants keep their copies.")
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton("Delete") { _, _ -> run("delete") }.show()
    }

    init {
        view.addView(toggle); view.addView(every); view.addView(count, LinearLayout.LayoutParams(0, -2, 1f))
        view.addView(progress, LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)))
        view.addView(read); view.addView(unread); view.addView(delete)
        every.setOnClickListener {
            if (allPagesSelected && selected.isNotEmpty() && rows.isNotEmpty() && selected.containsAll(rows)) {
                selected.clear(); failed = false; preserveSnapshot = false; allPagesSelected = false; render(); changed()
            } else {
                val ticket = epoch; busy = true; render(); changed()
                fragment.viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val ids = all(); if (valid(ticket)) {
                            selected.clear(); selected.addAll(ids); failed = false; allPagesSelected = true; preserveSnapshot = true
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException)throw e; if (valid(ticket))error("Could not select every chat. Please retry.")
                    } finally {if (valid(ticket)){busy = false; render(); changed()}}
                }
            }
        }
        render()
    }

    private fun valid(ticket: Int) = ticket == epoch && scope() == owner && current()

    fun bind(ids: Collection<String>) {
        val next = scope()
        if (owner != next){
            owner = next; epoch++; selecting = false; busy = false; selected.clear(); failed = false; preserveSnapshot = false; allPagesSelected = false
        }
        rows = ids.toSet()
        // Failed operations retain their snapshots even if a refresh returns only page one.
        if (!busy && !failed && !preserveSnapshot)selected.retainAll(rows)
        render()
    }

    fun checked(id: String) = id in selected

    fun toggle(id: String?) {
        allPagesSelected = false
        if (!busy && id != null){if (!selected.add(id))selected.remove(id); render(); changed()}
    }

    private fun render() {
        toggle.setImageResource(if (selecting) R.drawable.x else R.drawable.check_square_offset)
        toggle.isEnabled = !busy && (selecting || rows.isNotEmpty()); toggle.contentDescription = if (selecting)"Cancel selection" else "Select chats"
        every.visibility = if (selecting)View.VISIBLE else View.GONE; every.isEnabled = !busy
        every.isChecked = allPagesSelected && rows.isNotEmpty() && selected.containsAll(rows)
        count.text = if (selecting)"${selected.size} selected" else ""
        progress.visibility = if (busy)View.VISIBLE else View.GONE
        listOf(read, unread, delete).forEach{
            it.visibility = if (selecting && !busy)View.VISIBLE else View.GONE; it.isEnabled = selected.isNotEmpty()
        }
    }

    private fun error(message: String) {
        MaterialAlertDialogBuilder(ui.context).setMessage(message).setPositiveButton(android.R.string.ok, null).show()
    }

    private fun run(action: String) {
        if (busy || selected.isEmpty())return
        val ticket = epoch; val targets = selected.toList(); busy = true; render(); changed()
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            var cursor = 0; var failures = 0
            try {
                coroutineScope {
                    repeat(minOf(4, targets.size)){
                        launch {
                    while (valid(ticket) && cursor < targets.size){
                        val id = targets[cursor++]
                        try {
                            work(id, action); if (valid(ticket))selected.remove(id)
                        } catch (e: Exception){if (e is CancellationException)throw e; failures++}
                    }
                }
                    }
                }
                if (valid(ticket)){
                    failed = failures > 0; if (selected.isEmpty())selecting = false; refresh(); if (failed)error("Some chats could not be updated. They remain selected so you can retry.")
                }
            } finally {if (valid(ticket)){busy = false; render(); changed()}}
        }
    }
}
