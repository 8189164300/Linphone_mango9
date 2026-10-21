// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.ui.main.crm.fragment

import android.content.Context
import android.icu.text.BreakIterator
import android.os.Bundle
import android.text.InputType
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.linphone.R
import org.linphone.mango9.*
import org.linphone.ui.main.crm.viewmodel.Mango9TemplatesViewModel
import org.linphone.ui.main.fragment.GenericMainFragment

/** A native full-page library, kept separate from the underlying conversation's draft. */
class Mango9TemplatesFragment : GenericMainFragment() {
    private val model: Mango9TemplatesViewModel by viewModels()
    private lateinit var ui: Mango9LiveViews
    private lateinit var root: LinearLayout
    private lateinit var heading: TextView
    private lateinit var action: TextView
    private lateinit var notice: TextView
    private lateinit var body: LinearLayout
    private var items: LinearLayout? = null
    private var titleInput: EditText? = null
    private var messageInput: EditText? = null
    private var counter: TextView? = null
    private var rendered: Mango9TemplatesViewModel.Mode? = null
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        ui = Mango9LiveViews(requireContext()); root = ui.column().apply { setBackgroundColor(ui.surface) }; rendered = null
        val header = ui.row()
        header.addView(ui.icon(R.drawable.caret_left, "Back") { back() })
        heading = ui.text("Templates", 18f, true); ui.oneLine(heading)
        header.addView(heading, LinearLayout.LayoutParams(0, ui.dp(48), 1f).apply { gravity = Gravity.CENTER_VERTICAL }); heading.gravity = Gravity.CENTER_VERTICAL
        action = ui.button("Create") { when (model.mode) {
            Mango9TemplatesViewModel.Mode.EDITOR -> { hideKeyboard(); model.save() }
            Mango9TemplatesViewModel.Mode.DETAIL -> model.edit(model.selected)
            else -> model.edit()
        } }; header.addView(action); root.addView(header)
        notice = ui.text(size = 13f).apply { ui.padded(this); setOnClickListener { if (model.mode == Mango9TemplatesViewModel.Mode.LIST) model.load() } }; root.addView(notice)
        body = ui.column(); root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }
    override fun onViewCreated(view: View, state: Bundle?) {
        super.onViewCreated(view, state)
        model.bind(Mango9NavigationSession.resolve(requireArguments(), Mango9SessionStore(requireContext())))
        model.changed.observe(viewLifecycleOwner) { render() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() { back() } })
    }
    private fun hideKeyboard() { view?.let { (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken, 0) } }
    private fun back() {
        if (model.busy) return
        if (model.mode == Mango9TemplatesViewModel.Mode.EDITOR && model.pending != null) {
            MaterialAlertDialogBuilder(requireContext()).setMessage("This save is not confirmed. Retry the same save before closing to avoid creating a duplicate.").setPositiveButton("Keep editing", null).show(); return
        }
        if (model.mode == Mango9TemplatesViewModel.Mode.EDITOR && (model.text.isNotEmpty() || model.title.isNotEmpty())) {
            MaterialAlertDialogBuilder(requireContext()).setMessage("Discard unsaved edits?").setNegativeButton("Keep editing", null).setPositiveButton("Discard") { _, _ -> hideKeyboard(); model.back() }.show(); return
        }
        hideKeyboard()
        if (model.mode == Mango9TemplatesViewModel.Mode.LIST) findNavController().popBackStack() else model.back()
    }
    private fun editorField(hintValue: String, value: String, multiline: Boolean): EditText = EditText(requireContext()).apply {
        hint = hintValue; setText(value); textSize = 16f; minHeight = ui.dp(48)
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
        imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        background = ui.round(ui.tinted, 12); setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
        if (!multiline) setSingleLine(true)
    }
    private fun render() {
        heading.text = when(model.mode) { Mango9TemplatesViewModel.Mode.LIST -> "Templates"; Mango9TemplatesViewModel.Mode.DETAIL -> "Template"; else -> if (model.editing == null) "Create template" else "Edit template" }
        notice.text = model.error?.let { it + if (model.mode == Mango9TemplatesViewModel.Mode.LIST) "\nTap to retry." else "" } ?: if (model.busy) (if (model.mode == Mango9TemplatesViewModel.Mode.EDITOR) "Saving…" else "Loading templates…") else ""
        notice.visibility = if (notice.text.isEmpty()) View.GONE else View.VISIBLE
        if (rendered != model.mode) {
            rendered = model.mode; body.removeAllViews(); body.setPadding(0, 0, 0, 0); items = null; titleInput = null; messageInput = null; counter = null
            when(model.mode) {
                Mango9TemplatesViewModel.Mode.LIST -> {
                    val search = editorField("Search templates", model.query, false); search.contentDescription = "Search templates"
                    body.addView(ui.padded(search))
                    search.doAfterTextChanged { model.query = it.toString(); model.load(debounce = true) }
                    items = ui.column(); body.addView(ScrollView(requireContext()).apply { addView(items) }, LinearLayout.LayoutParams(-1, 0, 1f))
                }
                Mango9TemplatesViewModel.Mode.EDITOR -> {
                    val compact = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    ui.padded(body, 16, if (compact) 4 else 12)
                    if (!compact) {
                        body.addView(ui.text("Saves to your account", 12f).apply { setTextColor(ui.secondary) })
                        body.addView(ui.padded(ui.text("Title", 14f, true), 0, 8))
                    }
                    titleInput = editorField("Template name", model.title, false).also { field -> body.addView(field); field.doAfterTextChanged { model.title = it.toString(); updateEditor() } }
                    if (!compact) body.addView(ui.padded(ui.text("Message", 14f, true), 0, 8))
                    messageInput = editorField("Type your template…", model.text, true).also { field -> body.addView(field, LinearLayout.LayoutParams(-1, 0, 1f)); field.doAfterTextChanged { model.text = it.toString(); updateEditor() } }
                    counter = ui.text(size = 12f).apply { gravity = Gravity.END; setTextColor(ui.secondary); ui.padded(this, 0, 8) }; body.addView(counter)
                }
                else -> Unit
            }
        }
        when(model.mode) {
            Mango9TemplatesViewModel.Mode.LIST -> {
                action.text = "Create"; action.isEnabled = model.canCreate() && !model.busy
                items?.apply {
                    removeAllViews(); addView(ui.padded(ui.text("My templates", 13f, true)))
                    addView(ui.button("＋  Create template  ›") { model.edit() }.apply { isEnabled = model.canCreate() && !model.busy })
                    for (row in model.rows) {
                        val cell = ui.row(); val texts = ui.column(); texts.addView(ui.text(row.liveText("title"), 16f, true)); texts.addView(ui.text(row.liveText("preview"), 14f).apply { maxLines = 2; setTextColor(ui.secondary) })
                        cell.addView(texts, LinearLayout.LayoutParams(0, -2, 1f)); cell.addView(ui.text("›", 22f)); ui.padded(cell, 16, 14)
                        cell.contentDescription = row.liveText("title"); cell.setOnClickListener { if (!model.busy) model.select(row) }; addView(cell); addView(ui.line())
                    }
                    if (model.loaded && model.rows.isEmpty() && !model.busy) addView(ui.padded(ui.text(if (model.query.isBlank()) "No templates yet. Create your first template." else "No matching templates.")))
                    if (model.rows.size < model.total && !model.busy) addView(ui.button("Load more") { model.load(more = true) })
                    addView(ui.padded(ui.text("Saved to your account. Choose a template, then Insert or Edit.", 12f).apply { setTextColor(ui.secondary) }))
                }
            }
            Mango9TemplatesViewModel.Mode.DETAIL -> {
                action.text = "Edit"; action.isEnabled = model.selected?.optJSONObject("permissions")?.optBoolean("can_update") == true && !model.busy && model.owner() != null
                body.removeAllViews(); ui.padded(body, 16, 12)
                model.selected?.let { row ->
                    body.addView(ui.padded(ui.text(row.liveText("title"), 22f, true), 0, 8))
                    body.addView(ScrollView(requireContext()).apply { addView(ui.padded(ui.text(row.liveText("content"), 16f).apply { setTextIsSelectable(true); background = ui.round(ui.tinted) })) }, LinearLayout.LayoutParams(-1, 0, 1f))
                    body.addView(ui.padded(ui.text("${characters(row.liveText("content"))} characters", 12f).apply { gravity = Gravity.END; setTextColor(ui.secondary) }, 0, 8))
                    body.addView(ui.button("Insert") {
                        val owner = model.owner() ?: return@button
                        if (model.busy || row.optJSONObject("permissions")?.optBoolean("can_use") != true) return@button
                        val result = Bundle().apply { Mango9NavigationSession.bind(this, owner); putString("text", row.getString("content")); putInt("cursor", requireArguments().getInt("templateCursor", 0)) }
                        parentFragmentManager.setFragmentResult(RESULT, result); hideKeyboard(); findNavController().popBackStack()
                    }.apply { background = ui.round(ui.accent); setTextColor(android.graphics.Color.WHITE); isEnabled = !model.busy && model.owner() != null })
                    body.addView(ui.padded(ui.text("You can edit the message in the composer before sending.", 12f), 0, 8))
                }
            }
            Mango9TemplatesViewModel.Mode.EDITOR -> updateEditor()
        }
    }
    private fun updateEditor() {
        action.text = if (model.pending != null && !model.busy) "Retry save" else "Save"
        action.isEnabled = model.valid() && !model.busy && model.owner() != null
        titleInput?.isEnabled = !model.busy && model.pending == null; messageInput?.isEnabled = !model.busy && model.pending == null
        counter?.text = "${characters(model.text)} characters" + if (model.text.toByteArray(Charsets.UTF_8).size > model.textLimit() || model.title.toByteArray(Charsets.UTF_8).size > model.titleLimit()) " · Too long to save" else ""
    }
    companion object {
        private const val RESULT = "mango9.template.insert"
        internal fun characters(text: String): Int {
            val iterator = BreakIterator.getCharacterInstance(); iterator.setText(text); var count = 0
            while (iterator.next() != BreakIterator.DONE) count++
            return count
        }
        internal fun open(fragment: Fragment, lease: Mango9SessionLease?, input: EditText) {
            if (lease == null) return
            val args = Bundle().apply { Mango9NavigationSession.bind(this, lease); putInt("templateCursor", input.selectionStart.takeIf { it >= 0 } ?: input.length()) }
            fragment.findNavController().navigate(R.id.mango9TemplatesFragment, args)
        }
        internal fun listen(fragment: Fragment, lease: () -> Mango9SessionLease?, current: () -> Boolean = { true }, input: () -> EditText) {
            fragment.parentFragmentManager.setFragmentResultListener(RESULT, fragment.viewLifecycleOwner) { _, result ->
                if (!current() || Mango9NavigationSession.matching(result, lease()) == null) return@setFragmentResultListener
                val field = input(); val text = result.getString("text") ?: return@setFragmentResultListener
                val old = field.text.toString(); val cursor = result.getInt("cursor", old.length).coerceIn(0, old.length)
                field.setText(Mango9TemplateText.insert(old, text, cursor)); field.setSelection((cursor + text.length).coerceAtMost(field.length()))
                field.clearFocus(); (field.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(field.windowToken, 0)
            }
        }
    }
}
