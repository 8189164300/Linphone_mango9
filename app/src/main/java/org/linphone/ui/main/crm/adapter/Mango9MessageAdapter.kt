/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.ui.main.crm.adapter

import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
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
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import org.linphone.R
import org.linphone.mango9.Mango9TeamMessageLayout
import android.graphics.Typeface
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import androidx.core.content.ContextCompat
import org.linphone.databinding.Mango9MessageCellBinding
import org.linphone.mango9.Mango9ChatMedia
import org.linphone.mango9.Mango9ChatMessage
import org.linphone.mango9.Mango9SmsDeliveryPolicy
import org.linphone.mango9.Mango9SmsDeliveryState
import org.linphone.mango9.Mango9SmsMessage
import org.linphone.mango9.Mango9ConversationSearch
import org.linphone.mango9.Mango9SocialMessage
import org.linphone.ui.main.crm.viewmodel.Mango9AudioClip
import org.linphone.ui.main.crm.viewmodel.Mango9InlineAudioController

sealed class Mango9MessageListItem(val stableId: String) {
    val text: String get() = when (this) {
        is Team -> message.text
        is Sms -> message.text
        is Social -> if (message.deleted) "Message unavailable" else message.text
    }
    val files: String get() = when (this) {
        is Team -> message.files
        is Sms -> message.files
        is Social -> media.joinToString(",") { it.source }
    }

    data class Team(
        val message: Mango9ChatMessage,
        val outgoing: Boolean,
        val senderName: String?,
        val dayHeading: String? = null,
        val startsSender: Boolean = true,
    ) : Mango9MessageListItem("team:${message.id}")

    data class Sms(val message: Mango9SmsMessage) : Mango9MessageListItem("sms:${message.id}")

    data class Social(val message: Mango9SocialMessage, val media: List<Mango9ChatMedia>) : Mango9MessageListItem("social:${message.id}")
}

