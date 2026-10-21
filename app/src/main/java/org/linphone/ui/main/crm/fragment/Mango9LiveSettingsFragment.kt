// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.ui.main.crm.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.fragment.app.viewModels
import com.google.android.material.materialswitch.MaterialSwitch
import org.json.JSONObject
import org.linphone.R
import org.linphone.mango9.*
import org.linphone.ui.main.crm.viewmodel.Mango9LiveChatViewModel
import org.linphone.ui.main.fragment.GenericMainFragment

class Mango9LiveSettingsFragment : GenericMainFragment() {
    private val model: Mango9LiveChatViewModel by viewModels()
    private lateinit var ui: Mango9LiveViews
    private lateinit var content: LinearLayout
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        ui = Mango9LiveViews(requireContext())
        return ui.column().apply {
            setBackgroundColor(ui.surface)
            addView(ui.row().apply {
                addView(ui.icon(R.drawable.caret_left, "Back") { goBack() }); addView(ui.text("Live Chat settings", 18f, true))
            })
            content = ui.column(); ui.padded(content, 16, 4)
            addView(ScrollView(context).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = arguments ?: Bundle().also { arguments = it }
        model.bind(Mango9NavigationSession.resolve(args, Mango9SessionStore(requireContext())))
        model.state.observe(viewLifecycleOwner) { state ->
            content.removeAllViews()
            state.error?.let { content.addView(ui.button("$it  Retry") { model.refresh(force = true) }) }
            if (state.loading && state.sites.isEmpty()) content.addView(ui.text("Loading websites…"))
            if (!state.loading && state.sites.isEmpty()) content.addView(ui.text("No websites are connected to this account yet."))
            state.sites.forEach { site ->
                content.addView(ui.padded(ui.text(site.liveText("name"), 18f, true), 0, 12))
                fun row(title: String, enabled: Boolean) { content.addView(ui.padded(ui.text("$title · ${if (enabled) "Enabled" else "Disabled"}"), 0, 8)) }
                row("Website chat", site.optBoolean("enabled"))
                val preference = state.preferences.firstOrNull { it.liveText("siteId") == site.liveText("id") }
                listOf("push" to "Push notifications", "email" to "Email notifications").forEach { (field, label) ->
                    content.addView(MaterialSwitch(requireContext()).apply {
                        text = label; isChecked = preference?.optBoolean(field, true) ?: true; isEnabled = !state.sending
                        minHeight = ui.dp(48)
                        setOnCheckedChangeListener { _, value -> model.action("preferences", JSONObject().put("siteId", site.getString("id"))
                            .put("push", if (field == "push") value else preference?.optBoolean("push", true) ?: true)
                            .put("email", if (field == "email") value else preference?.optBoolean("email", true) ?: true)) }
                    })
                }
                val settings = site.optJSONObject("settings") ?: JSONObject()
                listOf("typingIndicators" to "Typing indicators", "readReceipts" to "Read receipts", "attachmentsEnabled" to "Attachments",
                    "trackCountry" to "Approximate location", "collectIp" to "IP address", "collectDevice" to "Device details", "collectReferrer" to "Referrer")
                    .forEach { (key, title) -> row(title, settings.optBoolean(key)) }
                content.addView(ui.line())
            }
            content.addView(ui.padded(ui.text("Website settings and staff access are managed in Connections on the web. Notification preferences apply to your account for each website.", 12f), 0, 16))
        }
        model.refresh(force = true)
    }
    override fun onResume() { super.onResume(); if (model.owner() == null) goBack() }
}
