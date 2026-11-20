/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.ui.main.crm.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.linphone.databinding.Mango9CrmRecordCellBinding
import org.linphone.mango9.Mango9CrmRecord

class Mango9CrmRecordAdapter(
    private val onClick: (Mango9CrmRecord) -> Unit,
    private val isNew: (Mango9CrmRecord) -> Boolean = { false },
    private val onAction: (Mango9CrmRecord) -> Unit = {},
    private val onOpportunity: (Mango9CrmRecord) -> Unit = {},
    private val onSharing: (Mango9CrmRecord) -> Unit = {},
) : ListAdapter<Mango9CrmRecord, Mango9CrmRecordAdapter.ViewHolder>(DiffCallback) {
    var opportunityCounts: Map<Int, Int> = emptyMap()
    var canCreateOpportunity = false
    var isLead = true

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        Mango9CrmRecordCellBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    inner class ViewHolder(private val binding: Mango9CrmRecordCellBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(record: Mango9CrmRecord) {
            binding.record = record

            fun action(view: android.view.View, visible: Boolean, handler: (Mango9CrmRecord) -> Unit) {
                view.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
                view.setOnClickListener {
                    val position = bindingAdapterPosition; if (position != RecyclerView.NO_POSITION)handler(getItem(position))
                }
            }
            action(binding.opportunity, (opportunityCounts[record.id] ?: 0) > 0, onOpportunity)
            action(binding.actions, (isLead && record.canUpdate) || canCreateOpportunity, onAction)
            action(binding.sharingIncoming, record.sharing.incoming, onSharing)
            action(binding.sharingOutgoing, record.sharing.outgoing, onSharing)
            binding.newRecord.visibility = if (isNew(record)) android.view.View.VISIBLE else android.view.View.GONE
            binding.root.setOnClickListener {
                // Equal-content refreshes don't rebind holders. Resolve the current
                // snapshot rather than retaining the previous account's row object.
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onClick(getItem(position))
            }
            binding.executePendingBindings()
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<Mango9CrmRecord>() {
        override fun areItemsTheSame(oldItem: Mango9CrmRecord, newItem: Mango9CrmRecord) = oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Mango9CrmRecord, newItem: Mango9CrmRecord) = oldItem == newItem
    }
}
