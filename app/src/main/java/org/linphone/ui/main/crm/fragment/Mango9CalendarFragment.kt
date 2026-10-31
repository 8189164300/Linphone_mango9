/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.ui.main.crm.fragment

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.ScrollView
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.linphone.R
import org.linphone.mango9.Mango9AppointmentAgenda
import org.linphone.mango9.Mango9AppointmentAppearance
import org.linphone.mango9.Mango9AppointmentContact
import org.linphone.mango9.Mango9CalendarMode
import org.linphone.mango9.Mango9CalendarPresentation
import org.linphone.mango9.Mango9CalendarScreenState
import org.linphone.mango9.Mango9SessionLease
import org.linphone.ui.main.crm.viewmodel.Mango9CalendarViewModel
import org.linphone.ui.main.crm.viewmodel.Mango9TasksViewModel
import org.linphone.mango9.Mango9ActivityKind
import org.linphone.mango9.task
import org.linphone.ui.main.fragment.GenericMainFragment

class Mango9CalendarFragment : GenericMainFragment() {
    private lateinit var model: Mango9CalendarViewModel
    private lateinit var tasksModel: Mango9TasksViewModel
    private val presentation get() = arguments?.getString("presentation").orEmpty()
    private var activityKind = Mango9ActivityKind.Appointments
    private val tasksTab = View.generateViewId()
    private lateinit var calendar: Mango9CalendarView
    private lateinit var agenda: RecyclerView
    private lateinit var agendaAdapter: Mango9AppointmentAgendaAdapter
    private lateinit var tabs: MaterialButtonToggleGroup
    private lateinit var heading: TextView
    private lateinit var zone: TextView
    private lateinit var mode: Button
    private lateinit var add: ImageButton
    private lateinit var hours: ImageButton
    private lateinit var loading: ProgressBar
    private lateinit var message: TextView
    private lateinit var empty: TextView
    private lateinit var legend: TextView
    private var informationDialog: android.content.DialogInterface? = null
    private lateinit var search: TextInputEditText
    private lateinit var status: ImageButton
    private lateinit var statusChip: Chip
    private lateinit var zoomButtons: LinearLayout
    private var dialogs: Mango9AppointmentDialogs? = null
    private var renderJob: Job? = null
    private var renderGeneration = 0L
    private var renderedLease: Mango9SessionLease? = null
    private var renderingSearch = false
    private var renderingTabs = false
    private val agendaTab = View.generateViewId()
    private val calendarTab = View.generateViewId()
    private val navy get() = requireContext().getColor(R.color.mango9_calendar_accent)

