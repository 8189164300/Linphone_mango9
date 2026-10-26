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
import org.linphone.mango9.Mango9ConversationRecency
import org.linphone.mango9.Mango9ChatRoom
import org.linphone.mango9.Mango9ChatState
import org.linphone.mango9.Mango9ChatUser
import org.linphone.mango9.Mango9SmsParty
import org.linphone.mango9.Mango9SocialThread
import org.linphone.mango9.humanMemberIds

sealed class Mango9MessagingListItem(val stableId: String) {
    data class Social(val thread: Mango9SocialThread, val preview: String = "Loading preview…", val previewEpoch: Long = 0) : Mango9MessagingListItem("social:${thread.id}")

    data class Group(val room: Mango9ChatRoom, val title: String, val memberCount: Int = room.userIds.size + 1) : Mango9MessagingListItem("group:${room.id}")

    data class User(
        val user: Mango9ChatUser,
        val room: Mango9ChatRoom?,
        val online: Boolean,
    ) : Mango9MessagingListItem("user:${user.id}")

    data class Sms(
        val party: Mango9SmsParty,
        val muted: Boolean,
    ) : Mango9MessagingListItem("sms:${party.phone}")
}

enum class Mango9MessagingTab {
    Sms,
    Team,
    Social,
    Live;

    fun accepts(item: Mango9MessagingListItem): Boolean = when (this) {
        Sms -> item is Mango9MessagingListItem.Sms
        Team -> item is Mango9MessagingListItem.Group || item is Mango9MessagingListItem.User
        Social -> item is Mango9MessagingListItem.Social
        Live -> false
    }
}

/** Builds both conversation lists from the same snapshot so tab badges and row badges stay aligned. */
object Mango9MessagingListItems {
    fun team(
        state: Mango9ChatState,
        isConversationDeleted: (String) -> Boolean,
        roomTitle: (Mango9ChatRoom) -> String,
    ): List<Mango9MessagingListItem> {
        // Build the directory lookup once; a room scan per person becomes quadratic
        // for large organizations and makes opening the recipient picker stall.
        val directRooms = mutableMapOf<Int, Mango9ChatRoom>()
        state.rooms.filter { it.isDirect && !isConversationDeleted(it.id) }.forEach { room ->
            room.userIds.forEach { id ->
                val previous = directRooms[id]
                if (previous == null || Mango9ConversationRecency.newer(room.latest, previous.latest)) directRooms[id] = room
            }
        }
        val groups = state.rooms
            .filter { !it.isDirect && !isConversationDeleted(it.id) }
            .sortedWith(compareByDescending<Mango9ChatRoom> { Mango9ConversationRecency.time(it.latest) }.thenBy { it.id })
            .map { Mango9MessagingListItem.Group(it, roomTitle(it), it.humanMemberIds(state.users).size + 1) }
        val people = state.users.filterNot { it.isSystem }.map { user ->
            val room = directRooms[user.id]
            Mango9MessagingListItem.User(user, room, state.onlineUserIds.contains(user.id))
        }.sortedWith(
            compareByDescending<Mango9MessagingListItem.User> { Mango9ConversationRecency.time(it.room?.latest.orEmpty()) }
                .thenByDescending { it.room != null }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.user.name }
                .thenBy { it.user.id },
        )
        return (groups + people).sortedWith(
            compareByDescending<Mango9MessagingListItem> {
                Mango9ConversationRecency.time(
                    when (it) {
                    is Mango9MessagingListItem.Group -> it.room.latest
                    is Mango9MessagingListItem.User -> it.room?.latest.orEmpty()
                    else -> ""
                }
                )
            }
        )
    }

    fun sms(
        state: Mango9ChatState,
        isMuted: (String) -> Boolean,
    ): List<Mango9MessagingListItem> = Mango9ConversationRecency.sms(state.smsParties).map { party ->
        Mango9MessagingListItem.Sms(party, isMuted(party.phone))
    }
}

