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
package org.linphone.ui.fileviewer.adapter

import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import java.nio.ByteBuffer
import java.security.MessageDigest
import org.linphone.core.tools.Log
import org.linphone.mango9.Mango9NativeAttachment
import org.linphone.ui.fileviewer.fragment.MediaViewerFragment
import org.linphone.ui.main.chat.model.FileModel

class MediaListAdapter(
    fragmentActivity: FragmentActivity
) :
    FragmentStateAdapter(fragmentActivity) {
    companion object {
        private const val TAG = "[Media List Adapter]"
    }

    private data class Item(val id: Long, val path: String, val mimeType: String, val name: String)

    private var items = emptyList<Item>()
    private var itemIds = emptySet<Long>()
    private var models = emptyList<FileModel>()

    fun submitList(models: List<FileModel>) {
        this.models = models
        val occurrences = mutableMapOf<String, Int>()
        val digest = MessageDigest.getInstance("SHA-256")
        val snapshot = models.map { model ->
            // Include the actual playable path: a re-exported encrypted file needs
            // a new fragment, even when its original path has not changed.
            val key = "${model.originalPath}\u0000${model.path}\u0000${model.mimeTypeString}\u0000${model.fileName}"
            val occurrence = occurrences[key] ?: 0
            occurrences[key] = occurrence + 1
            // Distinguish repeated attachments and avoid String.hashCode collisions.
            val id = ByteBuffer.wrap(digest.digest("$key\u0000$occurrence".toByteArray(Charsets.UTF_8))).long
            Item(id, model.path, model.mimeTypeString, model.fileName)
        }
        if (snapshot == items) return
        items = snapshot
        itemIds = snapshot.mapTo(HashSet()) { it.id }
        notifyDataSetChanged()
    }

    fun pathAt(position: Int): String? = items.getOrNull(position)?.path

    override fun getItemCount(): Int {
        return items.size
    }

    override fun getItemId(position: Int): Long {
        return items[position].id
    }

    override fun containsItem(itemId: Long): Boolean {
        return itemId in itemIds
    }

    override fun createFragment(position: Int): Fragment {
        val fragment = MediaViewerFragment()
        fragment.arguments = Bundle().apply {
            val path = items[position].path
            Log.d("$TAG Path is [$path] for position [$position]")
            putString("path", path)
            putString(Mango9NativeAttachment.MIME, items[position].mimeType)
            putString(Mango9NativeAttachment.NAME, items[position].name)
            org.linphone.mango9.Mango9NativeFileTransfers.bind(this, models[position])
        }
        return fragment
    }
}
