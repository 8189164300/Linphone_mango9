// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.ui.main.crm.fragment

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.graphics.Typeface
import androidx.core.widget.doAfterTextChanged
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import org.json.JSONObject
import org.linphone.R
import org.linphone.mango9.*
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

internal data class Mango9OpportunityScreen(val record: Mango9CrmRecord?, val lease: Mango9SessionLease, val mode: String, val draft: String? = null, val editingRow: String? = null)

/** Native pages use the account that supplied the card. Account changes dismiss every child page. */
internal class Mango9OpportunityUI(
    private val context: Context, private val scope: CoroutineScope, private val host: ViewGroup,
    private val capture: () -> Mango9SessionLease?, private val changed: () -> Unit,
    private val openContact: (Mango9SessionLease, Mango9AppointmentContact) -> Unit,
    private val openCalendar: (Int) -> Unit = {},
    private val beforeChat: () -> Unit = {},
        private val visibilityChanged: () -> Unit = {
        },
) {
    private val api = Mango9Opportunities(context)
    private val sessions = Mango9SessionStore(context)
    private val commands = Mango9OpportunityCommands(context)
    private val pages = mutableListOf<Mango9FullPage>()
    private var popup: androidx.appcompat.app.AlertDialog? = null
    private var generation = 0
    private var countJob: Job? = null
    private var dashboardGeneration = 0

    private var snapshotProvider: (() -> Mango9OpportunityScreen?)? = null
    val isShowing: Boolean get() = pages.isNotEmpty()
    val ownsHeader: Boolean get() = pages.lastOrNull()?.isOpportunityList == false

    fun snapshot() = snapshotProvider?.invoke()

    fun restore(state: Mango9OpportunityScreen) {
        if (!current(state.lease)) return
        when (state.mode) { "create" -> create(state.record, state.lease, state.draft); "convert" -> state.record?.let {
            convert(it, state.lease)
        }; "edit" -> {
            val row = state.editingRow?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
            val record = state.record ?: return
            workspace(record, row.getInt("id"))
            val body = column(); val p = page("Edit Opportunity", body)
            scope.launch {
                try {
                    val meta = Mango9OpportunityMetadata(api.request(state.lease, listOf("metadata")))
                    if (current(state.lease) && p.isShowing) {
                        form(body, p, record, state.lease, meta, state.draft, row) {
                        close(); workspace(record, row.getInt("id"))
                    }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e; if (current(state.lease) && p.isShowing) error(body, e)
                }
            }
        }; "list" -> workspace(state.record); "detail" -> workspace(state.record, state.draft?.toIntOrNull()); "sharing" -> state.record?.let {
            sharing(it)
        } }
    }

    fun requestLeave(action: () -> Unit) { pages.lastOrNull()?.requestLeave(action) ?: action() }

    fun close() {
        snapshotProvider = null; generation++; countJob?.cancel(); pages.toList().asReversed().forEach {
            it.dismiss()
        }; popup?.dismiss(); popup = null
    }

    private fun current(lease: Mango9SessionLease) = sessions.currentSession(lease) != null

    private fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()

    private fun column() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(20))
    }

    private fun label(text: String) = TextView(context).apply {
        this.text = text; textSize = 15f; setTextColor(context.getColor(R.color.mango9_calendar_ink)); setPadding(0, dp(8), 0, dp(8)); hyphenationFrequency = 0
    }

    private fun button(text: String, action: () -> Unit) = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
        this.text = text; isAllCaps = false; setSingleLine(true); textSize = 14f; minHeight = dp(48); setTextColor(context.getColor(R.color.mango9_calendar_accent)); setOnClickListener {
            action()
        }
    }

    fun activitySection(contactId: Int): View? {
        val lease = capture() ?: return null
        return Mango9CrmActivityUI(context, scope, lease, ::page, {
            workspace(openId = it)
        }, beforeChat).section(contactId, collapsed = true)
    }

    private fun page(title: String, content: LinearLayout): Mango9FullPage {
        val p = Mango9FullPage(context).setHost(host).setTitle(title).setView(ScrollView(context).apply {addView(content)})
            .setNegativeButton(R.string.mango9_calendar_back, null).opportunityChrome().create()
        pages.add(p); p.setOnDismissListener {
            pages.remove(p); if (pages.isEmpty()) snapshotProvider = null; visibilityChanged()
        }; p.show(); visibilityChanged(); return p
    }

    private fun error(parent: LinearLayout, e: Exception, retry: (() -> Unit)? = null) {
        parent.addView(
            label(e.message ?: "The request could not be completed.").apply {
            setTextColor(context.getColor(R.color.mango9_calendar_error))
        }
        )
        if (retry != null)parent.addView(button("Retry", retry))
    }

    fun counts(records: List<Mango9CrmRecord>, result: (Map<Int, Int>, Boolean) -> Unit) {
        val lease = capture() ?: return; val version = ++generation; countJob?.cancel()
        result(emptyMap(), false)
        countJob = scope.launch {
            try {
                val meta = Mango9OpportunityMetadata(api.request(lease, listOf("metadata")))
                val counts = mutableMapOf<Int, Int>()
                if (meta.canAccess){
                    for (ids in records.map {it.id}.distinct().chunked(100)) {
                    val raw = api.request(lease, listOf("contactCounts"), mapOf("ids" to ids.joinToString(",")))
                    raw.getJSONArray("counts").opportunityObjects().forEach {counts[it.getInt("id")] = it.getInt("count")}
                }
                }
                if (version == generation && current(lease))result(counts, meta.canCreate)
            }catch (e: Exception){if (e is CancellationException)throw e}
        }
    }

    fun sharing(record: Mango9CrmRecord) {
        val lease = capture() ?: return
        snapshotProvider = {Mango9OpportunityScreen(record, lease, "sharing")}
        val content = column()
        content.addView(label(record.name).apply {setTypeface(typeface, Typeface.BOLD)})
        record.sharing.entries.forEach {
            content.addView(
                label(
                    "Shared with " + it.sharedWith.ifBlank {
                "a team member"
            }
                ).apply {setTypeface(typeface, Typeface.BOLD)}
            )
            content.addView(label("Shared by " + it.sharedBy.ifBlank {"a team member"}))
            if (it.via == "appointment")content.addView(label("Through a shared appointment"))
        }
        popup?.dismiss(); popup = MaterialAlertDialogBuilder(context).setTitle("Sharing").setView(
            ScrollView(context).apply {
            addView(content)
        }
        ).setNegativeButton("Close", null).show()
    }

    fun actions(record: Mango9CrmRecord, kind: Mango9RecordKind, canCreate: Boolean) {
        val lease = capture() ?: return
        val content = column()
        popup?.dismiss()
        val dialog = MaterialAlertDialogBuilder(context).setTitle(record.name).setView(content)
            .setNegativeButton(android.R.string.cancel, null).create()
        popup = dialog
        dialog.setOnDismissListener { if (popup === dialog) popup = null }

        fun render(allowed: Boolean) {
            content.removeAllViews()
            if (allowed) content.addView(button("Add Opportunity") { dialog.dismiss(); if (current(lease)) create(record, lease) })
            if (kind == Mango9RecordKind.Lead && record.canUpdate) {
                content.addView(button("Convert to Client") { dialog.dismiss(); if (current(lease)) convert(record, lease) })
            }
        }

        fun checkPermission() {
            render(false)
            content.addView(label("Loading Opportunity actions…"))
            scope.launch {
                try {
                    val meta = Mango9OpportunityMetadata(api.request(lease, listOf("metadata")))
                    if (current(lease) && dialog.isShowing) render(meta.canCreate)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (current(lease) && dialog.isShowing) {
                        render(false); error(content, e) { checkPermission() }
                    }
                }
            }
        }
        dialog.show()
        // Counts load separately from the cards. A tap before that request completes
        // must not permanently omit the create action from the open menu.
        if (canCreate) render(true) else checkPermission()
    }

    fun list(record: Mango9CrmRecord) = workspace(record)

    fun access(result: (Boolean, Int?) -> Unit) {
        val lease = capture() ?: return
        scope.launch {
            var allowed = false
            try {
                val meta = Mango9OpportunityMetadata(api.request(lease, listOf("metadata")))
                allowed = meta.canAccess
                if (!meta.canAccess) { if (current(lease)) result(false, null); return@launch }
                val data = api.request(lease, query = mapOf("limit" to "25", "page" to "1", "sort" to "updated_at", "direction" to "desc", "archived" to "0"))
                val total = data.getJSONObject("pagination").getInt("total"); require(total >= 0)
                if (current(lease)) result(true, total)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (current(lease)) result(allowed, null)
            }
        }
    }

    fun dashboard(host: LinearLayout) {
        val lease = capture() ?: return
        host.removeAllViews()
        val body = column().apply { setPadding(dp(8), dp(4), dp(8), dp(8)) }; host.addView(body)

        fun load() {
            val version = ++dashboardGeneration
            scope.launch {
            body.removeAllViews(); body.addView(label("Loading Opportunities…"))
            try {
                val meta = Mango9OpportunityMetadata(api.request(lease, listOf("metadata")))
                if (!current(lease) || version != dashboardGeneration)return@launch
                if (!meta.canAccess){host.removeAllViews(); return@launch}
                val raw = api.request(lease, listOf("dashboard")); val data = Mango9OpportunityDashboard(raw)
                if (!current(lease) || version != dashboardGeneration)return@launch
                body.removeAllViews(); body.addView(button("Opportunities ›"){workspace()})
                val rows =
                    listOf(listOf("Open opportunities" to "open", "Open value" to "open"), listOf("Won this month" to "won", "Follow-ups due" to "due"))
                rows.forEach { cells ->
                    val line = LinearLayout(context)
                    cells.forEach { (title, filter) ->
                        val tile = column().apply {
                            setPadding(dp(10), dp(10), dp(10), dp(10))
                            background = android.graphics.drawable.GradientDrawable().apply {
                                setColor(android.graphics.Color.rgb(246, 247, 251)); cornerRadius = dp(12).toFloat()
                            }
                            val accent = android.graphics.Color.parseColor(if (filter == "won") "#15803D" else if (filter == "due") "#B45309" else "#4253C7")
                            addView(
                                android.widget.ImageView(context).apply {
                                setImageResource(when (title) { "Open opportunities" -> R.drawable.mango9_crm_money; "Open value" -> R.drawable.mango9_crm_chart; "Won this month" -> R.drawable.check; else -> R.drawable.calendar_blank })
                                imageTintList = android.content.res.ColorStateList.valueOf(accent)
                            },
                                LinearLayout.LayoutParams(dp(20), dp(20)).apply { bottomMargin = dp(4) }
                            )

                            fun caption(value: String) {
                                addView(label(value).apply { textSize = 12f; setTextColor(android.graphics.Color.rgb(96, 112, 135)) })
                            }

                            fun amount(value: String) {
                                addView(label(value).apply { setPadding(0, dp(3), 0, dp(2)); textSize = 22f; setTypeface(typeface, Typeface.BOLD) })
                            }
                            addView(
                                label(title).apply {
                                setPadding(0, dp(2), 0, 0); textSize = 13f; setTextColor(android.graphics.Color.rgb(96, 112, 135))
                            }
                            )
                            when (title) {
                                "Open opportunities" -> { amount(data.open.toString()); caption("Active opportunities") }
                                "Open value" -> {
                                    amount(data.amounts("openCurrencies", data.open)); if (data.unknownOpen > 0)caption("${data.unknownOpen} without a value")
                                }
                                "Won this month" -> {
                                    amount(data.amounts("currencies", data.won)); caption("${data.won} won"); if (data.estimatedWon > 0)caption("${data.estimatedWon} using estimated value"); if (data.unknownWon > 0)caption("${data.unknownWon} without a value")
                                }
                                else -> { amount(data.due.toString()); caption("${data.overdue} overdue · ${data.today} today") }
                            }
                            isClickable = true; contentDescription = title; setOnClickListener { workspace(dashboard = filter) }
                        }
                        line.addView(tile, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
                    }
                    body.addView(line, LinearLayout.LayoutParams(-1, -2))
                }
            }catch (e: Exception){
                if (e is CancellationException)throw e; if (current(lease) && version == dashboardGeneration){
                    body.removeAllViews(); error(body, e){load()}
                }
            }
        }
        }
        load()
    }

    fun workspace(record: Mango9CrmRecord? = null, openId: Int? = null, dashboard: String = "") {
        val lease = capture() ?: return
        snapshotProvider = { Mango9OpportunityScreen(record, lease, "list") }
        lateinit var workspace: Mango9OpportunityWorkspaceUI
        workspace = Mango9OpportunityWorkspaceUI(
            context, scope, lease, ::page,
            openContact = { openContact(lease, it) },
            beforeChat = beforeChat,
            openCalendar = openCalendar,
            create = { create(it, lease, updated = { workspace.refresh() }) },
            edit = { row, meta, refreshed ->
                val previous = snapshotProvider
                val body = column(); val p = page("Edit Opportunity", body).whenDismissed {
                    if (pages.isNotEmpty()) snapshotProvider = previous
                }
                val linked = Mango9OpportunityPresentation.contact(row)
                val contact =
                    Mango9CrmRecord(row.optInt("contact_id"), row.optInt("owner_id"), row.optString("owner_name"), linked?.optString("name") ?: "Restricted contact", "", "", "", "", "")
                form(body, p, contact, lease, meta, existing = row, updated = refreshed)
            }, changed = changed, screenChanged = { id ->
                snapshotProvider = {
                Mango9OpportunityScreen(record, lease, if (id == null) "list" else "detail", id?.toString())
            }
            }
        )
        workspace.list(record, dashboard)
        if (openId != null) workspace.detail(openId)
    }

    private fun convert(record: Mango9CrmRecord, lease: Mango9SessionLease) {
        snapshotProvider = {Mango9OpportunityScreen(record, lease, "convert")}
        val body = column(); val p = page("Convert to Client", body); var busy = false

        fun load() {
            scope.launch {
                try {
            val state = api.request(lease, listOf(record.id.toString(), "control-state"), contact = true)
            require(state.getInt("id") == record.id)
            if (!current(lease) || !p.isShowing)return@launch; body.removeAllViews()
            if (state.getBoolean("is_client")){
                commands.save(lease, record.id, "convert", null); Mango9CrmUnread.converted(record.id, lease); p.dismiss(); changed(); return@launch
            }
            if (!state.getJSONObject("permissions").optBoolean("can_convert_to_client")){
                body.addView(label("Conversion is not available for this lead in your account.")); return@launch
            }
            val revision = state.getString("revision"); require(revision.matches(Regex("[a-f0-9]{64}")))
            body.addView(label(record.name).apply {setTypeface(typeface, Typeface.BOLD)})
            body.addView(label("Convert this lead to a client? Its contact details and linked Opportunities will stay with the same record."))
            var needsFreshState = false
            val confirm = button("Convert to Client") {}; body.addView(confirm)
            confirm.setOnClickListener {
                if (busy || !current(lease))return@setOnClickListener; busy = true; confirm.isEnabled = false; p.setCancelable(false)
                scope.launch {
                    try {
                    val command = commands.load(lease, record.id, "convert") ?: JSONObject().put("requestId", UUID.randomUUID().toString()).put("revision", revision)
                    commands.save(lease, record.id, "convert", command)
                    val response = api.request(lease, listOf(record.id.toString(), "convert-to-client"), body = command, contact = true)
                    require(response.getInt("id") == record.id && response.getBoolean("is_client"))
                    commands.save(lease, record.id, "convert", null)
                    if (current(lease)){Mango9CrmUnread.converted(record.id, lease); p.dismiss(); changed()}
                }catch (e: Exception){
                    if (e is CancellationException)throw e; if (Mango9OpportunityCommands.definite(e)){
                        commands.save(lease, record.id, "convert", null); needsFreshState = true
                    }; if (current(lease) && p.isShowing)error(body, e){load()}
                }finally {busy = false; confirm.isEnabled = !needsFreshState; p.setCancelable(true)}
                }
            }
        }catch (e: Exception){
            if (e is CancellationException)throw e; if (current(lease) && p.isShowing){
                body.removeAllViews(); error(body, e){load()}
            }
        }
            }
        }
        body.addView(label("Checking record…")); load()
    }

    private class FormHost(val showing: () -> Boolean, val cancelable: (Boolean) -> Unit, val dismissAction: () -> Unit, val addSave: ((Button) -> Unit)? = null, val protect: (((() -> Boolean)) -> Unit)? = null) {
        val isShowing get() = showing()

        fun setCancelable(value: Boolean) = cancelable(value)

        fun dismiss() = dismissAction()
    }

    private fun create(
        record: Mango9CrmRecord?, lease: Mango9SessionLease, restoredDraft: String? = null,
        updated: () -> Unit = {
        }
    ) {
        val previous = snapshotProvider
        snapshotProvider = { Mango9OpportunityScreen(record, lease, "create", restoredDraft) }
        val body = column()
        val picker = page(if (record == null) "Lead or Client" else "Add Opportunity", body)
        var searchJob: Job? = null; var sequence = 0
        picker.whenDismissed {sequence++; searchJob?.cancel(); snapshotProvider = previous}

        fun selected(linked: Mango9CrmRecord) {
            val fields = column(); val editor = if (record != null)picker else page("Add Opportunity", fields)
            val target = if (record != null)body else fields
            target.removeAllViews(); target.addView(label("Loading Opportunity fields…"))

            fun loadFields() {
                scope.launch{
                try {
                    val meta = Mango9OpportunityMetadata(api.request(lease, listOf("metadata")))
                    require(meta.canCreate && meta.activeStages.isNotEmpty()){ "Creating Opportunities is not available for this account." }
                    if (!current(lease) || !editor.isShowing)return@launch
                    target.removeAllViews(); form(target, editor, linked, lease, meta, restoredDraft, updated = {
                        if (picker.isShowing)picker.dismiss(); updated()
                    })
                }catch (e: Exception){
                    if (e is CancellationException)throw e; if (current(lease) && editor.isShowing){
                        target.removeAllViews(); error(target, e){loadFields()}
                    }
                }
            }
            }
            loadFields()
        }
        if (record != null) { selected(record); return }
        val search = EditText(context).apply {
            hint = "Search name, company, email, or phone"; contentDescription = "Search leads or clients"; setSingleLine(); textSize = 16f; setTextColor(context.getColor(R.color.mango9_calendar_ink))
        }
        body.addView(search)
        val filter = Spinner(context).apply{
            contentDescription = "Record type"; adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, listOf("All", "Leads", "Clients"))
        }
        val pickerActions = LinearLayout(context).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        pickerActions.addView(filter, LinearLayout.LayoutParams(0, dp(48), 1f))
        val add = button("Add new") {
            android.widget.PopupMenu(context, search).apply {
            menu.add("Add Lead").setOnMenuItemClickListener { createLinkedRecord(Mango9RecordKind.Lead, lease, ::selected); true }
            menu.add("Add Client").setOnMenuItemClickListener {
                createLinkedRecord(Mango9RecordKind.Client, lease, ::selected); true
            }
            show()
        }
        }; pickerActions.addView(add, LinearLayout.LayoutParams(0, dp(48), 1f)); body.addView(pickerActions)
        val rows = column().apply { setPadding(0, 0, 0, 0) }; body.addView(rows)
        var page = 0; var loading = false

        fun load(append: Boolean = false) {
            if (append && loading) return
            val token = ++sequence; loading = true
            if (!append) { rows.removeAllViews(); rows.addView(label("Searching…")) }
            scope.launch {
                try {
                    val data = api.request(lease, listOf("contacts"), mapOf("q" to search.text.toString(), "type" to listOf("all", "leads", "clients")[filter.selectedItemPosition.coerceAtLeast(0)], "page" to (if (append) page + 1 else 1).toString()))
                    if (!current(lease) || !picker.isShowing || sequence != token) return@launch
                    if (!append) rows.removeAllViews()
                    rows.findViewWithTag<View>("more")?.let(rows::removeView)
                    val items = data.getJSONArray("items").opportunityObjects()
                    items.forEach { r ->
                        val linked =
                            Mango9CrmRecord(r.getInt("id"), r.optInt("owner_id"), "", r.getString("name"), Mango9OpportunityPresentation.text(r, "phone"), Mango9OpportunityPresentation.text(r, "email"), "", Mango9OpportunityPresentation.text(r, "source"), "")
                        val kind = if (r.optBoolean("is_client") || r.optInt("is_client") == 1) "Client" else "Lead"
                        val choice = button(linked.name) { selected(linked) }.apply { contentDescription = "$kind: ${linked.name}" }
                        rows.addView(choice)
                        rows.addView(
                            label(
                                listOf(kind, Mango9OpportunityPresentation.text(r, "company"), linked.email, linked.phone).filter {
                            it.isNotBlank()
                        }.joinToString(" · ")
                            ).apply { textSize = 13f }
                        )
                    }
                    page = data.getJSONObject("pagination").getInt("page")
                    if (items.isEmpty() && !append) rows.addView(label("No matching leads or clients."))
                    if (data.getJSONObject("pagination").getBoolean("hasMore")) {
                        rows.addView(
                            button("Load more") {
                        load(true)
                    }.apply { tag = "more" }
                        )
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (current(lease) && picker.isShowing && sequence == token) {
                        rows.removeAllViews(); error(rows, e); rows.addView(button("Retry search") { load() })
                    }
                } finally { if (sequence == token) loading = false }
            }
        }
        filter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {override fun onNothingSelected(p: AdapterView<*>?) {
            }

            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                sequence++; searchJob?.cancel(); load()
            } }
        search.doAfterTextChanged {
            sequence++; searchJob?.cancel(); rows.removeAllViews(); searchJob = scope.launch { delay(250); load() }
        }; load()
    }

    private fun createLinkedRecord(kind: Mango9RecordKind, lease: Mango9SessionLease, selected: (Mango9CrmRecord) -> Unit) {
        val body = column(); val p = page("Add ${if (kind == Mango9RecordKind.Lead) "Lead" else "Client"}", body)
        val inputs = listOf("First name", "Last name", "Phone", "Email").map { title ->
            body.addView(label(title)); EditText(context).apply{
            hint = title; contentDescription = title; textSize = 16f; body.addView(this)
        }
        }
        p.protectChanges { inputs.any{it.text.isNotBlank()} }
        val errors = column(); body.addView(errors); var busy = false; var uncertain = false
        val save = button("Save ${if (kind == Mango9RecordKind.Lead) "Lead" else "Client"}") {}; body.addView(save)
        save.setOnClickListener {
            if (busy || uncertain || !current(lease))return@setOnClickListener
            val values = inputs.map{it.text.toString().trim()}
            if (values[0].isBlank() && values[1].isBlank()){
                errors.removeAllViews(); errors.addView(label("Enter a name.")); return@setOnClickListener
            }
            busy = true; p.setCancelable(false); save.isEnabled = false
            scope.launch {
                try {
                val result = Mango9CrmRepository(context).createRecord(lease, kind, values[0], values[1], values[2], values[3], "")
                if (current(lease) && p.isShowing){p.dismiss(); changed(); selected(result.record)}
            }catch (e: Exception){
                if (e is CancellationException)throw e; if (current(lease) && p.isShowing){
                // Never silently repeat a creation after an unknown network result.
                uncertain = true; error(errors, e); errors.addView(label("Check the Lead or Client list before trying again."))
            }
            }finally {busy = false; p.setCancelable(true); save.isEnabled = !uncertain}
            }
        }
    }

    private fun form(
        body: LinearLayout, p: Mango9FullPage, record: Mango9CrmRecord, lease: Mango9SessionLease, meta: Mango9OpportunityMetadata, restoredDraft: String? = null, existing: JSONObject? = null,
        updated: () -> Unit = {
        }
    ) =
        form(
            body,
            FormHost({
            p.isShowing
        }, { p.setCancelable(it) }, { p.dismiss() }, protect = {p.protectChanges(it)}),
            record, lease, meta, restoredDraft, existing, updated
        )

    private fun form(
        body: LinearLayout, p: FormHost, record: Mango9CrmRecord, lease: Mango9SessionLease, meta: Mango9OpportunityMetadata, restoredDraft: String? = null, existing: JSONObject? = null,
        updated: () -> Unit = {
        }
    ) {
        val commandId = existing?.getInt("id") ?: record.id
        val operation = if (existing == null) "create" else "mutate"
        var pending = commands.load(lease, commandId, operation); var busy = false
        val inputs = mutableListOf<View>()

        fun field(parent: LinearLayout, title: String, value: String = "", multiline: Boolean = false): EditText {
            parent.addView(label(title)); val input = EditText(context).apply {
                setText(value); hint = title; contentDescription = title; setSingleLine(!multiline); if (multiline)minLines = 3; textSize = Mango9CrmText.value
                setPadding(dp(12), dp(12), dp(12), dp(12)); background = android.graphics.drawable.GradientDrawable().apply{
                    setColor(android.graphics.Color.WHITE); setStroke(dp(1), android.graphics.Color.rgb(224, 229, 239)); cornerRadius = dp(12).toFloat()
                }; setTextColor(context.getColor(R.color.mango9_calendar_ink)); hyphenationFrequency = 0
            }
            parent.addView(input, LinearLayout.LayoutParams(-1, -2)); inputs.add(input); return input
        }

        fun select(parent: LinearLayout, title: String, values: List<String>, index: Int = 0): Spinner {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            }
            row.addView(
                label(title).apply {
                textSize = Mango9CrmText.label; setSingleLine()
            },
                LinearLayout.LayoutParams(dp(88), -2)
            )
            val picker = Spinner(context).apply {
                contentDescription = title; adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, values)
                setSelection(index.coerceAtLeast(0)); inputs.add(this)
            }
            row.addView(picker, LinearLayout.LayoutParams(0, dp(48), 1f)); parent.addView(row, LinearLayout.LayoutParams(-1, -2))
            return picker
        }

        fun date(parent: LinearLayout, title: String, time: Boolean = false, initial: String = ""): EditText {
            val input = field(parent, title, initial); input.isFocusable = false
            input.setOnClickListener {
                val today = LocalDate.now(); DatePickerDialog(context, {_, y, m, d ->
                    val date = LocalDate.of(y, m + 1, d)
                if (time)TimePickerDialog(context, {_, h, min -> input.setText(date.atTime(h, min).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString())}, 9, 0, false).show() else input.setText(date.toString())
                }, today.year, today.monthValue - 1, today.dayOfMonth).show()
            }
            parent.addView(button("Clear $title") {input.setText("")}.also {inputs.add(it)}); return input
        }
        if (existing == null) body.addView(label("Lead or Client"))
        body.addView(label(record.name).apply {setTypeface(typeface, Typeface.BOLD)})
        val title = field(body, "Title (required)"); val stages = meta.stages.filter {
            !it.retired || it.id == existing?.optString("stage")
        }
        val stage = select(body, "Status", stages.map {it.name}, stages.indexOfFirst {it.default})
        val amount = field(body, "Value (optional)").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val currency = select(body, "Currency", Mango9OpportunityDraft.currencies)
        val close = date(body, "Expected close date")
        val more = column().apply {setPadding(0, 0, 0, 0); visibility = View.GONE}
        body.addView(
            button("More details") {
            more.visibility = if (more.visibility == View.GONE)View.VISIBLE else View.GONE
        }
        ); body.addView(more)
        val owners = meta.owners.toMutableList().apply {if (none {it.first == meta.userId})add(0, meta.userId to "Me")}
        val owner = if (existing == null && meta.manager && meta.permissions.optBoolean("assign")){
            select(
                more, "Owner",
                owners.map {
            it.second
        },
            owners.indexOfFirst {it.first == meta.userId}
            )
        } else {
            null
        }
        val manual = CheckBox(context).apply {
            text = "Set probability manually"; isSingleLine = true; more.addView(this); inputs.add(this)
        }
        val probability = field(more, "Probability (%)", "0").apply {inputType = InputType.TYPE_CLASS_NUMBER; isEnabled = false}
        manual.setOnCheckedChangeListener {_, checked -> probability.isEnabled = checked && pending == null}
        val source = field(more, "Source"); val description = field(more, "Description", multiline = true)
        var nextAction = existing?.let { Mango9OpportunityPresentation.text(it, "next_action") }.orEmpty()
        var nextDue = existing?.let {
            Mango9OpportunityPresentation.iso(Mango9OpportunityPresentation.text(it, "next_due_at"))
        }.orEmpty()
        more.addView(
            label("Use Follow-up on the Opportunity to schedule or change its calendar event.").apply {
            textSize = Mango9CrmText.label
        }
        )
        val outcome = column().apply {setPadding(0, 0, 0, 0)}; body.addView(outcome)
        val actualDate = date(outcome, "Actual close date", initial = LocalDate.now().toString()); val actualValue = field(outcome, "Actual value (optional)"); val loss = field(outcome, "Loss reason")

        fun stageChanged() {
            val selected = stages.getOrNull(stage.selectedItemPosition); outcome.visibility = if (selected?.classification == "open")View.GONE else View.VISIBLE; if (!manual.isChecked)probability.setText((selected?.probability ?: 0).toString()); loss.isEnabled = selected?.classification == "lost" && pending == null
        }
        stage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {override fun onItemSelected(a: AdapterView<*>?, v: View?, position: Int, id: Long) {
            stageChanged()
        }

        override fun onNothingSelected(a: AdapterView<*>?) {}}
        stageChanged()
        val errors = column().apply {setPadding(0, 0, 0, 0)}; body.addView(errors, 0)
        val save = button(if (pending == null)"Save Opportunity" else "Retry saved Opportunity") {
            }; if (p.addSave != null) p.addSave.invoke(save) else body.addView(save)
        val restoration = pending ?: restoredDraft?.let {
            runCatching {
                JSONObject(it)
            }.getOrNull()
        } ?: existing?.let { JSONObject().put("values", it).put("ownerId", it.optInt("owner_id")) }
        if (restoration != null){
            val saved = restoration.getJSONObject("values")

            fun savedText(key: String) = if (saved.isNull(key)) "" else saved.optString(key)
            title.setText(savedText("title")); amount.setText(savedText("value")); close.setText(savedText("close_date"))
            stage.setSelection(stages.indexOfFirst {it.id == savedText("stage")}.coerceAtLeast(0))
            currency.setSelection(Mango9OpportunityDraft.currencies.indexOf(savedText("currency")).coerceAtLeast(0))
            owner?.setSelection(owners.indexOfFirst {it.first == restoration.optInt("ownerId")}.coerceAtLeast(0))
            manual.isChecked = savedText("probability_mode") == "manual"; probability.setText(savedText("probability"))
            source.setText(savedText("source")); description.setText(savedText("description")); nextAction = savedText("next_action"); nextDue = Mango9OpportunityPresentation.iso(savedText("next_due_at"))
            actualValue.setText(savedText("actual_value")); actualDate.setText(savedText("actual_close_date")); loss.setText(savedText("loss_reason"))
            if (pending != null) {
                inputs.forEach {
                    it.isEnabled = false
                }; errors.addView(label("A previous save is unconfirmed. Retry its original details to avoid a duplicate."))
            }
        }
        snapshotProvider = {
            val values = JSONObject().put("title", title.text.toString()).put("stage", stages.getOrNull(stage.selectedItemPosition)?.id.orEmpty())
                .put("value", amount.text.toString()).put("currency", Mango9OpportunityDraft.currencies.getOrNull(currency.selectedItemPosition).orEmpty())
                .put("close_date", close.text.toString()).put("probability", probability.text.toString()).put("probability_mode", if (manual.isChecked) "manual" else "stage")
                .put("source", source.text.toString()).put("description", description.text.toString()).put("next_action", nextAction).put("next_due_at", nextDue)
                .put("actual_value", actualValue.text.toString()).put("actual_close_date", actualDate.text.toString()).put("loss_reason", loss.text.toString())
            Mango9OpportunityScreen(
                record, lease, if (existing == null) "create" else "edit",
                JSONObject().put(
                    "ownerId",
                    owner?.let {
                owners.getOrNull(it.selectedItemPosition)?.first
            } ?: meta.userId
                ).put("values", values).toString(),
                    existing?.toString()
            )
        }
        val baseline = snapshotProvider?.invoke()?.draft
        p.protect?.invoke { snapshotProvider?.invoke()?.draft != baseline }
        save.setOnClickListener {
            if (busy || !current(lease))return@setOnClickListener; busy = true; save.isEnabled = false; p.setCancelable(false); errors.removeAllViews()
            scope.launch {
                try {
                val draft = Mango9OpportunityDraft(
                    title.text.toString(), stages[stage.selectedItemPosition].id, amount.text.toString(), Mango9OpportunityDraft.currencies[currency.selectedItemPosition], close.text.toString(),
                    owner?.let {
                        owners[it.selectedItemPosition].first
                    } ?: meta.userId,
                        probability.text.toString().toIntOrNull() ?: -1, manual.isChecked, source.text.toString(), description.text.toString(), nextAction, nextDue, actualValue.text.toString(), actualDate.text.toString(), loss.text.toString()
                )
                val command = pending ?: if (existing == null) draft.command(meta, record.id) else JSONObject().put("action", "update").put("requestId", UUID.randomUUID().toString()).put("revision", existing.getString("revision")).put("values", draft.values(meta, existing.getString("stage")))
                commands.save(lease, commandId, operation, command); pending = command; inputs.forEach {it.isEnabled = false}
                val action = if (existing == null) "create" else command.getString("action")
                val payload = JSONObject(command.toString()).apply { remove("action") }
                val receipt = api.request(lease, if (existing == null) emptyList() else listOf(commandId.toString(), action), body = payload)
                if (existing == null) {
                    val createdId = Mango9Opportunities.creationId(receipt)
                    if (!receipt.optBoolean("saved")) {
                        val found = api.request(lease, listOf(createdId.toString()))
                        if (found.optInt("id") != createdId || !Mango9Opportunities.duplicateMatches(found, payload)) {
                            throw Mango9OpportunityFailure(409, "An Opportunity with this title already exists. Your draft is preserved; review it or use a different title.")
                        }
                    }
                } else {
                    require(receipt.getInt("id") == commandId && receipt.getString("action") == action && receipt.getBoolean("saved"))
                }
                commands.save(lease, commandId, operation, null); pending = null
                if (current(lease)) { p.dismiss(); updated(); changed() }
            }catch (e: Exception){
                if (e is CancellationException)throw e
                if (Mango9OpportunityCommands.definite(e)){commands.save(lease, commandId, operation, null); pending = null}
                if (current(lease) && p.isShowing){
                    error(errors, e)
                    (body.parent as? ScrollView)?.post { (body.parent as? ScrollView)?.smoothScrollTo(0, 0) }
                    if (existing != null && e is Mango9OpportunityFailure && e.status == 409) {
                        inputs.forEach { it.isEnabled = false }; save.visibility = View.GONE
                        errors.addView(button("Reload latest Opportunity") { p.dismiss(); updated() })
                    }
                    inputs.forEach {
                        it.isEnabled = pending == null
                    }; probability.isEnabled = manual.isChecked && pending == null; save.text = if (pending == null)"Save Opportunity" else "Retry saved Opportunity"
                }
            }finally {busy = false; save.isEnabled = true; p.setCancelable(true)}
            }
        }
    }
}