    private fun dp(value: Int) = Mango9AppointmentAgendaAdapter.dp(requireContext(), value)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val largeText = resources.configuration.fontScale >= 1.3f
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(ctx.getColor(R.color.mango9_calendar_background))
        }

        fun row() = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

        fun label(size: Float) = TextView(ctx).apply {
            textSize = size; setTextColor(ctx.getColor(R.color.mango9_calendar_ink))
        }

        fun icon(resource: Int, name: Int, click: () -> Unit) = ImageButton(ctx).apply {
            setImageResource(resource); imageTintList = ColorStateList.valueOf(navy); setBackgroundColor(Color.TRANSPARENT)
            contentDescription = getString(name); setPadding(dp(12), dp(12), dp(12), dp(12)); setOnClickListener { click() }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }

        fun button(name: Int, click: () -> Unit) = Button(ctx).apply {
            setText(name); setTextColor(navy); isAllCaps = false; setBackgroundColor(Color.TRANSPARENT); minHeight = dp(48)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { click() }
        }
        val toolbar = row().apply { setBackgroundColor(ctx.getColor(R.color.mango9_calendar_surface)) }
        toolbar.addView(icon(R.drawable.caret_left, R.string.mango9_calendar_back) { goBack() })
        val title = label(20f).apply {
            tag = "calendar.title"; text = if (presentation == "calendar") "Calendar" else "Events & tasks"; setTypeface(typeface, Typeface.BOLD)
        }
        // A plain View with WRAP_CONTENT height consumes the entire AT_MOST
        // height during ScrollView's fill-viewport pass. This is only a horizontal
        // spacer; it must never determine the toolbar's height.
        toolbar.addView(if (largeText) View(ctx) else title, LinearLayout.LayoutParams(0, if (largeText) 0 else -2, 1f))
        hours = icon(R.drawable.clock, R.string.mango9_calendar_hours) {
            model.screen.capture()?.let { lease -> dialogs?.businessHours(lease) }
        }
        add = icon(R.drawable.plus_circle, R.string.mango9_calendar_add) {
            val now = Instant.now()
            val start = Instant.ofEpochSecond(((now.epochSecond + 899) / 900) * 900)
            model.screen.creation(start, false)?.let {
                dialogs?.create(it.copy(activity = creationActivity(it.metadata.activities)))
            }
        }
        toolbar.addView(hours); toolbar.addView(add); root.addView(toolbar)
        if (presentation.isNotEmpty()) {
            root.addView(
                Button(ctx).apply {
            text = if (presentation == "calendar") "Events & tasks" else "See Calendar"; isAllCaps = false
            setOnClickListener {
                findNavController().navigate(
                    R.id.action_global_mango9CalendarFragment,
                Bundle().apply { putString("presentation", if (presentation == "calendar") "activities" else "calendar") }
                )
            }
        }
            )
        }
        if (largeText) root.addView(title.apply { setPadding(dp(14), 0, dp(14), dp(6)) })
        tabs = MaterialButtonToggleGroup(ctx).apply {
            tag = "calendar.tabs"
            orientation = if (largeText) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            isSingleSelection = true; isSelectionRequired = true
            addView(
                MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = agendaTab; setText(R.string.mango9_calendar_title); isAllCaps = false; maxLines = 2; ellipsize = null
            },
                LinearLayout.LayoutParams(if (largeText) -1 else 0, -2, if (largeText) 0f else 1f)
            )
            addView(
                MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = calendarTab; setText(R.string.mango9_calendar_tab); isAllCaps = false; maxLines = 2; ellipsize = null
            },
                LinearLayout.LayoutParams(if (largeText) -1 else 0, -2, if (largeText) 0f else 1f)
            )
        }
        root.addView(tabs, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(6), dp(12), 0) })
        if (presentation == "calendar") tabs.visibility = View.GONE
        if (presentation == "activities") {
            tabs.findViewById<MaterialButton>(agendaTab).text = "Appointments"
            tabs.findViewById<MaterialButton>(calendarTab).text = "Events"
            tabs.addView(
                MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = tasksTab; text = "Tasks"; isAllCaps = false
            },
                LinearLayout.LayoutParams(if (largeText) -1 else 0, -2, if (largeText) 0f else 1f)
            )
        }
        heading = label(20f).apply { setTypeface(typeface, Typeface.BOLD); setPadding(dp(14), dp(6), dp(14), 0) }
        root.addView(heading)
        val controls = row()
        val actions = row().apply { gravity = Gravity.END }
        controls.addView(
            button(R.string.mango9_calendar_today) {
            model.screen.chooseDate(LocalDate.now(model.screen.state.value.displayZone))
        }
        )
        mode = button(R.string.mango9_calendar_month) {
            PopupMenu(ctx, mode).apply {
                Mango9CalendarMode.entries.forEachIndexed { index, value -> menu.add(0, index, index, modeName(value)) }
                setOnMenuItemClickListener { model.screen.chooseMode(Mango9CalendarMode.entries[it.itemId]); true }
            }.show()
        }
        mode.tag = "calendar.mode"
        controls.addView(mode)
        actions.addView(
            icon(R.drawable.caret_left, R.string.mango9_calendar_previous) {
            model.screen.move(-1)
        }
        )
        actions.addView(icon(R.drawable.caret_right, R.string.mango9_calendar_next) { model.screen.move(1) })
        actions.addView(icon(R.drawable.arrow_clockwise, R.string.mango9_calendar_refresh) { model.screen.refresh() })
        root.addView(Mango9CalendarNavigation(ctx, controls, actions))
        zone = label(12f).apply {
            setTextColor(ctx.getColor(R.color.mango9_calendar_secondary)); setPadding(dp(14), 0, dp(14), dp(4))
        }
        root.addView(zone)
        val searchRow = row().apply { setPadding(dp(12), 0, dp(8), 0) }
        val input = TextInputLayout(ctx).apply { hint = getString(R.string.mango9_calendar_search) }
        search = TextInputEditText(ctx).apply { tag = "calendar.search"; setSingleLine(true); textSize = 14f }
        input.addView(search); searchRow.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        status = icon(R.drawable.funnel, R.string.mango9_calendar_all_statuses) {
            val options = model.screen.state.value.metadata?.statuses.orEmpty()
            PopupMenu(ctx, status).apply {
                menu.add(0, 0, 0, R.string.mango9_calendar_all_statuses)
                menu.add(0, 1, 1, R.string.mango9_calendar_no_status)
                options.forEachIndexed { index, value -> menu.add(0, index + 2, index + 2, value.name) }
                setOnMenuItemClickListener {
                    model.screen.filterStatus(when (it.itemId) { 0 -> null; 1 -> 0; else -> options.getOrNull(it.itemId - 2)?.id }); true
                }
            }.show()
        }
        status.tag = "calendar.status"
        searchRow.addView(status); root.addView(searchRow)
        statusChip = Chip(ctx).apply {
            tag = "calendar.status.active"
            textSize = 12f; setTextColor(navy)
            chipBackgroundColor = ColorStateList.valueOf(ctx.getColor(R.color.mango9_calendar_badge))
            setCloseIconResource(R.drawable.x); isCloseIconVisible = true
            closeIconTint = ColorStateList.valueOf(navy)
            closeIconContentDescription = getString(R.string.mango9_calendar_clear_status_filter)
            setOnCloseIconClickListener { model.screen.filterStatus(null) }
            visibility = View.GONE
        }
        root.addView(statusChip, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(12), 0, dp(12), 0) })
        loading = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true; visibility = View.INVISIBLE
        }
        root.addView(loading, LinearLayout.LayoutParams(-1, dp(3)))
        message = label(13f).apply {
            setTextColor(ctx.getColor(R.color.mango9_calendar_error)); setPadding(dp(14), dp(4), dp(14), dp(4)); visibility = View.GONE; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(message)
        val content = FrameLayout(ctx).apply { minimumHeight = dp(240) }
        calendar = Mango9CalendarView(ctx).apply { tag = "calendar.grid" }
        agenda = RecyclerView(ctx).apply {
            tag = "calendar.agenda"
            setPadding(dp(12), dp(4), dp(12), dp(4)); clipToPadding = false; layoutManager = LinearLayoutManager(ctx)
        }
        empty = label(14f).apply {
            setText(R.string.mango9_calendar_empty); gravity = Gravity.CENTER; setPadding(dp(18), dp(18), dp(18), dp(18)); visibility = View.GONE
        }
        content.addView(calendar, FrameLayout.LayoutParams(-1, -1)); content.addView(agenda, FrameLayout.LayoutParams(-1, -1)); content.addView(empty, FrameLayout.LayoutParams(-1, -1))
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        zoomButtons = row().apply { tag = "calendar.zoom"; gravity = Gravity.END; visibility = View.GONE }
        zoomButtons.addView(
            button(R.string.mango9_calendar_zoom_out) {
            calendar.setZoom(model.zoom / 1.25f)
        },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        zoomButtons.addView(
            button(R.string.mango9_calendar_zoom_in) {
            calendar.setZoom(model.zoom * 1.25f)
        },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        root.addView(zoomButtons)
        legend = label(11f).apply {
            setPadding(dp(12), dp(5), dp(12), dp(8)); setTextColor(ctx.getColor(R.color.mango9_calendar_secondary))
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            tag = "calendar.legend"; minHeight = dp(48)
            contentDescription = getString(R.string.mango9_calendar_legend_open)
            setOnClickListener {
                val state = model.screen.state.value
                informationDialog?.dismiss()
                informationDialog = Mango9CalendarLegend.dialog(
                    ctx,
                    Mango9AppointmentAppearance.legend(
                    state.metadata?.statuses.orEmpty(), state.hours?.configured == true,
                )
                ).also { it.show() }
            }
        }
        root.addView(legend)
        // Large text/landscape may make the controls taller than the screen.
        // Keep a usable calendar and let the surrounding page scroll as needed.
        // The calendar owns gestures starting on its grid, without nested-scroll
        // interception stealing long presses or vertical/hour scrolling.
        return ScrollView(ctx).apply { isFillViewport = true; addView(root, ViewGroup.LayoutParams(-1, -2)) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        model = ViewModelProvider(this)[Mango9CalendarViewModel::class.java]
        tasksModel = ViewModelProvider(this)[Mango9TasksViewModel::class.java]
        activityKind = savedInstanceState?.getString("activity_kind")?.let {
            runCatching { Mango9ActivityKind.valueOf(it) }.getOrNull()
        } ?: Mango9ActivityKind.Appointments
        val contact = arguments?.getInt(CONTACT_ID)?.takeIf {
            it > 0
        }?.let {
            Mango9AppointmentContact(it, arguments?.getString(CONTACT_NAME), arguments?.getString(CONTACT_KIND) ?: "lead")
        }
        val lease = model.repository.capture()?.takeIf {
            contact == null || (it.session.sipIdentity == arguments?.getString(IDENTITY) && it.loginGeneration == arguments?.getLong(LOGIN_GENERATION) && it.selectionGeneration == arguments?.getLong(SELECTION_GENERATION))
        }
        model.initialize(contact, lease)
        if (presentation.isNotEmpty()) model.screen.chooseCalendar(presentation == "calendar")
        // Repository success invalidates the calendar once, including writes from other screens.
        dialogs = Mango9AppointmentDialogs(requireContext(), viewLifecycleOwner.lifecycleScope, model.repository, {
            }, { lease, linked ->
            if (model.repository.isCurrent(lease)) {
                findNavController().navigate(
                    R.id.action_global_mango9CrmFragment,
                    Mango9CrmFragment.recordArguments(linked, lease)
                )
            }
        }, retainedEdits = model.edits, loadContacts = model.contacts)
        val pushId = arguments?.getInt("mango9_event_id", 0) ?: 0
        if (pushId > 0 && savedInstanceState == null) {
            val owner = org.linphone.mango9.Mango9NavigationSession.resolve(requireArguments(), org.linphone.mango9.Mango9SessionStore(requireContext()))
            if (owner != null) {
                viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val metadata = model.repository.metadata(owner)
                    val event = model.repository.appointment(owner, pushId)
                    if (model.repository.isCurrent(owner)) dialogs?.detail(owner, event, metadata)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    if (model.repository.isCurrent(owner)) MaterialAlertDialogBuilder(requireContext()).setMessage(e.message).setPositiveButton(android.R.string.ok, null).show()
                }
            }
            }
        }
        val taskId = arguments?.getInt("mango9_task_id", 0) ?: 0
        if (taskId > 0 && savedInstanceState == null) {
            val owner = org.linphone.mango9.Mango9NavigationSession.matching(requireArguments(), model.repository.capture())
            if (owner != null) { activityKind = Mango9ActivityKind.Tasks; openTask(taskId, owner) }
        }
        agendaAdapter = Mango9AppointmentAgendaAdapter { }
        agenda.adapter = agendaAdapter
        calendar.bind(viewLifecycleOwner.lifecycleScope); calendar.setZoom(model.zoom)
        calendar.onZoom = { model.zoom = it }
        tabs.addOnButtonCheckedListener { _, id, checked ->
            if (checked && !renderingTabs) {
                if (presentation == "activities") {
                    activityKind = when (id) { tasksTab -> Mango9ActivityKind.Tasks; calendarTab -> Mango9ActivityKind.Events; else -> Mango9ActivityKind.Appointments }
                    model.screen.chooseCalendar(false)
                    if (activityKind == Mango9ActivityKind.Tasks) tasksModel.load(model.screen.capture(), model.screen.state.value.query)
                    render()
                } else {
                    model.screen.chooseCalendar(id == calendarTab)
                }
        }
        }
        search.doAfterTextChanged {
            if (!renderingSearch) {
            model.screen.search(it.toString())
            if (activityKind == Mango9ActivityKind.Tasks) tasksModel.load(model.screen.capture(), it.toString())
        }
        }
        tasksModel.state.observe(viewLifecycleOwner) { render() }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    var lastQuery: String? = null
                    model.screen.state.collect {
                        val changed = lastQuery != null && lastQuery != it.query
                        lastQuery = it.query
                        render(debounce = changed)
                    }
                }
                launch {
                    model.contextChanges.collect {
                        dialogs?.closeExpired(); informationDialog?.dismiss(); informationDialog = null
                        if (presentation == "activities" && activityKind == Mango9ActivityKind.Tasks) tasksModel.load(model.screen.capture(), model.screen.state.value.query)
                    }
                }
                launch { while (isActive) { delay(30_000); render() } }
            }
        }
    }

    override fun onStart() { super.onStart(); model.start(); dialogs?.resumeEdits() }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("activity_kind", activityKind.name); super.onSaveInstanceState(outState)
    }

    private fun creationActivity(available: List<String>): String? = if (presentation == "activities") {
        available.firstOrNull {
        Mango9ActivityKind.of(it) == activityKind
    }
    } else {
        null
    }

    override fun onStop() {
        informationDialog?.dismiss(); informationDialog = null; dialogs?.detach(); model.stop(); super.onStop()
    }

    override fun onDestroyView() {
        renderGeneration++; renderJob?.cancel(); dialogs?.detach(); dialogs = null; agenda.adapter = null; renderedLease = null; super.onDestroyView()
    }

    private fun modeName(value: Mango9CalendarMode) = getString(when (value) { Mango9CalendarMode.Day -> R.string.mango9_calendar_day; Mango9CalendarMode.Week -> R.string.mango9_calendar_week; Mango9CalendarMode.Month -> R.string.mango9_calendar_month })

    private fun open(event: org.linphone.mango9.Mango9Appointment, lease: Mango9SessionLease?) {
        if (lease == null || !model.repository.isCurrent(lease)) return
        val metadata = model.screen.state.value.metadata ?: return
        dialogs?.detail(lease, event, metadata)
    }

    private fun bindContentOwner(lease: Mango9SessionLease?) {
        if (lease != null && renderedLease?.let(model.repository::isCurrent) == true &&
            renderedLease?.session?.sipIdentity == lease.session.sipIdentity &&
            renderedLease?.loginGeneration == lease.loginGeneration && renderedLease?.selectionGeneration == lease.selectionGeneration
        ) {
            return
        }
        renderedLease = lease
        calendar.resetViewport()
        calendar.restoreViewport(lease?.let(model::retainedViewport))

        // Old rows and gestures keep their original login even before the account
        // change observer or a replacement layout has had a chance to run.
        fun current() = lease != null && model.repository.isCurrent(lease)
        agendaAdapter = Mango9AppointmentAgendaAdapter { event -> open(event, lease) }
        agendaAdapter.onTask = { task -> openTask(task.id, lease) }
        agendaAdapter.onMore = {
            tasksModel.load(lease, model.screen.state.value.query, more = tasksModel.state.value?.error == null)
        }
        agenda.adapter = agendaAdapter
        calendar.onViewportChanged = { value -> if (lease != null) model.retainViewport(lease, value) }
        calendar.onEvent = { event -> open(event, lease) }
        calendar.onDay = { if (current()) model.screen.chooseDate(it, true) }
        calendar.onCrowdedDay = { if (current()) model.screen.showAgendaDay(it) }
        calendar.onOutsideHours = { if (current()) explainClosedHours() }
        calendar.onCreate = { instant ->
            if (current()) {
                val creation = model.screen.creation(instant, true)
                if (creation != null) dialogs?.create(creation) else Toast.makeText(requireContext(), R.string.mango9_calendar_unavailable_slot, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun render(debounce: Boolean = false) {
        if (view == null || !::model.isInitialized) return
        val state = model.screen.state.value
        if (presentation.isNotEmpty() && state.calendar != (presentation == "calendar") && state.agendaDay == null) {
            model.screen.chooseCalendar(presentation == "calendar"); return
        }
        val lease = model.screen.capture()
        val labels = resources
        if (search.text.toString() != state.query) {
            renderingSearch = true
            search.setText(state.query); search.setSelection(state.query.length)
            renderingSearch = false
        }
        status.contentDescription = when (state.selectedStatus) {
            null -> getString(R.string.mango9_calendar_all_statuses)
            0 -> getString(R.string.mango9_calendar_no_status)
            else -> state.metadata?.statuses?.firstOrNull { it.id == state.selectedStatus }?.name
                ?: getString(R.string.mango9_calendar_all_statuses)
        }
        statusChip.text = getString(R.string.mango9_calendar_status_filter, status.contentDescription)
        statusChip.visibility = if (state.selectedStatus != null) View.VISIBLE else View.GONE
        val requestedQuery = state.query.lowercase(Locale.ROOT); val requestedStatus = state.selectedStatus; val day = state.agendaDay
        val representedTasks = if (activityKind == Mango9ActivityKind.Tasks) {
            tasksModel.state.value?.items.orEmpty().mapNotNull {
            it.eventId
        }.toSet()
        } else {
            emptySet()
        }
        val selectedKind = activityKind
        val request = ++renderGeneration
        renderJob?.cancel()
        renderJob = viewLifecycleOwner.lifecycleScope.launch {
            if (debounce) delay(250)
            val now = Instant.now()
            val (filtered, rows) = withContext(Dispatchers.Default) {
                val activities = if (presentation == "activities") {
                    state.events.filter {
                    Mango9ActivityKind.of(it.activity) == selectedKind && it.id !in representedTasks
                }
                } else {
                    state.events
                }
                val events = Mango9CalendarPresentation.filteredEvents(activities, requestedQuery, requestedStatus, day, state.displayZone)
                val rows = Mango9AppointmentAgenda.sections(events, now, state.displayZone).flatMap { section ->
                    val bucketName = labels.getString(
                        when (section.bucket) {
                        Mango9AppointmentAgenda.Bucket.DueNow -> R.string.mango9_calendar_due
                        Mango9AppointmentAgenda.Bucket.Upcoming -> R.string.mango9_calendar_upcoming
                        Mango9AppointmentAgenda.Bucket.Past -> R.string.mango9_calendar_past
                        Mango9AppointmentAgenda.Bucket.Closed -> R.string.mango9_calendar_closed
                    }
                    )
                    listOf(Mango9AgendaRow.Heading("heading:${section.id}", "$bucketName · ${section.day.format(DateTimeFormatter.ofPattern("EEE, MMM d"))}")) + section.events.map {
                        Mango9AgendaRow.Event(it, state.displayZone, now.epochSecond / 60)
                    }
                }
                state.copy(events = events) to rows
            }
            if (request != renderGeneration || view == null || (lease != null && !model.repository.isCurrent(lease))) return@launch
            bindContentOwner(lease)
            heading.text = Mango9CalendarPresentation.heading(state.date, if (state.calendar) state.mode else Mango9CalendarMode.Month, state.firstWeekday, Locale.getDefault())
            zone.text = getString(R.string.mango9_calendar_zone, state.displayZone.id.replace('_', ' '))
            renderingTabs = true; tabs.check(
                if (presentation == "activities") {
                when (activityKind) {
                Mango9ActivityKind.Appointments -> agendaTab; Mango9ActivityKind.Events -> calendarTab; Mango9ActivityKind.Tasks -> tasksTab
            }
            } else if (state.calendar) {
                calendarTab
            } else {
                agendaTab
            }
            ); renderingTabs = false
            mode.text = modeName(state.mode) + " ▾"
            mode.visibility = if (state.calendar) View.VISIBLE else View.INVISIBLE
            add.isEnabled = model.screen.canCreate()
            hours.isEnabled = state.metadata?.businessHoursSettings != null && !state.loading
            loading.visibility = if (state.loading) View.VISIBLE else View.INVISIBLE
            message.text = state.error.orEmpty(); message.visibility = if (state.error != null) View.VISIBLE else View.GONE
            agenda.visibility = if (state.calendar) View.GONE else View.VISIBLE
            calendar.visibility = if (state.calendar) View.VISIBLE else View.GONE
            zoomButtons.visibility = if (state.calendar && state.mode != Mango9CalendarMode.Month) View.VISIBLE else View.GONE
            empty.visibility = if (!state.calendar && filtered.events.isEmpty() && !state.loading && state.error == null) View.VISIBLE else View.GONE
            val taskState = tasksModel.state.value
            val managedRows = if (presentation == "activities" && activityKind == Mango9ActivityKind.Tasks && taskState != null) {
                listOf(Mango9AgendaRow.Heading("managed.tasks", "Assigned tasks · all dates")) + taskState.items.map {
                    Mango9AgendaRow.Task(it)
                } +
                    if (taskState.error != null || taskState.hasMore) listOf(Mango9AgendaRow.More("tasks.more", taskState.error ?: "Load more tasks")) else emptyList()
            } else {
                emptyList()
            }
            agendaAdapter.submitList(rows + managedRows)
            if (managedRows.isNotEmpty()) empty.visibility = View.GONE
            if (state.calendar) calendar.submit(filtered)
            renderLegend(state)
        }
    }

    private fun explainClosedHours() {
        informationDialog?.dismiss()
        informationDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.mango9_calendar_outside_hours)
            .setMessage(R.string.mango9_calendar_outside_hours_help)
            .setPositiveButton(R.string.mango9_calendar_done, null).show()
    }

    private fun openTask(taskId: Int, lease: Mango9SessionLease?) {
        if (lease == null || !model.repository.isCurrent(lease)) return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val full = model.repository.task(lease, taskId)
                if (!model.repository.isCurrent(lease)) return@launch
                val body = listOf(
                    full.description, "Status: ${full.status}", "Type: ${full.type}", "Priority: ${full.priority}",
                    "Assigned to: ${full.assignees.joinToString(", ")}", "Due: ${full.dueAt ?: "Not set"}", "Time zone: ${full.timezone}",
                    full.contact.orEmpty(), full.agent.orEmpty(), full.history.joinToString("\n")
                ).filter { it.isNotBlank() }.joinToString("\n\n")
                informationDialog?.dismiss()
                val page = Mango9FullPage(requireContext()).setTitle(full.title)
                    .setView(
                        android.widget.ScrollView(requireContext()).apply {
                        addView(TextView(requireContext()).apply { text = body; textSize = 16f; setPadding(dp(20), dp(20), dp(20), dp(20)) })
                    }
                    )
                    .setNegativeButton(R.string.mango9_calendar_done, null)
                    .setPositiveButton(R.string.mango9_calendar_title) { _, _ ->
                        if (model.repository.isCurrent(lease)) {
                            findNavController().navigate(
                                R.id.action_global_mango9CalendarFragment,
                            Bundle().apply {
                                org.linphone.mango9.Mango9NavigationSession.bind(this, lease)
                                putString("presentation", "calendar"); full.eventId?.let {
                                    putInt("mango9_event_id", it)
                                }
                            }
                            )
                        }
                    }
                informationDialog = page
                page.show()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (model.repository.isCurrent(lease)) Toast.makeText(requireContext(), "Task could not be loaded.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun renderLegend(state: Mango9CalendarScreenState) {
        legend.text = Mango9CalendarLegend.summary(
            requireContext(),
            Mango9AppointmentAppearance.legend(
            state.metadata?.statuses.orEmpty(), state.hours?.configured == true,
        )
        )
        legend.visibility = if (state.calendar) View.VISIBLE else View.GONE
    }

    companion object {
        const val CONTACT_ID = "calendar_contact_id"
        const val CONTACT_NAME = "calendar_contact_name"
        const val CONTACT_KIND = "calendar_contact_kind"
        private const val IDENTITY = "calendar_contact_identity"
        private const val LOGIN_GENERATION = "calendar_contact_login_generation"
        private const val SELECTION_GENERATION = "calendar_contact_selection_generation"

        fun arguments(contact: Mango9AppointmentContact, lease: Mango9SessionLease) = Bundle().apply {
            putInt(CONTACT_ID, contact.id); putString(CONTACT_NAME, contact.name); putString(CONTACT_KIND, contact.kind)
            putString(IDENTITY, lease.session.sipIdentity); putLong(LOGIN_GENERATION, lease.loginGeneration); putLong(SELECTION_GENERATION, lease.selectionGeneration)
        }
    }
}
