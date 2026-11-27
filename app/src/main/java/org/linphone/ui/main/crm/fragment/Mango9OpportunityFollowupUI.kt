// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.ui.main.crm.fragment

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.widget.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.linphone.R
import org.linphone.mango9.*
import org.linphone.mango9.Mango9OpportunityPresentation as P
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal class Mango9OpportunityFollowupUI(
    private val context: Context, private val scope: CoroutineScope, private val lease: Mango9SessionLease,
    private val page: (String, LinearLayout) -> Mango9FullPage
) {
    fun open(row: JSONObject, done: () -> Unit) {
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(20))
        }
        val p = page("Follow-up", body)
        val calendar = Mango9CalendarRepository(context) // Account lease only; no Calendar writes.
        val api = Mango9Opportunities(context); val commands = Mango9OpportunityCommands(context); val id = row.getInt("id")

        fun label(value: String) = TextView(context).apply {
            text = value; textSize = Mango9CrmText.value; setTextColor(context.getColor(R.color.mango9_calendar_ink)); setPadding(0, dp(10), 0, dp(10))
        }
        val status = label("Loading follow-up…"); body.addView(status)
        scope.launch {
            try {
                val meta = Mango9OpportunityMetadata(api.request(lease, listOf("metadata")))
                val latest = api.request(lease, listOf(id.toString()))
                require(latest.optInt("id") == id && meta.raw.optJSONObject("permissions")?.optBoolean("edit") == true && P.text(latest, "archived_at").isEmpty()) {
                    "You cannot edit this follow-up."
                }
                require(commands.load(lease, id, "mutate") == null) { "Finish the previous Opportunity change first." }
                if (!calendar.isCurrent(lease) || !p.isShowing) return@launch
                val prior = commands.load(lease, id, "followup")
                if (prior != null && prior.optInt("format") != 2) {
                    status.text = "A previous Calendar save needs review. Check Calendar before continuing. Existing events will be kept."
                    body.addView(
                        Button(context).apply {
                        text = "I checked Calendar — continue"; isAllCaps = false
                        setOnClickListener {
                            AlertDialog.Builder(context).setTitle("Keep existing Calendar events?")
                                .setMessage("This clears only the older pending request. Review any older appointment separately to avoid duplicate reminders.")
                                .setNegativeButton("Cancel", null).setPositiveButton("I reviewed the events") { _, _ ->
                                    if (calendar.isCurrent(lease)) { commands.save(lease, id, "followup", null); p.dismiss(); open(row, done) }
                                }.show()
                        }
                    }
                    ); return@launch
                }
                val values = prior?.optJSONObject("command")?.optJSONObject("values")
                val zone = ZoneId.systemDefault()
                var start = runCatching { Instant.parse(P.iso(P.text(values ?: latest, "next_due_at"))) }.getOrNull()
                    ?: Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
                body.removeAllViews()
                body.addView(
                    label("The Opportunity manages its Calendar follow-up. Open opportunities with a next action and due time get a 30-minute entry when the responsible user has Calendar access.").apply {
                    textSize = Mango9CrmText.label
                }
                )
                body.addView(label("Next action").apply { textSize = Mango9CrmText.label })
                val title = EditText(context).apply {
                    textSize = Mango9CrmText.value; hint = "Next action"; setText(P.text(values ?: latest, "next_action")); filters = arrayOf(android.text.InputFilter.LengthFilter(500))
                }
                body.addView(title)
                val scheduled = Switch(context).apply {
                    text = "Schedule in Calendar"; textSize = Mango9CrmText.value; isChecked = values == null || !P.text(values, "next_due_at").isEmpty()
                }
                body.addView(scheduled)
                val date = Button(context).apply { isAllCaps = false; textSize = Mango9CrmText.value }
                val originalTitle = title.text.toString(); val originalStart = start; val originalScheduled = scheduled.isChecked
                p.protectChanges {
                    title.text.toString() != originalTitle || start != originalStart || scheduled.isChecked != originalScheduled
                }
                val format = DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a", Locale.US)

                fun dates() { date.text = "Due   ${start.atZone(zone).format(format)}" }
                date.setOnClickListener {
                    val local = start.atZone(zone)
                    DatePickerDialog(context, { _, y, m, d ->
                        TimePickerDialog(context, { _, h, min -> start = java.time.LocalDate.of(y, m + 1, d).atTime(h, min).atZone(zone).toInstant(); dates() }, local.hour, local.minute, false).show()
                    }, local.year, local.monthValue - 1, local.dayOfMonth).show()
                }; dates(); body.addView(date)
                body.addView(label("Times shown in ${zone.id}").apply { textSize = Mango9CrmText.label })
                status.text = Mango9OpportunityFollowup.calendarStatus(latest); body.addView(status)
                val save = Button(context).apply {
                    text = "Save follow-up"; isAllCaps = false; textSize = Mango9CrmText.value
                }; body.addView(save)

                var mustReload = false
                val reload = Button(context).apply {
                    text = "Reload follow-up"; isAllCaps = false; visibility = android.view.View.GONE
                    setOnClickListener { if (calendar.isCurrent(lease)) { p.dismiss(); open(row, done) } }
                }; body.addView(reload)

                fun controls(saving: Boolean) {
                    val pending = commands.load(lease, id, "followup") != null
                    title.isEnabled = !saving && !pending; scheduled.isEnabled = title.isEnabled
                    date.isEnabled = title.isEnabled && scheduled.isChecked
                    date.visibility = if (scheduled.isChecked) android.view.View.VISIBLE else android.view.View.GONE
                    save.isEnabled = !saving && !mustReload; reload.visibility = if (mustReload) android.view.View.VISIBLE else android.view.View.GONE; save.text = if (pending) "Retry save" else "Save follow-up"
                }
                scheduled.setOnCheckedChangeListener { _, _ -> controls(false) }; controls(false)
                save.setOnClickListener {
                    if (!calendar.isCurrent(lease)) return@setOnClickListener
                    controls(true); p.setCancelable(false)
                    scope.launch {
                        try {
                            val flow = Mango9OpportunityFollowup(
                                read = { api.request(lease, listOf(id.toString())) },
                                mutate = { action, fields -> api.request(lease, listOf(id.toString(), action), body = fields) },
                                stored = { commands.load(lease, id, "followup") }, persist = { commands.save(lease, id, "followup", it) }
                            )
                            flow.save(latest, meta, title.text.toString(), if (scheduled.isChecked) start.toString() else null)
                            if (calendar.isCurrent(lease) && p.isShowing) { p.dismiss(); done() }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            if (calendar.isCurrent(lease) && p.isShowing) {
                                status.text = e.message ?: "The follow-up save could not be confirmed."; mustReload = Mango9OpportunityCommands.definite(e)
                            }
                        } finally { if (p.isShowing) { controls(false); p.setCancelable(true) } }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (calendar.isCurrent(lease) && p.isShowing) status.text = e.message ?: "Follow-up unavailable."
            }
        }
    }

    private fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
}
