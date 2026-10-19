/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.ui.main.crm.fragment

import android.content.Intent
import android.content.res.ColorStateList
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
import android.widget.Toast
import androidx.annotation.UiThread
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.databinding.Mango9CrmFragmentBinding
import org.linphone.mango9.Mango9ChatStore
import org.linphone.mango9.Mango9CrmGroup
import org.linphone.mango9.Mango9CrmRecordDetail
import org.linphone.mango9.Mango9CrmSchema
import org.linphone.mango9.Mango9CrmFieldLayout
import org.linphone.mango9.Mango9CrmStatusPolicy
import org.linphone.mango9.Mango9CrmEditorOwner
import org.linphone.mango9.Mango9RecordKind
import org.linphone.mango9.Mango9AppointmentContact
import org.linphone.mango9.Mango9SessionLease
import org.linphone.mango9.Mango9NavigationSession
import org.linphone.mango9.Mango9SessionStore
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
    private var recordDialog: Mango9FullPage? = null
    private var recordChildDialog: AlertDialog? = null
    private var recordActionPopup: Mango9MessageActionPopup? = null

    private fun dismissRecordDialogs() {
        recordActionPopup?.dismiss(); recordActionPopup = null
        recordChildDialog?.dismiss(); recordChildDialog = null
        recordDialog?.dismiss(); recordDialog = null
    }

    override fun onDestroyView() {
        dismissRecordDialogs()
        super.onDestroyView()
    }

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
        binding.appointmentsRow.setOnClickListener {
            findNavController().navigate(
                R.id.action_global_mango9CalendarFragment,
                Bundle().apply {
                putString("presentation", "activities")
            }
            )
        }
        binding.calendarRow.setOnClickListener {
            findNavController().navigate(
                R.id.action_global_mango9CalendarFragment,
                Bundle().apply {
                putString("presentation", "calendar")
            }
            )
        }
        if (arguments?.getBoolean("mango9_open_leads") == true &&
            Mango9NavigationSession.resolve(requireArguments(), Mango9SessionStore(requireContext())) != null
        ) {
                viewModel.selectLeads()
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
        viewModel.accountRevision.observe(viewLifecycleOwner) { dismissRecordDialogs() }
        viewModel.search.observe(viewLifecycleOwner) { value ->
            if (binding.search.text?.toString() != value.orEmpty()) binding.search.setText(value.orEmpty())
        }
        viewModel.selectedGroupId.observe(viewLifecycleOwner) { applyGroupSelection() }
        viewModel.selectedDateFilter.observe(viewLifecycleOwner) { value ->
            val position = DATE_FILTER_VALUES.indexOf(value.orEmpty()).coerceAtLeast(0)
            if (binding.dateFilter.selectedItemPosition != position) binding.dateFilter.setSelection(position)
        }
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
            event?.consume { (kind, detail) -> showRecordDialog(kind, detail) }
        }
        viewModel.createRequestedEvent.observe(viewLifecycleOwner) { event ->
            event?.consume(::showCreateDialog)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                messagingStore.state.collect { state ->
                    val unread = messagingStore.teamUnreadCount(state)
                    binding.teamChatSubtitle.setText(R.string.mango9_crm_team_chat_subtitle)
                    binding.teamChatUnreadBadge.apply {
                        visibility = if (unread > 0) View.VISIBLE else View.GONE
                        text = if (unread < 100) unread.toString() else "99+"
                        contentDescription = resources.getQuantityString(
                            R.plurals.mango9_chat_unread_messages,
                            unread,
                            unread,
                        )
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
            val route = Mango9NavigationSession.resolve(requireArguments(), Mango9SessionStore(requireContext()))
            val lease = recordLease(requireArguments(), route)
            if (lease != null) {
                viewModel.openRecordFromPush(kind, arguments?.getInt(ARG_PUSH_RECORD_ID, 0) ?: 0, lease)
            } else {
                Toast.makeText(requireContext(), R.string.mango9_calendar_reconnect, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.reloadAccountContext()
    }

    private fun renderSession(session: org.linphone.mango9.Mango9Session?) {
        val connected = session != null
        binding.connectedContent.visibility = if (connected) View.VISIBLE else View.GONE
        binding.appointmentsRow.visibility = if (connected && viewModel.showDashboard.value == true) View.VISIBLE else View.GONE
        binding.calendarRow.visibility = binding.appointmentsRow.visibility
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
        binding.appointmentsRow.visibility = if (!recordsVisible && viewModel.activeSession.value != null) View.VISIBLE else View.GONE
        binding.calendarRow.visibility = binding.appointmentsRow.visibility
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
                val value = if (position == 0) "" else groups.getOrNull(position - 1)?.id ?: return
                if (value != viewModel.selectedGroupId.value.orEmpty()) viewModel.setGroup(value)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        applyGroupSelection()
        renderingFilters = false
    }

    private fun applyGroupSelection() {
        val selected = viewModel.selectedGroupId.value.orEmpty()
        val position = if (selected.isEmpty()) {
            0
        } else {
            (
                viewModel.groups.value.orEmpty().indexOfFirst {
            it.id == selected
        } + 1
            ).coerceAtLeast(0)
        }
        if (binding.groupFilter.selectedItemPosition != position) binding.groupFilter.setSelection(position)
    }

    private fun setupDateFilter() {
        val labels = listOf(
            getString(R.string.mango9_crm_all_dates),
            getString(R.string.mango9_crm_today),
            getString(R.string.mango9_crm_last_7_days),
            getString(R.string.mango9_crm_last_30_days),
        )
        binding.dateFilter.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            labels,
        )
        binding.dateFilter.setSelection(DATE_FILTER_VALUES.indexOf(viewModel.selectedDateFilter.value.orEmpty()).coerceAtLeast(0))
        binding.dateFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val value = DATE_FILTER_VALUES.getOrNull(position) ?: return
                if (value != viewModel.selectedDateFilter.value.orEmpty()) viewModel.setDateFilter(value)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun showCreateDialog(kind: Mango9RecordKind) {
        val owner = viewModel.editorOwner(kind, null) ?: return
        val originalView = view
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

        val dialog = Mango9FullPage(requireContext())
            .setTitle(getString(R.string.mango9_crm_add_named_record, kind.displayName))
            .setView(ScrollView(requireContext()).apply { addView(form) })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.mango9_crm_create, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!viewModel.isEditorCurrent(owner) || recordDialog !== dialog || view !== originalView) return@setOnClickListener
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
                    owner,
                    given,
                    family,
                    phoneValue,
                    emailValue,
                    viewModel.selectedGroupId.value.orEmpty(),
                ) { success, message ->
                    if (!isAdded || view !== originalView || recordDialog !== dialog || !dialog.isShowing || !viewModel.isEditorCurrent(owner)) return@createRecord
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
        dismissRecordDialogs()
        recordDialog = dialog
        dialog.show()
    }

    private fun showRecordDialog(
        kind: Mango9RecordKind,
        detail: Mango9CrmRecordDetail,
        editing: Boolean = false,
    ) {
        val owner = viewModel.editorOwner(kind, detail.record.id) ?: return
        val originalView = view
        val schema = viewModel.schema.value
        val status = viewModel.quickStatus(kind, detail)
        var currentDetail = detail
        var statusJob: Job? = null
        val statusButtons = mutableListOf<Button>()
        lateinit var dialog: Mango9FullPage
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        addRecordHeader(container, detail, owner, editing)
        val calendarLease = viewModel.calendarSession(kind)
        if (!editing && calendarLease != null) {
            container.addView(
                com.google.android.material.button.MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                setText(R.string.mango9_calendar_title)
                isAllCaps = false
                cornerRadius = dp(12)
                minHeight = dp(48)
                strokeColor = ColorStateList.valueOf(requireContext().getColor(R.color.mango9_calendar_separator))
                backgroundTintList = ColorStateList.valueOf(requireContext().getColor(R.color.mango9_calendar_surface))
                setTextColor(requireContext().getColor(R.color.mango9_calendar_ink))
                icon = AppCompatResources.getDrawable(requireContext(), R.drawable.calendar)
                iconTint = ColorStateList.valueOf(requireContext().getColor(R.color.mango9_calendar_accent))
                iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
                setOnClickListener {
                    if (recordDialog === dialog && viewModel.isEditorCurrent(owner) && viewModel.isCalendarSessionCurrent(calendarLease)) {
                        recordDialog?.dismiss()
                        val contact = org.linphone.mango9.Mango9AppointmentContact(detail.record.id, detail.record.name, if (kind == Mango9RecordKind.Lead) "lead" else "client")
                        findNavController().navigate(R.id.action_global_mango9CalendarFragment, Mango9CalendarFragment.arguments(contact, calendarLease))
                    }
                }
            }
            )
        }
        val error = TextView(requireContext()).apply {
            setTextColor(resources.getColor(R.color.red_danger_500, requireContext().theme))
            setPadding(0, dp(8), 0, dp(8))
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }

        fun statusButton(parent: LinearLayout) {
            val button = com.google.android.material.button.MaterialButton(requireContext()).apply {
                cornerRadius = dp(24)
                backgroundTintList = ColorStateList.valueOf(requireContext().getColor(R.color.mango9_calendar_badge))
                insetTop = 0; insetBottom = 0
                minHeight = dp(44)
                text = status.status.ifBlank { getString(R.string.mango9_crm_select_status) }
                isAllCaps = false
                textSize = 14f
                setTextColor(resources.getColor(R.color.orange_main_500, requireContext().theme))
                setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.caret_down, 0)
                compoundDrawableTintList = ColorStateList.valueOf(resources.getColor(R.color.orange_main_500, requireContext().theme))
                contentDescription = getString(R.string.mango9_crm_change_status, text)
                setOnClickListener {
                    if (statusJob?.isActive == true || !viewModel.isEditorCurrent(owner) || recordDialog !== dialog) return@setOnClickListener
                    recordChildDialog?.dismiss()
                    recordChildDialog = MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.mango9_crm_select_status)
                        .setSingleChoiceItems(status.options.toTypedArray(), status.options.indexOf(status.status)) { picker, index ->
                            picker.dismiss()
                            if (!dialog.isShowing || recordDialog !== dialog || !viewModel.isEditorCurrent(owner)) return@setSingleChoiceItems
                            statusJob = viewLifecycleOwner.lifecycleScope.launch {
                                statusButtons.forEach { it.isEnabled = false }
                                setDialogButtonsEnabled(dialog, false)
                                error.visibility = View.GONE
                                try {
                                    if (status.change(status.options[index])) {
                                        currentDetail = status.detail
                                        viewModel.reflectStatus(currentDetail)
                                        statusButtons.forEach {
                                            it.text = status.status.ifBlank { getString(R.string.mango9_crm_select_status) }
                                            it.contentDescription = getString(R.string.mango9_crm_change_status, it.text)
                                        }
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    error.setText(R.string.mango9_crm_status_not_confirmed)
                                    error.visibility = View.VISIBLE
                                } finally {
                                    if (dialog.isShowing) {
                                        statusButtons.forEach { it.isEnabled = true }
                                        setDialogButtonsEnabled(dialog, true)
                                    }
                                }
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
            statusButtons += button
            parent.addView(
                button,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
            )
        }
        if (!editing && status.options.isNotEmpty()) statusButton(container)
        container.addView(error)

        val readers = linkedMapOf<String, () -> String>()
        val visibleFields = schema?.fields.orEmpty().filter { field ->
            field.visible && field.key !in HIDDEN_MOBILE_FIELDS &&
                (editing || status.options.isEmpty() || field.key != Mango9CrmStatusPolicy.editableField(schema)?.key) &&
                !(field.key == "contact_name" && schema?.fields.orEmpty().any { it.key == "first_name" })
        }
        val fieldsBySection = visibleFields.groupBy(Mango9CrmSchema.Field::section)
        // Unknown/custom sections must not disappear merely because section metadata is incomplete.
        val sections = schema?.sections.orEmpty() + fieldsBySection.keys
            .filter { id -> schema?.sections.orEmpty().none { it.id == id } }
            .map { Mango9CrmSchema.Section(it, it.humanized()) }
        sections.distinctBy { it.id }.forEach { section ->
            val fields = fieldsBySection[section.id].orEmpty()
            if (fields.isEmpty()) return@forEach
            val cardContent = addSectionCard(container, section)
            Mango9CrmFieldLayout.rows(fields, detail.values).forEach { fieldsInRow ->
                val row = Mango9CrmFieldRow(requireContext()).apply { setPadding(0, dp(8), 0, dp(8)) }
                fieldsInRow.forEach { field ->
                    val cell = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
                    val value = detail.values[field.key].orEmpty()
                    if (editing && field.editable) {
                        readers[field.key] = addDynamicInput(cell, field, value)
                    } else if (!editing && field.key == Mango9CrmStatusPolicy.editableField(schema)?.key && status.options.isNotEmpty()) {
                        statusButton(cell)
                    } else {
                        addValue(cell, field.label, displayValue(value, field.type))
                    }
                    row.addView(cell)
                }
                cardContent.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            if (section.id == "location" && !editing) {
                // Only geocode address fields visible to this account's CRM schema.
                val address = listOf("address", "city", "state", "zip_code", "country")
                    .filter { key -> fields.any { it.key == key } }
                    .mapNotNull { detail.values[it]?.trim()?.takeIf(String::isNotEmpty) }.joinToString(", ")
                if (address.isNotBlank()) {
                    cardContent.addView(
                        Mango9LeadMapView(requireContext(), address),
                    LinearLayout.LayoutParams(-1, dp(300)).apply { topMargin = dp(12) }
                    )
                }
            }
        }
        // Never reveal hidden fields through a raw-values fallback when schema permissions are absent.
        val scroll = ScrollView(requireContext()).apply { addView(container) }
        dialog = Mango9FullPage(requireContext())
            .setTitle(
                getString(
                    if (editing) R.string.mango9_crm_edit_named_record else R.string.mango9_crm_view_named_record,
                    kind.displayName,
                ),
            )
            .setView(scroll)
            .setNegativeButton(if (editing) android.R.string.cancel else R.string.mango9_calendar_back, null)
            .setPositiveButton(if (editing) R.string.mango9_crm_save else R.string.mango9_crm_edit, null)
            .apply {
                if (editing) setNeutralButton(R.string.mango9_crm_delete, null)
            }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (view !== originalView || recordDialog !== dialog || !viewModel.isEditorCurrent(owner)) return@setOnClickListener
                if (!editing) {
                    dialog.dismiss()
                    showRecordDialog(kind, currentDetail, editing = true)
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
                viewModel.updateRecord(owner, values) { success, message ->
                    if (!isAdded || view !== originalView || recordDialog !== dialog || !dialog.isShowing || !viewModel.isEditorCurrent(owner)) return@updateRecord
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
                    if (!viewModel.isEditorCurrent(owner) || recordDialog !== dialog) return@setOnClickListener
                    recordChildDialog?.dismiss()
                    recordChildDialog = MaterialAlertDialogBuilder(requireContext())
                        .setTitle(getString(R.string.mango9_crm_delete_named_record, kind.displayName.lowercase()))
                        .setMessage(R.string.mango9_crm_delete_warning)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.mango9_crm_delete) { _, _ ->
                            if (view !== originalView || !dialog.isShowing || recordDialog !== dialog || !viewModel.isEditorCurrent(owner)) return@setPositiveButton
                            setDialogButtonsEnabled(dialog, false)
                            viewModel.deleteRecord(owner) { success, message ->
                                if (!isAdded || view !== originalView || recordDialog !== dialog || !dialog.isShowing || !viewModel.isEditorCurrent(owner)) return@deleteRecord
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
        dialog.setOnDismissListener {
            statusJob?.cancel()
            recordActionPopup?.dismiss(); recordActionPopup = null
            if (recordDialog === dialog) { recordChildDialog?.dismiss(); recordChildDialog = null }
        }
        dismissRecordDialogs()
        recordDialog = dialog
        dialog.show()
    }

    private fun addSectionCard(parent: LinearLayout, section: Mango9CrmSchema.Section): LinearLayout {
        val color = resources.getColor(R.color.orange_main_500, requireContext().theme)
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(4))
        }
        TextView(requireContext()).apply {
            text = section.label
            textSize = 16f
            setTextColor(color)
            val icon = when (section.id) {
                "personal" -> R.drawable.address_book
                "location" -> R.drawable.globe_hemisphere_west
                else -> R.drawable.list
            }
            val drawable = AppCompatResources.getDrawable(requireContext(), icon)?.mutate()?.apply {
                setBounds(0, 0, dp(22), dp(22)); setTint(color)
            }
            setCompoundDrawablesRelative(drawable, null, null, null)
            compoundDrawablePadding = dp(8)
            content.addView(this)
        }
        val card = MaterialCardView(requireContext()).apply {
            radius = dp(14).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(requireContext().getColor(R.color.mango9_calendar_surface))
            strokeWidth = dp(1)
            strokeColor = resources.getColor(R.color.gray_200, requireContext().theme)
            addView(content)
        }
        parent.addView(
            card,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        }
        )
        return content
    }

    private fun addRecordHeader(parent: LinearLayout, detail: Mango9CrmRecordDetail, owner: Mango9CrmEditorOwner, editing: Boolean) {
        val name = detail.record.name.ifBlank { getString(R.string.mango9_unnamed_record) }
        val header = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(16))
        }
        header.addView(
            TextView(requireContext()).apply {
            text = name.trim().split(Regex("\\s+")).take(2).mapNotNull {
                it.firstOrNull()
            }.joinToString("").uppercase(Locale.getDefault())
            textSize = 22f; gravity = android.view.Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(requireContext().getColor(R.color.mango9_calendar_accent))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(requireContext().getColor(R.color.mango9_calendar_badge))
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        },
            LinearLayout.LayoutParams(dp(60), dp(60)).apply { marginEnd = dp(14) }
        )
        val identity = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        identity.addView(
            TextView(requireContext()).apply {
            text = name; textSize = 23f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(requireContext().getColor(R.color.mango9_calendar_ink))
        }
        )

        fun contactLine(value: String, phone: Boolean) {
            if (value.isBlank()) return
            val line = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val label = TextView(requireContext()).apply {
                text = value; textSize = 14f
                setTextColor(requireContext().getColor(R.color.mango9_calendar_accent))
                minHeight = dp(48); gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), dp(6), dp(4))
            }
            line.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
            if (!editing) {
                val more = android.widget.ImageButton(requireContext()).apply {
                    setImageResource(R.drawable.dots_three_vertical)
                    imageTintList = ColorStateList.valueOf(requireContext().getColor(R.color.mango9_calendar_accent))
                    setBackgroundResource(android.R.color.transparent)
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    contentDescription = getString(R.string.mango9_crm_contact_actions, value)
                }
                line.addView(more, LinearLayout.LayoutParams(dp(48), dp(48)))
                val open: (View) -> Unit = {
                    if (viewModel.isEditorCurrent(owner)) showContactActions(more, detail, owner, phone)
                }
                more.setOnClickListener(open)
                label.setOnClickListener(open)
                label.contentDescription = getString(R.string.mango9_crm_contact_actions, value)
            }
            identity.addView(line, LinearLayout.LayoutParams(-1, -2))
        }
        contactLine(detail.record.phone.trim(), true)
        contactLine(detail.record.email.trim(), false)
        header.addView(identity, LinearLayout.LayoutParams(0, -2, 1f))
        parent.addView(header, LinearLayout.LayoutParams(-1, -2))
    }

    private fun showContactActions(anchor: View, detail: Mango9CrmRecordDetail, owner: Mango9CrmEditorOwner, phoneActions: Boolean) {
        recordActionPopup?.dismiss()
        val popup = Mango9MessageActionPopup(anchor)
        recordActionPopup = popup
        val phone = detail.record.phone.trim()
        val email = detail.record.email.trim()

        fun item(label: Int, icon: Int, run: () -> Unit) {
            popup.action(getString(label), icon) {
                popup.dismiss()
                if (isAdded && recordDialog?.isShowing == true && viewModel.isEditorCurrent(owner)) run()
            }
        }
        if (phoneActions && phone.isNotEmpty()) {
            item(R.string.mango9_crm_mango9_call, R.drawable.phone_call) { startMango9Call(phone, owner.lease) }
            item(R.string.mango9_crm_mango9_sms, R.drawable.chat_teardrop_text) {
                openMango9Sms(phone, detail.record.name, owner.lease)
            }
            popup.separator()
            item(R.string.mango9_crm_phone_call, R.drawable.phone) {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null)))
            }
            item(R.string.mango9_crm_text, R.drawable.chat_teardrop_text) {
                startActivity(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", phone, null)))
            }
        } else if (email.isNotEmpty()) {
            item(R.string.mango9_crm_email, R.drawable.envelope_simple) {
                startActivity(Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", email, null)))
            }
        }
        popup.separator()
        item(R.string.mango9_crm_copy, R.drawable.copy) {
            AppUtils.copyToClipboard(requireContext(), detail.record.name, if (phoneActions) phone else email)
        }
        popup.position()
    }

    private fun openMango9Sms(phone: String, name: String, lease: Mango9SessionLease) {
        val arguments = Bundle().apply {
            Mango9NavigationSession.bind(this, lease)
            putString(Mango9MessagingListFragment.ARG_TYPE, Mango9MessagingListFragment.TYPE_SMS)
            putInt(Mango9MessagingListFragment.ARG_USER_ID, 0)
            putString(Mango9MessagingListFragment.ARG_ROOM_ID, null)
            putString(Mango9MessagingListFragment.ARG_TARGET, name.ifBlank { phone })
            putString(Mango9MessagingListFragment.ARG_PHONE, phone)
        }
        findNavController().navigate(R.id.action_global_mango9MessagingConversationFragment, arguments)
    }

    private fun startMango9Call(phone: String, lease: Mango9SessionLease) {
        val localIdentity = lease.session.sipIdentity ?: return
        coreContext.postOnCoreThread { core ->
            if (!viewModel.isCalendarSessionCurrent(lease)) return@postOnCoreThread
            val target = core.interpretUrl(phone, LinphoneUtils.applyInternationalPrefix()) ?: return@postOnCoreThread
            val local = core.interpretUrl(localIdentity, false) ?: return@postOnCoreThread
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
            textSize = 12f
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

    private fun setDialogButtonsEnabled(dialog: Mango9FullPage, enabled: Boolean) {
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
        private val DATE_FILTER_VALUES = listOf("", "today", "last_7_days", "last_30_days")
        const val ARG_PUSH_RECORD_KIND = "mango9_push_record_kind"
        const val ARG_PUSH_RECORD_ID = "mango9_push_record_id"
        private const val ARG_RECORD_IDENTITY = "mango9_record_identity"
        private const val ARG_RECORD_LOGIN = "mango9_record_login"
        private const val ARG_RECORD_SELECTION = "mango9_record_selection"

        internal fun recordArguments(contact: Mango9AppointmentContact, lease: Mango9SessionLease) = Bundle().apply {
            Mango9NavigationSession.bind(this, lease)
            putString(ARG_PUSH_RECORD_KIND, contact.kind); putInt(ARG_PUSH_RECORD_ID, contact.id)
            putString(ARG_RECORD_IDENTITY, lease.session.sipIdentity)
            putLong(ARG_RECORD_LOGIN, lease.loginGeneration); putLong(ARG_RECORD_SELECTION, lease.selectionGeneration)
        }

        internal fun recordLease(arguments: Bundle, current: Mango9SessionLease?): Mango9SessionLease? = current?.takeIf {
            !arguments.containsKey(ARG_RECORD_LOGIN) ||
                (
                    arguments.getString(ARG_RECORD_IDENTITY) == it.session.sipIdentity &&
                    arguments.getLong(ARG_RECORD_LOGIN) == it.loginGeneration &&
                    arguments.getLong(ARG_RECORD_SELECTION) == it.selectionGeneration
                )
        }

        private val HIDDEN_MOBILE_FIELDS = setOf("utm_source", "utm_medium", "utm_campaign")
    }
}
