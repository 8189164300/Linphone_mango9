/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.ui.main.crm.fragment

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.UiThread
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.Locale
import kotlinx.coroutines.launch
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.databinding.Mango9CrmFragmentBinding
import org.linphone.mango9.Mango9ChatStore
import org.linphone.mango9.Mango9CrmGroup
import org.linphone.mango9.Mango9CrmRecordDetail
import org.linphone.mango9.Mango9CrmSchema
import org.linphone.mango9.Mango9RecordKind
import org.linphone.ui.assistant.AssistantActivity
import org.linphone.ui.main.crm.adapter.Mango9CrmRecordAdapter
import org.linphone.ui.main.crm.viewmodel.Mango9CrmViewModel
import org.linphone.ui.main.fragment.GenericMainFragment
import org.linphone.utils.AppUtils
import org.linphone.utils.LinphoneUtils

@UiThread
class Mango9CrmFragment : GenericMainFragment() {
    private lateinit var binding: Mango9CrmFragmentBinding
    private lateinit var viewModel: Mango9CrmViewModel
    private lateinit var adapter: Mango9CrmRecordAdapter
    private val messagingStore by lazy { Mango9ChatStore.get(requireContext()) }
    private var renderingFilters = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = Mango9CrmFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(this)[Mango9CrmViewModel::class.java]
        binding.lifecycleOwner = viewLifecycleOwner
        adapter = Mango9CrmRecordAdapter(viewModel::openRecord)
        binding.records.layoutManager = LinearLayoutManager(requireContext())
        binding.records.adapter = adapter

