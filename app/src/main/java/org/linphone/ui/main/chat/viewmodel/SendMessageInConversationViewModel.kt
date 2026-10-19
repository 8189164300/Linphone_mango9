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
package org.linphone.ui.main.chat.viewmodel

import org.linphone.mango9.Mango9SharedDraft
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.linphone.core.GlobalState

import android.Manifest
import android.content.pm.PackageManager
import android.text.Spannable
import android.view.inputmethod.EditorInfo
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.core.app.ActivityCompat
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.viewModelScope
import androidx.media.AudioFocusRequestCompat
import java.text.SimpleDateFormat
import java.util.Locale
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.R
import org.linphone.core.ChatMessage
import org.linphone.core.Account
import org.linphone.core.ChatRoom
import org.linphone.core.ChatRoomListenerStub
import org.linphone.core.EventLog
import org.linphone.core.Factory
import org.linphone.core.MediaFileFormat
import org.linphone.core.Player
import org.linphone.core.PlayerListener
import org.linphone.core.Recorder
import org.linphone.core.tools.Log
import org.linphone.ui.GenericViewModel
import org.linphone.ui.main.chat.model.FileModel
import org.linphone.ui.main.chat.model.MessageModel
import org.linphone.ui.main.chat.model.ParticipantModel
import org.linphone.utils.AudioUtils
import org.linphone.utils.Event
import org.linphone.utils.FileUtils
import org.linphone.utils.LinphoneUtils
import org.linphone.mango9.Mango9DraftFileLease
import org.linphone.mango9.Mango9SessionStore
import org.linphone.mango9.Mango9PendingShare

