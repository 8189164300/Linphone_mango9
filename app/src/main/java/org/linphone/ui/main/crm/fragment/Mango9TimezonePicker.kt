/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.ui.main.crm.fragment

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import java.time.ZoneId
import java.util.Locale
import org.linphone.R

/** Known zone identifiers only; no free-form text can be submitted as a time zone. */
internal object Mango9TimezonePicker {
    private val zones by lazy { (ZoneId.getAvailableZoneIds() + "UTC").sorted() }

    fun matchingZones(query: String): List<String> {
        val search = query.replace('_', ' ').trim().lowercase(Locale.ROOT)
        return zones.filter { it.replace('_', ' ').lowercase(Locale.ROOT).contains(search) }
    }

    fun create(
        context: Context, selection: String, accountTimezone: String? = null,
        choose: (String) -> Unit,
    ): AlertDialog {
        val device = ZoneId.systemDefault().id
        val shortcuts = if (accountTimezone != null) {
            listOf(
            "account" to context.getString(R.string.mango9_crm_zone_account, accountTimezone),
            "device" to context.getString(R.string.mango9_crm_zone_device, device),
        )
        } else {
            listOf(device to context.getString(R.string.mango9_crm_zone_phone, device))
        }
        val dp = context.resources.displayMetrics.density
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), 0, (20 * dp).toInt(), 0)
        }
        val search = TextInputEditText(context).apply {
            id = R.id.mango9_calendar_zone_search
            hint = context.getString(R.string.mango9_crm_zone_search)
            contentDescription = hint; isSingleLine = true; tag = "crm.zone.search"
        }
        val list = ListView(context).apply { id = R.id.mango9_calendar_zone_list; choiceMode = ListView.CHOICE_MODE_SINGLE }
        body.addView(search, LinearLayout.LayoutParams(-1, -2))
        val height = minOf((380 * dp).toInt(), (context.resources.displayMetrics.heightPixels * 0.5f).toInt())
        body.addView(list, LinearLayout.LayoutParams(-1, height))
        val dialog = MaterialAlertDialogBuilder(context).setTitle(R.string.mango9_calendar_timezone_field)
            .setView(body).setNegativeButton(R.string.mango9_calendar_cancel, null).create()
        var choices = emptyList<Pair<String, String>>()

        fun filter() {
            choices = shortcuts + matchingZones(search.text?.toString().orEmpty()).map { it to it.replace('_', ' ') }
            list.adapter = ArrayAdapter(context, android.R.layout.simple_list_item_single_choice, choices.map { it.second })
            val selected = choices.indexOfFirst { it.first == selection }
            if (selected >= 0) list.setItemChecked(selected, true)
        }
        search.doAfterTextChanged { filter() }
        list.setOnItemClickListener { _, _, position, _ ->
            choices.getOrNull(position)?.let { choose(it.first); dialog.dismiss() }
        }
        filter()
        return dialog
    }
}
