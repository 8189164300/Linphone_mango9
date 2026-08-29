/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.ui.main.crm.adapter

import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.setPadding
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import org.linphone.R
import org.linphone.databinding.Mango9MessageCellBinding
import org.linphone.mango9.Mango9ChatMedia
import org.linphone.mango9.Mango9ChatMessage
import org.linphone.mango9.Mango9SmsDeliveryPolicy
import org.linphone.mango9.Mango9SmsDeliveryState
import org.linphone.mango9.Mango9SmsMessage

sealed class Mango9MessageListItem(val stableId: String) {
    data class Team(
        val message: Mango9ChatMessage,
        val outgoing: Boolean,
        val senderName: String?,
    ) : Mango9MessageListItem("team:${message.id}")

    data class Sms(val message: Mango9SmsMessage) : Mango9MessageListItem("sms:${message.id}")
}

class Mango9MessageAdapter(
    private val onMediaClick: (Mango9ChatMedia) -> Unit,
    private val onLongClick: (Mango9MessageListItem) -> Unit,
) : ListAdapter<Mango9MessageListItem, Mango9MessageAdapter.ViewHolder>(DiffCallback) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = Mango9MessageCellBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    inner class ViewHolder(private val binding: Mango9MessageCellBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: Mango9MessageListItem) {
            val outgoing: Boolean
            val sender: String?
            val text: String
            val time: String
            val status: String?
            val files: String
            when (item) {
                is Mango9MessageListItem.Team -> {
                    outgoing = item.outgoing
                    sender = item.senderName
                    text = item.message.text
                    time = item.message.time
                    status = if (outgoing) {
                        binding.root.context.getString(
                            when {
                                item.message.status >= 3 -> R.string.mango9_chat_read
                                item.message.status >= 2 -> R.string.mango9_chat_delivered
                                else -> R.string.mango9_chat_sent
                            },
                        )
                    } else {
                        null
                    }
                    files = item.message.files
                }
                is Mango9MessageListItem.Sms -> {
                    outgoing = !item.message.isIncoming
                    sender = null
                    text = item.message.text
                    time = item.message.time
                    status = if (outgoing) {
                        binding.root.context.getString(
                            when (Mango9SmsDeliveryPolicy.state(item.message.status)) {
                                Mango9SmsDeliveryState.Sent -> R.string.mango9_chat_sent
                                Mango9SmsDeliveryState.Delivered -> R.string.mango9_chat_delivered
                                Mango9SmsDeliveryState.Failed -> R.string.mango9_chat_failed
                            },
                        )
                    } else {
                        null
                    }
                    files = item.message.files
                }
            }
            val params = binding.messageContainer.layoutParams as FrameLayout.LayoutParams
            params.gravity = if (outgoing) Gravity.END else Gravity.START
            binding.messageContainer.layoutParams = params
            binding.sender.visibility = if (sender.isNullOrBlank()) View.GONE else View.VISIBLE
            binding.sender.text = sender.orEmpty()
            binding.message.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
            binding.message.text = text
            binding.footer.text = listOfNotNull(shortTime(time), status).filter(String::isNotBlank).joinToString(" · ")
            bindMedia(Mango9ChatMedia.parse(files))
            binding.root.setOnLongClickListener {
                onLongClick(item)
                true
            }
        }

        private fun bindMedia(media: List<Mango9ChatMedia>) {
            binding.media.removeAllViews()
            binding.media.visibility = if (media.isEmpty()) View.GONE else View.VISIBLE
            media.forEachIndexed { index, item ->
                val view = if (item.kind == Mango9ChatMedia.Kind.Image || item.kind == Mango9ChatMedia.Kind.Video) {
                    ImageView(binding.root.context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        contentDescription = item.name
                        load(item.url)
                        layoutParams = LinearLayout.LayoutParams(dp(230), dp(150)).apply {
                            if (index > 0) topMargin = dp(7)
                        }
                    }
                } else {
                    TextView(binding.root.context).apply {
                        text = when (item.kind) {
                            Mango9ChatMedia.Kind.Audio -> "▶  ${item.name}"
                            else -> "▣  ${item.name}"
                        }
                        setTextColor(Color.WHITE)
                        textSize = 12f
                        maxWidth = dp(230)
                        compoundDrawablePadding = dp(7)
                        setPadding(dp(4))
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                            .apply { if (index > 0) topMargin = dp(6) }
                    }
                }
                view.setOnClickListener { onMediaClick(item) }
                binding.media.addView(view)
            }
        }

        private fun dp(value: Int): Int = (value * binding.root.resources.displayMetrics.density).toInt()
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<Mango9MessageListItem>() {
            override fun areItemsTheSame(oldItem: Mango9MessageListItem, newItem: Mango9MessageListItem) =
                oldItem.stableId == newItem.stableId

            override fun areContentsTheSame(oldItem: Mango9MessageListItem, newItem: Mango9MessageListItem) =
                oldItem == newItem
        }

        private fun shortTime(value: String): String {
            if (value.isBlank()) return ""
            val zone = ZoneId.systemDefault()
            val time = try {
                Instant.parse(value).atZone(zone)
            } catch (_: DateTimeParseException) {
                val formats = listOf(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"),
                    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
                )
                formats.firstNotNullOfOrNull { formatter ->
                    runCatching { LocalDateTime.parse(value, formatter).atZone(ZoneId.of("UTC")) }.getOrNull()
                } ?: return ""
            }
            return time.withZoneSameInstant(zone).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
        }
    }
}