class Mango9MessagingListAdapter(
    private val onClick: (Mango9MessagingListItem) -> Unit,
    private val onHold: ((View, Mango9MessagingListItem) -> Unit)? = null,
    private val onSocialVisible: ((String) -> Unit)? = null,
) : ListAdapter<Mango9MessagingListItem, Mango9MessagingListAdapter.ViewHolder>(DiffCallback) {
    internal var notificationScope: org.linphone.mango9.Mango9SessionLease? = null

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
                is Mango9MessagingListItem.Social -> {
                    title = item.thread.title
                    preview = item.preview
                    onSocialVisible?.invoke(item.thread.id)
                    unread = if (item.thread.unread) 1 else 0
                    online = null
                    initials = when (item.thread.platform) { "facebook" -> "FB"; "instagram" -> "IG"; "tiktok" -> "TT"; else -> "" }
                }
                is Mango9MessagingListItem.Group -> {
                    title = item.title
                    preview = item.room.lastMessage.ifBlank {
                        context.getString(R.string.mango9_chat_group_status, item.memberCount, 0)
                    }
                    unread = item.room.unread
                    online = null
                    initials = "G"
                }
                is Mango9MessagingListItem.User -> {
                    title = item.user.name
                    preview = item.room?.lastMessage?.takeIf(String::isNotBlank)
                        ?: item.user.roleLabel.ifBlank {
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
            binding.role.visibility = if (item is Mango9MessagingListItem.User) View.VISIBLE else View.GONE
            binding.role.text = (item as? Mango9MessagingListItem.User)?.let {
                it.user.roleLabel + if (it.online) " · Online" else ""
            }.orEmpty()
            binding.preview.text = preview
            binding.preview.visibility = if (item is Mango9MessagingListItem.User && item.room?.lastMessage.isNullOrBlank()) View.GONE else View.VISIBLE
            binding.avatar.text = initials
            binding.online.visibility = if (online == null) View.GONE else View.VISIBLE
            binding.online.setBackgroundResource(
                if (online == true) R.drawable.mango9_chat_online_background else R.drawable.mango9_chat_offline_background,
            )
            binding.unread.visibility = if (unread > 0) View.VISIBLE else View.GONE
            binding.unread.text = if (unread > 99) "99+" else unread.toString()
            val lease = notificationScope
            val notifications = org.linphone.mango9.Mango9ConversationNotifications(context)
            val muted = when (item) {
                is Mango9MessagingListItem.Sms -> item.muted
                is Mango9MessagingListItem.Social -> lease?.let {
                    notifications.cached(it, org.linphone.mango9.Mango9MessageChannel.social, item.thread.id)
                } == true
                is Mango9MessagingListItem.Group -> lease?.let {
                    notifications.cached(it, org.linphone.mango9.Mango9MessageChannel.team, item.room.id)
                } == true
                is Mango9MessagingListItem.User -> item.room?.let { room ->
                    lease?.let {
                    notifications.cached(it, org.linphone.mango9.Mango9MessageChannel.team, room.id)
                }
                } == true
            }
            binding.muted.visibility = if (muted) View.VISIBLE else View.GONE
            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnLongClickListener { onHold?.invoke(it, item); onHold != null }
            binding.date.text = Mango9MessageAdapter.dateText(
                when (item) {
                is Mango9MessagingListItem.Sms -> item.party.latest
                is Mango9MessagingListItem.Group -> item.room.latest
                is Mango9MessagingListItem.User -> item.room?.latest.orEmpty()
                is Mango9MessagingListItem.Social -> item.thread.activityAt
            }
            )
            val logo = (item as? Mango9MessagingListItem.Social)?.thread?.platform?.let {
                when (it) { "facebook" -> R.drawable.social_facebook; "instagram" -> R.drawable.social_instagram; "tiktok" -> R.drawable.social_tiktok; else -> null }
            }
            binding.brand.visibility = if (logo != null) View.VISIBLE else View.GONE
            binding.avatar.visibility = if (logo != null) View.INVISIBLE else View.VISIBLE
            if (logo != null) {
                binding.brand.setImageResource(logo); binding.brand.contentDescription = (item as Mango9MessagingListItem.Social).thread.platform
            }
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