class SendMessageInConversationViewModel
    @UiThread
    internal constructor(private val sendToSdk: (ChatMessage) -> Unit) : GenericViewModel() {
    @UiThread
        constructor() : this({ it.send() })

    companion object {
        private const val TAG = "[Send Message In Conversation ViewModel]"

        const val MAX_FILES_TO_ATTACH = 12
        private val fileCleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    }

    val textToSend = MutableLiveData<String>()

    private val main = Handler(Looper.getMainLooper())
    private val draftFiles = LinkedHashMap<FileModel, Mango9DraftFileLease>()
    private var textRevision = 0L
    private var messageActionRevision = 0L
    private val textObserver = Observer<String> { textRevision++ }

    @Volatile private var disposed = false

    @Volatile private var roomGeneration = 0L
    private var sending = false

    @Volatile private var voiceGeneration = 0L
    private var voiceFileLease: Mango9DraftFileLease? = null
    // These are only resolved/read on the SDK thread, never by querying an SDK object on UI.
    private var roomAccount: Account? = null
    private var roomLease: Mango9SessionStore.CallLease? = null
    private val sessions by lazy { Mango9SessionStore(coreContext.context) }

    val isFileTransferServerAvailable = MutableLiveData<Boolean>()

    val isEmojiPickerOpen = MutableLiveData<Boolean>()

    val areFilePickersOpen = MutableLiveData<Boolean>()

    val isParticipantsListOpen = MutableLiveData<Boolean>()

    val participants = MutableLiveData<ArrayList<ParticipantModel>>()

    val isFileAttachmentsListOpen = MutableLiveData<Boolean>()

    val maxNumberOfAttachmentsReached = MutableLiveData<Boolean>()

    val attachments = MutableLiveData<ArrayList<FileModel>>()

    val isEditing = MutableLiveData<Boolean>()

    val isEditingMessage = MutableLiveData<Spannable>()

    val isReplying = MutableLiveData<Boolean>()

    val isReplyingTo = MutableLiveData<String>()

    val isReplyingToMessage = MutableLiveData<Spannable>()

    val isKeyboardOpen = MutableLiveData<Boolean>()

    val isCallConversation = MutableLiveData<Boolean>()

    val isVoiceRecording = MutableLiveData<Boolean>()

    val isVoiceRecordingInProgress = MutableLiveData<Boolean>()

    val voiceRecordingDuration = MutableLiveData<Int>()

    val formattedVoiceRecordingDuration = MutableLiveData<String>()

    val isPlayingVoiceRecord = MutableLiveData<Boolean>()

    val voiceRecordPlayerPosition = MutableLiveData<Int>()

    val isComputingParticipantsList = MutableLiveData<Boolean>()

    val imeFlags = MutableLiveData<Int>()

    private lateinit var voiceRecordPlayer: Player

    private val playerListener = PlayerListener {
        Log.i("$TAG End of file reached")
        stopVoiceRecordPlayer()
    }

    val requestKeyboardHidingEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    val emojiToAddEvent: MutableLiveData<Event<String>> by lazy {
        MutableLiveData()
    }

    val participantUsernameToAddEvent: MutableLiveData<Event<String>> by lazy {
        MutableLiveData()
    }

    val askRecordAudioPermissionEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    val messageSentEvent: MutableLiveData<Event<ChatMessage>> by lazy {
        MutableLiveData()
    }

    lateinit var chatRoom: ChatRoom

    private var chatMessageToReplyTo: ChatMessage? = null

    private var chatMessageToEdit: ChatMessage? = null

    private lateinit var voiceMessageRecorder: Recorder

    private var voiceRecordAudioFocusRequest: AudioFocusRequestCompat? = null

    private var participantsListFilter = ""

    private var capturedPicturedPath = ""

    private val chatRoomListener = object : ChatRoomListenerStub() {
        @WorkerThread
        override fun onParticipantAdded(chatRoom: ChatRoom, eventLog: EventLog) {
            computeParticipantsList()
        }

        @WorkerThread
        override fun onParticipantRemoved(chatRoom: ChatRoom, eventLog: EventLog) {
            computeParticipantsList()
        }
    }

    init {
        textToSend.observeForever(textObserver)
        coreContext.postOnCoreThread { core ->
            isFileTransferServerAvailable.postValue(!core.fileTransferServer.isNullOrEmpty())
        }

        isKeyboardOpen.value = false
        isEmojiPickerOpen.value = false
        areFilePickersOpen.value = false
        isParticipantsListOpen.value = false
        isVoiceRecording.value = false
        isPlayingVoiceRecord.value = false
        isCallConversation.value = false
        maxNumberOfAttachmentsReached.value = false
    }

    override fun onCleared() {
        disposed = true
        roomGeneration++
        textToSend.removeObserver(textObserver)
        releaseDraftFiles(attachments.value.orEmpty())
        super.onCleared()
        coreContext.postOnCoreThread {
            if (::voiceMessageRecorder.isInitialized) stopVoiceRecorder()
            voiceFileLease?.close()
            voiceFileLease = null
            if (::voiceRecordPlayer.isInitialized) {
                stopVoiceRecordPlayer()
                voiceRecordPlayer.removeListener(playerListener)
            }
            if (::chatRoom.isInitialized) {
                chatRoom.removeListener(chatRoomListener)
            }
        }
    }

    @UiThread
    fun configureChatRoom(room: ChatRoom) {
        if (disposed || (::chatRoom.isInitialized && chatRoom === room)) return
        val oldRoom = if (::chatRoom.isInitialized) chatRoom else null
        val generation = ++roomGeneration
        if (oldRoom != null) {
            closeFileAttachmentsList()
            textToSend.value = ""
            cancelReply()
            cancelEdit()
        }
        Log.i("$TAG Chat room configured")
        chatRoom = room
        coreContext.postOnCoreThread {
            oldRoom?.removeListener(chatRoomListener)
            if (disposed || generation != roomGeneration) return@postOnCoreThread
            roomAccount = room.account
            roomLease = sessions.captureCallLease(roomAccount?.params?.identityAddress?.asStringUriOnly())
            room.addListener(chatRoomListener)

            val ime = if (room.hasCapability(ChatRoom.Capabilities.Encrypted.toInt())) {
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            } else {
                EditorInfo.IME_NULL
            }
            imeFlags.postValue(ime)

            computeParticipantsList()
        }
    }

    @UiThread
    fun toggleEmojiPickerVisibility() {
        isEmojiPickerOpen.value = isEmojiPickerOpen.value == false
        if (isEmojiPickerOpen.value == true) {
            requestKeyboardHidingEvent.value = Event(true)
        }
        closeFilePickerBottomSheet()
        closeParticipantsList()
    }

    @UiThread
    fun insertEmoji(emoji: String) {
        emojiToAddEvent.value = Event(emoji)
    }

    @UiThread
    fun toggleFilePickersVisibility() {
        areFilePickersOpen.value = areFilePickersOpen.value == false
        isEmojiPickerOpen.value = false
        closeParticipantsList()
    }

    @UiThread
    fun closeFilePickerBottomSheet() {
        areFilePickersOpen.value = false
    }

    @UiThread
    fun editMessage(model: MessageModel) {
        if (isReplying.value == true) {
            cancelReply()
        }
        if (isVoiceRecording.value == true) {
            cancelVoiceMessageRecording()
        }

        val newValue = model.text.value?.toString() ?: ""
        textToSend.value = newValue
        val generation = roomGeneration
        val action = ++messageActionRevision
        val message = model.chatMessage // Reference only; SDK properties stay on the core thread.
        chatMessageToEdit = message
        isEditing.value = true
        coreContext.postOnCoreThread {
            val description = LinphoneUtils.getFormattedTextDescribingMessage(message)
            main.post {
                if (!disposed && generation == roomGeneration && action == messageActionRevision) isEditingMessage.value = description
            }
        }
    }

    @UiThread
    fun cancelEdit() {
        messageActionRevision++
        Log.i("$TAG Cancelling edit")
        isEditing.value = false
        chatMessageToEdit = null
        textToSend.value = ""
    }

    @UiThread
    fun replyToMessage(model: MessageModel) {
        if (isEditing.value == true) {
            cancelEdit()
        }

        val generation = roomGeneration
        val action = ++messageActionRevision
        val message = model.chatMessage
        chatMessageToReplyTo = message
        isReplying.value = true
        coreContext.postOnCoreThread {
            val name = model.avatarModel.value?.friend?.name
            val description = LinphoneUtils.getFormattedTextDescribingMessage(message)
            main.post {
                if (!disposed && generation == roomGeneration && action == messageActionRevision) {
                    isReplyingTo.value = name.orEmpty()
                    isReplyingToMessage.value = description
                }
            }
        }
    }

    @UiThread
    fun cancelReply() {
        messageActionRevision++
        Log.i("$TAG Cancelling reply")
        isReplying.value = false
        chatMessageToReplyTo = null
    }

    @UiThread fun appendSharedText(text: String) {
        if (!disposed) textToSend.value = Mango9PendingShare.mergeText(textToSend.value.orEmpty(), text)
    }

    @UiThread
    fun sendMessage() {
        if (disposed || sending || !::chatRoom.isInitialized) return
        val room = chatRoom
        val generation = roomGeneration
        val revision = textRevision
        val text = textToSend.value.orEmpty().trim()
        val reply = chatMessageToReplyTo
        val edit = chatMessageToEdit
        val voice = isVoiceRecording.value == true
        val voiceRevision = voiceGeneration
        val files = if (voice) emptyList() else attachments.value.orEmpty().toList()
        if (text.isEmpty() && files.isEmpty() && !voice) return
        val pins = files.associateWith { requireNotNull(draftFiles[it]).retain() }
        sending = true
        coreContext.postOnCoreThread { core ->
            val accepted = mutableSetOf<FileModel>()
            var acceptedText = false
            var acceptedVoice = false
            var failed = false
            var voicePin: Mango9DraftFileLease? = null
            try {
                check(core.globalState == org.linphone.core.GlobalState.On)
                val account = roomAccount
                val lease = roomLease
                check(!disposed && generation == roomGeneration && lease != null && account != null)
                check(core.accountList.any { it.nativePointer == account.nativePointer })
                check(core.defaultAccount?.nativePointer == account.nativePointer && room.account?.nativePointer == account.nativePointer)
                check(account.params.identityAddress?.asStringUriOnly() == lease.identity)
                check(!room.isReadOnly)
                check(files.all { java.io.File(it.path).let { file -> file.isFile && file.length() > 0 } })
                val dispatched = sessions.withCurrentCallLease(lease) {
                    check(!disposed && generation == roomGeneration)
                    val basic = room.hasCapability(ChatRoom.Capabilities.Basic.toInt())
                    var message = when {
                        reply != null -> room.createReplyMessage(reply)
                        edit != null -> room.createReplacesMessage(edit)
                        else -> room.createEmptyMessage()
                    }
                    var messageHasText = false
                    var messageHasVoice = false
                    val messageFiles = mutableListOf<FileModel>()

                    fun dispatch() {
                        if (message.contents.isEmpty()) return
                        var submitted = false
                        try {
                            sendToSdk(message)
                            submitted = true
                        } finally {
                            // Pins protect the file during send(). If it failed before the SDK
                            // accepted anything, the draft still owns/removes it. Once accepted,
                            // retain its durable path for upload/retry/history, including a
                            // synchronous SDK state transition followed by an exception.
                            if (submitted || message.state != ChatMessage.State.Idle || message.isFileTransferInProgress) {
                                messageFiles.forEach { pins.getValue(it).keepForHistory() }
                                if (messageHasVoice) voicePin?.keepForHistory()
                                accepted.addAll(messageFiles)
                                acceptedText = acceptedText || messageHasText
                                acceptedVoice = acceptedVoice || messageHasVoice
                                val sent = message
                                main.post { if (!disposed && generation == roomGeneration) messageSentEvent.value = Event(sent) }
                            }
                        }
                        message = room.createEmptyMessage()
                        messageFiles.clear()
                        messageHasText = false
                        messageHasVoice = false
                    }
                    if (text.isNotEmpty()) { message.addUtf8TextContent(text); messageHasText = true }
                    if (voice) {
                        check(voiceRevision == voiceGeneration && ::voiceMessageRecorder.isInitialized)
                        stopVoiceRecorder()
                        val content = requireNotNull(voiceMessageRecorder.createContent())
                        voicePin = requireNotNull(voiceFileLease).retain()
                        if (basic) dispatch()
                        message.addContent(content)
                        messageHasVoice = true
                    } else {
                        for (file in files) {
                            if (basic) dispatch()
                            val content = Factory.instance().createContent()
                            content.type = file.mimeTypeString.substringBefore('/')
                            content.subtype = file.mimeTypeString.substringAfter('/')
                            content.name = file.fileName
                            content.filePath = file.path
                            message.addFileContent(content)
                            messageFiles.add(file)
                        }
                    }
                    dispatch()
                    true
                }
                check(dispatched == true)
            } catch (_: Exception) {
                failed = true
            } finally {
                pins.values.forEach { it.close() }
                voicePin?.close()
                if (acceptedVoice && voiceRevision == voiceGeneration) {
                    voiceFileLease?.close()
                    voiceFileLease = null
                }
                main.post {
                    sending = false
                    if (!disposed && generation == roomGeneration) {
                        val unchanged = revision == textRevision
                        if (acceptedText && unchanged) textToSend.value = ""
                        releaseDraftFiles(accepted)
                        publishAttachments(attachments.value.orEmpty().filterNot { it in accepted })
                        if (acceptedVoice && voiceRevision == voiceGeneration) isVoiceRecording.value = false
                        if ((acceptedText || accepted.isNotEmpty() || acceptedVoice) && unchanged) {
                            if (chatMessageToReplyTo === reply) cancelReply()
                            if (chatMessageToEdit === edit) {
                                chatMessageToEdit = null
                                isEditing.value = false
                            }
                        }
                        if (failed) showRedToast(R.string.mango9_native_draft_send_failed, R.drawable.warning_circle)
                    }
                }
            }
        }
    }

    @UiThread
    fun notifyComposing(composing: Boolean) {
        coreContext.postOnCoreThread {
            if (::chatRoom.isInitialized) {
                if (composing) {
                    chatRoom.composeTextMessage()
                } else {
                    chatRoom.stopComposing()
                }
            }
        }
    }

    @UiThread
    fun openParticipantsList() {
        isParticipantsListOpen.value = true
        isEmojiPickerOpen.value = false
        closeFilePickerBottomSheet()
    }

    @UiThread
    fun closeParticipantsList() {
        isParticipantsListOpen.value = false
        coreContext.postOnCoreThread {
            participantsListFilter = ""
            computeParticipantsList()
        }
    }

    @UiThread
    fun closeFileAttachmentsList() {
        releaseDraftFiles(attachments.value.orEmpty())
        publishAttachments(emptyList())
    }

    private fun releaseDraftFiles(files: Collection<FileModel>) {
        files.forEach { file -> draftFiles.remove(file)?.close(); file.destroy() }
    }

    private fun publishAttachments(files: List<FileModel>) {
        attachments.value = ArrayList(files)
        maxNumberOfAttachmentsReached.value = files.size >= MAX_FILES_TO_ATTACH
        isFileAttachmentsListOpen.value = files.isNotEmpty()
    }

    private fun ownDraftFile(path: String): Mango9DraftFileLease = Mango9DraftFileLease {
        fileCleanup.launch { FileUtils.deleteFile(path) }
    }

    @UiThread
    fun addAttachments(files: ArrayList<String>) {
        val list = arrayListOf<FileModel>()
        list.addAll(attachments.value.orEmpty())
        var rejected = false
        for (file in files) {
            // Receiving the same owned path twice must not schedule deletion of its first row.
            if (list.any { it.path == file }) continue
            if (disposed || list.size >= MAX_FILES_TO_ATTACH) {
                rejected = true
                ownDraftFile(file).close()
                continue
            }

            val fileName = FileUtils.getNameFromFilePath(file)
            val timestamp = System.currentTimeMillis() / 1000
            val size = FileUtils.getFileSize(file)
            val model = FileModel(file, fileName, size, timestamp, false, file, false) { model ->
                removeAttachment(model.path)
            }
            draftFiles[model] = ownDraftFile(file)
            list.add(model)
        }
        if (!disposed) {
            publishAttachments(list)
            if (rejected) showRedToast(R.string.conversation_maximum_number_of_attachments_reached, R.drawable.warning_circle)
        }
    }

    /** SIP owns durable copies: deleting the external-share snapshot cannot break SDK upload/retry. */
    @UiThread
    internal fun adoptSharedDraft(share: Mango9SharedDraft, expectedConversationId: String? = null) {
        if (disposed || !::chatRoom.isInitialized || !share.isCurrent()) { share.close(); return }
        val generation = roomGeneration
        viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            val promoted = mutableListOf<File>()
            try {
                val allowed = suspendCancellableCoroutine<Boolean> { continuation ->
                    coreContext.postOnCoreThread { core ->
                        if (!continuation.isActive) return@postOnCoreThread
                        val account = chatRoom.account
                        continuation.resume(
                            !disposed && generation == roomGeneration && share.isCurrent() &&
                            core.globalState == GlobalState.On && account != null &&
                            core.defaultAccount?.nativePointer == account.nativePointer &&
                                core.accountList.any { it.nativePointer == account.nativePointer } &&
                                (expectedConversationId == null || LinphoneUtils.getConversationId(chatRoom) == expectedConversationId)
                        )
                    }
                }
                if (!allowed) return@launch
                val remaining = (MAX_FILES_TO_ATTACH - attachments.value.orEmpty().size).coerceAtLeast(0)
                val selected = share.files.take(remaining)
                withContext(Dispatchers.IO) {
                    for (prepared in selected) {
                        ensureActive()
                        val name = org.linphone.mango9.Mango9AttachmentPreparer.safeName(prepared.attachment.name)
                        val destination = FileUtils.getFileStoragePath("${UUID.randomUUID()}_$name")
                        check(destination.createNewFile())
                        promoted.add(destination)
                        File(requireNotNull(prepared.attachment.uri.path)).inputStream().use { input ->
                            destination.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                    }
                }
                ensureActive()
                if (disposed || generation != roomGeneration || !share.isCurrent()) return@launch
                val list = ArrayList(attachments.value.orEmpty())
                for ((index, file) in promoted.withIndex()) {
                    if (list.size >= MAX_FILES_TO_ATTACH) break
                    val attachment = selected[index].attachment
                    val model = FileModel(
                        file.path, attachment.name, attachment.size,
                        System.currentTimeMillis() / 1000, false, file.path, false,
                        mimeTypeOverride = attachment.mimeType
                    ) { removeAttachment(it.path) }
                    draftFiles[model] = ownDraftFile(file.path)
                    list.add(model)
                }
                val accepted = list.map { it.path }.toSet()
                promoted.removeAll { it.path in accepted }
                publishAttachments(list)
                appendSharedText(share.text)
                if (share.error != null) showRedToast(R.string.mango9_share_failed, R.drawable.warning_circle)
                if (selected.size < share.files.size || promoted.isNotEmpty()) {
                    showRedToast(R.string.conversation_maximum_number_of_attachments_reached, R.drawable.warning_circle)
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                if (!disposed) showRedToast(R.string.mango9_share_failed, R.drawable.warning_circle)
            } finally {
                share.close()
                val abandoned = promoted.toList()
                fileCleanup.launch { abandoned.forEach { FileUtils.deleteFile(it.path) } }
            }
        }
    }

    @UiThread
    fun removeAttachment(file: String, delete: Boolean = true) {
        val list = arrayListOf<FileModel>()
        list.addAll(attachments.value.orEmpty())
        val found = list.find {
            it.path == file
        }
        if (found != null) {
            if (!delete) draftFiles[found]?.keepForHistory()
            releaseDraftFiles(listOf(found))
            list.remove(found)
        } else {
            Log.w("$TAG Failed to find file attachment matching [$file]")
        }
        publishAttachments(list)
    }

    @UiThread
    fun forwardMessage(toForward: MessageModel) {
        coreContext.postOnCoreThread {
            if (::chatRoom.isInitialized) {
                val messageToForward = toForward.chatMessage
                val forwardedMessage = chatRoom.createForwardMessage(messageToForward)
                Log.i("$TAG Sending forwarded message")
                forwardedMessage.send()
                messageSentEvent.postValue(Event(forwardedMessage))

                showGreenToast(R.string.conversation_message_forwarded_toast, R.drawable.forward)
            }
        }
    }

    @UiThread
    fun startVoiceMessageRecording() {
        if (disposed || sending || !::chatRoom.isInitialized) return
        val generation = roomGeneration
        val voiceRevision = ++voiceGeneration
        if (ActivityCompat.checkSelfPermission(
                coreContext.context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(
                "$TAG Can't start voice message recording, RECORD_AUDIO permission wasn't granted yet"
            )
            askRecordAudioPermissionEvent.postValue(Event(true))
            return
        }

        isEmojiPickerOpen.value = false
        closeFilePickerBottomSheet()
        closeParticipantsList()

        coreContext.postOnCoreThread {
            if (disposed || generation != roomGeneration || voiceRevision != voiceGeneration) return@postOnCoreThread
            requestKeyboardHidingEvent.postValue(Event(true))
            isVoiceRecording.postValue(true)
            initVoiceRecorder()

            isVoiceRecordingInProgress.postValue(true)
            startVoiceRecorder()
        }
    }

    @UiThread
    fun stopVoiceMessageRecording() {
        coreContext.postOnCoreThread {
            chatRoom.stopComposing()
            stopVoiceRecorder()
        }
    }

    @UiThread
    fun cancelVoiceMessageRecording() {
        val voiceRevision = ++voiceGeneration
        coreContext.postOnCoreThread {
            if (voiceRevision != voiceGeneration || !::voiceMessageRecorder.isInitialized) return@postOnCoreThread
            chatRoom.stopComposing()
            stopVoiceRecorder()
            voiceFileLease?.close()
            voiceFileLease = null
            isVoiceRecording.postValue(false)
        }
    }

    @UiThread
    fun togglePlayPauseVoiceRecord() {
        coreContext.postOnCoreThread {
            if (isPlayingVoiceRecord.value == false) {
                startVoiceRecordPlayer()
            } else {
                pauseVoiceRecordPlayer()
            }
        }
    }

    @WorkerThread
    fun filterParticipantsList(filter: String) {
        Log.i("$TAG Filtering participants list using user-input [$filter]")
        if (filter.isEmpty() && participantsListFilter.isNotEmpty()) {
            participantsListFilter = ""
            computeParticipantsList()
            return
        }

        if (filter.length >= participantsListFilter.length) {
            isComputingParticipantsList.postValue(true)
            participantsListFilter = filter
            val currentList = participants.value.orEmpty()
            val newList = currentList.filter {
                it.address.username.orEmpty().contains(filter) || it.avatarModel.contactName?.contains(filter) == true
            }
            participants.postValue(newList as ArrayList<ParticipantModel>)
            isComputingParticipantsList.postValue(false)
        } else {
            participantsListFilter = filter
            computeParticipantsList(filter)
        }
    }

    @UiThread
    fun setCapturedPicturePath(path: String) {
        capturedPicturedPath = path
    }

    @UiThread
    fun getCapturedPicturePath(): String {
        return capturedPicturedPath
    }

    @UiThread
    fun clearCapturedPicturePath() {
        capturedPicturedPath = ""
    }

    @WorkerThread
    private fun computeParticipantsList(filter: String = "") {
        isComputingParticipantsList.postValue(true)
        val participantsList = arrayListOf<ParticipantModel>()

        if (::chatRoom.isInitialized) {
            for (participant in chatRoom.participants) {
                val model = ParticipantModel(participant.address, onClicked = { clicked ->
                    Log.i("$TAG Clicked on participant [${clicked.sipUri}]")
                    coreContext.postOnCoreThread {
                        val username = clicked.address.username
                        if (!username.isNullOrEmpty()) {
                            participantUsernameToAddEvent.postValue(Event(username.substring(participantsListFilter.length)))
                        }
                    }
                })

                if (
                    filter.isEmpty() ||
                    participant.address.asStringUriOnly().contains(filter) ||
                    model.avatarModel.contactName?.contains(filter) == true
                ) {
                    participantsList.add(model)
                }
            }
        }

        participants.postValue(participantsList)
        isComputingParticipantsList.postValue(false)
    }

    @WorkerThread
    private fun initVoiceRecorder() {
        if (::voiceMessageRecorder.isInitialized) stopVoiceRecorder()
        voiceFileLease?.close()
        voiceFileLease = null
        val core = coreContext.core
        Log.i("$TAG Creating voice message recorder")
        val recorderParams = core.createRecorderParams()
        recorderParams.fileFormat = MediaFileFormat.Mkv

        val recordingAudioDevice = AudioUtils.getAudioRecordingDeviceIdForVoiceMessage()
        recorderParams.audioDevice = recordingAudioDevice
        Log.i(
            "$TAG Using device ${recorderParams.audioDevice?.id} to make the voice message recording"
        )

        voiceMessageRecorder = core.createRecorder(recorderParams)
        Log.i("$TAG Voice message recorder created")
    }

    @WorkerThread
    private fun startVoiceRecorder() {
        if (voiceRecordAudioFocusRequest == null) {
            Log.i("$TAG Requesting audio focus for voice message recording")
            voiceRecordAudioFocusRequest = AudioUtils.acquireAudioFocusForVoiceRecordingOrPlayback(
                coreContext.context
            )
        }

        when (voiceMessageRecorder.state) {
            Recorder.State.Running -> Log.w("$TAG Recorder is already recording")
            Recorder.State.Paused -> {
                Log.w("$TAG Recorder is paused, resuming recording")
                voiceMessageRecorder.start()
            }
            Recorder.State.Closed -> {
                val extension = when (voiceMessageRecorder.params.fileFormat) {
                    MediaFileFormat.Smff -> "smff"
                    MediaFileFormat.Mkv -> "mka"
                    else -> "wav"
                }
                val tempFileName = "voice-recording-${System.currentTimeMillis()}.$extension"
                val file = FileUtils.getFileStoragePath(tempFileName)
                voiceFileLease = ownDraftFile(file.absolutePath)
                Log.w(
                    "$TAG Recorder is closed, starting recording in ${file.absoluteFile}"
                )
                voiceMessageRecorder.open(file.absolutePath)
                voiceMessageRecorder.start()
            }
            else -> {}
        }
        chatRoom.composeVoiceMessage()

        val duration = voiceMessageRecorder.duration
        val formattedDuration = SimpleDateFormat("mm:ss", Locale.getDefault()).format(duration) // duration is in ms
        formattedVoiceRecordingDuration.postValue(formattedDuration)

        val maxVoiceRecordDuration = corePreferences.voiceRecordingMaxDuration
        recorderTickerFlow().onEach {
            coreContext.postOnCoreThread {
                val duration = voiceMessageRecorder.duration
                val formattedDuration = SimpleDateFormat("mm:ss", Locale.getDefault()).format(
                    duration
                ) // duration is in ms
                formattedVoiceRecordingDuration.postValue(formattedDuration)

                if (duration >= maxVoiceRecordDuration) {
                    Log.w(
                        "$TAG Max duration for voice recording exceeded (${maxVoiceRecordDuration}ms), stopping."
                    )
                    stopVoiceRecorder()
                    showRedToast(R.string.conversation_voice_recording_max_duration_reached_toast, R.drawable.warning_circle)
                }
            }
        }.launchIn(viewModelScope)
    }

    @WorkerThread
    private fun stopVoiceRecorder() {
        if (voiceMessageRecorder.state == Recorder.State.Running) {
            Log.i("$TAG Closing voice recorder")
            voiceMessageRecorder.pause()
            voiceMessageRecorder.close()
        }

        val request = voiceRecordAudioFocusRequest
        if (request != null) {
            Log.i("$TAG Releasing voice recording audio focus request")
            AudioUtils.releaseAudioFocusForVoiceRecordingOrPlayback(
                coreContext.context,
                request
            )
            voiceRecordAudioFocusRequest = null
        }

        isVoiceRecordingInProgress.postValue(false)
    }

    @WorkerThread
    private fun initVoiceRecordPlayer() {
        Log.i("$TAG Creating player for voice record")

        val playbackSoundCard = AudioUtils.getAudioPlaybackDeviceIdForCallRecordingOrVoiceMessage()
        Log.i(
            "$TAG Using device $playbackSoundCard to make the voice message playback"
        )

        val localPlayer = coreContext.core.createLocalPlayer(playbackSoundCard, null, null)
        if (localPlayer != null) {
            voiceRecordPlayer = localPlayer
        } else {
            Log.e("$TAG Couldn't create local player!")
            return
        }
        voiceRecordPlayer.addListener(playerListener)
        Log.i("$TAG Voice record player created")

        val path = voiceMessageRecorder.file
        if (path != null) {
            Log.i("$TAG Opening voice record file [$path]")
            voiceRecordPlayer.open(path)
            voiceRecordingDuration.postValue(voiceRecordPlayer.duration)
        }
    }

    @WorkerThread
    private fun startVoiceRecordPlayer() {
        if (isPlayerClosed()) {
            Log.w("$TAG Player closed, let's open it first")
            initVoiceRecordPlayer()
        }

        val context = coreContext.context
        val lowMediaVolume = AudioUtils.isMediaVolumeLow(context)
        if (lowMediaVolume) {
            Log.w("$TAG Media volume is low, notifying user as they may not hear voice message")
            showRedToast(R.string.media_playback_low_volume_warning_toast, R.drawable.speaker_slash)
        }

        if (voiceRecordAudioFocusRequest == null) {
            voiceRecordAudioFocusRequest = AudioUtils.acquireAudioFocusForVoiceRecordingOrPlayback(
                context
            )
        }

        Log.i("$TAG Playing voice record")
        voiceRecordPlayer.start()
        isPlayingVoiceRecord.postValue(true)

        playerTickerFlow().onEach {
            coreContext.postOnCoreThread {
                voiceRecordPlayerPosition.postValue(voiceRecordPlayer.currentPosition)
            }
        }.launchIn(viewModelScope)
    }

    @WorkerThread
    private fun pauseVoiceRecordPlayer() {
        if (!isPlayerClosed()) {
            Log.i("$TAG Pausing voice record")
            voiceRecordPlayer.pause()
        }

        val request = voiceRecordAudioFocusRequest
        if (request != null) {
            AudioUtils.releaseAudioFocusForVoiceRecordingOrPlayback(
                coreContext.context,
                request
            )
            voiceRecordAudioFocusRequest = null
        }

        isPlayingVoiceRecord.postValue(false)
    }

    @WorkerThread
    private fun stopVoiceRecordPlayer() {
        if (!isPlayerClosed()) {
            Log.i("$TAG Stopping voice record")
            voiceRecordPlayer.pause()
            voiceRecordPlayer.seek(0)
            voiceRecordPlayerPosition.postValue(0)
            voiceRecordPlayer.close()
        }

        voiceRecordPlayerPosition.postValue(0)
        isPlayingVoiceRecord.postValue(false)

        val request = voiceRecordAudioFocusRequest
        if (request != null) {
            AudioUtils.releaseAudioFocusForVoiceRecordingOrPlayback(
                coreContext.context,
                request
            )
            voiceRecordAudioFocusRequest = null
        }

        isPlayingVoiceRecord.postValue(false)
    }

    @WorkerThread
    private fun isPlayerClosed(): Boolean {
        return !::voiceRecordPlayer.isInitialized || voiceRecordPlayer.state == Player.State.Closed
    }

    private fun recorderTickerFlow() = flow {
        while (isVoiceRecordingInProgress.value == true) {
            emit(Unit)
            delay(500)
        }
    }

    private fun playerTickerFlow() = flow {
        while (isPlayingVoiceRecord.value == true) {
            emit(Unit)
            delay(10)
        }
    }
}
