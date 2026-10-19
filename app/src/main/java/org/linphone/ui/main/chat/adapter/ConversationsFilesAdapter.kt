/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.ui.main.chat.adapter

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.UiThread
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.linphone.R
import org.linphone.databinding.ChatBubbleSingleFileContentBinding
import org.linphone.databinding.ChatMediaContentGridCellBinding
import org.linphone.databinding.MeetingsListDecorationBinding
import org.linphone.ui.main.chat.model.FileModel
import org.linphone.utils.AppUtils
import org.linphone.utils.HeaderAdapter
import java.io.Closeable

class ConversationsFilesAdapter :
    ListAdapter<FileModel, RecyclerView.ViewHolder>(
        FilesDiffCallback()
    ),
    HeaderAdapter {
    companion object {
        const val MEDIA_FILE = 1
        const val DOCUMENT_FILE = 2
    }

    private val topBottomPadding = AppUtils.getDimension(R.dimen.chat_documents_list_padding_top_bottom).toInt()
    private val startEndPadding = AppUtils.getDimension(R.dimen.chat_documents_list_padding_start_end).toInt()
    private val holders = linkedSetOf<FileViewHolder>()
    private var presentationEnabled = true

    fun setPresentationEnabled(enabled: Boolean) {
        presentationEnabled = enabled
        holders.forEach { it.setActive(enabled && it.attached) }
    }

    override fun displayHeaderForPosition(position: Int): Boolean {
        if (position == 0) return true

        val previous = getItem(position - 1)
        val item = getItem(position)
        return previous.month != item.month
    }

    override fun getHeaderViewForPosition(context: Context, position: Int): View {
        val binding = MeetingsListDecorationBinding.inflate(LayoutInflater.from(context))
        val item = getItem(position)
        binding.header.text = item.month
        return binding.root
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            MEDIA_FILE -> createMediaFileViewHolder(parent)
            else -> createDocumentFileViewHolder(parent)
        }
    }

    override fun getItemViewType(position: Int): Int {
        val data = getItem(position)
        if (data.isMedia || data.isAudio) return MEDIA_FILE
        return DOCUMENT_FILE
    }

    private fun createMediaFileViewHolder(parent: ViewGroup): RecyclerView.ViewHolder {
        val binding: ChatMediaContentGridCellBinding = DataBindingUtil.inflate(
            LayoutInflater.from(parent.context),
            R.layout.chat_media_content_grid_cell,
            parent,
            false
        )
        val viewHolder = MediaFileViewHolder(binding)
        binding.apply {
            lifecycleOwner = parent.findViewTreeLifecycleOwner()
        }
        return viewHolder
    }

    private fun createDocumentFileViewHolder(parent: ViewGroup): RecyclerView.ViewHolder {
        val binding: ChatBubbleSingleFileContentBinding = DataBindingUtil.inflate(
            LayoutInflater.from(parent.context),
            R.layout.chat_bubble_single_file_content,
            parent,
            false
        )
        val viewHolder = DocumentFileViewHolder(binding)
        binding.apply {
            lifecycleOwner = parent.findViewTreeLifecycleOwner()
            root.setPadding(startEndPadding, topBottomPadding, startEndPadding, topBottomPadding)
        }
        return viewHolder
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val fileModel = getItem(position)
        (holder as FileViewHolder).let { holders.add(it); it.bind(fileModel) }
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        (holder as FileViewHolder).let { holders.add(it); it.attached = true; it.setActive(presentationEnabled) }
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        (holder as FileViewHolder).let { it.attached = false; it.setActive(false) }
        super.onViewDetachedFromWindow(holder)
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        (holder as FileViewHolder).let { it.recycle(); holders.remove(it) }
        super.onViewRecycled(holder)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        holders.forEach { it.recycle() }; holders.clear()
        super.onDetachedFromRecyclerView(recyclerView)
    }

    abstract class FileViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        var attached = false
        private var active = false
        private var file: FileModel? = null
        private var presentation: Closeable? = null

        @UiThread fun bind(model: FileModel) {
            if (file !== model) { presentation?.close(); presentation = null; file = model }
            render(model)
            if (active && presentation == null) presentation = model.retainPresentation()
        }

        fun setActive(value: Boolean) {
            active = value
            if (value) {
                if (presentation == null) presentation = file?.retainPresentation()
            } else { presentation?.close(); presentation = null }
        }

        fun recycle() { attached = false; setActive(false); file = null; render(null) }

        protected abstract fun render(model: FileModel?)
    }

    class MediaFileViewHolder(
        val binding: ChatMediaContentGridCellBinding
    ) : FileViewHolder(binding.root) {
        override fun render(model: FileModel?) {
            with(binding) {
                this.model = model
                executePendingBindings()
            }
        }
    }

    class DocumentFileViewHolder(
        val binding: ChatBubbleSingleFileContentBinding
    ) : FileViewHolder(binding.root) {
        override fun render(model: FileModel?) {
            with(binding) {
                this.model = model
                executePendingBindings()
            }
        }
    }

    private class FilesDiffCallback : DiffUtil.ItemCallback<FileModel>() {
        override fun areItemsTheSame(oldItem: FileModel, newItem: FileModel): Boolean {
            return oldItem.originalPath == newItem.originalPath && oldItem.fileName == newItem.fileName &&
                oldItem.relatedMessageId == newItem.relatedMessageId && oldItem.fileCreationTimestamp == newItem.fileCreationTimestamp
        }

        @SuppressLint("DiffUtilEquals") // These observable models own callbacks/leases; replacement must rebind.
        override fun areContentsTheSame(oldItem: FileModel, newItem: FileModel): Boolean {
            // A replacement owns new callbacks/leases even when metadata is unchanged.
            return oldItem === newItem
        }
    }
}
