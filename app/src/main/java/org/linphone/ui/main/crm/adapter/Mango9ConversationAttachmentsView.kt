/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.ui.main.crm.adapter

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.linphone.R
import org.linphone.mango9.Mango9ChatMedia

internal data class Mango9ConversationAttachment(val messageId: String, val index: Int, val media: Mango9ChatMedia) {
    companion object {
        fun collect(
            items: List<Mango9MessageListItem>, documents: Boolean,
            checkActive: () -> Unit = {
            }
        ): List<Mango9ConversationAttachment> =
            buildList {
                items.asReversed().forEach { item ->
                    checkActive()
                    Mango9ChatMedia.parse(item.files).forEachIndexed { index, media ->
                        if ((media.kind == Mango9ChatMedia.Kind.File) == documents) add(Mango9ConversationAttachment(item.stableId, index, media))
                    }
                }
            }
    }
}

/** Only attached preview rows load bounded source bytes; the browser never creates every row. */
@SuppressLint("ViewConstructor") // Programmatic dialog content requires its conversation-owned click callback.
internal class Mango9ConversationAttachmentsView(
    context: Context,
    private val documents: Boolean,
    private val thumbnails: Mango9ThumbnailLoader? = null,
    private val onOpen: (Mango9ConversationAttachment) -> Unit,
) : LinearLayout(context) {
    val recycler = RecyclerView(context)
    val empty = TextView(context)
    private val grid = GridLayoutManager(context, 1)
    private val rows = AttachmentAdapter()

    init {
        orientation = VERTICAL
        setBackgroundColor(ContextCompat.getColor(context, R.color.bc_white))
        addView(
            TextView(context).apply {
            setText(R.string.mango9_chat_attachment_scope)
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.gray_main2_700))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        )
        empty.apply {
            setText(if (documents) R.string.conversation_no_document_found else R.string.conversation_no_media_found)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }
        addView(empty, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        recycler.layoutManager = grid
        recycler.adapter = rows
        addView(recycler, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun submit(items: List<Mango9ConversationAttachment>) {
        empty.visibility = if (items.isEmpty()) VISIBLE else GONE
        rows.submitList(items)
    }

    fun dispose() {
        recycler.adapter = null
        rows.submitList(emptyList())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        grid.spanCount = if (documents) 1 else (w / dp(132)).coerceIn(1, 4)
    }

    private inner class AttachmentAdapter : ListAdapter<Mango9ConversationAttachment, Holder>(object : DiffUtil.ItemCallback<Mango9ConversationAttachment>() {
        override fun areItemsTheSame(old: Mango9ConversationAttachment, new: Mango9ConversationAttachment) = old.messageId == new.messageId && old.index == new.index

        override fun areContentsTheSame(old: Mango9ConversationAttachment, new: Mango9ConversationAttachment) = old == new
    }) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder()

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))
    }

    private inner class Holder : RecyclerView.ViewHolder(LinearLayout(context)) {
        private val container = itemView as LinearLayout
        private val preview = FrameLayout(context)
        private val image = Mango9ThumbnailView(context)
        private val icon = ImageView(context)
        private val title = TextView(context)

        init {
            container.orientation = if (documents) HORIZONTAL else VERTICAL
            container.gravity = Gravity.CENTER_VERTICAL
            container.setPadding(dp(6), dp(6), dp(6), dp(6))
            container.layoutParams = RecyclerView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            container.isFocusable = true
            container.setBackgroundColor(ContextCompat.getColor(context, R.color.bc_white))
            preview.setBackgroundColor(ContextCompat.getColor(context, R.color.gray_100))
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            preview.addView(image, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            preview.addView(icon, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))
            container.addView(preview, LayoutParams(if (documents) dp(56) else LayoutParams.MATCH_PARENT, if (documents) dp(56) else dp(120)))
            title.textSize = 14f
            title.maxLines = 2
            title.ellipsize = TextUtils.TruncateAt.MIDDLE
            title.setPadding(dp(8), dp(6), dp(8), dp(6))
            title.setTextColor(ContextCompat.getColor(context, R.color.gray_main2_700))
            container.addView(title, if (documents) LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f) else LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }

        fun bind(item: Mango9ConversationAttachment) {
            val media = item.media
            val visual = media.kind == Mango9ChatMedia.Kind.Image || media.kind == Mango9ChatMedia.Kind.Video
            image.bind(if (visual) media else null, thumbnails)
            image.visibility = if (visual) VISIBLE else GONE
            title.text = media.name
            title.visibility = if (visual) GONE else VISIBLE
            icon.visibility = if (media.kind == Mango9ChatMedia.Kind.Image) GONE else VISIBLE
            icon.setImageResource(
                when (media.kind) {
                Mango9ChatMedia.Kind.Video -> R.drawable.play_fill
                Mango9ChatMedia.Kind.Audio -> R.drawable.file_audio
                else -> R.drawable.file
            }
            )
            icon.setColorFilter(if (visual) Color.WHITE else ContextCompat.getColor(context, R.color.orange_main_500))
            container.contentDescription = media.name
            container.setOnClickListener { onOpen(item) }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
