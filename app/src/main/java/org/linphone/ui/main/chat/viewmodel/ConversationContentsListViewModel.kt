// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.ui.main.chat.viewmodel

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resumeWithException
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.ChatRoom
import org.linphone.core.ChatRoomListenerStub
import org.linphone.core.ChatMessage
import org.linphone.core.EventLog
import org.linphone.core.Content
import org.linphone.core.GlobalState
import org.linphone.mango9.Mango9ContentPager
import org.linphone.mango9.Mango9SessionStore
import org.linphone.mango9.Mango9NativeFileAsset
import org.linphone.mango9.Mango9NativeFileLease
import org.linphone.R
import org.linphone.mango9.attachmentMimeType
import org.linphone.ui.main.chat.model.FileModel
import org.linphone.utils.Event
import org.linphone.utils.FileUtils

/** Shared ownership and source-cursor rules for native Media and Documents lists. */
abstract class ConversationContentsListViewModel(private val media: Boolean) : AbstractConversationViewModel() {
    val contentsList = MutableLiveData<List<FileModel>>(emptyList())
    val operationInProgress = MutableLiveData(false)
    val loadFailed = MutableLiveData(false)
    val openContentEvent = MutableLiveData<Event<FileModel>>()
    private val main = Handler(Looper.getMainLooper())
    private val sessions = Mango9SessionStore(coreContext.context)
    private val owner = sessions.selectionStamp()
    private val ownerCore = coreContext.core

    @Volatile private var disposed = false

    @Volatile private var loadedRoom: ChatRoom? = null // Mutated on Core; UI only compares identity.
    private var sourceCount = -1
    private var refreshQueued = false
    private var openedFile: FileModel? = null // At most one source-owned open document; viewers also retain their own lease.

    private fun current() = !disposed && sessions.selectionStamp() == owner && coreContext.core === ownerCore

    private val roomListener = object : ChatRoomListenerStub() {
        override fun onChatMessageReceived(chatRoom: ChatRoom, eventLog: EventLog) = refreshLater(chatRoom)

        override fun onChatMessagesReceived(chatRoom: ChatRoom, eventLogs: Array<EventLog>) = refreshLater(chatRoom)

        override fun onChatMessageSent(chatRoom: ChatRoom, eventLog: EventLog) = refreshLater(chatRoom)

        override fun onEphemeralMessageDeleted(chatRoom: ChatRoom, eventLog: EventLog) = refreshLater(chatRoom)

        override fun onMessageContentEdited(chatRoom: ChatRoom, message: ChatMessage) = refreshLater(chatRoom)

        override fun onMessageRetracted(chatRoom: ChatRoom, message: ChatMessage) = refreshLater(chatRoom)
    }

    private val pager: Mango9ContentPager<Content, FileModel> = Mango9ContentPager(
        if (media) 50 else 20,
        { action -> coreContext.postOnCoreThread { action() } },
        { current() }, FileModel::destroy,
        { snapshot ->
            main.post {
            if (current() && snapshot.generation == pagerGeneration()) {
                contentsList.value = snapshot.items
                operationInProgress.value = snapshot.loading
                loadFailed.value = snapshot.failed
            }
        }
        },
    )

    private fun pagerGeneration(): Long = pager.generation

    init {
        viewModelScope.launch {
            Mango9SessionStore.contextChanges.collect {
                if (!current()) {
                    disposed = true; pager.close(); contentsList.value = emptyList()
                    openedFile?.destroy(); openedFile = null
                    operationInProgress.value = false; loadFailed.value = false
                    coreContext.postOnCoreThread { detachRoom() }
                }
            }
        }
    }

    override fun afterNotifyingChatRoomFound(sameOne: Boolean) {
        if (!current() || ownerCore.globalState != GlobalState.On) return
        val room = chatRoom
        if (room.core !== ownerCore) return
        if (loadedRoom !== room) {
            detachRoom(); loadedRoom = room; room.addListener(roomListener)
            pager.reset(0, { _, _ -> emptyList() }, { null })
            restart(room)
        } else {
            // Preserve the scroll position on recreation only while the source is unchanged.
            refreshIfChanged(room)
        }
    }

    private fun usable(room: ChatRoom) = current() && loadedRoom === room && ownerCore.globalState == GlobalState.On

    private fun count(room: ChatRoom) = if (media) room.mediaContentsSize else room.documentContentsSize

