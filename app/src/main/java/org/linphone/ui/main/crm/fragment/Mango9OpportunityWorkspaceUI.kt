// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.ui.main.crm.fragment

import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.linphone.R
import org.linphone.mango9.*
import org.linphone.mango9.Mango9OpportunityPresentation as P
import java.util.UUID

/** Server-scoped, paginated workspace. Every child stays inside the native CRM header/footer. */
internal class Mango9OpportunityWorkspaceUI(
    private val context: Context, private val scope: CoroutineScope, private val lease: Mango9SessionLease,
    private val page: (String, LinearLayout) -> Mango9FullPage,
    private val openContact: (Mango9AppointmentContact) -> Unit,
    private val create: (Mango9CrmRecord) -> Unit,
    private val edit: (JSONObject, Mango9OpportunityMetadata, () -> Unit) -> Unit,
    private val changed: () -> Unit,
    private val beforeChat: () -> Unit = {},
    private val screenChanged: (Int?) -> Unit = {}
) {
    private var activeQuick: androidx.appcompat.app.AlertDialog? = null

    private fun quick(title: String, body: LinearLayout): androidx.appcompat.app.AlertDialog {
        activeQuick?.dismiss()
        return MaterialAlertDialogBuilder(context).setTitle(title).setView(
            ScrollView(context).apply {
            addView(body)
        }
        ).setNegativeButton("Close", null).create().also { dialog ->
            activeQuick = dialog; dialog.setOnDismissListener { if (activeQuick === dialog) activeQuick = null }; dialog.show()
        }
    }

    private val api = Mango9Opportunities(context)
    private val sessions = Mango9SessionStore(context)
    private val commands = Mango9OpportunityCommands(context)
    private var refreshList: () -> Unit = {}
    private val blue = Color.rgb(66, 83, 199)
    private val ink = Color.rgb(32, 40, 55)
    private val muted = Color.rgb(96, 112, 135)
    private val green = Color.rgb(25, 130, 76)

    private fun current() = sessions.currentSession(lease) != null

    private fun dp(n: Int) = (context.resources.displayMetrics.density * n).toInt()

    private fun bg(color: Int, radius: Int = 15, border: Boolean = false) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); if (border) setStroke(dp(1), Color.rgb(227, 231, 238))
    }

    private fun column(pad: Int = 0) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    }

    private fun line() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
    }

    private fun text(value: String, size: Float = Mango9CrmText.value, color: Int = ink, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color); hyphenationFrequency = 0
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun add(parent: LinearLayout, child: View, gap: Int = 12) {
        parent.addView(child, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(gap) })
    }

    private fun card() = column(15).apply { background = bg(Color.WHITE, border = true) }

    private fun action(label: String, click: () -> Unit) = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
        text = label; contentDescription = label; isAllCaps = false; textSize = 14f; minHeight = dp(44); setTextColor(blue); setOnClickListener {
            if (current()) click()
        }
    }

    private fun icon(drawable: Int, label: String, color: Int = blue, click: () -> Unit) = ImageButton(context).apply {
        setImageResource(drawable); imageTintList = ColorStateList.valueOf(color); background = bg(Color.WHITE, 24)
        contentDescription = label; setPadding(dp(10), dp(10), dp(10), dp(10)); setOnClickListener { if (current()) click() }
    }

    private fun fact(parent: LinearLayout, label: String, value: String, secondary: String = "") {
        parent.gravity = Gravity.TOP
        val box = column(); box.addView(text(label, Mango9CrmText.label, muted))
        val main = value.ifBlank { "Not set" }
        val content = android.text.SpannableString(main + if (secondary.isBlank()) "" else " $secondary")
        if (secondary.isNotBlank()) {
            content.setSpan(android.text.style.AbsoluteSizeSpan(11, true), main.length, content.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            content.setSpan(android.text.style.ForegroundColorSpan(muted), main.length, content.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        box.addView(text(main, Mango9CrmText.value).apply { text = content })
        parent.addView(box, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun failure(parent: LinearLayout, error: Exception, retry: () -> Unit) {
        add(parent, text(error.message ?: "The request could not be completed.", color = Color.rgb(170, 45, 65)))
        add(parent, action("Retry", retry))
    }

    private fun stage(row: JSONObject, meta: Mango9OpportunityMetadata) = text(
        "$ " + (meta.stages.firstOrNull { it.id == row.optString("stage") }?.name ?: row.optString("stage")), 12f, blue, true
    ).apply {
        val title = android.text.SpannableString(text)
        title.setSpan(android.text.style.ForegroundColorSpan(green), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text = title; background = bg(Color.rgb(236, 239, 253), 7); setPadding(dp(9), dp(5), dp(9), dp(5))
    }

    fun list(contact: Mango9CrmRecord? = null) {
        screenChanged(null)
        val body = column(14); val p = page("Opportunities", body)
        val addButton = icon(R.drawable.plus_circle, "Add Opportunity") {
            if (contact != null) create(contact) else contactPicker()
        }.apply { visibility = View.GONE }
        p.headerAction(addButton)
        val metrics = column(); val rows = column(); val status = column()
        val search = EditText(context).apply {
            hint = "Search Opportunities or contacts"; contentDescription = "Search Opportunities"; textSize = 14f; setSingleLine(); background = bg(Color.WHITE, 12, true); setPadding(dp(12), dp(10), dp(12), dp(10)); setTextColor(ink)
        }
        val filterLine = line()
        var filterDialog: androidx.appcompat.app.AlertDialog? = null
        p.whenDismissed { filterDialog?.dismiss() }
        if (contact != null) add(body, text(contact.name, 17f, bold = true))
        add(body, metrics); add(body, search); add(body, filterLine); add(body, status); add(body, rows)
        val displayedIds = mutableSetOf<Int>()
        val badgeViews = mutableMapOf<Int, TextView>()
        val badgeJob = scope.launch {
            Mango9CrmUnread.opportunities.collect { unread ->
                badgeViews.forEach { (id, dot) -> dot.visibility = if (unread.containsKey(id)) View.VISIBLE else View.GONE }
            }
        }
        p.whenDismissed { badgeJob.cancel(); badgeViews.clear() }
        var metadata: Mango9OpportunityMetadata? = null; var next = 1; var seq = 0; var queryJob: Job? = null
        val query = mutableMapOf("sort" to "updated_at", "direction" to "desc", "limit" to "25")
        if (contact != null) query["contactId"] = contact.id.toString()

        val choiceButtons = mutableMapOf<String, Button>()

        fun choices(label: String, options: () -> List<Pair<String, String>>, key: String): Button {
            lateinit var button: Button
            button = action(label) {
                val values = options()
                MaterialAlertDialogBuilder(context).setTitle(label).setItems(values.map { it.second }.toTypedArray()) { _, i ->
                    query[key] = values[i].first; button.text = values[i].second
                    queryJob?.cancel(); search.setText(search.text.toString())
                }.show()
            }
            choiceButtons[key] = button
            return button
        }

        fun load(reset: Boolean) {
            val version = ++seq
            scope.launch {
                if (reset) { status.removeAllViews(); add(status, text("Loading…", color = muted)) }
                try {
                    val meta = if (reset || metadata == null) Mango9OpportunityMetadata(api.request(lease, listOf("metadata"))) else metadata!!
                    if (!current() || !p.isShowing || version != seq) return@launch
                    metadata = meta
                    query["owner"] = meta.visibleOwnerFilter(query["owner"].orEmpty())
                    query["createdBy"] = meta.visibleOwnerFilter(query["createdBy"].orEmpty())
                    choiceButtons["createdBy"]?.text = meta.owners.firstOrNull {
                        it.first.toString() == query["createdBy"]
                    }?.second ?: "Created by"
                    choiceButtons["filters"]?.text = "Filters" + Mango9OpportunityFilters.count(query).let { if (it > 0) " ($it)" else "" }
                    val raw = api.request(lease, query = query + mapOf("q" to search.text.toString(), "page" to (if (reset) 1 else next).toString()))
                    val pagination = raw.getJSONObject("pagination"); val items = raw.getJSONArray("items").opportunityObjects()
                    if (!current() || !p.isShowing || version != seq) return@launch
                    addButton.visibility = if (meta.canCreate) View.VISIBLE else View.GONE
                    status.removeAllViews(); add(status, text("${pagination.getInt("total")} Opportunities · Latest updated", 12f, muted))
                    if (reset) {
                        rows.removeAllViews(); displayedIds.clear(); badgeViews.clear(); metrics.removeAllViews()
                        val summary = raw.getJSONArray("summary").opportunityObjects()

                        fun count(key: String) = summary.sumOf { it.optInt(key) }

                        fun amounts(key: String) = summary.joinToString("\n") {
                            P.money(P.text(it, key), it.optString("currency")) + " " + it.optString("currency")
                        }.ifBlank { "—" }
                        for (pair in listOf(listOf("Open opportunities" to count("open").toString(), "Open value" to amounts("open_value")), listOf("Weighted value" to amounts("weighted_value"), "Won / Lost" to "${count("won")} / ${count("lost")}"))) {
                            val row = line(); pair.forEach { (label, value) ->
                                val c = card(); c.addView(text(label, Mango9CrmText.label, muted)); c.addView(text(value, 18f, bold = true)); row.addView(
                                    c,
                                    LinearLayout.LayoutParams(0, -2, 1f).apply {
                                marginEnd = dp(5)
                            }
                                )
                            }; add(metrics, row, 6)
                        }
                        if (count("unknown_value") > 0) add(metrics, text("${count("unknown_value")} without an entered value", 11f, muted), 0)
                    }
                    rows.findViewWithTag<View>("more")?.let(rows::removeView)
                    items.filter { displayedIds.add(it.getInt("id")) }.forEach { item ->
                        val c = card(); val top = line(); top.addView(stage(item, meta))
                        val dot = text("●", 11f, blue).apply {
                            contentDescription = "New Opportunity"; visibility = if (Mango9CrmUnread.opportunities.value.containsKey(item.getInt("id"))) View.VISIBLE else View.GONE
                        }; top.addView(dot)
                        badgeViews[item.getInt("id")] = dot
                        top.addView(
                            text(P.money(P.text(item, "value"), item.optString("currency")) + " " + item.optString("currency"), 14f, bold = true).apply {
                            gravity = Gravity.END
                        },
                            LinearLayout.LayoutParams(0, -2, 1f)
                        ); add(c, top, 8)
                        add(c, text(item.optString("title"), 17f, bold = true), 5); add(c, text(P.contact(item)?.optString("name") ?: "Restricted contact", 12f, muted), 8)
                        val foot = line(); fact(foot, "Owner", P.text(item, "owner_name")); fact(foot, "Expected close", P.date(P.text(item, "close_date"))); c.addView(foot)
                        c.contentDescription = "Open Opportunity ${item.optString("title")}"; c.isFocusable = true; c.setOnClickListener {
                            detail(item.getInt("id"))
                        }; add(rows, c)
                    }
                    if (reset && items.isEmpty()) add(rows, text("No Opportunities match these filters.", color = muted))
                    next = pagination.getInt("page") + 1
                    if (pagination.getBoolean("hasMore"))add(rows, action("Load more"){load(false)}.apply{tag = "more"})
                } catch (e: Exception) {
                    if (e is CancellationException)throw e; if (current() && p.isShowing && version == seq){
                        if (reset) { rows.removeAllViews(); badgeViews.clear(); displayedIds.clear(); metrics.removeAllViews() }
                        status.removeAllViews(); failure(status, e){load(reset)}
                    }
                }
            }
        }
        filterLine.addView(
            choices("All stages", {
            listOf("" to "All stages") + (
                metadata?.stages?.map{
                it.id to it.name
            } ?: emptyList()
            )
        }, "stage"),
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        filterLine.addView(
            choices("Created by", {
            listOf("" to "Anyone") + (metadata?.owners?.map { it.first.toString() to it.second } ?: emptyList())
        }, "createdBy"),
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        val configuration = action("Filters") {
            val draft = query.filterKeys { it in Mango9OpportunityFilters.keys }.toMutableMap()
            val content = column(16)
            val error = text("", 13f, Color.rgb(170, 45, 65))
            lateinit var render: () -> Unit

            fun heading(title: String) { add(content, text(title, 15f, ink, true), 6) }

            fun row(label: String, control: View) {
                val row = line(); row.addView(text(label, 14f), LinearLayout.LayoutParams(dp(96), -2))
                row.addView(control, LinearLayout.LayoutParams(0, -2, 1f)); add(content, row, 5)
            }

            fun choice(label: String, key: String, options: List<Pair<String, String>>) {
                row(
                    label,
                    action(options.firstOrNull { it.first == draft[key].orEmpty() }?.second ?: options.first().second) {
                    MaterialAlertDialogBuilder(context).setTitle(label).setItems(options.map { it.second }.toTypedArray()) { _, i ->
                        draft[key] = options[i].first; render()
                    }.setNegativeButton("Cancel", null).show()
                }.apply { contentDescription = "Filter $key" }
                )
            }

            fun date(label: String, key: String) {
                val box = line()
                box.addView(
                    action(draft[key]?.takeIf { it.isNotBlank() } ?: "Any date") {
                    val day = runCatching { java.time.LocalDate.parse(draft[key]) }.getOrDefault(java.time.LocalDate.now())
                    android.app.DatePickerDialog(context, { _, y, m, d -> draft[key] = java.time.LocalDate.of(y, m + 1, d).toString(); render() }, day.year, day.monthValue - 1, day.dayOfMonth).show()
                }.apply { contentDescription = "Filter $key" },
                    LinearLayout.LayoutParams(0, -2, 1f)
                )
                if (!draft[key].isNullOrBlank()) {
                    box.addView(
                        action("×") {
                    draft[key] = ""; render()
                }.apply { contentDescription = "Clear $key" },
                    LinearLayout.LayoutParams(dp(44), -2)
                    )
                }
                row(label, box)
            }

            fun amount(label: String, key: String, hintText: String) {
                row(
                    label,
                    EditText(context).apply {
                    contentDescription = "$label amount"; hint = hintText; setSingleLine(); textSize = 14f; setTextColor(ink)
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                    setText(draft[key].orEmpty()); doAfterTextChanged { draft[key] = it.toString(); error.text = "" }
                }
                )
            }
            render = {
                content.removeAllViews()
                heading("People")
                val people = metadata?.owners?.map { it.first.toString() to it.second } ?: emptyList()
                choice("Created by", "createdBy", listOf("" to "Anyone") + people)
                choice("Owner", "owner", listOf("" to "All owners") + people)
                choice("Sharing", "shared", listOf("" to "All sharing", "with_me" to "Shared with me", "by_me" to "Shared by me"))
                heading("Due dates")
                choice("Follow-up", "followup", listOf("" to "All follow-ups", "overdue" to "Overdue", "missing" to "Next step needed", "stale" to "No activity for 14+ days"))
                add(content, text("Expected close date", 12f, muted), 4); date("From", "closeFrom"); date("To", "closeTo")
                heading("Amount"); amount("Minimum", "minValue", "No minimum"); amount("Maximum", "maxValue", "No maximum")
                choice("Currency", "currency", listOf("" to "All currencies") + Mango9OpportunityDraft.currencies.map { it to it })
                add(content, text("Amounts use each Opportunity’s currency.", 12f, muted))
                heading("Created date"); date("From", "createdFrom"); date("To", "createdTo")
                heading("More")
                choice(
                    "Source", "source",
                    listOf("" to "All sources") + (
                        metadata?.raw?.optJSONArray("sources")?.let { a ->
                            (0 until a.length()).map {
                    a.getString(it) to a.getString(it)
                }
                } ?: emptyList()
                    )
                )
                row(
                    "Archived",
                    Switch(context).apply {
                    contentDescription = "Show archived Opportunities"; isChecked = draft["archived"] == "1"; setOnCheckedChangeListener { _, yes -> draft["archived"] = if (yes) "1" else "" }
                }
                )
                add(content, error)
            }
            render()
            val scroll = ScrollView(context).apply { addView(content) }
            val dialog = MaterialAlertDialogBuilder(context).setTitle("Opportunity filters").setView(scroll)
                .setNegativeButton("Cancel", null).setNeutralButton("Reset", null).setPositiveButton("Apply", null).create()
            filterDialog = dialog
            dialog.setOnShowListener {
                scroll.layoutParams = scroll.layoutParams.apply {
                    height = (context.resources.displayMetrics.heightPixels * 0.62).toInt()
                }
                dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener { draft.clear(); error.text = ""; render() }
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                    try {
                        val validated = Mango9OpportunityFilters.query(draft)
                        Mango9OpportunityFilters.keys.forEach { query.remove(it) }; query.putAll(validated)
                        dialog.dismiss(); queryJob?.cancel(); search.setText(search.text.toString())
                    } catch (e: IllegalArgumentException) { error.text = e.message; scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } }
                }
            }
            dialog.setOnDismissListener { if (filterDialog === dialog) filterDialog = null }; dialog.show()
        }.apply { contentDescription = "More filters" }
        choiceButtons["filters"] = configuration
        filterLine.addView(configuration)
        refreshList = { if (p.isShowing) load(true) }
        search.doAfterTextChanged { ++seq; queryJob?.cancel(); queryJob = scope.launch{delay(250); load(true)} }
        load(true)
    }

    private fun contactPicker() {
        val body = column(14); val p = page("Choose contact", body); val search = EditText(context).apply{
            hint = "Search leads or clients"; setSingleLine(); setTextColor(ink)
        }; add(body, search); val rows = column(); add(body, rows)
        var job: Job? = null; var version = 0; var next = 1

        fun load(append: Boolean = false) {
            val seq = ++version; scope.launch{
                try {
                    val data = api.request(lease, listOf("contacts"), mapOf("q" to search.text.toString(), "page" to (if (append)next else 1).toString())); if (!current() || !p.isShowing || seq != version)return@launch
            if (!append)rows.removeAllViews(); rows.findViewWithTag<View>("more")?.let(rows::removeView)
            data.getJSONArray("items").opportunityObjects().forEach{r ->
                add(
                    rows,
                    action(r.getString("name")){
                create(Mango9CrmRecord(r.getInt("id"), r.optInt("owner_id"), r.optString("owner_name"), r.getString("name"), P.text(r, "phone"), P.text(r, "email"), "", P.text(r, "source"), ""))
            }
                )
            }
            next = data.getJSONObject("pagination").getInt("page") + 1; if (data.getJSONObject("pagination").getBoolean("hasMore")){
                add(
                    rows,
                    action("Load more"){
                load(true)
            }.apply{tag = "more"}
                )
            }
        }catch (e: Exception){
            if (e is CancellationException)throw e; if (current() && p.isShowing && seq == version){
                rows.removeAllViews(); failure(rows, e){load()}
            }
        }
            }
        }
        search.doAfterTextChanged{job?.cancel(); job = scope.launch{delay(250); load()}}; load()
    }

    fun detail(id: Int) {
        screenChanged(id)
        val body = column(14); val p = page("Opportunity", body).whenDismissed {
            activeQuick?.dismiss(); screenChanged(null); refreshList()
        }; var row: JSONObject? = null; var metadata: Mango9OpportunityMetadata? = null; var busy = false; var selected = "Details"; var tabBody = column(); var version = 0

        fun permitted(action: String) = !busy && commands.load(lease, id, "mutate") == null && row?.let{r ->
            metadata?.let{
            P.can(r, it, action)
        }
        } == true
        lateinit var load: () -> Unit

        fun mutate(action: String, fields: JSONObject = JSONObject(), done: () -> Unit = {}) {
            if (busy || !current())return
            val old = commands.load(lease, id, "mutate"); if (old == null && !permitted(action))return
            busy = true; p.setCancelable(false)
            scope.launch {
                try {
                val fields = JSONObject(fields.toString())
                if (old == null && action == "share") {
                    val latest = Mango9OpportunityMetadata(api.request(lease, listOf("metadata")))
                    if (!current() || !p.isShowing) return@launch
                    metadata = latest
                    require(latest.permissions.optBoolean("share")) { "Sharing is no longer available. Reload this Opportunity." }
                    val selected = fields.getJSONArray("userIds").let { ids -> (0 until ids.length()).map(ids::getInt) }
                    fields.put("userIds", JSONArray(latest.visibleRecipients(selected, row!!.optInt("owner_id"))))
                }
                val command = old ?: JSONObject(fields.toString()).put("action", action).put("requestId", UUID.randomUUID().toString()).put("revision", row!!.getString("revision"))
                commands.save(lease, id, "mutate", command); val originalAction = command.getString("action"); val payload = JSONObject(command.toString()).apply{
                    remove("action")
                }
                val receipt = api.request(lease, listOf(id.toString(), originalAction), body = payload)
                require(receipt.getInt("id") == id && receipt.getString("action") == originalAction && receipt.getBoolean("saved"))
                commands.save(lease, id, "mutate", null); if (current() && p.isShowing){changed(); done(); load()}
            }catch (e: Exception){
                if (e is CancellationException)throw e; if (Mango9OpportunityCommands.definite(e))commands.save(lease, id, "mutate", null); if (current() && p.isShowing){
                    if (activeQuick?.isShowing == true) MaterialAlertDialogBuilder(context).setTitle("Change not confirmed").setMessage(e.message ?: "Please retry.").setPositiveButton("OK", null).show()
                    failure(body, e){mutate(action, fields, done)}
                }
            } finally {busy = false; p.setCancelable(true)}
            }
        }

        fun history(notes: Boolean) {
            tabBody.removeAllViews(); val output = tabBody; var nextPage = 1; var cursor = ""; var source = if (metadata?.raw?.optInt("activityVersion") == 1)"opportunity" else "fast"; var seq = 0; var job: Job? = null
            val entries = column(); val sources = line(); add(output, sources)
            if (notes && permitted("note")){
                val input = EditText(context).apply{
                    hint = "Add a note"; setTextColor(ink); maxLines = 5; filters = arrayOf(android.text.InputFilter.LengthFilter(5000))
                }; add(output, input); add(
                    output,
                    action("Add note"){
                    if (input.text.isNotBlank())mutate("note", JSONObject().put("text", input.text.toString()))
                }
                )
            }
            add(output, entries)

            fun fetch(reset: Boolean) {
                val request = ++seq; job?.cancel(); job = scope.launch{
                    try {
                val query = if (notes)mapOf("kind" to "notes", "page" to (if (reset)1 else nextPage).toString()) else mapOf("kind" to "timeline", "source" to source, "cursor" to if (reset)"" else cursor)
                val data = api.request(lease, listOf(id.toString(), "history"), query)
                if (!current() || !p.isShowing || tabBody !== output || request != seq)return@launch
                if (reset)entries.removeAllViews(); entries.findViewWithTag<View>("more")?.let(entries::removeView)
                data.getJSONArray("items").opportunityObjects().forEach{r ->
                    val c = card(); val detail = r.optJSONObject("details"); val contactData = r.optJSONObject("data"); add(
                        c,
                        text(
                            detail?.optString("text")?.ifBlank{
                    null
                } ?: contactData?.optString("text")?.ifBlank{
                    null
                } ?: contactData?.optString("title")?.ifBlank{
                    null
                } ?: P.text(r, "action").replace('_', ' ').ifBlank{
                    "Activity"
                }
                        ),
                    8
                    ); c.addView(text(P.date(P.text(r, "created_at").ifBlank{P.text(r, "at")}, true), 11f, muted)); add(entries, c)
                }
                if (entries.childCount == 0)add(entries, text(if (notes)"No notes yet." else "No activity yet.", color = muted))
                if (notes){
                    val pagination = data.getJSONObject("pagination"); nextPage = pagination.getInt("page") + 1; if (pagination.getBoolean("hasMore")){
                        add(
                            entries,
                            action("Load more"){
                        fetch(false)
                    }.apply{
                        tag = "more"
                    }
                        )
                    }
                } else {
                    cursor = P.text(data, "next"); if (cursor.isNotBlank()){
                        add(
                            entries,
                            action("Load more"){
                        fetch(false)
                    }.apply{
                        tag = "more"
                    }
                        )
                    }; if (reset){
                        sources.removeAllViews(); val available = data.optJSONArray("sources"); if (available != null){
                            sources.addView(
                                action("Activity source"){
                                val names = (0 until available.length()).map{
                                    available.getString(it)
                                }; MaterialAlertDialogBuilder(context).setItems(names.toTypedArray()){_, i -> source = names[i]; fetch(true)}.show()
                            }
                            )
                        }; data.optJSONArray("warnings")?.let{w -> for (i in 0 until w.length())add(entries, text(w.getString(i), 12f, muted))}
                    }
                }
            }catch (e: Exception){
                if (e is CancellationException)throw e; if (current() && p.isShowing && tabBody === output && request == seq){
                    failure(entries, e){
                    fetch(reset)
                }
                }
            }
                }
            }
            fetch(true)
        }

        fun renderSharing(r: JSONObject, meta: Mango9OpportunityMetadata) {
            val content = column(14); val child = quick("Sharing", content); p.whenDismissed {
                child.dismiss()
            }
            val prior = r.optJSONArray("shares")?.opportunityObjects()?.filter {
                it.optInt("shared_by") == meta.userId
            }?.map { it.getInt("id") }.orEmpty()
            val ids = meta.visibleRecipients(prior, r.optInt("owner_id")).toMutableSet()
            add(content, text("Shared by you", 17f, bold = true))
            if (meta.owners.none {
                it.first != r.optInt("owner_id")
            }
            ) {
                add(content, text("No people are available to share with.", color = muted))
            }
            meta.owners.filter{
                it.first != r.optInt("owner_id")
            }.forEach{(uid, name) ->
                add(
                    content,
                    CheckBox(context).apply{
                text = name; setTextColor(ink); isChecked = uid in ids; setOnCheckedChangeListener{_, on -> if (on)ids.add(uid)else ids.remove(uid)}
            }
                )
            }
            add(
                content,
                action("Save sharing"){
                mutate("share", JSONObject().put("userIds", JSONArray(ids.toList()))){child.dismiss()}
            }
            )
        }

        fun sharing(r: JSONObject, meta: Mango9OpportunityMetadata) {
            if (busy) return
            busy = true
            scope.launch {
                try {
                    val latest = Mango9OpportunityMetadata(api.request(lease, listOf("metadata")))
                    if (!current() || !p.isShowing) return@launch
                    metadata = latest
                    require(latest.permissions.optBoolean("share")) { "Sharing is no longer available. Reload this Opportunity." }
                    renderSharing(r, latest)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (current() && p.isShowing) failure(body, e) { sharing(r, meta) }
                } finally { busy = false }
            }
        }

        fun renderTab(r: JSONObject, meta: Mango9OpportunityMetadata) {
            tabBody.removeAllViews(); when (selected){
            "Notes" -> history(true)
            "Activity" -> {
                history(false)
                if (meta.raw.optInt("activityVersion") == 1 && r.optBoolean("canOpenContact") && r.optInt("contact_id") > 0) {
                    tabBody.addView(Mango9CrmActivityUI(context, scope, lease, page, ::detail, beforeChat).section(r.getInt("contact_id")), 0)
                }
            }
            "Follow-up" -> {
                val c = card(); add(c, text("Next action", 12f, muted)); add(
                    c,
                    text(
                        P.text(r, "next_action").ifBlank{
                    "No next action"
                }
                    ),
                    8
                ); add(c, text(P.date(P.text(r, "next_due_at"), true), 12f, muted)); if (permitted("update")){
                    add(
                        c,
                        action("Edit follow-up"){
                    Mango9OpportunityFollowupUI(context, scope, lease, page).open(r) { changed(); load() }
                }
                    )
                }; val activity = r.optJSONObject("activity"); add(c, text("Linked task or appointment", 12f, muted)); add(c, text(if (activity?.optBoolean("restricted") == true)"Restricted activity" else activity?.optString("title") ?: "None linked")); if (P.contact(r) != null && permitted("activity")){
                    add(
                        c,
                        action("Link an existing activity"){
                    activities(id){key, child ->
                        mutate("activity", JSONObject().put("activityKey", key ?: JSONObject.NULL)){
                        child.dismiss()
                    }
                    }
                }
                    )
                }; add(tabBody, c)
            }
            else -> {
                val c = card(); val facts = line(); fact(facts, "Source", P.text(r, "source")); fact(facts, "Contact group", P.text(r, "group_name")); add(c, facts); add(c, text("Description", 12f, muted), 4); add(
                    c,
                    text(
                        P.text(r, "description").ifBlank{
                    "No description"
                }
                    ),
                    16
                ); add(c, text("Owner", Mango9CrmText.label, muted)); add(c, text(P.text(r, "owner_name")))
                if (r.optBoolean("fromSharedLead"))add(c, text("Lead shared by " + P.text(r, "sharedLeadBy"), Mango9CrmText.label, muted))
                add(c, Mango9CrmActivityUI(context, scope, lease, page, ::detail, beforeChat).documents(r.optJSONArray("documents")))
                val shared = r.optJSONArray("shares")?.opportunityObjects().orEmpty(); val share = line(); share.addView(
                    ImageView(context).apply {
                    setImageResource(R.drawable.users); imageTintList = ColorStateList.valueOf(if (shared.isEmpty()) muted else Color.rgb(140, 102, 5))
                },
                    LinearLayout.LayoutParams(dp(20), dp(20))
                ); val names = column(); names.addView(text("Shared with", 12f, muted)); names.addView(
                    text(
                        shared.joinToString(", "){
                    it.optString("name")
                }.ifBlank{
                    "Not shared"
                },
                    13f, bold = true
                    )
                ); share.addView(
                    names,
                    LinearLayout.LayoutParams(0, -2, 1f).apply{
                    marginStart = dp(9)
                }
                ); if (permitted("share")){
                    share.addView(
                        icon(R.drawable.pencil_simple, "Edit sharing"){
                    sharing(r, meta)
                },
                    LinearLayout.LayoutParams(dp(40), dp(40))
                    )
                }; c.addView(share); add(tabBody, c)
            }
        }
        }

        fun render(r: JSONObject, meta: Mango9OpportunityMetadata) {
            body.removeAllViews(); val pending = commands.load(lease, id, "mutate"); if (pending != null){
                add(
                    body,
                    action("Retry original change"){
                mutate(pending.getString("action"))
            }
                )
            }
            val hero = card(); val top = line(); top.addView(stage(r, meta)); top.addView(
                text(if (P.text(r, "archived_at").isNotBlank())"Archived" else "${r.optInt("stage_age_days")} days in stage", 11f, muted).apply{
                gravity = Gravity.END
            },
                LinearLayout.LayoutParams(0, -2, 1f)
            ); add(hero, top, 10); add(hero, text(r.optString("title"), 21f, bold = true), 8)
            val amount = line(); amount.addView(text(P.money(P.text(r, "value"), r.optString("currency")), 30f, bold = true)); amount.addView(text("  " + r.optString("currency"), 12f, muted)); add(hero, amount, 16)
            hero.addView(
                View(context).apply {
                setBackgroundColor(Color.rgb(227, 231, 238))
            },
                LinearLayout.LayoutParams(-1, dp(1)).apply { bottomMargin = dp(14) }
            )
            val one = line(); fact(one, "Expected close", P.date(P.text(r, "close_date"))); fact(one, "Probability", "${r.optInt("probability")}%", if (r.optString("probability_mode") == "manual")"manual" else "from stage"); add(hero, one)
            val two = line(); fact(two, "Weighted value", P.money(P.weighted(P.text(r, "value"), r.optInt("probability")), r.optString("currency"))); fact(
                two, "Created by",
                P.text(r, "created_by_name").ifBlank{
                P.text(r, "owner_name")
            }
            ); hero.addView(two)
            if (P.text(r, "actual_close_date").isNotBlank())add(hero, text("Closed " + P.date(P.text(r, "actual_close_date")) + " · " + P.money(P.text(r, "actual_value"), r.optString("currency")), 12f, muted))
            if (P.text(r, "loss_reason").isNotBlank())add(hero, text(P.text(r, "loss_reason"), 12f, muted)); add(body, hero)
            val linked = P.contact(r); val contact = card(); val contactLine = line(); val name = linked?.optString("name") ?: if (r.optString("contact_state") == "missing")"No linked contact" else "Restricted contact"
            contactLine.addView(
                text(
                    name.split(' ').filter{
                it.isNotBlank()
            }.take(2).joinToString(""){
                it.take(1)
            },
                16f, blue, true
                ).apply{
                gravity = Gravity.CENTER; background = bg(Color.rgb(238, 240, 255), 12)
            },
                LinearLayout.LayoutParams(dp(42), dp(42))
            )
            val contactText = column(); contactText.addView(text(name, 15f, bold = true)); if (linked != null){
                contactText.addView(
                    text(
                        (if (linked.optBoolean("is_client") || linked.optInt("is_client") == 1)"Client" else "Lead") + P.text(linked, "company").let{
                if (it.isNotBlank())" · $it" else ""
            },
                12f, muted
                    )
                )
            }; contactLine.addView(
                contactText,
                LinearLayout.LayoutParams(0, -2, 1f).apply{
                marginStart = dp(12)
            }
            ); if (linked != null){
                contactLine.addView(
                    ImageView(context).apply {
                    setImageResource(R.drawable.caret_right); imageTintList = ColorStateList.valueOf(muted)
                },
                    LinearLayout.LayoutParams(dp(16), dp(20))
                ); contact.setOnClickListener{
                    openContact(Mango9AppointmentContact(linked.getInt("id"), linked.optString("name"), if (linked.optBoolean("is_client") || linked.optInt("is_client") == 1)"client" else "lead"))
                }
            }; contact.addView(contactLine); add(body, contact)
            val follow = line().apply{
                background = bg(Color.rgb(237, 239, 255), 12); setPadding(dp(12), dp(11), dp(12), dp(11))
            }; val calendar = ImageView(context).apply{
                setImageResource(R.drawable.calendar); imageTintList = ColorStateList.valueOf(blue)
            }; follow.addView(calendar, LinearLayout.LayoutParams(dp(20), dp(20)))
            val note = column(); note.addView(text("NEXT FOLLOW-UP · " + P.date(P.text(r, "next_due_at"), true), 11f, blue, true)); note.addView(
                text(
                    P.text(r, "next_action").ifBlank{
                "No next action"
            },
                13f, bold = true
                )
            ); follow.addView(
                note,
                LinearLayout.LayoutParams(0, -2, 1f).apply{
                marginStart = dp(9)
            }
            ); if (permitted("update")){
                follow.addView(
                    icon(R.drawable.pencil_simple, "Edit follow-up"){
                Mango9OpportunityFollowupUI(context, scope, lease, page).open(r) { changed(); load() }
            },
                LinearLayout.LayoutParams(dp(40), dp(40))
                )
            }; add(body, follow)
            val tabs = line(); val buttons = mutableListOf<TextView>(); listOf("Details", "Notes", "Activity", "Follow-up").forEach{label ->
                val b = text(label, 13f, if (label == selected)blue else muted, label == selected).apply{
                gravity = Gravity.CENTER; contentDescription = "Opportunity tab $label"; setSingleLine(); minimumHeight = dp(44); setOnClickListener{
                    selected = label; buttons.forEach{
                        it.setTextColor(if (it.text == label)blue else muted); it.background = if (it.text == label)bg(Color.rgb(237, 239, 255), 9)else null
                    }; renderTab(r, meta)
                }
            }; if (label == selected)b.background = bg(Color.rgb(237, 239, 255), 9); buttons.add(b); tabs.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
            }; add(body, tabs, 12); tabBody = column(); add(body, tabBody); renderTab(r, meta)
        }
        load = {
            val seq = ++version; scope.launch{
                try {
                    val meta = Mango9OpportunityMetadata(api.request(lease, listOf("metadata"))); val r = api.request(lease, listOf(id.toString())); require(r.getInt("id") == id); if (current() && p.isShowing && seq == version){
                        row = r; metadata = meta; render(r, meta)
                        scope.launch { Mango9CrmUnread.readOpportunity(id, lease) }
                    }
                }catch (e: Exception){
                    if (e is CancellationException)throw e; if (current() && p.isShowing && seq == version){
                        body.removeAllViews(); failure(body, e){load()}
                    }
                }
            }
        }
        p.headerAction(
            icon(R.drawable.dots_three_vertical, "Opportunity actions"){
            val r = row; val meta = metadata; if (r != null && meta != null){
                val options = mutableListOf<Pair<String, () -> Unit>>(); if (permitted("update")){
                    options.add(
                        "Edit Opportunity" to {
                    edit(r, meta, load)
                }
                    )
                    options.add(
                        "Schedule follow-up" to {
                        Mango9OpportunityFollowupUI(context, scope, lease, page).open(r) { changed(); load() }
                    }
                    )
                }; if (permitted("share")){
                    options.add(
                        "Manage sharing" to {
                    sharing(r, meta)
                }
                    )
                }; val archived = P.text(r, "archived_at").isNotBlank(); val operation = if (archived)"restore" else "archive"; if (permitted(operation)){
                    options.add(
                        (if (archived)"Restore Opportunity" else "Archive Opportunity") to {
                    MaterialAlertDialogBuilder(context).setTitle(if (archived)"Restore Opportunity?" else "Archive Opportunity?").setMessage("The linked contact and activity history are preserved.").setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok){_, _ -> mutate(operation)}.show()
                }
                    )
                }; options.add("Refresh" to load); MaterialAlertDialogBuilder(context).setItems(
                    options.map{
                    it.first
                }.toTypedArray()
                ){_, i -> options[i].second()}.show()
            }
        }.apply{rotation = 90f}
        )
        add(body, text("Loading…", color = muted)); load()
    }

    private fun contactDetail(row: JSONObject) {
        val body = column(14); page("Contact", body); val c = card(); add(c, text(row.optString("name"), 21f, bold = true)); for ((key, title)in listOf("email" to "Email", "phone" to "Phone", "company" to "Company", "group_name" to "Contact group", "source" to "Source")){
            add(c, text(title, 12f, muted), 3); add(c, text(P.text(row, key).ifBlank{"Not set"}))
        }; add(body, c)
    }

    private fun activities(id: Int, select: (String?, DialogInterface) -> Unit) {
        val body = column(14); val p = quick("Link activity", body); var kind = "tasks"; val search = EditText(context).apply{
            hint = "Search tasks or appointments"; setSingleLine(); setTextColor(ink)
        }; val controls = line(); add(body, controls); add(body, search); val rows = column(); add(body, rows); var next = 1; var version = 0; var job: Job? = null

        fun load(append: Boolean = false) {
            val seq = ++version; scope.launch{
                try {
                    val data = api.request(lease, listOf(id.toString(), "activities"), mapOf("kind" to kind, "q" to search.text.toString(), "page" to (if (append)next else 1).toString())); if (!current() || !p.isShowing || seq != version)return@launch; if (!append)rows.removeAllViews(); rows.findViewWithTag<View>("more")?.let(rows::removeView); data.getJSONArray("items").opportunityObjects().forEach{r ->
                        add(
                            rows,
                            action(r.getString("title")){
                        select(r.getString("key"), p)
                    }
                        )
                    }; val pagination = data.getJSONObject("pagination"); next = pagination.getInt("page") + 1; if (pagination.getBoolean("hasMore")){
                        add(
                            rows,
                            action("Load more"){
                        load(true)
                    }.apply{
                        tag = "more"
                    }
                        )
                    }
                }catch (e: Exception){
                    if (e is CancellationException)throw e; if (current() && p.isShowing && seq == version)failure(rows, e){load()}
                }
            }
        }
        for ((key, title)in listOf("tasks" to "Tasks", "appointments" to "Appointments"))controls.addView(
            action(title){
            kind = key; load()
        },
            LinearLayout.LayoutParams(0, -2, 1f)
        ); add(
            body,
            action("Unlink activity"){
            select(null, p)
        }
        ); search.doAfterTextChanged{job?.cancel(); job = scope.launch{delay(250); load()}}; load()
    }
}
