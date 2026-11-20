// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.ui.main.crm.fragment

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
        val p = page("Schedule follow-up", body)
        val calendar = Mango9CalendarRepository(context); val api = Mango9Opportunities(context)
        val commands = Mango9OpportunityCommands(context); val id = row.getInt("id")

        fun label(value: String) = TextView(context).apply {
            text = value; textSize = Mango9CrmText.value; setTextColor(context.getColor(R.color.mango9_calendar_ink)); setPadding(0, dp(10), 0, dp(10))
        }
        val status = label("Loading Calendar…"); body.addView(status)
        scope.launch {
            try {
                val contact = P.contact(row); require(contact != null && P.text(row, "archived_at").isEmpty()) {
                    "Contact access is required to schedule a follow-up."
                }
                val linked = row.optJSONObject("activity")
                require(linked?.optBoolean("managed") != true && !row.optString("activity_key").startsWith("task-")) {
                    "Change this managed task in Events & Tasks so its calendar stays in sync."
                }
                val eventId = linked?.optInt("event_id")?.takeIf { it > 0 }
                require(P.text(row, "activity_key").isEmpty() || eventId != null) {
                    "The linked activity is unavailable. Review its access first."
                }
                require(commands.load(lease, id, "mutate") == null) { "Finish the previous Opportunity change before scheduling." }
                val meta = calendar.metadata(lease)
                val original = eventId?.let { calendar.appointment(lease, it) }
                require(original == null && meta.capabilities.create || original != null && original.permissions.canEdit && !original.isRecurring) {
                    "This schedule cannot be edited here. Review it in Calendar."
                }
                require(original == null || original.contact?.id == row.getInt("contact_id")) {
                    "The linked appointment belongs to a different contact."
                }
                if (!calendar.isCurrent(lease) || !p.isShowing) return@launch
                val prior = commands.load(lease, id, "followup")
                val zone = ZoneId.of(original?.timezone ?: meta.timezone)
                var start = prior?.getString("start")?.let(Instant::parse) ?: original?.startAt ?: runCatching {
                    Instant.parse(P.iso(P.text(row, "next_due_at")))
                }.getOrNull() ?: Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
                var end = prior?.getString("end")?.let(Instant::parse) ?: original?.endAt ?: start.plusSeconds(1800)
                body.removeAllViews()
                body.addView(label(contact.optString("name")))
                body.addView(label("Next action").apply { textSize = Mango9CrmText.label })
                val title = EditText(context).apply {
                    textSize = Mango9CrmText.value; setSingleLine(); hint = "Follow-up title"; setText(
                        prior?.getString("title") ?: original?.title ?: P.text(row, "next_action").ifBlank {
                        "Follow up: ${row.optString("title")}"
                    }
                    )
                }
                body.addView(title)
                val startButton = Button(context).apply { isAllCaps = false; textSize = Mango9CrmText.value }
                val endButton = Button(context).apply { isAllCaps = false; textSize = Mango9CrmText.value }
                val format = DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a", Locale.US)

                fun dates() {
                    startButton.text = "Starts   ${start.atZone(zone).format(format)}"; endButton.text = "Ends   ${end.atZone(zone).format(format)}"
                }

                fun pick(value: Instant, apply: (Instant) -> Unit) {
                    val local = value.atZone(zone)
                    DatePickerDialog(context, {_, y, m, d ->
                        TimePickerDialog(context, {_, h, min ->
                        apply(java.time.LocalDate.of(y, m + 1, d).atTime(h, min).atZone(zone).toInstant()); dates()
                    }, local.hour, local.minute, false).show()
                    }, local.year, local.monthValue - 1, local.dayOfMonth).show()
                }
                startButton.setOnClickListener {
                    pick(start) { val duration = java.time.Duration.between(start, end); start = it; end = it.plus(duration) }
                }
                endButton.setOnClickListener { pick(end) { end = it } }; dates()
                body.addView(startButton); body.addView(endButton); body.addView(
                    label("Time zone: ${zone.id}").apply {
                    textSize = Mango9CrmText.label
                }
                )
                status.text = "This follow-up will appear in Calendar."; body.addView(status)
                val save = Button(context).apply {
                    text = "Save follow-up"; isAllCaps = false; textSize = Mango9CrmText.value
                }; body.addView(save)

                fun controls(saving: Boolean) {
                    val pending = commands.load(lease, id, "followup") != null; title.isEnabled = !saving && !pending; startButton.isEnabled = title.isEnabled; endButton.isEnabled = title.isEnabled; save.isEnabled = !saving; save.text = if (pending)"Finish saved follow-up" else "Save follow-up"
                }
                controls(false)
                save.setOnClickListener {
                    if (!calendar.isCurrent(lease)) return@setOnClickListener
                    if (title.text.isBlank() || end <= start) {
                        status.text = "Enter a title and an end time after the start."; return@setOnClickListener
                    }
                    controls(true); p.setCancelable(false)
                    scope.launch {
                        try {
                            val flow = Mango9OpportunityFollowup(
                                read = {api.request(lease, listOf(id.toString()))},
                                metadata = {Mango9OpportunityMetadata(api.request(lease, listOf("metadata")))},
                                mutate = {action, values -> api.request(lease, listOf(id.toString(), action), body = values)},
                                event = {calendar.appointment(lease, it)},
                                stored = {commands.load(lease, id, "followup")}, persist = {commands.save(lease, id, "followup", it)}
                            )
                            flow.save(row.getInt("contact_id"), original?.id, title.text.toString(), start.toString(), end.toString()) {
                                val draft = original?.let(Mango9AppointmentDraft::from)?.copy(title = title.text.toString().trim(), start = start, end = end)
                                    ?: Mango9AppointmentDraft(
                                        title.text.toString().trim(), "", start, end, "follow_up", "medium", null,
                                        Mango9AppointmentContact(row.getInt("contact_id"), contact.optString("name"), if (contact.optBoolean("is_client") || contact.optInt("is_client") == 1)"client" else "lead"), emptySet()
                                    )
                                calendar.save(lease, draft, meta, original, zone.id)
                            }
                            if (calendar.isCurrent(lease) && p.isShowing) { p.dismiss(); done() }
                        } catch (e: Exception) {
                            if (e is CancellationException)throw e
                            if (calendar.isCurrent(lease) && p.isShowing)status.text = e.message ?: "The schedule could not be confirmed."
                        } finally { if (p.isShowing){controls(false); p.setCancelable(true)} }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException)throw e; if (calendar.isCurrent(lease) && p.isShowing)status.text = e.message ?: "Calendar is unavailable."
            }
        }
    }

    private fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
}
