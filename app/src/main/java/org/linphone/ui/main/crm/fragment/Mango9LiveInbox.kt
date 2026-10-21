// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.ui.main.crm.fragment

import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.linphone.R
import org.linphone.mango9.*
import org.linphone.ui.main.crm.viewmodel.Mango9LiveChatViewModel
import org.linphone.ui.main.crm.viewmodel.Mango9LiveState

/** The same inbox is hosted by the Conversations tab and the CRM conversations entry. */
internal class Mango9LiveInbox(
    private val fragment: Fragment,
    private val model: Mango9LiveChatViewModel,
    private val open: (Mango9LiveConversation) -> Unit,
) {
    private val ui = Mango9LiveViews(fragment.requireContext())
    val view = ui.column()
    private val sites = Spinner(ui.context)
    private val status = Spinner(ui.context)
    private val assigned = CheckBox(ui.context).apply { text = "Assigned to me"; textSize = 12f; minHeight = ui.dp(44) }
    private val unread = CheckBox(ui.context).apply { text = "Unread"; textSize = 12f; minHeight = ui.dp(44) }
    private val presence = ui.button("● Away") { model.presence(!model.current().available) }
    private val search = EditText(ui.context).apply { hint = "Find a visitor or message"; textSize = 14f; setSingleLine(); maxLines = 1 }
    private val notice = ui.button("") { model.refresh(force = true) }
    private val progress = ProgressBar(ui.context, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
    private val list = RecyclerView(ui.context)
    private val adapter = Rows()
    private var siteIds = listOf("")
    private var siteNames = emptyList<String>()
    private val statuses = listOf("", "open", "resolved")
    private var updating = false
    private var filtering: Job? = null
    private var active = false
    private var filterOwner: String? = null

    init {
        view.setBackgroundColor(ui.surface)
        val filters = ui.row(); ui.padded(filters, 8, 0)
        listOf(sites, status).forEach { spinner ->
            val wrapper = FrameLayout(ui.context).apply { background = ui.round(ui.tinted, 8) }
            spinner.background = null
            wrapper.addView(spinner, FrameLayout.LayoutParams(-1, -1))
            wrapper.addView(ImageView(ui.context).apply { setImageResource(R.drawable.caret_down); imageTintList = android.content.res.ColorStateList.valueOf(ui.accent); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, FrameLayout.LayoutParams(ui.dp(14), ui.dp(14), android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL).apply { marginEnd = ui.dp(8) })
            filters.addView(wrapper, LinearLayout.LayoutParams(0, ui.dp(44), 1f).apply { marginEnd = ui.dp(4) })
        }
        view.addView(filters)
        status.adapter = optionsAdapter(listOf("All conversations", "Open", "Resolved"))
        val options = ui.row(); ui.padded(options, 4, 0)
        options.addView(assigned); options.addView(unread)
        options.addView(presence, LinearLayout.LayoutParams(0, ui.dp(44), 1f))
        options.addView(ui.icon(R.drawable.dots_three_vertical, "Live Chat settings") {
            fragment.findNavController().navigate(R.id.action_global_mango9LiveSettingsFragment)
        })
        view.addView(options)
        view.addView(search, LinearLayout.LayoutParams(-1, ui.dp(44)).apply { setMargins(ui.dp(12), 0, ui.dp(12), 0) })
        view.addView(progress, LinearLayout.LayoutParams(-1, ui.dp(2)))
        view.addView(notice)
        list.layoutManager = LinearLayoutManager(ui.context); list.adapter = adapter
        list.itemAnimator = null
        list.clipToPadding = false; list.setPadding(0, 0, 0, ui.dp(72))
        view.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        fun changed() { if (!updating) model.filters(search.text.toString(), siteIds.getOrNull(sites.selectedItemPosition).orEmpty(), statuses.getOrElse(status.selectedItemPosition) { "" }, assigned.isChecked, unread.isChecked) }
        val selected = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { changed() }
        }
        sites.onItemSelectedListener = selected; status.onItemSelectedListener = selected
        assigned.setOnCheckedChangeListener { _, _ -> changed() }; unread.setOnCheckedChangeListener { _, _ -> changed() }
        search.doAfterTextChanged { filtering?.cancel(); filtering = fragment.viewLifecycleOwner.lifecycleScope.launch { delay(300); changed() } }
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && (list.layoutManager as LinearLayoutManager).findLastVisibleItemPosition() >= adapter.itemCount - 4) model.refresh(more = true)
            }
        })
        bindAccount()
        model.state.observe(fragment.viewLifecycleOwner, ::render)
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            fragment.viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    if (active) { bindAccount(); model.refresh() }
                    delay(5000)
                }
            }
        }
    }
    private fun bindAccount() {
        val owner = Mango9SessionStore(ui.context).captureSession()
        val scope = owner?.let { "${Mango9MessagingApi.account(it.session)}:${it.loginGeneration}:${it.selectionGeneration}" }
        if (scope != filterOwner) {
            filterOwner = scope; updating = true; filtering?.cancel()
            search.setText(""); assigned.isChecked = false; unread.isChecked = false
            status.setSelection(0); sites.setSelection(0); updating = false
        }
        model.bind(owner)
    }
    private fun optionsAdapter(items: List<String>) = object : ArrayAdapter<String>(ui.context, android.R.layout.simple_spinner_dropdown_item, items) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = super.getView(position, convertView, parent).apply {
            (this as TextView).textSize = 13f; setTextColor(ui.accent); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(ui.dp(10), 0, ui.dp(28), 0)
        }
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View = super.getDropDownView(position, convertView, parent).apply { (this as TextView).textSize = 14f }
    }
    fun show(value: Boolean) {
        val changed = active != value
        active = value; view.visibility = if (value) View.VISIBLE else View.GONE
        if (value && changed) { bindAccount(); model.refresh(force = true) }
    }
    private fun render(state: Mango9LiveState) {
        val names = listOf("All websites") + state.sites.map { it.liveText("name").ifBlank { "Website" } }
        val ids = listOf("") + state.sites.map { it.getString("id") }
        if (names != siteNames || ids != siteIds) {
            val previous = siteIds.getOrNull(sites.selectedItemPosition)
            updating = true; siteNames = names; siteIds = ids
            sites.adapter = optionsAdapter(names)
            sites.setSelection(ids.indexOf(previous).coerceAtLeast(0)); updating = false
        }
        presence.text = if (state.available) "● Available" else "● Away"
        presence.isEnabled = !state.presenceBusy
        progress.visibility = if (state.loading) View.VISIBLE else View.INVISIBLE
        notice.text = state.error?.let { "$it  Retry" } ?: if (state.conversations.isEmpty() && !state.loading) "No conversations match these filters." else ""
        notice.visibility = if (notice.text.isEmpty()) View.GONE else View.VISIBLE
        adapter.submitList(state.conversations)
    }
    private inner class Rows : ListAdapter<Mango9LiveConversation, Holder>(object : DiffUtil.ItemCallback<Mango9LiveConversation>() {
        override fun areItemsTheSame(a: Mango9LiveConversation, b: Mango9LiveConversation) = a.id == b.id
        override fun areContentsTheSame(a: Mango9LiveConversation, b: Mango9LiveConversation) = a.copy(context = b.context) == b
    }) {
        override fun onCreateViewHolder(parent: ViewGroup, type: Int) = Holder(ui.column())
        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))
    }
    private inner class Holder(val root: LinearLayout) : RecyclerView.ViewHolder(root) {
        private val title = ui.text(size = 17f, bold = true)
        private val preview = ui.text(size = 15f).apply { maxLines = 2; setTextColor(ui.secondary) }
        private val website = ui.text(size = 12f).apply { setTextColor(ui.secondary); ui.oneLine(this) }
        private val date = ui.text(size = 11f).apply { setTextColor(ui.secondary); gravity = android.view.Gravity.END }
        init {
            root.layoutParams = RecyclerView.LayoutParams(-1, -2)
            ui.padded(root, 16, 8); ui.oneLine(title)
            root.addView(title); root.addView(preview)
            val footer = ui.row(); footer.addView(website, LinearLayout.LayoutParams(0, -2, 1f)); footer.addView(date)
            root.addView(footer); root.addView(ui.line())
        }
        fun bind(item: Mango9LiveConversation) {
            title.text = (if (item.unread) "● " else "") + item.name
            preview.text = item.summary.ifBlank { "No messages yet" }; website.text = item.siteName; date.text = ui.date(item.updated)
            root.setOnClickListener { open(item) }
        }
    }
}