    private fun restart(room: ChatRoom) {
        if (!usable(room)) return
        try {
            sourceCount = count(room)
            pager.reset(
            sourceCount,
            { begin, end ->
                check(usable(room))
                if (count(room) != sourceCount) {
                    restart(room)
                    emptyList()
                } else {
                    (if (media) room.getMediaContentsRange(begin, end) else room.getDocumentContentsRange(begin, end)).toList()
                }
            },
            { content -> file(room, content) },
        )
        } catch (_: Exception) { sourceCount = -1; showLoadFailure(room) }
    }

    private fun showLoadFailure(room: ChatRoom) {
        val request = pager.generation
        main.post {
            if (current() && loadedRoom === room && request == pager.generation) {
                operationInProgress.value = false; loadFailed.value = true
            }
        }
    }

    private fun refreshIfChanged(room: ChatRoom): Boolean {
        if (!usable(room)) return true
        return try {
            if (count(room) != sourceCount) { restart(room); true } else {
                false
            }
        } catch (_: Exception) { showLoadFailure(room); true }
    }

    private fun refreshLater(room: ChatRoom) {
        if (!usable(room) || refreshQueued) return
        refreshQueued = true
        coreContext.postOnCoreThread {
            refreshQueued = false
            if (usable(room)) restart(room)
        }
    }

    fun loadMoreData() {
        coreContext.postOnCoreThread {
            loadedRoom?.let { room -> if (!refreshIfChanged(room)) pager.more() }
        }
    }

    private fun detachRoom() {
        loadedRoom?.removeListener(roomListener)
        loadedRoom = null; sourceCount = -1; refreshQueued = false
    }

    private fun file(room: ChatRoom, content: Content): FileModel? {
        check(usable(room))
        // Paging reads only metadata. Plaintext is acquired when an attached row needs it.
        val name = content.name.orEmpty()
        val original = content.filePath.orEmpty()
        if (name.isBlank() || original.isBlank()) return null
        val encrypted = content.isFileEncrypted
        val id = content.relatedChatMessageId
        val ephemeral = id?.let { room.findMessage(it)?.isEphemeral } ?: room.isEphemeralEnabled
        return FileModel(
            original, name, content.fileSize.toLong(), content.creationTimestamp, encrypted, original, ephemeral,
            loadMetadata = false, mimeTypeOverride = content.attachmentMimeType(), relatedMessageId = id,
            ownsEncryptedFile = false, presentationScoped = true,
            deferredFile = if (encrypted) ({ acquire(room, content) }) else null,
            onLoadFailed = { if (current()) showRedToast(R.string.mango9_attachment_open_failed, R.drawable.file) },
        ) { model -> open(model) }
    }

    private suspend fun acquire(room: ChatRoom, content: Content): Mango9NativeFileAsset = suspendCancellableCoroutine { continuation ->
        val ticket = coreContext.postOnCoreThreadCancellable {
            try {
                check(usable(room) && continuation.isActive)
                val path = content.exportPlainFile()
                check(path.isNotBlank()) { "File export unavailable" }
                val asset =
                    Mango9NativeFileAsset(path, Mango9NativeFileLease { cleanup.launch { FileUtils.deleteFile(path) } })
                if (!usable(room) || !continuation.isActive) {
                    asset.close()
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("File source changed"))
                } else {
                    continuation.resume(asset) { _, rejected, _ -> rejected.close() }
                }
            } catch (failure: Exception) {
                if (continuation.isActive) continuation.resumeWithException(failure)
            }
        }
        continuation.invokeOnCancellation { ticket.cancel() }
    }

    private fun open(model: FileModel) {
        if (!current()) return
        val opened = FileModel(
            model.path, model.fileName, model.fileSize, model.fileCreationTimestamp,
            model.isEncrypted, model.originalPath, model.isFromEphemeralMessage,
            loadMetadata = false, mimeTypeOverride = model.mimeTypeString, relatedMessageId = model.relatedMessageId,
            retainedFile = model.retainFile(), ownsEncryptedFile = false,
        )
        openedFile?.destroy(); openedFile = opened
        openContentEvent.value = Event(opened)
    }

    override fun onCleared() {
        disposed = true; pager.close()
        openedFile?.destroy(); openedFile = null
        contentsList.value = emptyList(); operationInProgress.value = false; loadFailed.value = false
        coreContext.postOnCoreThread { detachRoom() }
        super.onCleared()
    }

    private companion object { val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO) }
}