class Mango9MessageAdapter(
    private val onMediaClick: (Mango9ChatMedia) -> Unit,
    private val onLongClick: (Mango9MessageListItem) -> Unit,
) : ListAdapter<Mango9MessageListItem, Mango9MessageAdapter.ViewHolder>(DiffCallback) {
    private var searchQuery = ""
    private var searchMessageId: String? = null
    private var audioController: Mango9InlineAudioController? = null
    private var thumbnails: Mango9ThumbnailLoader? = null
    var heldAnchor: View? = null
        private set
    var selectionMode = false
        private set
    var selected = emptySet<String>()
        private set
    var onSelection: ((Set<String>) -> Unit)? = null
    var socialProfile: ((Mango9SocialMessage) -> String?)? = null
    var openProfile: ((String) -> Unit)? = null

    fun select(ids: Set<String>?, notify: Boolean = true) {
        selectionMode = ids != null; selected = ids.orEmpty()
        notifyDataSetChanged()
        if (notify) onSelection?.invoke(selected)
    }

    internal fun setThumbnailLoader(loader: Mango9ThumbnailLoader) { thumbnails = loader }

    internal fun setAudioController(controller: Mango9InlineAudioController) { audioController = controller }

    override fun onCurrentListChanged(previousList: MutableList<Mango9MessageListItem>, currentList: MutableList<Mango9MessageListItem>) {
        super.onCurrentListChanged(previousList, currentList)
        audioController?.reconcile(currentList)
        if (selectionMode) {
            val remaining = selected.intersect(currentList.map { it.stableId }.toSet())
            if (remaining != selected) { selected = remaining; onSelection?.invoke(selected) }
        }
    }

    fun highlight(query: String, messageId: String?) {
        if (query == searchQuery && messageId == searchMessageId) return
        val previous = searchMessageId
        searchQuery = query
        searchMessageId = messageId
        currentList.forEachIndexed { index, item ->
            if (item.stableId == previous || item.stableId == messageId) notifyItemChanged(index, SEARCH_PAYLOAD)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = Mango9MessageCellBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == SEARCH_PAYLOAD }) {
            holder.bindText(getItem(position))
        } else {
            holder.bind(getItem(position))
        }
    }

    inner class ViewHolder(private val binding: Mango9MessageCellBinding) : RecyclerView.ViewHolder(binding.root) {
        private var boundMedia: List<Mango9ChatMedia>? = null
        private var boundMessageId: String? = null
        private var boundCardStyle = false

        fun bind(item: Mango9MessageListItem) {
            val outgoing: Boolean
            val sender: String?
            val text: String
            val time: String
            val status: String?
            val files: String
            when (item) {
                is Mango9MessageListItem.Social -> {
                    outgoing = item.message.outgoing; sender = if (outgoing) null else item.message.sender
                    text = item.text; time = item.message.time; files = item.files
                    status = if (!outgoing) {
                        null
                    } else {
                        when (item.message.status) {
                        "sent", "confirmed_sent", "succeeded" -> "Sent"
                        "delivered" -> "Delivered"
                        "read" -> "Read"
                        "failed", "confirmed_unsent" -> "Not sent"
                        else -> "Delivery unconfirmed"
                    }
                    }
                }
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
            binding.bubble.setBackgroundResource(if (outgoing) R.drawable.mango9_chat_bubble_background else R.drawable.mango9_chat_incoming_background)
            binding.message.setTextColor(if (outgoing) Color.WHITE else com.google.android.material.color.MaterialColors.getColor(binding.message, R.attr.color_main2_800))
            val team = item as? Mango9MessageListItem.Team
            binding.root.setPadding(dp(if (team != null) 18 else 12), dp(if (team?.startsSender == true && team.dayHeading == null) 14 else 4), dp(if (team != null) 18 else 12), dp(if (team != null) 7 else 4))
            binding.dayHeading.text = team?.dayHeading.orEmpty()
            binding.dayHeading.visibility = if (team?.dayHeading.isNullOrBlank()) View.GONE else View.VISIBLE
            binding.sender.textSize = if (team != null) 14f else 11f
            val profile = (item as? Mango9MessageListItem.Social)?.message?.let { socialProfile?.invoke(it) }
            binding.sender.setOnClickListener(
                if (profile != null && !selectionMode) {
                View.OnClickListener {
                openProfile?.invoke(profile)
            }
            } else {
                null
            }
            )
            binding.sender.isClickable = profile != null && !selectionMode
            binding.sender.contentDescription = if (profile != null) "$sender, open profile" else null
            binding.sender.setTextColor(if (profile != null) ContextCompat.getColor(binding.root.context, R.color.orange_main_500) else com.google.android.material.color.MaterialColors.getColor(binding.sender, R.attr.color_main2_500))
            binding.message.textSize = if (team != null) 17f else 14f
            binding.footer.textSize = if (team != null) 12f else 11f
            val separateCards = team != null && Mango9ChatMedia.parse(files).isNotEmpty()
            binding.bubble.setPadding(dp(if (separateCards) 0 else 13), dp(if (separateCards) 0 else 9), dp(if (separateCards) 0 else 13), dp(if (separateCards) 0 else 9))
            if (separateCards) binding.bubble.background = null
            binding.message.background = null
            binding.message.setPadding(0, 0, 0, 0)
            if (separateCards && text.isNotBlank()) {
                binding.message.setBackgroundResource(if (outgoing) R.drawable.mango9_chat_bubble_background else R.drawable.mango9_chat_incoming_background)
                binding.message.setPadding(dp(13), dp(9), dp(13), dp(9))
            }
            params.marginStart = dp(if (selectionMode) 44 else 0)
            binding.messageContainer.layoutParams = params
            binding.selected.visibility = if (selectionMode) View.VISIBLE else View.GONE
            binding.selected.isChecked = item.stableId in selected
            binding.selected.setOnClickListener { toggleSelection(item) }
            binding.sender.visibility = if (sender.isNullOrBlank()) View.GONE else View.VISIBLE
            binding.sender.text = sender.orEmpty()
            binding.message.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
            bindText(item)
            binding.footer.text = listOfNotNull(if (team != null) Mango9TeamMessageLayout.timeLabel(time) else dateText(time, true), status).filter(String::isNotBlank).joinToString(" · ")
            val reactions = when (item) {
                is Mango9MessageListItem.Team -> item.message.reactions.values.map { "${it.key} ${it.value.size}" }
                is Mango9MessageListItem.Sms -> item.message.reactions.values.map { "${it.key} ${it.value.size}" }
                is Mango9MessageListItem.Social -> listOfNotNull(item.message.reactions.self, item.message.reactions.participant)
            }
            binding.reactions.text = reactions.joinToString("  ")
            binding.reactions.visibility = if (reactions.isEmpty()) View.GONE else View.VISIBLE
            bindMedia(item.stableId, if (item is Mango9MessageListItem.Social) item.media else Mango9ChatMedia.parse(files), team != null)
            for (index in 0 until binding.media.childCount) {
                (binding.media.getChildAt(index) as? Mango9AudioMessageView)?.apply {
                    setInk(binding.message.currentTextColor)
                    selection(if (selectionMode) ({ toggleSelection(item) }) else null)
                }
            }
            val hold = View.OnLongClickListener {
                heldAnchor = binding.bubble
                if (selectionMode) { toggleSelection(item); return@OnLongClickListener true }
                onLongClick(item)
                true
            }
            // TextView resets longClickable when selectable changes. Configure text first,
            // then attach the hold listener, including on a freshly inflated first row.
            binding.message.setTextIsSelectable(false)
            binding.bubble.setOnLongClickListener(hold)
            binding.message.setOnLongClickListener(hold)
            binding.reactions.setOnLongClickListener(hold)
            binding.reactions.contentDescription = if (reactions.isEmpty()) null else "${reactions.joinToString(", ")}. Hold the message to change your reaction."
            binding.root.setOnClickListener { if (selectionMode) toggleSelection(item) }
            binding.bubble.setOnClickListener { if (selectionMode) toggleSelection(item) }
            binding.message.setOnClickListener { if (selectionMode) toggleSelection(item) }
            binding.reactions.setOnClickListener { if (selectionMode) toggleSelection(item) }
            binding.footer.setOnClickListener { if (selectionMode) toggleSelection(item) }
        }

        private fun toggleSelection(item: Mango9MessageListItem) {
            if (!selectionMode) return
            select(if (item.stableId in selected) selected - item.stableId else selected + item.stableId)
        }

        fun bindText(item: Mango9MessageListItem) {
            binding.message.text = if (item.stableId == searchMessageId && searchQuery.isNotEmpty()) {
                SpannableString(item.text).apply {
                    Mango9ConversationSearch.ranges(item.text, searchQuery).forEach { range ->
                        setSpan(BackgroundColorSpan(Color.rgb(255, 224, 130)), range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        setSpan(ForegroundColorSpan(Color.BLACK), range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            } else {
                item.text
            }
        }

        private fun bindMedia(messageId: String, media: List<Mango9ChatMedia>, cardStyle: Boolean) {
            if (messageId == boundMessageId && media == boundMedia && cardStyle == boundCardStyle) return
            boundCardStyle = cardStyle
            boundMessageId = messageId
            boundMedia = media
            binding.media.removeAllViews()
            binding.media.visibility = if (media.isEmpty()) View.GONE else View.VISIBLE
            media.forEachIndexed { index, item ->
                val owner = audioController
                val view = if (item.kind == Mango9ChatMedia.Kind.Audio && owner != null) {
                    Mango9AudioMessageView(binding.root.context, cardStyle).apply {
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            if (index > 0) topMargin = dp(7)
                        }
                        bind(Mango9AudioClip(messageId, index, item), owner)
                    }
                } else if (item.kind == Mango9ChatMedia.Kind.Image || item.kind == Mango9ChatMedia.Kind.Video) {
                    FrameLayout(binding.root.context).apply {
                        contentDescription = item.name
                        layoutParams = LinearLayout.LayoutParams(dp(230), dp(150)).apply {
                            if (index > 0) topMargin = dp(7)
                        }
                        addView(
                            Mango9ThumbnailView(context).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            bind(item, thumbnails)
                        },
                            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        )
                        if (item.kind == Mango9ChatMedia.Kind.Video) {
                            addView(
                                TextView(context).apply {
                                text = "▶"
                                setTextColor(Color.WHITE)
                                textSize = 32f
                                gravity = Gravity.CENTER
                                setShadowLayer(4f, 0f, 1f, Color.BLACK)
                            },
                                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            )
                        }
                    }
                } else if (cardStyle) {
                    attachmentCard(item).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(minOf(264, resources.configuration.screenWidthDp - 80)), ViewGroup.LayoutParams.WRAP_CONTENT)
                            .apply { if (index > 0) topMargin = dp(8) }
                    }
                } else {
                    TextView(binding.root.context).apply {
                        text = when (item.kind) {
                            Mango9ChatMedia.Kind.Audio -> "▶  ${item.name}"
                            else -> "▣  ${item.name}"
                        }
                        setTextColor(binding.message.currentTextColor)
                        textSize = 12f
                        maxWidth = dp(230)
                        compoundDrawablePadding = dp(7)
                        setPadding(dp(4))
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                            .apply { if (index > 0) topMargin = dp(6) }
                    }
                }
                if (view !is Mango9AudioMessageView) {
                    view.setOnClickListener {
                    val message = currentList.firstOrNull { it.stableId == boundMessageId }
                    if (selectionMode && message != null) toggleSelection(message) else onMediaClick(item)
                }
                }
                view.setOnLongClickListener {
                    val message = currentList.firstOrNull { it.stableId == boundMessageId } ?: return@setOnLongClickListener false
                    heldAnchor = binding.bubble
                    if (selectionMode) toggleSelection(message) else onLongClick(message)
                    true
                }
                binding.media.addView(view)
            }
        }

        private fun attachmentCard(media: Mango9ChatMedia): View {
            val context = binding.root.context
            return LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(11), dp(11), dp(11), dp(11))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(ContextCompat.getColor(context, R.color.mango9_team_card))
                    setStroke(dp(1), ContextCompat.getColor(context, R.color.mango9_team_border))
                }
                addView(
                    ImageView(context).apply {
                    setImageResource(R.drawable.file_text)
                    imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.orange_main_500))
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(9).toFloat(); setColor(ContextCompat.getColor(context, R.color.mango9_team_icon_tile))
                    }
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                    LinearLayout.LayoutParams(dp(44), dp(44))
                )
                addView(
                    LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        TextView(context).apply {
                        text = media.name; textSize = 15f; setTypeface(typeface, Typeface.BOLD)
                        maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                        setTextColor(ContextCompat.getColor(context, R.color.mango9_team_ink))
                    }
                    )
                    addView(
                        TextView(context).apply {
                        setText(if (media.name.endsWith(".pdf", true)) R.string.mango9_pdf_document else R.string.mango9_attachment_document)
                        textSize = 12f; setPadding(0, dp(3), 0, 0)
                        setTextColor(ContextCompat.getColor(context, R.color.mango9_team_secondary))
                    }
                    )
                },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) }
                )
            }
        }

        private fun dp(value: Int): Int = (value * binding.root.resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val SEARCH_PAYLOAD = "message-search"
        private val localFormats = listOf(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
        )
        private val displayTimeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        private val DiffCallback = object : DiffUtil.ItemCallback<Mango9MessageListItem>() {
            override fun areItemsTheSame(oldItem: Mango9MessageListItem, newItem: Mango9MessageListItem) =
                oldItem.stableId == newItem.stableId

            override fun areContentsTheSame(oldItem: Mango9MessageListItem, newItem: Mango9MessageListItem) =
                oldItem == newItem
        }

        fun dateText(value: String, conversation: Boolean = false): String {
            if (value.isBlank()) return ""
            val zone = ZoneId.systemDefault()
            val time = try {
                Instant.parse(value).atZone(zone)
            } catch (_: DateTimeParseException) {
                localFormats.firstNotNullOfOrNull { formatter ->
                    runCatching { LocalDateTime.parse(value, formatter).atZone(ZoneId.of("UTC")) }.getOrNull()
                } ?: return ""
            }
            val local = time.withZoneSameInstant(zone)
            val formatter = if (conversation) {
                DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
            } else if (local.toLocalDate() == java.time.LocalDate.now(zone)) {
                displayTimeFormat
            } else {
                DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
            }
            return local.format(formatter.withLocale(java.util.Locale.getDefault()))
        }
    }
}
