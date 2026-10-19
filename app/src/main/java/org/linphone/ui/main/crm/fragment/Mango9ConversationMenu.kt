// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.ui.main.crm.fragment

import android.view.View
import android.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.linphone.R
import org.linphone.mango9.*
import org.linphone.ui.main.crm.adapter.Mango9MessagingListItem

internal fun Fragment.showMango9ConversationMenu(anchor: View, item: Mango9MessagingListItem, refresh: () -> Unit) {
    val sessions = Mango9SessionStore(requireContext())
    val lease = sessions.captureSession() ?: return
    val (channel, id) = when (item) {
        is Mango9MessagingListItem.Sms -> Mango9MessageChannel.sms to item.party.phone
        is Mango9MessagingListItem.Group -> Mango9MessageChannel.team to item.room.id
        is Mango9MessagingListItem.User -> Mango9MessageChannel.team to (item.room?.id ?: return)
        is Mango9MessagingListItem.Social -> Mango9MessageChannel.social to item.thread.id
    }
    val notifications = Mango9ConversationNotifications(requireContext())
    val owner = view

    fun valid() = view === owner && sessions.currentSession(lease) != null

    fun action(block: suspend () -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch {
        try { if (valid()) block(); if (valid()) refresh() } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (valid()) MaterialAlertDialogBuilder(requireContext()).setMessage(e.message).setPositiveButton(android.R.string.ok, null).show()
        }
    }
    }
    PopupMenu(requireContext(), anchor).apply {
        menu.add(if (notifications.cached(lease, channel, id)) R.string.mango9_sms_unmute else R.string.mango9_sms_mute).setOnMenuItemClickListener {
            action {
                val actual = notifications.request(lease, channel, id); if (valid()) notifications.request(lease, channel, id, !actual)
            }; true
        }
        menu.add(R.string.mango9_delete_for_me).setOnMenuItemClickListener {
            if (valid()) {
                MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.mango9_delete_for_me)
                .setMessage(R.string.mango9_delete_for_me_info).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.mango9_delete_for_me) { _, _ ->
                    action {
                    Mango9MessageCommands(requireContext()).visibility(lease, channel, null, listOf(id), true, true)
                }
                }.show()
            }
            true
        }
    }.show()
}
