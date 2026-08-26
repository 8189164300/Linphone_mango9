/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.ui.main.crm.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.linphone.R
import org.linphone.databinding.Mango9MessagingListCellBinding
import org.linphone.mango9.Mango9ChatRoom
import org.linphone.mango9.Mango9ChatUser
import org.linphone.mango9.Mango9SmsParty

sealed class Mango9MessagingListItem(val stableId: String) {
    data class Group(val room: Mango9ChatRoom, val title: String) : Mango9MessagingListItem("group:${room.id}")

    data class User(
        val user: Mango9ChatUser,
        val room: Mango9ChatRoom?,
        val online: Boolean,
    ) : Mango9MessagingListItem("user:${user.id}")

    data class Sms(val party: Mango9SmsParty) : Mango9MessagingListItem("sms:${party.phone}")
}

class Mango9MessagingListAdapter(
    private val onClick: (Mango9MessagingListItem) -> Unit,
) : ListAdapter<Mango9MessagingListItem, Mango9MessagingListAdapter.ViewHolder>(DiffCallback) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = Mango9MessagingListCellBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    inner class ViewHolder(private val binding: Mango9MessagingListCellBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: Mango9MessagingListItem) {
            val context = binding.root.context
            val title: String
            val preview: String
            val unread: Int
            val online: Boolean?
            val initials: String
            when (item) {
                is Mango9MessagingListItem.Group -> {
                    title = item.title
                    preview = item.room.lastMessage.ifBlank {
                        context.getString(R.string.mango9_chat_group_status, item.room.userIds.size + 1, 0)
                    }
                    unread = item.room.unread
                    online = null
                    initials = "G"
                }
                is Mango9MessagingListItem.User -> {
                    title = item.user.name
                    preview = item.room?.lastMessage?.takeIf(String::isNotBlank)
                        ?: item.user.category.replaceFirstChar(Char::uppercase).ifBlank {
                            context.getString(
                                if (item.online) R.string.mango9_chat_online_label else R.string.mango9_chat_offline_label,
                            )
                        }
                    unread = item.room?.unread ?: 0
                    online = item.online
                    initials = initials(title)
                }
                is Mango9MessagingListItem.Sms -> {
                    title = formattedPhone(item.party.phone)
                    preview = item.party.lastMessage.ifBlank { context.getString(R.string.mango9_chat_sms_label) }
                    unread = item.party.unread
                    online = null
                    initials = "SMS"
                }
            }
            binding.title.text = title
            binding.preview.text = preview
            binding.avatar.text = initials
            binding.online.visibility = if (online == null) View.GONE else View.VISIBLE
            binding.online.setBackgroundResource(
                if (online == true) R.drawable.mango9_chat_online_background else R.drawable.mango9_chat_offline_background,
            )
            binding.unread.visibility = if (unread > 0) View.VISIBLE else View.GONE
            binding.unread.text = if (unread > 99) "99+" else unread.toString()
            binding.root.setOnClickListener { onClick(item) }
        }
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<Mango9MessagingListItem>() {
            override fun areItemsTheSame(oldItem: Mango9MessagingListItem, newItem: Mango9MessagingListItem) =
                oldItem.stableId == newItem.stableId

            override fun areContentsTheSame(oldItem: Mango9MessagingListItem, newItem: Mango9MessagingListItem) =
                oldItem == newItem
        }

        private fun initials(name: String): String = name.trim().split(Regex("\\s+"))
            .filter(String::isNotBlank)
            .take(2)
            .mapNotNull(String::firstOrNull)
            .joinToString("")
            .uppercase()
            .ifBlank { "?" }

        private fun formattedPhone(raw: String): String {
            val digits = raw.filter(Char::isDigit).removePrefix("1")
            return if (digits.length == 10) {
                "(${digits.take(3)}) ${digits.substring(3, 6)}-${digits.takeLast(4)}"
            } else {
                raw
            }
        }
    }
}
