/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.ui.main.crm.fragment

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.doOnLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.linphone.R
import org.linphone.mango9.Mango9Appointment
import org.linphone.mango9.Mango9AppointmentActions
import org.linphone.mango9.Mango9AppointmentContact
import org.linphone.mango9.Mango9AppointmentCreationContext
import org.linphone.mango9.Mango9AppointmentEdit
import org.linphone.mango9.Mango9BusinessHoursEdit
import org.linphone.mango9.Mango9CalendarEdit
import org.linphone.mango9.Mango9CalendarFormState
import org.linphone.mango9.Mango9CalendarEdits
import org.linphone.mango9.Mango9AppointmentDetail
import org.linphone.mango9.Mango9CalendarMetadata
import org.linphone.mango9.Mango9CalendarRepository
import org.linphone.mango9.Mango9CalendarScreen
import org.linphone.mango9.Mango9BusinessHours
import org.linphone.mango9.Mango9BusinessDay
import org.linphone.mango9.Mango9CrmRepository
import org.linphone.mango9.Mango9RecordKind
import org.linphone.mango9.Mango9SessionLease
import org.linphone.mango9.Mango9ReminderAction

/** Forms retain their original account and revision, independently of list refreshes. */
internal class Mango9AppointmentDialogs(
    private val context: Context,
    private val parentScope: CoroutineScope,
    private val repository: Mango9CalendarRepository,
    private val changed: () -> Unit,
    private val openContact: (Mango9SessionLease, Mango9AppointmentContact) -> Unit,
    private val now: () -> Instant = Instant::now,
    private val onAllClosed: () -> Unit = {},
    retainedEdits: Mango9CalendarEdits? = null,
    private val loadContacts:
    suspend (Mango9SessionLease, Mango9RecordKind, String, Int) -> org.linphone.mango9.Mango9CrmRecordPage = { lease, kind, query, page ->
        Mango9CrmRepository(context).records(lease, kind, query, page)
    },
) {
    private val sessions = linkedSetOf<Form>()
    private val edits = retainedEdits ?: Mango9CalendarEdits(repository, parentScope)
    private var editForm: Form? = null
    private var hoursSaved: (Mango9BusinessHours) -> Unit = {}
    val hasEditor: Boolean get() = edits.active != null

    fun close() { sessions.toList().filter { it.edit?.progress?.value?.writing != true }.forEach { it.dialog.dismiss() } }

    fun detach() {
        // OnDismissListener is posted by Android. Keep intent on each old Form
        // until its callback runs, even after the replacement dialog has opened.
        val shown = sessions.toList().filter { it.dialog.isShowing }
        shown.forEach { it.retain(); it.detached = true }
        shown.forEach { it.dialog.dismiss() }
    }

    fun closeExpired() {
        edits.checkAccount(); sessions.toList().filter { !repository.isCurrent(it.lease) }.forEach { it.dialog.dismiss() }
    }

    fun resumeEdits() {
        edits.checkAccount()
        val edit = edits.active ?: return
        if (editForm?.dialog?.isShowing == true) return
        if (edit.progress.value.completed) { edits.dismiss(edit); return }
        when (edit) {
            is Mango9AppointmentEdit -> showEditor(edit)
            is Mango9BusinessHoursEdit -> showBusinessHours(edit)
            is Mango9AppointmentDetail -> showDetail(edit)
        }
    }

    private fun dp(value: Int) = Mango9AppointmentAgendaAdapter.dp(context, value)

    private fun title(value: Int) = context.getString(value)

    private fun fieldText(label: Int, value: String) = context.getString(R.string.mango9_calendar_field_value, title(label), value)

    private inner class Form(
        val lease: Mango9SessionLease, caption: String, save: Boolean = false, val edit: Mango9CalendarEdit? = null,
        val presentation: Mango9CalendarFormState = edit?.presentation ?: Mango9CalendarFormState(),
    ) {
        var detached = false
        val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(6), dp(18), dp(12))
        }
        val error = TextView(context).apply {
            tag = "calendar.error"
            setTextColor(context.getColor(R.color.mango9_calendar_error)); visibility = View.GONE; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val scroll = ScrollView(context).apply { addView(body); tag = "calendar.form.scroll" }
        val dialog: Mango9FullPage = Mango9FullPage(context).setTitle(caption)
            .setView(scroll)
            .setNegativeButton(if (save) R.string.mango9_calendar_cancel else R.string.mango9_calendar_done, null)
            .apply { if (save) setPositiveButton(R.string.mango9_calendar_save, null) }.create()
        var busy = false
        var blocked = false
        var recovery: Button? = null
        private val permissionRules = linkedMapOf<Button, () -> Boolean>()
        private val actions = linkedMapOf<String, () -> Unit>()
        private val children = linkedMapOf<android.app.Dialog, Mango9CalendarFormState.Child>()
        private var actionNumber = 0
        private var infoNumber = 0
        var actionKey = ""
        var restoreReady = true
        private var restoreScheduled = false
        private var restored = false
        var afterDismiss: () -> Unit = {}

        fun action(key: String, action: () -> Unit): () -> Unit {
            val bound: () -> Unit = {
                if (dialog.isShowing && !busy && !blocked && repository.isCurrent(lease)) {
                    actionKey = key; action()
                }
            }
            actions[key] = bound
            return bound
        }

        fun retain() {
            // A detached old Form may dismiss after its replacement is already visible.
            // Save before dismissal, and never mutate a replacement's checkpoint later.
            if (!restored || detached) return
            presentation.captureScroll(scroll.scrollY, body.width, body.height, body.resources.configuration.fontScale, scrollRegions())
            presentation.inputTag = null
            (body.findFocus() as? TextInputEditText)?.let {
                presentation.inputTag = it.tag as? String
                presentation.selectionStart = it.selectionStart; presentation.selectionEnd = it.selectionEnd
            }
            children.forEach { (child, checkpoint) ->
                if (child.isShowing && presentation.child === checkpoint) checkpoint.snapshot = child.onSaveInstanceState()
            }
        }

        private fun scrollRegions(): List<Mango9CalendarFormState.Region> {
            val regions = arrayListOf<Mango9CalendarFormState.Region>()

            fun visit(view: View) {
                if (view.visibility != View.VISIBLE) return
                val tag = view.tag as? String
                if (view is TextView && tag != null && view.height > 0) {
                    val bounds = android.graphics.Rect(0, 0, view.width, view.height)
                    body.offsetDescendantRectToMyCoords(view, bounds)
                    regions.add(Mango9CalendarFormState.Region(tag, bounds.top, bounds.bottom))
                }
                if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
            for (index in 0 until body.childCount) visit(body.getChildAt(index))
            return regions
        }

        fun checkpoint(stage: String, values: Bundle = Bundle()): Mango9CalendarFormState.Child =
            presentation.child?.takeIf { it.action == actionKey && it.stage == stage }
                ?: Mango9CalendarFormState.Child(actionKey, stage, values).also { presentation.child = it }

        fun attachChild(child: android.app.Dialog, checkpoint: Mango9CalendarFormState.Child, installListener: Boolean = true): () -> Unit {
            children[child] = checkpoint
            val guard = scope.launch { try { kotlinx.coroutines.awaitCancellation() } finally { child.dismiss() } }
            val finished: () -> Unit = {
                children.remove(child); guard.cancel()
                if (!detached && presentation.child === checkpoint) presentation.child = null
            }
            if (installListener) child.setOnDismissListener { finished() }
            return finished
        }

        fun attachChild(child: Mango9FullPage, checkpoint: Mango9CalendarFormState.Child, installListener: Boolean = true): () -> Unit {
            val guard = scope.launch { try { kotlinx.coroutines.awaitCancellation() } finally { child.dismiss() } }
            val finished: () -> Unit = {
                guard.cancel()
                if (!detached && presentation.child === checkpoint) presentation.child = null
            }
            if (installListener) child.setOnDismissListener { finished() }
            return finished
        }

        fun restore() {
            if (!restoreReady || !dialog.isShowing || detached) return
            if (restored) { resumeChild(); return }
            if (restoreScheduled) return
            restoreScheduled = true
            body.doOnLayout {
                restoreScheduled = false
                if (detached || !dialog.isShowing || !restoreReady || !repository.isCurrent(lease)) return@doOnLayout
                restored = true
                presentation.inputTag?.let { tag ->
                    body.findViewWithTag<TextInputEditText>(tag)?.takeIf { it.isEnabled }?.let { input ->
                        input.requestFocus()
                        val size = input.text?.length ?: 0
                        input.setSelection(presentation.selectionStart.coerceIn(0, size), presentation.selectionEnd.coerceIn(0, size))
                    }
                }
                scroll.scrollTo(0, presentation.restoredScroll(body.width, body.height, body.resources.configuration.fontScale, scrollRegions()))
                resumeChild()
            }
        }

        private fun resumeChild() {
            val pending = presentation.child
            if (pending != null && children.isEmpty() && !busy && !blocked && repository.isCurrent(lease)) {
                actions[pending.action]?.invoke() ?: run { presentation.child = null }
            }
        }

        fun permit(button: Button, allowed: () -> Boolean) { permissionRules[button] = allowed; button.isEnabled = allowed() }

        init {
            body.addView(error)
            sessions.add(this)
            dialog.setOnDismissListener {
                scope.cancel(); sessions.remove(this)
                afterDismiss()
                if (editForm === this) editForm = null
                if (!detached && edit != null) edits.dismiss(edit)
                if (detached) return@setOnDismissListener
                parentScope.launch {
                    // Editing/reloading opens its replacement synchronously. Only
                    // report closure after that transition has had a chance to finish.
                    kotlinx.coroutines.yield()
                    if (sessions.isEmpty()) onAllClosed()
                }
            }
        }

        fun show() {
            dialog.show()
            if (edit != null) {
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { if (edits.dismiss(edit)) dialog.dismiss() }
                dialog.setOnCancelListener { edits.dismiss(edit) }
            }
        }

        fun bindEdit(render: () -> Unit = {}, accepted: () -> Unit = {}) {
            val editing = edit ?: return
            editForm = this
            val disabled = linkedSetOf<View>()

            fun disable(view: View) {
                if (view.isEnabled) { disabled.add(view); view.isEnabled = false }
                if (view is ViewGroup) for (i in 0 until view.childCount) disable(view.getChildAt(i))
            }
            scope.launch {
                editing.progress.collect { state ->
                    if (state.closed || !repository.isCurrent(lease)) { dialog.dismiss(); return@collect }
                    busy = state.busy; blocked = state.blocked
                    disabled.forEach { it.isEnabled = true }; disabled.clear()
                    render()
                    permissionRules.forEach { (button, allowed) -> button.isEnabled = allowed() }
                    if (busy || blocked) disable(body)
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = editing.canSave
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = !state.writing
                    dialog.setCancelable(!state.writing)
                    error.text = state.error.orEmpty(); error.visibility = if (state.error == null) View.GONE else View.VISIBLE
                    recovery?.apply { visibility = if (blocked || state.error != null) View.VISIBLE else View.GONE; isEnabled = !busy }
                    if (state.completed) { accepted(); changed(); dialog.dismiss() } else {
                        restore()
                    }
                }
            }
        }

        fun message(value: String) { error.text = value; error.visibility = View.VISIBLE }

        fun info(value: String): TextView = TextView(context).apply {
            tag = "calendar.info:${infoNumber++}"
            text = value; textSize = 14f; setPadding(0, dp(6), 0, dp(6)); body.addView(this)
        }

        fun button(caption: String, action: () -> Unit): Button = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            val key = "button:${actionNumber++}"
            val bound = this@Form.action(key) {
                if (isEnabled && permissionRules[this]?.invoke() != false) action() else presentation.child = null
            }
            tag = "calendar.action.$key"
            text = caption; isAllCaps = false; textSize = 14f; minHeight = dp(48)
            cornerRadius = dp(10); backgroundTintList = ColorStateList.valueOf(context.getColor(R.color.mango9_calendar_surface))
            strokeColor = ColorStateList.valueOf(context.getColor(R.color.mango9_calendar_separator))
            setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()), intArrayOf(context.getColor(R.color.mango9_calendar_accent), context.getColor(R.color.mango9_calendar_secondary))))
            setOnClickListener {
                if (permissionRules[this]?.invoke() != false) bound()
            }
            body.addView(this, LinearLayout.LayoutParams(-1, -2))
        }

    }

    fun detail(lease: Mango9SessionLease, displayed: Mango9Appointment, metadata: Mango9CalendarMetadata) {
        edits.detail(lease, displayed, metadata, now)
        resumeEdits()
    }

    private fun showDetail(edit: Mango9AppointmentDetail) {
        val lease = edit.lease
        val metadata = edit.metadata
        val form = Form(lease, title(R.string.mango9_calendar_title), edit = edit)
        val loading = form.info(title(R.string.mango9_calendar_refresh))
        val controls = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        fun contact() {
            val linked = edit.event?.contact ?: return
            if (edits.dismiss(edit)) { form.dialog.dismiss(); openContact(lease, linked) }
        }
        val name = form.button("") { contact() }

        fun displayedTime(instant: Instant) = Mango9CalendarDateLabels(context).dateTime(instant, ZoneId.systemDefault(), true)
        val time = form.info("").apply { tag = "appointment.times" }
        val timezone = form.info(context.getString(R.string.mango9_calendar_zone, ZoneId.systemDefault().id)).apply {
            tag = "appointment.timezone"
        }
        val activity = form.info("").apply { tag = "appointment.activity" }
        val priority = form.info("").apply { tag = "appointment.priority" }
        val status = form.button("") {
            val event = edit.event ?: return@button
            choice(
                form, title(R.string.mango9_calendar_status),
                listOf(title(R.string.mango9_calendar_no_status)) + metadata.statuses.map { it.name },
                metadata.statuses.indexOfFirst { it.id == event.status?.id } + 1,
            ) { edit.changeStatus(metadata.statuses.getOrNull(it - 1)?.id) }
        }.apply { tag = "appointment.status"; form.permit(this) { edit.canSave && edit.event?.permissions?.canEdit == true } }
        val description = form.info("")
        val recurrence = form.info(title(R.string.mango9_calendar_recurrence_notice))
        val reason = form.info("")
        val editor = form.button(title(R.string.mango9_calendar_edit)) {
            val event = edit.event ?: return@button
            // Clear the exact detail before opening its editor; a posted old dismiss
            // callback must not discard the replacement session.
            if (edits.dismiss(edit)) { form.dialog.dismiss(); editor(lease, metadata, event, null) }
        }.apply {
            tag = "appointment.edit"; form.permit(this) {
            edit.canSave && edit.event?.permissions?.let { it.canEdit || it.canShare || it.canAssign } == true
        }
        }
        val push = form.button(title(R.string.mango9_reminder_push_back)) {
            choice(
                form, title(R.string.mango9_reminder_push_back),
                Mango9AppointmentActions.minuteChoices.map {
                context.getString(R.string.mango9_reminder_minutes, it)
            },
                -1
            ) { index ->
                val original = edit.event ?: return@choice
                val minutes = Mango9AppointmentActions.minuteChoices[index]
                val start = original.startAt.plusSeconds(minutes * 60L)
                val end = original.endAt.plusSeconds(minutes * 60L)
                if (start <= now()) { form.message(title(R.string.mango9_reminder_future)); return@choice }
                confirmation(
                    form,
                    context.getString(
                    R.string.mango9_reminder_push_confirm,
                    displayedTime(original.startAt),
                    displayedTime(start),
                    displayedTime(end),
                ),
                    R.string.mango9_calendar_save
                ) {
                    if (start <= now()) {
                        form.message(title(R.string.mango9_reminder_future))
                    } else if (edit.event === original) edit.pushBack(minutes)
                }
            }
        }.apply { tag = "appointment.pushBack"; form.permit(this) { edit.canSave && edit.event?.permissions?.canEdit == true } }
        for (view in listOf(name, time, timezone, status, activity, priority, description, recurrence, reason, editor, push)) {
            form.body.removeView(view); controls.addView(view)
        }
        form.body.addView(controls)
        val personal = PersonalReminderSection(form, edit)
        val linked = form.button("") { contact() }.apply { tag = "appointment.linkedContact" }
        val delete = form.button(title(R.string.mango9_calendar_delete)) {
            confirmation(form, title(R.string.mango9_calendar_delete_confirm)) { edit.delete() }
        }.apply {
            tag = "appointment.delete"; setTextColor(context.getColor(R.color.mango9_calendar_error)); form.permit(this) {
                edit.canSave && edit.event?.permissions?.canDelete == true
            }
        }
        form.recovery = form.button(title(R.string.mango9_reminder_reload_appointment)) {}.apply {
            tag = "appointment.reload"
            setOnClickListener { if (!form.busy && repository.isCurrent(lease)) edit.reload() }
        }
        form.show()
        form.bindEdit(render = {
            val event = edit.event
            form.restoreReady = event != null
            loading.visibility = if (event == null && edit.progress.value.busy) View.VISIBLE else View.GONE
            controls.visibility = if (event == null) View.GONE else View.VISIBLE
            name.text = event?.title.orEmpty(); name.isEnabled = edit.canSave && event?.contact != null
            name.setTextColor(context.getColor(if (event?.contact == null) R.color.mango9_calendar_ink else R.color.mango9_calendar_accent))
            time.text = edit.shown?.let {
                displayedTime(it.startAt) + "\n" + displayedTime(it.endAt)
            }.orEmpty()
            status.text = fieldText(
                R.string.mango9_calendar_status,
                event?.status?.name?.takeIf {
                it.isNotBlank()
            } ?: title(R.string.mango9_calendar_no_status)
            )
            activity.text = fieldText(R.string.mango9_calendar_activity, Mango9CalendarLegend.humanize(event?.activity.orEmpty()))
            priority.text = fieldText(R.string.mango9_calendar_priority_field, Mango9CalendarLegend.humanize(event?.priority.orEmpty()))
            val flag = androidx.appcompat.content.res.AppCompatResources.getDrawable(context, R.drawable.mango9_priority_flag)?.mutate()
            flag?.setTint(org.linphone.mango9.Mango9AppointmentAppearance.indicatorPriorityRgb(event?.priority.orEmpty()) or (0xff shl 24))
            flag?.setBounds(0, 0, dp(18), dp(18)); priority.setCompoundDrawablesRelative(flag, null, null, null); priority.compoundDrawablePadding = dp(8)
            description.text = event?.description.orEmpty(); description.visibility = if (description.text.isEmpty()) View.GONE else View.VISIBLE
            recurrence.visibility = if (event?.isRecurring == true) View.VISIBLE else View.GONE
            reason.text = event?.permissions?.readOnlyReason.orEmpty(); reason.visibility = if (reason.text.isEmpty()) View.GONE else View.VISIBLE
            editor.visibility = if (event?.permissions?.let {
                it.canEdit || it.canShare || it.canAssign
            } == true
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }
            push.visibility = if (event?.permissions?.canEdit == true) View.VISIBLE else View.GONE
            val linkedLabel = if (event?.contact?.kind == "client") R.string.mango9_calendar_linked_client else R.string.mango9_calendar_linked_lead
            linked.text = fieldText(linkedLabel, event?.contact?.displayName.orEmpty())
            name.contentDescription = event?.contact?.let {
                "${event.title}. ${context.getString(R.string.mango9_calendar_open_linked, it.displayName)}"
            }
            linked.contentDescription = linked.text
            linked.visibility = if (event?.contact == null) View.GONE else View.VISIBLE; linked.isEnabled = edit.canSave
            delete.visibility = if (event?.permissions?.canDelete == true) View.VISIBLE else View.GONE
            personal.render()
        })
    }

    /** Personal reminder writes never change appointment times or email/SMS reminders. */
    private inner class PersonalReminderSection(private val form: Form, private val detail: Mango9AppointmentDetail) {
        private val reminder get() = detail.personal.value.reminder
        private val preferences get() = detail.personal.value.preferences
        private val loading get() = detail.personal.value.loading
        private val settingsLoading get() = detail.personal.value.settingsLoading
        private val heading = form.info(title(R.string.mango9_reminder_heading)).apply {
            textSize = 18f; setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        private val configure = form.button(title(R.string.mango9_reminder_loading)) {
            val choices = listOf(-1) + Mango9AppointmentActions.reminderChoices
            choice(form, title(R.string.mango9_reminder_title), choices.map(::label), choices.indexOf(reminder?.minutesBefore ?: -1)) {
                update(Mango9ReminderAction.Configure(choices[it]))
            }
        }.apply {
            tag = "appointment.personalReminder"
            val icon = androidx.appcompat.content.res.AppCompatResources.getDrawable(context, R.drawable.bell)?.mutate()
            icon?.setTint(context.getColor(R.color.mango9_calendar_accent)); icon?.setBounds(0, 0, dp(22), dp(22))
            setCompoundDrawablesRelative(icon, null, null, null); compoundDrawablePadding = dp(10)
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        }
        private val suggested = form.button("") {
            preferences?.let {
                update(Mango9ReminderAction.Configure(it.reminderMinutes))
            }
        }.apply { tag = "appointment.reminderSuggested" }
        private val defaultsNote = form.info("").apply { tag = "appointment.reminderDefaults" }
        private val next = form.info("").apply { tag = "appointment.reminderNext" }
        private val snoozeDefault = form.button("") {
            preferences?.let { snooze(it.snoozeMinutes) }
        }.apply { tag = "appointment.reminderSnoozeDefault" }
        private val snoozeChoice = form.button(title(R.string.mango9_reminder_snooze)) {
            choice(
                form, title(R.string.mango9_reminder_snooze),
                Mango9AppointmentActions.minuteChoices.map {
                context.getString(R.string.mango9_reminder_minutes, it)
            },
                -1
            ) {
                snooze(Mango9AppointmentActions.minuteChoices[it])
            }
        }.apply { tag = "appointment.reminderSnooze" }
        private val dismiss = form.button(title(R.string.mango9_reminder_dismiss)) {
            update(Mango9ReminderAction.Dismiss)
        }.apply { tag = "appointment.reminderDismiss" }
        private val reenable = form.info(title(R.string.mango9_reminder_reenable))
        private val problem = form.info("").apply {
            setTextColor(context.getColor(R.color.mango9_calendar_error)); tag = "appointment.reminderError"
        }
        private val reload = form.button(title(R.string.mango9_reminder_reload)) {
            refresh()
        }.apply { tag = "appointment.reminderReload" }
        private val footer = form.info(title(R.string.mango9_reminder_scope))

        private val sectionViews =
            listOf(heading, configure, suggested, defaultsNote, next, snoozeDefault, snoozeChoice, dismiss, reenable, problem, reload, footer)
        private val section = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        private val position = form.body.indexOfChild(heading)

        init {
            sectionViews.forEach { form.body.removeView(it); section.addView(it) }
            form.scope.launch { detail.personal.collect { render() } }; render()
        }

        private fun label(minutes: Int): String = when (minutes) {
            -1 -> title(R.string.mango9_reminder_off)
            0 -> title(R.string.mango9_reminder_at_start)
            1440 -> title(R.string.mango9_reminder_day_before)
            else -> context.getString(R.string.mango9_reminder_before, minutes)
        }

        fun refresh() = detail.refreshReminder()

        private fun snooze(minutes: Int) = update(Mango9ReminderAction.Snooze(now().plusSeconds(minutes * 60L)))

        private fun update(action: Mango9ReminderAction) { detail.updateReminder(action) }

        fun render() {
            val event = detail.event
            val visible = event != null && detail.metadata.capabilities.inAppReminders && !event.isRecurring
            if (!visible) { form.body.removeView(section); return }
            if (section.parent == null) form.body.addView(section, position.coerceAtMost(form.body.childCount))
            sectionViews.forEach { it.visibility = if (visible) View.VISIBLE else View.GONE }
            problem.text = detail.personal.value.error.orEmpty()
            val state = reminder
            val writable = !loading && detail.canSave && state != null
            val allowed = writable && event.endAt > now()
            configure.text = context.getString(
                R.string.mango9_reminder_option,
                if (loading) title(R.string.mango9_reminder_loading) else label(if (state?.enabled == true) state.minutesBefore ?: -1 else -1)
            )
            configure.isEnabled = allowed
            suggested.text = context.getString(R.string.mango9_reminder_suggested, label(preferences?.reminderMinutes ?: 15))
            suggested.visibility = if (state?.enabled != true && preferences?.inAppReminders == true) View.VISIBLE else View.GONE
            suggested.isEnabled = allowed
            defaultsNote.text = if (preferences?.inAppReminders == false) title(R.string.mango9_reminder_paused) else if (!settingsLoading && preferences == null) title(R.string.mango9_reminder_defaults_unavailable) else ""
            defaultsNote.visibility = if (defaultsNote.text.isEmpty()) View.GONE else View.VISIBLE
            next.text = state?.dueAt?.let {
                context.getString(R.string.mango9_reminder_next, Mango9CalendarDateLabels(context).dateTime(it, ZoneId.systemDefault()))
            } ?: if (state?.dismissed == true) title(R.string.mango9_reminder_dismissed) else ""
            next.visibility = if (state?.enabled == true && next.text.isNotEmpty()) View.VISIBLE else View.GONE
            snoozeDefault.text = context.getString(R.string.mango9_reminder_snooze_minutes, preferences?.snoozeMinutes ?: 15)
            snoozeDefault.visibility = if (state?.enabled == true && preferences != null) View.VISIBLE else View.GONE
            listOf(snoozeDefault, snoozeChoice).forEach { it.isEnabled = allowed && state.enabled }
            snoozeChoice.visibility = if (state?.enabled == true) View.VISIBLE else View.GONE
            dismiss.visibility = snoozeChoice.visibility; dismiss.isEnabled = writable && state.enabled && !state.dismissed
            reenable.visibility = if (state?.needsReenable == true) View.VISIBLE else View.GONE
            problem.visibility = if (problem.text.isEmpty()) View.GONE else View.VISIBLE
            reload.visibility = if (!loading && (state == null || (!settingsLoading && preferences == null))) View.VISIBLE else View.GONE
            reload.isEnabled = !loading && !settingsLoading && !form.busy && !form.blocked && repository.isCurrent(form.lease)
        }
    }

    fun create(context: Mango9AppointmentCreationContext) = editor(context.lease, context.metadata, null, context)

    fun businessHours(lease: Mango9SessionLease, saved: (Mango9BusinessHours) -> Unit = {}) {
        hoursSaved = saved
        edits.businessHours(lease)
        resumeEdits()
    }

    private fun showBusinessHours(edit: Mango9BusinessHoursEdit) {
        val lease = edit.lease
        val form = Form(lease, title(R.string.mango9_calendar_hours), true, edit)
        val loading = form.info(title(R.string.mango9_calendar_refresh))
        val controls = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        // Build these controls once. Refresh only their values, preserving focus and
        // scroll position when a day, time, or timezone changes.
        val timezone = form.button("") {
            val draft = edit.draft ?: return@button
            if (!draft.timezoneEditable) return@button
            child(
                form,
                Mango9TimezonePicker.create(context, draft.timezone) { selected ->
                if (form.dialog.isShowing && !form.busy && !form.blocked && repository.isCurrent(lease)) {
                    edit.draft = edit.draft?.copy(timezone = selected)
                }
            }
            )
        }.apply { tag = "business-hours-timezone" }
        form.body.removeView(timezone); controls.addView(timezone)
        controls.addView(
            TextView(context).apply {
            text = title(R.string.mango9_calendar_hours_explanation); setPadding(0, dp(6), 0, dp(6))
        }
        )
        val defaults = form.button(title(R.string.mango9_calendar_default_hours)) { edit.draft = edit.draft?.useDefaults() }
        val closed = form.button(title(R.string.mango9_calendar_close_days)) {
            confirmation(form, title(R.string.mango9_calendar_close_days_confirm)) { edit.draft = edit.draft?.closeAll() }
        }
        for (button in listOf(defaults, closed)) { form.body.removeView(button); controls.addView(button) }
        var rendering = false

        data class DayRow(val check: CheckBox, val times: LinearLayout, val open: Button, val close: Button)
        val rows = (0..6).associateWith { weekday ->
            val line = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val check = CheckBox(context).apply {
                tag = "business-hours-day-$weekday"
                text = java.time.DayOfWeek.of(if (weekday == 0) 7 else weekday).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
                setOnCheckedChangeListener { _, checked ->
                    if (!rendering && !form.busy && !form.blocked) {
                        val draft = edit.draft
                        if (draft != null) {
                            val standard = edit.original?.defaults?.firstOrNull { it.weekday == weekday && it.isOpen }
                            val next = if (checked) Mango9BusinessDay(weekday, standard?.open ?: "09:00", standard?.close ?: "18:00") else Mango9BusinessDay(weekday, null, null)
                            edit.draft = draft.copy(configured = true, days = draft.days.map { if (it.weekday == weekday) next else it })
                        }
                    }
                }
            }
            val times = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val buttons = listOf(true, false).map { opening ->
                Button(context).apply {
                    isAllCaps = false; tag = "business-hours-$weekday-${if (opening) "open" else "close"}"
                    val openPicker = form.action("hours:$weekday:$opening") {
                        val day = edit.draft?.days?.firstOrNull { it.weekday == weekday }
                        if (day != null && !form.busy && !form.blocked && repository.isCurrent(lease)) {
                            val seconds = Mango9BusinessHours.seconds(if (opening) day.open else day.close) ?: 0
                            child(
                                form,
                                TimePickerDialog(context, { _, hour, minute ->
                                if (form.dialog.isShowing && !form.busy && !form.blocked && repository.isCurrent(lease)) {
                                    val value = java.time.LocalTime.of(hour, minute).format(DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.ROOT))
                                    edit.draft = edit.draft?.let { draft ->
                                        draft.copy(
                                            configured = true,
                                            days = draft.days.map {
                                        if (it.weekday != weekday) it else if (opening) it.copy(open = value) else it.copy(close = value)
                                    }
                                        )
                                    }
                                }
                            }, seconds / 3600, seconds % 3600 / 60, android.text.format.DateFormat.is24HourFormat(context))
                            )
                        }
                    }
                    setOnClickListener { openPicker() }
                    times.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
                }
            }
            line.addView(check); line.addView(times); controls.addView(line)
            DayRow(check, times, buttons[0], buttons[1])
        }
        form.body.addView(controls)
        val validation = form.info("").apply { setTextColor(context.getColor(R.color.mango9_calendar_error)) }
        form.recovery = form.button(title(R.string.mango9_calendar_hours_reload)) {}.apply {
            tag = "business-hours-reload"
            setOnClickListener { if (!form.busy && repository.isCurrent(lease)) edit.reload() }
        }
        form.show()
        form.bindEdit(render = {
            val draft = edit.draft
            form.restoreReady = draft != null
            loading.visibility = if (draft == null && edit.progress.value.busy) View.VISIBLE else View.GONE
            controls.visibility = if (draft == null) View.GONE else View.VISIBLE
            timezone.text = "${title(R.string.mango9_calendar_timezone_field)}\n${draft?.timezone?.replace('_', ' ').orEmpty()}"
            timezone.isEnabled = draft?.timezoneEditable == true
            rendering = true
            val labels = Mango9CalendarDateLabels(context)
            rows.forEach { (weekday, row) ->
                val day = draft?.days?.firstOrNull { it.weekday == weekday }
                row.check.isChecked = day?.isOpen == true
                row.times.visibility = if (day?.isOpen == true) View.VISIBLE else View.GONE
                row.open.text = labels.businessTime(day?.open); row.close.text = labels.businessTime(day?.close)
                row.open.contentDescription = context.getString(R.string.mango9_calendar_hours_opens, row.check.text, row.open.text)
                row.close.contentDescription = context.getString(R.string.mango9_calendar_hours_closes, row.check.text, row.close.text)
            }
            rendering = false
            validation.text = draft?.validationMessage.orEmpty()
            validation.visibility = if (draft?.validationMessage != null) View.VISIBLE else View.GONE
        }, accepted = { edit.accepted?.let(hoursSaved) })
        form.dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (edit.canSave) {
                if (edit.timezoneChanged) {
                    confirmation(form, title(R.string.mango9_calendar_timezone_warning)) {
                    edit.save()
                }
                } else {
                    edit.save()
                }
            }
        }
    }

    private fun editor(lease: Mango9SessionLease, metadata: Mango9CalendarMetadata, event: Mango9Appointment?, creation: Mango9AppointmentCreationContext?) {
        edits.appointment(lease, metadata, event, creation)
        resumeEdits()
    }

    private fun showEditor(edit: Mango9AppointmentEdit) {
        val lease = edit.lease
        val metadata = edit.metadata
        val event = edit.event
        val zone = edit.zone
        var draft by edit::draft
        val form =
            Form(lease, title(if (event == null) R.string.mango9_calendar_add else R.string.mango9_calendar_edit), true, edit)
        val canEdit = event?.permissions?.canEdit != false
        val owner = event == null || event.ownerId.toString() == lease.session.userId
        input(form, R.string.mango9_calendar_title_field, draft.title).apply {
            tag = "appointment-title"; isEnabled = canEdit
            doAfterTextChanged { draft = draft.copy(title = it.toString()) }
        }
        input(form, R.string.mango9_calendar_notes, draft.notes, true).apply {
            tag = "appointment-notes"; isEnabled = canEdit
            doAfterTextChanged { draft = draft.copy(notes = it.toString()) }
        }
        form.info(context.getString(R.string.mango9_calendar_zone, zone.id))
        lateinit var start: Button
        lateinit var end: Button

        fun updateDates() {
            val labels = Mango9CalendarDateLabels(context)
            start.text = fieldText(R.string.mango9_calendar_start, labels.dateTime(draft.start, zone))
            end.text = fieldText(R.string.mango9_calendar_end, labels.dateTime(draft.end, zone))
        }
        start = form.button(title(R.string.mango9_calendar_start)) {
            dateTime(form, draft.start, zone) { value ->
            val duration = java.time.Duration.between(draft.start, draft.end)
            draft = draft.copy(start = value, end = value.plus(duration)); updateDates()
        }
        }.apply { isEnabled = canEdit; tag = "appointment.starts" }
        end = form.button(title(R.string.mango9_calendar_end)) {
            dateTime(form, draft.end, zone) { draft = draft.copy(end = it); updateDates() }
        }.apply { isEnabled = canEdit; tag = "appointment.ends" }
        updateDates()

        fun field(label: Int, options: List<String>, initial: String, enabled: Boolean, selected: (String) -> Unit) {
            var current = initial
            lateinit var button: Button
            button = form.button(fieldText(label, Mango9CalendarLegend.humanize(current))) {
                choice(form, title(label), options.map(Mango9CalendarLegend::humanize), options.indexOf(current)) { index ->
                    current = options[index]; selected(current); button.text = fieldText(label, Mango9CalendarLegend.humanize(current))
                }
            }.apply { isEnabled = enabled && options.isNotEmpty() }
        }
        field(R.string.mango9_calendar_activity, metadata.activities, draft.activity, canEdit) {
            draft = draft.copy(activity = it)
        }
        field(R.string.mango9_calendar_priority_field, metadata.priorities, draft.priority, canEdit) {
            draft = draft.copy(priority = it)
        }
        val statuses = listOf(title(R.string.mango9_calendar_no_status)) + metadata.statuses.map { it.name }
        lateinit var status: Button

        fun updateStatus() {
            status.text = "${title(R.string.mango9_calendar_status)}: ${metadata.statuses.firstOrNull { it.id == draft.status }?.name ?: title(R.string.mango9_calendar_no_status)}"
        }
        status = form.button("") {
            choice(
                form, title(R.string.mango9_calendar_status), statuses,
                metadata.statuses.indexOfFirst {
                it.id == draft.status
            } + 1
            ) { index -> draft = draft.copy(status = metadata.statuses.getOrNull(index - 1)?.id); updateStatus() }
        }.apply { isEnabled = canEdit }
        updateStatus()
        lateinit var contact: Button

        fun updateContact() {
            contact.text = "${title(R.string.mango9_calendar_contact)}: ${draft.contact?.displayName ?: title(R.string.mango9_calendar_no_contact)}"
        }
        contact = form.button("") {
            contactPicker(form) {
                draft = draft.copy(contact = it); updateContact()
            }
        }.apply { isEnabled = event?.permissions?.canChangeContact != false }
        updateContact()
        if (event?.permissions?.canShare != false && metadata.shareRecipients.isNotEmpty()) {
            form.button(title(R.string.mango9_calendar_shares)) {
            multiChoice(
                form, title(R.string.mango9_calendar_shares),
                metadata.shareRecipients.map {
                it.name
            },
                metadata.shareRecipients.map { it.id in draft.shares }
            ) { chosen ->
                draft = draft.selectingShares(
                    metadata.shareRecipients.map {
                    it.id
                }.toSet(),
                    chosen.map { metadata.shareRecipients[it].id }.toSet()
                )
            }
        }
        }
        if ((event?.permissions?.canAssign ?: metadata.capabilities.assign) && metadata.assignees.isNotEmpty()) {
            lateinit var assign: Button
            assign = form.button("${title(R.string.mango9_calendar_assign)}: ${metadata.assignees.firstOrNull { it.id == draft.assignee }?.name ?: title(R.string.mango9_calendar_keep_owner)}") {
                choice(
                    form, title(R.string.mango9_calendar_assign),
                    listOf(title(R.string.mango9_calendar_keep_owner)) + metadata.assignees.map {
                    it.name
                },
                    metadata.assignees.indexOfFirst { it.id == draft.assignee } + 1
                ) { index ->
                    draft = draft.copy(assignee = metadata.assignees.getOrNull(index - 1)?.id)
                    assign.text = "${title(R.string.mango9_calendar_assign)}: ${metadata.assignees.getOrNull(index - 1)?.name ?: title(R.string.mango9_calendar_keep_owner)}"
                }
            }
        }
        if (owner && canEdit) {
            val minutes = listOf(-1) + metadata.reminderMinutes
            lateinit var reminder: Button

            fun updateReminder() {
                reminder.text = "${title(R.string.mango9_calendar_reminder)}: ${if (draft.reminder < 0) title(R.string.mango9_calendar_reminder_off) else context.getString(R.string.mango9_calendar_minutes_before, draft.reminder)}"
            }
            reminder = form.button("") {
                choice(
                    form, title(R.string.mango9_calendar_reminder),
                    minutes.map {
                    if (it < 0) title(R.string.mango9_calendar_reminder_off) else context.getString(R.string.mango9_calendar_minutes_before, it)
                },
                    minutes.indexOf(draft.reminder)
                ) { index ->
                draft = draft.copy(reminder = minutes[index]); updateReminder()
            }
            }
            updateReminder()
            form.button(title(R.string.mango9_calendar_channels)) {
                multiChoice(
                    form, title(R.string.mango9_calendar_channels), metadata.reminderChannels,
                    metadata.reminderChannels.map {
                    it in draft.channels
                }
                ) { indices -> draft = draft.copy(channels = indices.map { metadata.reminderChannels[it] }) }
            }
        }
        form.show()
        val validation = form.info("").apply {
            setTextColor(context.getColor(R.color.mango9_calendar_error)); tag = "appointment-validation"
        }
        form.bindEdit(render = {
            validation.text = edit.validation.orEmpty()
            validation.visibility = if (draft.title.isNotBlank() && edit.validation != null) View.VISIBLE else View.GONE
        })
        form.dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (!edit.canSave) return@setOnClickListener
            val save: () -> Unit = { edit.save(); Unit }
            val assignee = metadata.assignees.firstOrNull { it.id == draft.assignee }
            if (assignee != null) confirmation(form, context.getString(R.string.mango9_calendar_assignment_warning, assignee.name), accept = save) else save()
        }
    }

    private fun input(form: Form, label: Int, value: String, multiline: Boolean = false): TextInputEditText {
        val layout = TextInputLayout(context).apply {
            hint = title(label); form.body.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        return TextInputEditText(context).apply {
            tag = "calendar.input.$label"
            setText(value); inputType = InputType.TYPE_CLASS_TEXT or if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; if (multiline) minLines = 3; layout.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun choice(form: Form, caption: String, values: List<String>, selected: Int, choose: (Int) -> Unit) {
        val dialog = MaterialAlertDialogBuilder(context).setTitle(caption).setSingleChoiceItems(values.toTypedArray(), selected) { picker, index ->
            picker.dismiss(); if (form.dialog.isShowing && !form.busy && !form.blocked && repository.isCurrent(form.lease)) choose(index)
        }.setNegativeButton(R.string.mango9_calendar_cancel, null).create()
        child(form, dialog)
    }

    private fun multiChoice(form: Form, caption: String, values: List<String>, initial: List<Boolean>, done: (List<Int>) -> Unit) {
        val selected = initial.toBooleanArray()
        val dialog = MaterialAlertDialogBuilder(context).setTitle(caption).setMultiChoiceItems(values.toTypedArray(), selected) { _, i, checked -> selected[i] = checked }
            .setPositiveButton(R.string.mango9_calendar_done) { picker, _ ->
                if (form.dialog.isShowing && !form.busy && !form.blocked && repository.isCurrent(form.lease)) {
                done(
                    selected.indices.filter {
                (picker as AlertDialog).listView.isItemChecked(it)
            }
                )
            }
            }
            .setNegativeButton(R.string.mango9_calendar_cancel, null).create()
        child(form, dialog)
    }

    private fun confirmation(form: Form, message: String, positive: Int = R.string.mango9_calendar_done, accept: () -> Unit) {
        child(form, MaterialAlertDialogBuilder(context).setMessage(message).setPositiveButton(positive) { _, _ -> if (form.dialog.isShowing && !form.busy && !form.blocked && repository.isCurrent(form.lease)) accept() }.setNegativeButton(R.string.mango9_calendar_cancel, null).create(), restorable = false)
    }

    private fun child(form: Form, dialog: android.app.Dialog, stage: String = "picker", values: Bundle = Bundle(), restorable: Boolean = true) {
        val checkpoint = if (restorable) {
            form.checkpoint(stage, values)
        } else {
            form.presentation.child = null
            Mango9CalendarFormState.Child("", "confirmation")
        }
        dialog.show()
        checkpoint.snapshot?.let(dialog::onRestoreInstanceState)
        if (dialog is TimePickerDialog) {
            // Native snapshots also store the old clock mode. Keep the selected
            // wall time but respect a preference changed while the app was away.
            fun updateClock(view: View) {
                if (view is android.widget.TimePicker) {
                    val hour = view.hour; val minute = view.minute
                    view.setIs24HourView(android.text.format.DateFormat.is24HourFormat(context))
                    view.hour = hour; view.minute = minute
                } else if (view is ViewGroup) for (index in 0 until view.childCount) updateClock(view.getChildAt(index))
            }
            dialog.window?.decorView?.let(::updateClock)
        }
        form.attachChild(dialog, checkpoint)
    }

    private fun dateTime(form: Form, instant: Instant, zone: ZoneId, selected: (Instant) -> Unit) {
        val time = instant.atZone(zone)

        fun chooseTime(date: java.time.LocalDate) {
            child(
                form,
                TimePickerDialog(context, { _, hour, minute ->
                if (form.dialog.isShowing && !form.busy && !form.blocked && repository.isCurrent(form.lease)) {
                    selected(date.atTime(hour, minute).atZone(zone).toInstant())
                }
            }, time.hour, time.minute, android.text.format.DateFormat.is24HourFormat(context)),
                "time", Bundle().apply { putString("date", date.toString()) }
            )
        }
        val pending = form.presentation.child?.takeIf { it.action == form.actionKey && it.stage == "time" }
        val pendingDate = pending?.values?.getString("date")?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
        if (pendingDate != null) {
            chooseTime(pendingDate)
        } else {
            child(
                form,
                DatePickerDialog(context, { _, year, month, day ->
                if (form.dialog.isShowing && !form.busy && !form.blocked && repository.isCurrent(form.lease)) chooseTime(java.time.LocalDate.of(year, month + 1, day))
            }, time.year, time.monthValue - 1, time.dayOfMonth),
                "date"
            )
        }
    }

    private fun contactPicker(owner: Form, selected: (Mango9AppointmentContact?) -> Unit) {
        val checkpoint = owner.checkpoint("contacts")
        val saved = checkpoint.values
        val form = Form(owner.lease, title(R.string.mango9_calendar_contact), presentation = checkpoint.form)
        form.restoreReady = false
        var kind = if (saved.getBoolean("clients")) Mango9RecordKind.Client else Mango9RecordKind.Lead
        var page = saved.getInt("page", 1); var pages = saved.getInt("pages", 1)
        var request = 0L; var job: Job? = null; var loading = false
        var retryPage = saved.getInt("requestedPage", page)
        val search = input(form, R.string.mango9_calendar_search, saved.getString("query", "")).apply {
            tag = "calendar.contact.search"
        }
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val empty = form.info(title(R.string.mango9_calendar_no_matching_records)).apply { visibility = View.GONE }
        lateinit var more: Button; lateinit var previous: Button; lateinit var retry: Button

        fun current(generation: Long) = generation == request && form.dialog.isShowing && owner.dialog.isShowing && repository.isCurrent(owner.lease)

        fun pager() {
            previous.isEnabled = !loading && page > 1; more.isEnabled = !loading && page < pages; retry.isEnabled = !loading
        }

        fun load(target: Int) {
            job?.cancel(); val generation = ++request
            loading = true; retryPage = target.coerceAtLeast(1); saved.putInt("requestedPage", retryPage)
            pager(); retry.visibility = View.GONE; empty.visibility = View.GONE
            val query = search.text.toString(); val selectedKind = kind
            job = form.scope.launch {
                try {
                    delay(250)
                    val response = loadContacts(owner.lease, selectedKind, query, target)
                    if (!current(generation)) return@launch
                    // Keep the picker bounded. Paging replaces the page rather than
                    // inflating every CRM contact into this dialog. Commit the cursor
                    // only after success, so a failed Next request cannot skip records.
                    page = target; pages = response.pagination.pages
                    saved.putInt("page", page); saved.putInt("pages", pages); saved.remove("requestedPage")
                    list.removeAllViews()
                    response.records.forEach { record ->
                        list.addView(
                            Button(context).apply {
                        text = record.name; isAllCaps = false; tag = "calendar.contact.record.${selectedKind.name}.${record.id}"
                        setOnClickListener {
                            if (current(generation) && !owner.busy && !owner.blocked) {
                                selected(Mango9AppointmentContact(record.id, record.name, if (selectedKind == Mango9RecordKind.Lead) "lead" else "client")); form.dialog.dismiss()
                            }
                        }
                    }
                        )
                    }
                    empty.visibility = if (response.records.isEmpty()) View.VISIBLE else View.GONE
                    form.error.visibility = View.GONE
                    form.restoreReady = true; form.restore()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (current(generation)) { form.message(Mango9CalendarScreen.message(error)); retry.visibility = View.VISIBLE }
                } finally { if (current(generation)) { loading = false; pager() } }
            }
        }

        fun reset() {
            page = 1; pages = 1; saved.putInt("page", 1); saved.putInt("pages", 1)
            saved.putString("query", search.text.toString()); saved.putBoolean("clients", kind == Mango9RecordKind.Client)
            list.removeAllViews(); load(1)
        }
        lateinit var type: Button

        fun typeLabel() = title(if (kind == Mango9RecordKind.Lead) R.string.mango9_calendar_contact_leads else R.string.mango9_calendar_contact_clients)
        type = form.button(typeLabel()) {
            kind = if (kind == Mango9RecordKind.Lead) Mango9RecordKind.Client else Mango9RecordKind.Lead; type.text = typeLabel(); reset()
        }.apply { tag = "calendar.contact.kind" }
        form.button(title(R.string.mango9_calendar_no_contact)) { selected(null); form.dialog.dismiss() }
        form.body.addView(list)
        previous = form.button(title(R.string.mango9_calendar_previous)) {
            if (!loading && page > 1) load(page - 1)
        }.apply { tag = "calendar.contact.previous" }
        more = form.button(title(R.string.mango9_calendar_next)) {
            if (!loading && page < pages) load(page + 1)
        }.apply { tag = "calendar.contact.next" }
        retry = form.button(title(R.string.mango9_calendar_refresh)) {
            if (!loading) load(retryPage)
        }.apply { tag = "calendar.contact.retry"; visibility = View.GONE }
        search.doAfterTextChanged { reset() }
        form.show()
        form.afterDismiss = owner.attachChild(form.dialog, checkpoint, installListener = false)
        load(retryPage)
    }
}
