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
package org.linphone.ui.fileviewer.viewmodel

import android.os.Handler
import android.os.Looper
import androidx.annotation.UiThread
import androidx.lifecycle.MutableLiveData
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.GlobalState
import org.linphone.mango9.Mango9NativeFileLease
import org.linphone.mango9.Mango9NativeMediaHistory
import org.linphone.mango9.Mango9SessionStore
import org.linphone.mango9.attachmentMimeType
import org.linphone.ui.main.chat.model.FileModel
import org.linphone.ui.main.chat.viewmodel.AbstractConversationViewModel
import org.linphone.utils.FileUtils

/** Bounded, bidirectional window around the opened message, never an all-media export. */
class MediaListViewModel
    @UiThread
    constructor() : AbstractConversationViewModel() {
    val mediaList = MutableLiveData<List<FileModel>>()
    val currentlyDisplayedFileName = MutableLiveData<String>()
    val currentlyDisplayedFileDateTime = MutableLiveData<String>()
    val isCurrentlyDisplayedFileFromEphemeralMessage = MutableLiveData<Boolean>()
    val loadingHistory = MutableLiveData(false)
    private val main = Handler(Looper.getMainLooper())
    private val sessions = Mango9SessionStore(coreContext.context)
    private val owner = sessions.selectionStamp()

    @Volatile private var cleared = false
    private var started = false
    private var busy = false
    private var newerEnd = false
    private var olderEnd = false
    private var selectedPath = ""

    fun selectedFilePath(): String? = selectedPath.takeIf(String::isNotEmpty)

    private var temporary: FileModel? = null

    private data class Item(val cursor: Mango9NativeMediaHistory.Cursor, val model: FileModel)

    private var items = emptyList<Item>()

    private fun current() = !cleared && sessions.selectionStamp() == owner

    override fun beforeNotifyingChatRoomFound(sameOne: Boolean) {
        if (!current() || coreContext.core.globalState != GlobalState.On || started) return
        started = true
        val model = temporary ?: return
        val entry = model.relatedMessageId?.let { Mango9NativeMediaHistory(chatRoom).anchor(it, model.originalPath) }
        if (entry != null) installAnchor(entry.cursor, model) else locateAnchor(0, model)
    }

    // Compatibility for unidentified SDK contents: metadata-only pages, never plaintext exports.
    private fun locateAnchor(offset: Int, model: FileModel) {
        coreContext.postOnCoreThread { core ->
            if (!current() || core.globalState != GlobalState.On) return@postOnCoreThread
            val events = chatRoom.getHistoryRange(offset, offset + 50, org.linphone.core.ChatRoom.HistoryFilter.ChatMessage.toInt())
            val source = Mango9NativeMediaHistory(chatRoom)
            val entry = events.firstNotNullOfOrNull { source.anchor(it, model.originalPath) }
            if (entry != null) {
                installAnchor(entry.cursor, model)
            } else if (events.size == 50) locateAnchor(offset + events.size, model)
        }
    }

    private fun installAnchor(cursor: Mango9NativeMediaHistory.Cursor, model: FileModel) {
        main.post {
            if (!current()) return@post
            items = listOf(Item(cursor, model))
            onSelected(model.path)
        }
    }

    override fun onCleared() {
        cleared = true
        (mediaList.value.orEmpty() + listOfNotNull(temporary)).distinct().forEach(FileModel::destroy)
        super.onCleared()
    }

    @UiThread fun initTempModel(
        path: String, timestamp: Long, isEncrypted: Boolean, originalPath: String, isFromEphemeralMessage: Boolean,
        mimeType: String? = null, displayName: String? = null, messageId: String? = null,
        retainedFile: Mango9NativeFileLease? = null
    ) {
        val model = FileModel(
            path, displayName?.takeIf(String::isNotBlank) ?: FileUtils.getNameFromFilePath(path),
            0, timestamp, isEncrypted, originalPath, isFromEphemeralMessage,
            loadMetadata = false, mimeTypeOverride = mimeType, relatedMessageId = messageId,
            retainedFile = retainedFile, ownsEncryptedFile = false
        )
        temporary = model
        selectedPath = path
        mediaList.value = listOf(model)
    }

    @UiThread fun onSelected(path: String) {
        selectedPath = path
        if (!current() || busy || items.isEmpty()) return
        val index = items.indexOfFirst { it.model.path == path }
        if (index < 0) return
        when {
            index < 4 && !newerEnd -> load(false)
            index >= items.size - 4 && !olderEnd -> load(true)
        }
    }

    private fun load(older: Boolean) {
        busy = true
        loadingHistory.value = true
        readSlice((if (older) items.last() else items.first()).cursor, older, arrayListOf())
    }

    private fun readSlice(cursor: Mango9NativeMediaHistory.Cursor, older: Boolean, result: ArrayList<Item>) {
        coreContext.postOnCoreThread { core ->
            if (!current() || core.globalState != GlobalState.On) {
                result.forEach { it.model.destroy() }
                main.post { busy = false; loadingHistory.value = false }
                return@postOnCoreThread
            }
            val slice = Mango9NativeMediaHistory(chatRoom).slice(cursor, older, 8 - result.size)
            for (entry in slice.entries) {
                val content = entry.content
                val original = content.filePath.orEmpty()
                val encrypted = content.isFileEncrypted
                val path = if (encrypted) content.exportPlainFile() else original
                if (path.isEmpty()) continue
                result.add(
                    Item(
                        entry.cursor,
                        FileModel(
                    path, content.name.orEmpty(), content.fileSize.toLong(), entry.time, encrypted, original,
                    entry.ephemeral, loadMetadata = false, mimeTypeOverride = content.attachmentMimeType(),
                    relatedMessageId = entry.cursor.message
                )
                    )
                )
            }
            if (result.size < 8 && !slice.exhausted && slice.next != null) {
                readSlice(slice.next, older, result)
            } else {
                main.post { installPage(older, result, slice.exhausted) }
            }
        }
    }

    private fun installPage(older: Boolean, page: List<Item>, exhausted: Boolean) {
        busy = false
        loadingHistory.value = false
        if (!current()) { page.forEach { it.model.destroy() }; return }
        if (older) olderEnd = exhausted else newerEnd = exhausted
        val combined = if (older) items + page else page.reversed() + items
        val unique = combined.distinctBy { it.cursor }
        val selected = unique.indexOfFirst { it.model.path == selectedPath }.coerceAtLeast(0)
        val start = (selected - 12).coerceIn(0, (unique.size - 25).coerceAtLeast(0))
        val kept = unique.drop(start).take(25)
        if (start > 0) newerEnd = false
        if (start + kept.size < unique.size) olderEnd = false
        items = kept
        mediaList.value = kept.map { it.model }
        // Update the pager first. Its fragments retain their own file pins.
        val live = kept.mapTo(HashSet()) { it.model }
        combined.filter { it.model !in live }.forEach { it.model.destroy() }
        onSelected(selectedPath)
    }
}