        binding.back.setOnClickListener {
            if (viewModel.showRecords.value == true) viewModel.selectDashboard() else goBack()
        }
        binding.refresh.setOnClickListener { viewModel.refresh() }
        binding.leadsMetric.setOnClickListener { viewModel.selectLeads() }
        binding.leadsRow.setOnClickListener { viewModel.selectLeads() }
        binding.clientsMetric.setOnClickListener { viewModel.selectClients() }
        binding.clientsRow.setOnClickListener { viewModel.selectClients() }
        binding.teamChatRow.setOnClickListener {
            findNavController().navigate(R.id.action_mango9CrmFragment_to_mango9MessagingListFragment)
        }
        binding.addRecord.setOnClickListener { viewModel.requestCreate() }
        binding.connectAccount.setOnClickListener {
            startActivity(Intent(requireContext(), AssistantActivity::class.java))
        }
        binding.search.doAfterTextChanged { editable ->
            val value = editable?.toString().orEmpty()
            if (viewModel.search.value != value) viewModel.search.value = value
        }
        binding.records.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    val manager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                    if (dy > 0 && manager.findLastVisibleItemPosition() >= adapter.itemCount - 5) {
                        viewModel.loadMore()
                    }
                }
            },
        )

        viewModel.dashboard.observe(viewLifecycleOwner) { dashboard ->
            binding.leadsCount.text = dashboard?.leads?.toString() ?: "—"
            binding.clientsCount.text = dashboard?.clients?.toString() ?: "—"
        }
        viewModel.activeSession.observe(viewLifecycleOwner, ::renderSession)
        viewModel.records.observe(viewLifecycleOwner, adapter::submitList)
        viewModel.schema.observe(viewLifecycleOwner, ::renderStatusFilters)
        viewModel.groups.observe(viewLifecycleOwner, ::renderGroupFilter)
        viewModel.showDashboard.observe(viewLifecycleOwner) { renderMode() }
        viewModel.showRecords.observe(viewLifecycleOwner) { renderMode() }
        viewModel.recordTitle.observe(viewLifecycleOwner) { renderMode() }
        viewModel.loading.observe(viewLifecycleOwner) {
            binding.loading.visibility = if (it) View.VISIBLE else View.GONE
        }
        viewModel.empty.observe(viewLifecycleOwner) {
            binding.emptyRecords.visibility = if (it && viewModel.showRecords.value == true) View.VISIBLE else View.GONE
        }
        viewModel.errorMessage.observe(viewLifecycleOwner) { message ->
            binding.errorCard.visibility = if (message.isNullOrBlank()) View.GONE else View.VISIBLE
            binding.errorMessage.text = message.orEmpty()
        }
        viewModel.detailLoadedEvent.observe(viewLifecycleOwner) { event ->
            event.consume { (kind, detail) -> showRecordDialog(kind, detail) }
        }
        viewModel.createRequestedEvent.observe(viewLifecycleOwner) { event ->
            event.consume(::showCreateDialog)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                messagingStore.state.collect { state ->
                    val unread = messagingStore.teamUnreadCount(state)
                    binding.teamChatRow.text = if (unread > 0) {
                        "${getString(R.string.mango9_crm_team_chat_subtitle)} · " +
                            resources.getQuantityString(R.plurals.mango9_chat_unread_messages, unread, unread)
                    } else {
                        getString(R.string.mango9_crm_team_chat_subtitle)
                    }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch { messagingStore.connect() }
        setupDateFilter()
        if (savedInstanceState == null && arguments?.getInt(ARG_PUSH_RECORD_ID, 0) != 0) {
            val kind = when (arguments?.getString(ARG_PUSH_RECORD_KIND)) {
                "client" -> Mango9RecordKind.Client
                else -> Mango9RecordKind.Lead
            }
            viewModel.openRecordFromPush(kind, arguments?.getInt(ARG_PUSH_RECORD_ID, 0) ?: 0)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.reloadAccountContext()
    }

    private fun renderSession(session: org.linphone.mango9.Mango9Session?) {
        val connected = session != null
        binding.connectedContent.visibility = if (connected) View.VISIBLE else View.GONE
        binding.signInCard.visibility = if (connected) View.GONE else View.VISIBLE
        binding.connectedBadge.visibility = if (connected) View.VISIBLE else View.GONE
        binding.accountName.text = session?.displayName?.takeIf(String::isNotBlank)
            ?: session?.loginId
            ?: getString(R.string.app_name)
        binding.accountLogin.text = session?.loginId.orEmpty()
        binding.accountRole.text = session?.role?.replaceFirstChar(Char::uppercase).orEmpty()
    }

    private fun renderMode() {
        val recordsVisible = viewModel.showRecords.value == true
        binding.dashboardScroll.visibility = if (recordsVisible) View.GONE else View.VISIBLE
        binding.recordsContent.visibility = if (recordsVisible) View.VISIBLE else View.GONE
        binding.addRecord.visibility = if (recordsVisible) View.VISIBLE else View.GONE
        binding.title.text = if (recordsVisible) viewModel.recordTitle.value else getString(R.string.mango9_crm_title)
        binding.subtitle.text = if (recordsVisible) {
            getString(R.string.mango9_crm_records_scope)
        } else {
            getString(R.string.mango9_crm_subtitle)
        }
        binding.search.hint = if (viewModel.recordTitle.value == getString(R.string.mango9_crm_clients)) {
            getString(R.string.mango9_crm_search_clients)
        } else {
            getString(R.string.mango9_crm_search_leads)
        }
    }

    private fun renderStatusFilters(schema: Mango9CrmSchema?) {
        renderingFilters = true
        binding.statusFilters.removeAllViews()
        val values = listOf("") + schema?.statuses.orEmpty()
        values.forEachIndexed { index, value ->
            val chip = Chip(requireContext()).apply {
                id = View.generateViewId()
                text = value.ifEmpty { getString(R.string.mango9_crm_all) }
                isCheckable = true
                isChecked = value == viewModel.selectedStatus.value.orEmpty()
                setOnCheckedChangeListener { _, checked ->
                    if (checked && !renderingFilters) viewModel.setStatus(value)
                }
            }
            binding.statusFilters.addView(chip)
            if (index == 0 && viewModel.selectedStatus.value.isNullOrEmpty()) chip.isChecked = true
        }
        renderingFilters = false
    }

    private fun renderGroupFilter(groups: List<Mango9CrmGroup>) {
        renderingFilters = true
        val labels = listOf(getString(R.string.mango9_crm_all_groups)) + groups.map(Mango9CrmGroup::name)
        binding.groupFilter.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            labels,
        )
        binding.groupFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (renderingFilters) return
                val value = if (position == 0) "" else groups[position - 1].id
                if (value != viewModel.selectedGroupId.value.orEmpty()) viewModel.setGroup(value)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        renderingFilters = false
    }

    private fun setupDateFilter() {
        val labels = listOf(
            getString(R.string.mango9_crm_all_dates),
            getString(R.string.mango9_crm_today),
            getString(R.string.mango9_crm_last_7_days),
            getString(R.string.mango9_crm_last_30_days),
        )
        val values = listOf("", "today", "last_7_days", "last_30_days")
        binding.dateFilter.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            labels,
        )
        binding.dateFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val value = values[position]
                if (value != viewModel.selectedDateFilter.value.orEmpty()) viewModel.setDateFilter(value)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun showCreateDialog(kind: Mango9RecordKind) {
        val form = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val firstName = addInput(form, getString(R.string.mango9_crm_first_name), "", "text")
        val lastName = addInput(form, getString(R.string.mango9_crm_last_name), "", "text")
        val phone = addInput(form, getString(R.string.mango9_crm_phone), "", "phone")
        val email = addInput(form, getString(R.string.mango9_crm_email), "", "email")
        val error = TextView(requireContext()).apply {
            setTextColor(resources.getColor(R.color.red_danger_500, requireContext().theme))
            setPadding(0, dp(8), 0, 0)
            visibility = View.GONE
        }
        form.addView(error)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.mango9_crm_add_named_record, kind.displayName))
            .setView(form)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.mango9_crm_create, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val given = firstName.text?.toString()?.trim().orEmpty()
                val family = lastName.text?.toString()?.trim().orEmpty()
                val phoneValue = phone.text?.toString()?.trim().orEmpty()
                val emailValue = email.text?.toString()?.trim().orEmpty()
                val validation = when {
                    given.isEmpty() -> getString(R.string.mango9_crm_first_name_required)
                    phoneValue.isEmpty() && emailValue.isEmpty() -> getString(R.string.mango9_crm_contact_required)
                    else -> null
                }
                if (validation != null) {
                    error.text = validation
                    error.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                setDialogButtonsEnabled(dialog, false)
                viewModel.createRecord(
                    kind,
                    given,
                    family,
                    phoneValue,
                    emailValue,
                    viewModel.selectedGroupId.value.orEmpty(),
                ) { success, message ->
                    if (!isAdded) return@createRecord
                    if (success) {
                        dialog.dismiss()
                    } else {
                        error.text = message
                        error.visibility = View.VISIBLE
                        setDialogButtonsEnabled(dialog, true)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showRecordDialog(
        kind: Mango9RecordKind,
        detail: Mango9CrmRecordDetail,
        editing: Boolean = false,
    ) {
        val schema = viewModel.schema.value
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        addRecordHeader(container, detail)
        if (!editing) addCommunicationActions(container, detail)

        val readers = linkedMapOf<String, () -> String>()
        val visibleFields = schema?.fields.orEmpty().filter { field ->
            field.visible && field.key !in HIDDEN_MOBILE_FIELDS &&
                !(field.key == "contact_name" && schema?.fields.orEmpty().any { it.key == "first_name" })
        }
        val fieldsBySection = visibleFields.groupBy(Mango9CrmSchema.Field::section)
        schema?.sections.orEmpty().forEach { section ->
            val fields = fieldsBySection[section.id].orEmpty()
            if (fields.isEmpty()) return@forEach
            addSectionTitle(container, section.label)
            fields.forEach { field ->
                val value = detail.values[field.key].orEmpty()
                if (editing && field.editable) {
                    readers[field.key] = addDynamicInput(container, field, value)
                } else {
                    addValue(container, field.label, displayValue(value, field.type))
                }
            }
        }
        if (schema == null || visibleFields.isEmpty()) {
            detail.values.entries.sortedBy(Map.Entry<String, String>::key).forEach { (key, value) ->
                addValue(container, key.humanized(), value.ifBlank { getString(R.string.mango9_crm_not_set) })
            }
        }
        val error = TextView(requireContext()).apply {
            setTextColor(resources.getColor(R.color.red_danger_500, requireContext().theme))
            setPadding(0, dp(12), 0, 0)
            visibility = View.GONE
        }
        container.addView(error)
        val scroll = ScrollView(requireContext()).apply { addView(container) }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(
                getString(
                    if (editing) R.string.mango9_crm_edit_named_record else R.string.mango9_crm_view_named_record,
                    kind.displayName,
                ),
            )
            .setView(scroll)
            .setNegativeButton(if (editing) android.R.string.cancel else android.R.string.ok, null)
            .setPositiveButton(if (editing) R.string.mango9_crm_save else R.string.mango9_crm_edit, null)
            .apply {
                if (editing) setNeutralButton(R.string.mango9_crm_delete, null)
            }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!editing) {
                    dialog.dismiss()
                    showRecordDialog(kind, detail, editing = true)
                    return@setOnClickListener
                }
                val values = readers.mapValues { it.value() }
                val required = visibleFields.firstOrNull { it.required && it.editable && values[it.key].isNullOrBlank() }
                if (required != null) {
                    error.text = getString(R.string.mango9_crm_field_required, required.label)
                    error.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                setDialogButtonsEnabled(dialog, false)
                viewModel.updateRecord(kind, detail.record.id, values) { success, message ->
                    if (!isAdded) return@updateRecord
                    if (success) {
                        dialog.dismiss()
                    } else {
                        error.text = message
                        error.visibility = View.VISIBLE
                        setDialogButtonsEnabled(dialog, true)
                    }
                }
            }
            if (editing) {
                dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(getString(R.string.mango9_crm_delete_named_record, kind.displayName.lowercase()))
                        .setMessage(R.string.mango9_crm_delete_warning)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.mango9_crm_delete) { _, _ ->
                            setDialogButtonsEnabled(dialog, false)
                            viewModel.deleteRecord(kind, detail.record.id) { success, message ->
                                if (!isAdded) return@deleteRecord
                                if (success) {
                                    dialog.dismiss()
                                } else {
                                    error.text = message
                                    error.visibility = View.VISIBLE
                                    setDialogButtonsEnabled(dialog, true)
                                }
                            }
                        }
                        .show()
                }
            }
        }
        dialog.show()
    }

    private fun addRecordHeader(parent: LinearLayout, detail: Mango9CrmRecordDetail) {
        TextView(requireContext()).apply {
            text = detail.record.name.ifBlank { getString(R.string.mango9_unnamed_record) }
            textSize = 18f
            setTextColor(resources.getColor(R.color.gray_main2_800, requireContext().theme))
            setPadding(0, 0, 0, dp(4))
            parent.addView(this)
        }
        TextView(requireContext()).apply {
            text = listOf(detail.record.phone, detail.record.email).filter(String::isNotBlank).joinToString(" · ")
            setTextColor(resources.getColor(R.color.orange_main_500, requireContext().theme))
            setPadding(0, 0, 0, dp(8))
            parent.addView(this)
        }
    }

    private fun addCommunicationActions(parent: LinearLayout, detail: Mango9CrmRecordDetail) {
        val phone = detail.record.phone.trim()
        val email = detail.record.email.trim()
        if (phone.isEmpty() && email.isEmpty()) return
        val actions = mutableListOf<Pair<Int, () -> Unit>>()
        if (phone.isNotEmpty()) {
            actions += R.string.mango9_crm_mango9_call to { startMango9Call(phone) }
            actions += R.string.mango9_crm_mango9_sms to { openMango9Sms(phone, detail.record.name) }
            actions += R.string.mango9_crm_phone_call to {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null)))
            }
            actions += R.string.mango9_crm_text to {
                startActivity(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", phone, null)))
            }
        }
        if (email.isNotEmpty()) {
            actions += R.string.mango9_crm_email to {
                startActivity(Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", email, null)))
            }
        }
        actions += R.string.mango9_crm_copy to {
            AppUtils.copyToClipboard(requireContext(), detail.record.name, phone.ifEmpty { email })
        }

        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(8))
        }
        actions.chunked(3).forEach { chunk ->
            val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { (label, action) -> row.addAction(label, action) }
            repeat(3 - chunk.size) {
                row.addView(View(requireContext()), LinearLayout.LayoutParams(0, 1, 1f))
            }
            container.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        parent.addView(container)
    }

    private fun openMango9Sms(phone: String, name: String) {
        val arguments = Bundle().apply {
            putString(Mango9MessagingListFragment.ARG_TYPE, Mango9MessagingListFragment.TYPE_SMS)
            putInt(Mango9MessagingListFragment.ARG_USER_ID, 0)
            putString(Mango9MessagingListFragment.ARG_ROOM_ID, null)
            putString(Mango9MessagingListFragment.ARG_TARGET, name.ifBlank { phone })
            putString(Mango9MessagingListFragment.ARG_PHONE, phone)
        }
        findNavController().navigate(R.id.action_global_mango9MessagingConversationFragment, arguments)
    }

    private fun startMango9Call(phone: String) {
        val localIdentity = viewModel.activeSession.value?.sipIdentity
        coreContext.postOnCoreThread { core ->
            val target = core.interpretUrl(phone, LinphoneUtils.applyInternationalPrefix()) ?: return@postOnCoreThread
            val local = localIdentity?.let { core.interpretUrl(it, false) }
            coreContext.startAudioCall(target, localAddress = local)
        }
    }

    private fun addSectionTitle(parent: LinearLayout, title: String) {
        TextView(requireContext()).apply {
            text = title
            textSize = 15f
            setTextColor(resources.getColor(R.color.gray_main2_800, requireContext().theme))
            setPadding(0, dp(16), 0, dp(4))
            parent.addView(this)
        }
    }

    private fun addValue(parent: LinearLayout, label: String, value: String) {
        TextView(requireContext()).apply {
            text = label
            textSize = 11f
            setTextColor(resources.getColor(R.color.gray_main2_500, requireContext().theme))
            setPadding(0, dp(7), 0, 0)
            parent.addView(this)
        }
        TextView(requireContext()).apply {
            text = value.ifBlank { getString(R.string.mango9_crm_not_set) }
            textSize = 14f
            setTextColor(resources.getColor(R.color.gray_main2_800, requireContext().theme))
            setPadding(0, dp(2), 0, dp(4))
            parent.addView(this)
        }
    }

    private fun addDynamicInput(
        parent: LinearLayout,
        field: Mango9CrmSchema.Field,
        value: String,
    ): () -> String {
        if (field.type == "select") {
            val input = MaterialAutoCompleteTextView(requireContext()).apply {
                setText(value, false)
                setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, field.options))
            }
            val layout = TextInputLayout(requireContext()).apply {
                hint = field.label + if (field.required) " *" else ""
                setPadding(0, dp(6), 0, 0)
                addView(input)
            }
            parent.addView(layout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return { input.text?.toString().orEmpty() }
        }
        val input = addInput(parent, field.label + if (field.required) " *" else "", value, field.type)
        if (field.type == "textarea") {
            input.minLines = 3
            input.maxLines = 6
            input.isSingleLine = false
        }
        return { input.text?.toString().orEmpty() }
    }

    private fun addInput(parent: LinearLayout, label: String, value: String, type: String): TextInputEditText {
        val input = TextInputEditText(requireContext()).apply {
            setText(value)
            isSingleLine = type != "textarea"
            inputType = when (type) {
                "email" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                "phone" -> InputType.TYPE_CLASS_PHONE
                "url" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                "number" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
        }
        val layout = TextInputLayout(requireContext()).apply {
            hint = label
            setPadding(0, dp(6), 0, 0)
            addView(input)
        }
        parent.addView(layout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return input
    }

    private fun displayValue(value: String, type: String): String = when {
        value.isBlank() -> getString(R.string.mango9_crm_not_set)
        type == "datetime" -> value.replace('T', ' ')
        else -> value
    }

    private fun LinearLayout.addAction(label: Int, action: () -> Unit) {
        addView(
            Button(requireContext()).apply {
                text = getString(label)
                textSize = 11f
                isAllCaps = false
                setOnClickListener { action() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
    }

    private fun setDialogButtonsEnabled(dialog: AlertDialog, enabled: Boolean) {
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.isEnabled = enabled
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.isEnabled = enabled
        dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)?.isEnabled = enabled
    }

    private fun String.humanized(): String = split('_').joinToString(" ") { word ->
        word.replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private val Mango9RecordKind.displayName: String
        get() = getString(if (this == Mango9RecordKind.Lead) R.string.mango9_crm_lead else R.string.mango9_crm_client)

    companion object {
        const val ARG_PUSH_RECORD_KIND = "mango9_push_record_kind"
        const val ARG_PUSH_RECORD_ID = "mango9_push_record_id"
        private val HIDDEN_MOBILE_FIELDS = setOf("utm_source", "utm_medium", "utm_campaign")
    }
}
