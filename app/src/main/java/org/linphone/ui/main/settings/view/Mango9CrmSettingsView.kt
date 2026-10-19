/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.ui.main.settings.view

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.provider.Settings
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import java.time.ZoneId
import kotlinx.coroutines.launch
import org.linphone.R
import org.linphone.mango9.Mango9AppointmentActions
import org.linphone.mango9.Mango9CrmPreferences
import org.linphone.mango9.Mango9CrmSettingsScreen
import org.linphone.mango9.Mango9CrmSettingsState
import org.linphone.mango9.Mango9SessionLease
import org.linphone.ui.main.crm.fragment.Mango9TimezonePicker

/** Stable controls, natural text height and native dialogs. No row rebuilding on each change. */
class Mango9CrmSettingsView
    @JvmOverloads
    constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    private val navy = 0xff444dcd.toInt()
    private val primaryTextColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, 0xff202020.toInt())
    private val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, 0xffffffff.toInt())
    private val heading = Button(context).apply {
        text = text(R.string.mango9_crm_settings_title); isAllCaps = false; textSize = 20f
        gravity = Gravity.CENTER_VERTICAL or Gravity.START; setTextColor(navy)
        tag = "crm.expand"; setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }
    private val body = column()
    private val account = label()
    private val fields = column()
    private val appointments = card(R.string.mango9_crm_appointments, R.drawable.bell_ringing)
    private val calendar = card(R.string.mango9_crm_calendar, R.drawable.calendar)
    private val messages = card(R.string.mango9_crm_messages, R.drawable.chat_teardrop_text)
    private val reminders =
        toggle(appointments, R.string.mango9_crm_reminders, "crm.reminders") { p, v -> p.copy(inAppReminders = v) }
    private val before = option(appointments, R.string.mango9_crm_reminder_before, "crm.before")
    private val repeat = option(appointments, R.string.mango9_crm_reminder_repeat, "crm.repeat")
    private val snooze = option(appointments, R.string.mango9_crm_reminder_snooze, "crm.snooze")
    private val mode = option(calendar, R.string.mango9_crm_calendar_default, "crm.mode")
    private val weekday = option(calendar, R.string.mango9_crm_weekday, "crm.weekday")
    private val timezone = option(calendar, R.string.mango9_crm_new_zone, "crm.zone")
    private val calendarInfo = label()
    private val sms = toggle(messages, R.string.mango9_crm_sms_push, "crm.sms") { p, v -> p.copy(smsPush = v) }
    private val team = toggle(messages, R.string.mango9_crm_team_push, "crm.team") { p, v -> p.copy(teamChatPush = v) }
    private val social =
        toggle(messages, R.string.mango9_crm_social_push, "crm.social") { p, v -> p.copy(socialPush = v) }
    private val leads = toggle(messages, R.string.mango9_crm_lead_push, "crm.leads") { p, v -> p.copy(leadPush = v) }
    private val events =
        toggle(messages, R.string.mango9_crm_events_push, "crm.events") { p, v -> p.copy(eventsPush = v) }
    private val newerControlsInfo = label()
    private val permission = label().apply { tag = "crm.notification.permission" }
    private val notificationSettings = option(messages, R.string.mango9_crm_notification_settings, "crm.notifications")
    private val error = label().apply {
        setTextColor(0xffbb3030.toInt()); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        tag = "crm.error"
    }
    private val reload = Button(context).apply {
        text = text(R.string.mango9_crm_reload); isAllCaps = false; tag = "crm.reload"
    }
    private val progress = ProgressBar(context)
    private val saved = label().apply {
        text = text(R.string.mango9_crm_saved); setTextColor(0xff167841.toInt()); tag = "crm.saved"
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val save = Button(context).apply {
        text = text(R.string.mango9_crm_settings_save); isAllCaps = false; tag = "crm.save"
    }
    private var screen: Mango9CrmSettingsScreen? = null
    private var rendering = false
    private var shownLease: Mango9SessionLease? = null
    private var picker: AlertDialog? = null

    init {
        orientation = VERTICAL
        for (button in listOf(heading, before, repeat, snooze, mode, weekday, timezone, notificationSettings, reload, save)) {
            button.backgroundTintList = null
            button.background = RippleDrawable(
                ColorStateList.valueOf(0x22444dcd),
                GradientDrawable().apply { setColor(0x09444dcd); cornerRadius = dp(12).toFloat() }, null
            )
            button.setPadding(dp(12), dp(12), dp(12), dp(12))
            button.setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()), intArrayOf(navy, 0xff888888.toInt())))
        }
        for (toggle in listOf(reminders, sms, team, social, leads, events)) {
            toggle.thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(navy, 0xff9295a5.toInt()))
            toggle.trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(0xffd9dcf8.toInt(), 0xffdedfe5.toInt()))
        }
        addView(heading, LayoutParams(-1, -2))
        addView(body, LayoutParams(-1, -2))
        body.addView(account)
        body.addView(label().apply { text = text(R.string.mango9_crm_scope) })
        body.addView(fields)
        listOf(appointments, calendar, messages).forEach {
            fields.addView(it, LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        appointments.addView(label().apply { text = text(R.string.mango9_crm_reminder_info) })
        calendar.addView(calendarInfo)
        messages.addView(label().apply { text = text(R.string.mango9_crm_push_info) })
        messages.addView(newerControlsInfo)
        messages.addView(label().apply { text = text(R.string.mango9_crm_events_push_info) })
        messages.addView(permission)
        body.addView(error); body.addView(reload)
        val controls = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        controls.addView(progress, LayoutParams(dp(24), dp(24)))
        controls.addView(saved, LayoutParams(0, -2, 1f))
        controls.addView(save, LayoutParams(-2, -2))
        body.addView(controls)
        heading.setOnClickListener { screen?.let { it.setExpanded(!it.state.value.expanded) } }
        reload.setOnClickListener {
            val model = screen ?: return@setOnClickListener
            val lease = model.capture()
            if (model.state.value.draft != model.state.value.baseline) {
                show(
                    MaterialAlertDialogBuilder(context).setMessage(R.string.mango9_crm_discard)
                    .setNegativeButton(R.string.mango9_calendar_cancel, null)
                    .setPositiveButton(R.string.mango9_crm_reload) { _, _ ->
                        if (model.capture() == lease) model.reload()
                    }.create()
                )
            } else {
                model.reload()
            }
        }
        save.setOnClickListener { screen?.save() }
        before.setOnClickListener {
            choices(R.string.mango9_crm_reminder_before, Mango9AppointmentActions.reminderChoices, {
                reminderLabel(it)
            }) { p, v -> p.copy(reminderMinutes = v) }
        }
        repeat.setOnClickListener {
            choices(R.string.mango9_crm_reminder_repeat, listOf(0) + Mango9AppointmentActions.minuteChoices, {
                repeatLabel(it)
            }) { p, v -> p.copy(repeatMinutes = v) }
        }
        snooze.setOnClickListener {
            choices(R.string.mango9_crm_reminder_snooze, Mango9AppointmentActions.minuteChoices, {
                minutes(it)
            }) { p, v -> p.copy(snoozeMinutes = v) }
        }
        mode.setOnClickListener {
            choices(R.string.mango9_crm_calendar_default, listOf("appointments", "calendar"), {
                modeLabel(it)
            }) { p, v -> p.copy(defaultCalendarView = v) }
        }
        weekday.setOnClickListener {
            choices(R.string.mango9_crm_weekday, listOf(0, 1, 2), { weekdayLabel(it) }) { p, v -> p.copy(firstWeekday = v) }
        }
        timezone.setOnClickListener {
            val model = screen ?: return@setOnClickListener
            val lease = model.capture() ?: return@setOnClickListener
            val draft = model.state.value.draft ?: return@setOnClickListener
            show(
                Mango9TimezonePicker.create(context, draft.newAppointmentTimezone, draft.accountTimezone) { zone ->
                model.edit(lease) { it.copy(newAppointmentTimezone = zone) }
            }
            )
        }
        notificationSettings.setOnClickListener {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            runCatching { context.startActivity(intent) }
        }
        render(Mango9CrmSettingsState())
    }

    fun bind(model: Mango9CrmSettingsScreen, owner: LifecycleOwner) {
        screen = model
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.accountChanged()
                model.state.collect { render(it) }
            }
        }
    }

    fun render(state: Mango9CrmSettingsState) {
        val current = screen?.capture()
        if (shownLease != current) { picker?.dismiss(); picker = null; shownLease = current }
        rendering = true
        body.visibility = if (state.expanded) VISIBLE else GONE
        heading.setCompoundDrawablesRelative(icon(R.drawable.gear), null, icon(if (state.expanded) R.drawable.caret_up else R.drawable.caret_down), null)
        heading.compoundDrawablePadding = dp(10)
        ViewCompat.setStateDescription(heading, text(if (state.expanded) R.string.mango9_crm_expanded else R.string.mango9_crm_collapsed))
        account.text = state.accountName ?: text(R.string.mango9_crm_connect)
        fields.visibility = if (state.draft != null) VISIBLE else GONE
        state.draft?.let { p ->
            reminders.isChecked = p.inAppReminders; sms.isChecked = p.smsPush; team.isChecked = p.teamChatPush
            listOf(social to p.socialPush, leads to p.leadPush, events to p.eventsPush).forEach { (control, value) ->
                control.isChecked = value == true
                control.isEnabled = !state.busy && value != null
            }
            newerControlsInfo.text = text(R.string.mango9_crm_push_update_required)
            newerControlsInfo.visibility = if (listOf(p.socialPush, p.leadPush, p.eventsPush).any { it == null }) VISIBLE else GONE
            before.text = optionLabel(R.string.mango9_crm_reminder_before, reminderLabel(p.reminderMinutes))
            repeat.text = optionLabel(R.string.mango9_crm_reminder_repeat, repeatLabel(p.repeatMinutes))
            snooze.text = optionLabel(R.string.mango9_crm_reminder_snooze, minutes(p.snoozeMinutes))
            mode.text = optionLabel(R.string.mango9_crm_calendar_default, modeLabel(p.defaultCalendarView))
            weekday.text = optionLabel(R.string.mango9_crm_weekday, weekdayLabel(p.firstWeekday))
            val zone = when (p.newAppointmentTimezone) {
                "account" -> context.getString(R.string.mango9_crm_zone_account, p.accountTimezone)
                "device" -> context.getString(R.string.mango9_crm_zone_device, ZoneId.systemDefault().id)
                else -> p.newAppointmentTimezone.replace('_', ' ')
            }
            timezone.text = optionLabel(R.string.mango9_crm_new_zone, zone)
            listOf(reminders, sms, team, mode, weekday, timezone).forEach { it.isEnabled = !state.busy }
            listOf(before, repeat, snooze).forEach { it.isEnabled = !state.busy && p.inAppReminders }
        }
        calendarInfo.text = context.getString(R.string.mango9_crm_zone_info, ZoneId.systemDefault().id)
        permission.text = text(if (NotificationManagerCompat.from(context).areNotificationsEnabled()) R.string.mango9_crm_permission_on else R.string.mango9_crm_permission_off)
        error.text = state.error.orEmpty(); error.visibility = if (state.error == null) GONE else VISIBLE
        reload.isEnabled = !state.busy && state.accountName != null
        progress.visibility = if (state.busy) VISIBLE else INVISIBLE
        saved.visibility = if (state.saved && !state.busy) VISIBLE else INVISIBLE
        save.isEnabled = state.canSave
        rendering = false
    }

    private fun <T> choices(title: Int, values: List<T>, label: (T) -> String, apply: (Mango9CrmPreferences, T) -> Mango9CrmPreferences) {
        val model = screen ?: return
        val lease = model.capture() ?: return
        show(
            MaterialAlertDialogBuilder(context).setTitle(title).setItems(values.map(label).toTypedArray()) { _, position ->
            model.edit(lease) { apply(it, values[position]) }
        }.setNegativeButton(R.string.mango9_calendar_cancel, null).create()
        )
    }

    private fun show(dialog: AlertDialog) { picker?.dismiss(); picker = dialog; dialog.show() }

    override fun onDetachedFromWindow() { picker?.dismiss(); picker = null; super.onDetachedFromWindow() }

    private fun toggle(parent: LinearLayout, title: Int, key: String, apply: (Mango9CrmPreferences, Boolean) -> Mango9CrmPreferences) = SwitchMaterial(context).apply {
        text = text(title); textSize = 16f; tag = key; setTextColor(primaryTextColor)
        setPadding(0, dp(8), 0, dp(8)); minHeight = dp(52)
        setOnCheckedChangeListener { _, value ->
            if (!rendering) {
            screen?.let { model ->
                model.edit(model.capture()) {
            apply(it, value)
        }
        }
        }
        }
        parent.addView(this, LayoutParams(-1, -2))
    }

    private fun option(parent: LinearLayout, title: Int, key: String) = Button(context).apply {
        text = text(title); textSize = 16f; isAllCaps = false; gravity = Gravity.START or Gravity.CENTER_VERTICAL
        minHeight = dp(56); tag = key; setTextColor(navy)
        parent.addView(this, LayoutParams(-1, -2))
    }

    private fun card(title: Int, drawable: Int) = column().apply {
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = GradientDrawable().apply { setColor(surface); cornerRadius = dp(16).toFloat() }
        addView(
            label().apply {
            text = text(title); textSize = 18f; setTypeface(typeface, Typeface.BOLD)
            setCompoundDrawablesRelative(icon(drawable), null, null, null); compoundDrawablePadding = dp(10)
        }
        )
    }

    private fun label() = TextView(context).apply {
        textSize = 14f; setTextColor(primaryTextColor); setPadding(0, dp(6), 0, dp(6))
    }

    private fun column() = LinearLayout(context).apply { orientation = VERTICAL }

    private fun icon(id: Int) = ContextCompat.getDrawable(context, id)?.mutate()?.apply {
        setTint(navy); setBounds(0, 0, dp(24), dp(24))
    }

    private fun text(id: Int) = context.getString(id)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun optionLabel(id: Int, value: String) = "${text(id)}\n$value"

    private fun minutes(value: Int) = resources.getQuantityString(R.plurals.mango9_crm_minutes, value, value)

    private fun reminderLabel(value: Int) = when (value) {
        0 -> text(R.string.mango9_crm_at_start)
        1440 -> text(R.string.mango9_crm_day_before)
        else -> resources.getQuantityString(R.plurals.mango9_crm_minutes_before, value, value)
    }

    private fun repeatLabel(value: Int) = if (value == 0) text(R.string.mango9_crm_once) else resources.getQuantityString(R.plurals.mango9_crm_every_minutes, value, value)

    private fun modeLabel(value: String) = text(if (value == "calendar") R.string.mango9_crm_calendar else R.string.mango9_crm_appointment_list)

    private fun weekdayLabel(value: Int) = text(when (value) { 1 -> R.string.mango9_crm_sunday; 2 -> R.string.mango9_crm_monday; else -> R.string.mango9_crm_device_default })
}
