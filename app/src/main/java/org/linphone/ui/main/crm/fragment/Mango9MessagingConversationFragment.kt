/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.ui.main.crm.fragment

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.UiThread
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import org.linphone.R
import org.linphone.databinding.Mango9MessagingConversationFragmentBinding
import org.linphone.mango9.Mango9ChatConnectionState
import org.linphone.mango9.Mango9ChatMedia
import org.linphone.mango9.Mango9ChatRoom
import org.linphone.mango9.Mango9ChatState
import org.linphone.mango9.Mango9PendingAttachment
import org.linphone.mango9.Mango9PendingShare
import org.linphone.mango9.Mango9SmsConversationInsights
import org.linphone.ui.main.crm.adapter.Mango9MessageAdapter
import org.linphone.ui.main.crm.adapter.Mango9MessageListItem
import org.linphone.ui.main.crm.viewmodel.Mango9MessagingViewModel
import org.linphone.ui.main.fragment.GenericMainFragment

@UiThread
class Mango9MessagingConversationFragment : GenericMainFragment() {
    private lateinit var binding: Mango9MessagingConversationFragmentBinding
    private lateinit var viewModel: Mango9MessagingViewModel
    private lateinit var adapter: Mango9MessageAdapter
    private val attachments = mutableListOf<Mango9PendingAttachment>()
    private val temporaryAttachmentFiles = mutableSetOf<File>()
    private var senderIds = emptyList<String>()
    private var recorder: MediaRecorder? = null
    private var voiceFile: File? = null
    private var pendingCameraFile: File? = null
    private var recording = false
    private var openedRoomId: String? = null
    private var insightsLoadingDialog: androidx.appcompat.app.AlertDialog? = null

    private val type: String by lazy {
        requireArguments().getString(Mango9MessagingListFragment.ARG_TYPE, Mango9MessagingListFragment.TYPE_TEAM)
    }
    private val userId: Int by lazy { requireArguments().getInt(Mango9MessagingListFragment.ARG_USER_ID) }
    private val requestedRoomId: String? by lazy { requireArguments().getString(Mango9MessagingListFragment.ARG_ROOM_ID) }
    private val targetName: String by lazy {
        requireArguments().getString(Mango9MessagingListFragment.ARG_TARGET).orEmpty()
    }
    private val phone: String? by lazy { requireArguments().getString(Mango9MessagingListFragment.ARG_PHONE) }
    private val isSms: Boolean get() = type == Mango9MessagingListFragment.TYPE_SMS

    private val mediaPicker = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = MAX_ATTACHMENTS),
    ) { uris -> addPickedAttachments(uris) }

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> addPickedAttachments(uris) }

    private val cameraCapture = registerForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        val file = pendingCameraFile
        pendingCameraFile = null
        if (captured && file != null) {
            viewModel.attachment(file.absolutePath)?.let { attachment ->
                attachments += attachment
                temporaryAttachmentFiles += file
                render(viewModel.currentState())
                renderPendingAttachments()
            } ?: run {
                file.delete()
                showLocalError(getString(R.string.mango9_chat_camera_failed))
            }
        } else {
            file?.delete()
        }
    }

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else showLocalError(getString(R.string.mango9_chat_camera_required))
    }

    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else showLocalError(getString(R.string.mango9_chat_microphone_required))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = Mango9MessagingConversationFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(this)[Mango9MessagingViewModel::class.java]
        adapter = Mango9MessageAdapter(::openMedia, ::showMessageOptions)
        binding.messages.layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        binding.messages.adapter = adapter
        binding.title.text = targetName
        binding.transportLabel.setText(if (isSms) R.string.mango9_sms_transport else R.string.mango9_chat_transport)
        binding.sender.visibility = if (isSms) View.VISIBLE else View.GONE
        binding.back.setOnClickListener { goBack() }
        binding.more.setOnClickListener { showConversationOptions() }
        binding.attach.setOnClickListener { showAttachmentMenu() }
        binding.microphone.setOnClickListener { toggleRecording() }
        binding.send.setOnClickListener { sendMessage() }
        binding.message.doAfterTextChanged { editable ->
            if (!isSms && !editable.isNullOrEmpty()) viewModel.notifyTyping()
            updateComposerState(viewModel.state.value ?: Mango9ChatState())
        }
        viewModel.state.observe(viewLifecycleOwner, ::render)
        viewModel.conversationInsightsEvent.observe(viewLifecycleOwner) { event ->
            event.consume(::showConversationInsights)
        }
        viewModel.sending.observe(viewLifecycleOwner) { updateComposerState(viewModel.state.value ?: Mango9ChatState()) }
        sharedViewModel.filesToShareFromIntent.observe(viewLifecycleOwner, ::consumeSharedFiles)
        sharedViewModel.textToShareFromIntent.observe(viewLifecycleOwner, ::consumeSharedText)

        if (isSms) {
            viewModel.openSmsConversation(phone.orEmpty())
        } else {
            viewModel.openTeamConversation(userId, requestedRoomId, targetName)
        }
    }

    override fun onDestroyView() {
        insightsLoadingDialog?.dismiss()
        insightsLoadingDialog = null
        cancelRecording()
        pendingCameraFile?.delete()
        pendingCameraFile = null
        deleteTemporaryAttachments()
        if (isSms) viewModel.closeSmsConversation(phone) else viewModel.closeTeamConversation(openedRoomId ?: requestedRoomId)
        super.onDestroyView()
    }

    private fun render(state: Mango9ChatState) {
        if (!isSms) openedRoomId = state.activeRoomId ?: openedRoomId
        val items = if (isSms) smsMessages(state) else teamMessages(state)
        val previousSize = adapter.itemCount
        adapter.submitList(items) {
            if (items.isNotEmpty() && items.size >= previousSize) binding.messages.scrollToPosition(items.lastIndex)
        }
        binding.empty.visibility = if (items.isEmpty() && state.connection == Mango9ChatConnectionState.Connected) {
            View.VISIBLE
        } else {
            View.GONE
        }
        binding.loading.visibility = if (
            state.connection == Mango9ChatConnectionState.Connecting && items.isEmpty()
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
        binding.error.text = state.errorMessage.orEmpty()
        binding.error.visibility = if (state.errorMessage.isNullOrBlank()) View.GONE else View.VISIBLE
        if (isSms) renderSmsSenders(state)
        renderHeader(state)
        updateComposerState(state)
    }

    private fun teamMessages(state: Mango9ChatState): List<Mango9MessageListItem> {
        val blocked = directUserId(state)?.let(viewModel.moderation::isBlocked) == true
        return state.messages.filter { message ->
            !viewModel.moderation.isMessageHidden(message.id) &&
                (message.fromUserId == state.currentUserId || !viewModel.moderation.isBlocked(message.fromUserId)) &&
                !(blocked && message.fromUserId != state.currentUserId)
        }.map { message ->
            Mango9MessageListItem.Team(
                message,
                message.fromUserId == state.currentUserId,
                if (currentRoom(state)?.isDirect == false) viewModel.userName(message.fromUserId) else null,
            )
        }
    }

    private fun smsMessages(state: Mango9ChatState): List<Mango9MessageListItem> = state.smsMessages
        .filterNot { viewModel.moderation.isSmsMessageHidden(phone.orEmpty(), it.id) }
        .map(Mango9MessageListItem::Sms)

    private fun renderHeader(state: Mango9ChatState) {
        val connected = state.connection == Mango9ChatConnectionState.Connected
        if (!connected) {
            binding.status.setText(
                if (state.connection == Mango9ChatConnectionState.Connecting) {
                    R.string.mango9_chat_connecting
                } else {
                    R.string.mango9_chat_disconnected
                },
            )
            binding.statusDot.setBackgroundResource(R.drawable.mango9_chat_offline_background)
            if (isSms) binding.title.text = targetName.ifBlank { phone.orEmpty() }
            return
        }
        if (isSms) {
            binding.title.text = targetName.ifBlank { phone.orEmpty() }
            binding.status.text = getString(
                if (viewModel.moderation.isSmsMuted(phone.orEmpty())) {
                    R.string.mango9_sms_muted_status
                } else {
                    R.string.mango9_chat_connected
                },
            )
            binding.statusDot.setBackgroundResource(R.drawable.mango9_chat_online_background)
            return
        }
        val room = currentRoom(state)
        if (room != null && !room.isDirect) binding.title.text = viewModel.roomTitle(room)
        val direct = directUserId(state)
        val blocked = direct?.let(viewModel.moderation::isBlocked) == true
        val typingNames = room?.userIds.orEmpty().filter(state.typingUserIds::contains).map(viewModel::userName)
        val online = direct?.let(state.onlineUserIds::contains) == true || room?.userIds?.any(state.onlineUserIds::contains) == true
        binding.status.text = when {
            blocked -> getString(R.string.mango9_chat_blocked)
            typingNames.isNotEmpty() -> getString(R.string.mango9_chat_typing, typingNames.joinToString(", "))
            room != null && !room.isDirect -> getString(
                R.string.mango9_chat_group_status,
                room.userIds.size + 1,
                room.userIds.count(state.onlineUserIds::contains),
            )
            online -> getString(R.string.mango9_chat_online)
            else -> getString(R.string.mango9_chat_offline)
        }
        binding.statusDot.setBackgroundResource(
            if (online && !blocked) R.drawable.mango9_chat_online_background else R.drawable.mango9_chat_offline_background,
        )
    }

    private fun renderSmsSenders(state: Mango9ChatState) {
        val updated = state.smsSenders.map { it.senderId }
        if (updated == senderIds) return
        val previous = selectedSender()
        senderIds = updated
        binding.sender.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            updated.map { getString(R.string.mango9_sms_sender, formattedPhone(it)) },
        )
        val selection = updated.indexOf(previous).takeIf { it >= 0 } ?: 0
        if (updated.isNotEmpty()) binding.sender.setSelection(selection)
    }

    private fun updateComposerState(state: Mango9ChatState) {
        val blocked = !isSms && directUserId(state)?.let(viewModel.moderation::isBlocked) == true
        val connected = state.connection == Mango9ChatConnectionState.Connected
        val hasContent = !binding.message.text.isNullOrBlank() || attachments.isNotEmpty()
        val senderAvailable = !isSms || senderIds.isNotEmpty()
        val busy = viewModel.sending.value == true || recording
        binding.send.isEnabled = connected && hasContent && senderAvailable && !blocked && !busy
        binding.message.isEnabled = !blocked && !recording
        binding.attach.isEnabled = !blocked && !busy
        binding.microphone.isEnabled = !blocked && viewModel.sending.value != true
        if (blocked) showLocalError(getString(R.string.mango9_chat_unblock_to_send))
        if (isSms && senderIds.isEmpty() && connected) showLocalError(getString(R.string.mango9_sms_no_sender))
    }

    private fun sendMessage() {
        val text = binding.message.text?.toString().orEmpty()
        val outgoing = attachments.toList()
        val complete: (Boolean) -> Unit = { sent ->
            if (sent) {
                deleteTemporaryAttachments()
                if (isAdded) {
                    binding.message.text?.clear()
                    attachments.clear()
                    renderPendingAttachments()
                }
            }
        }
        if (isSms) {
            val destination = viewModel.currentState().activeSmsPhone ?: phone.orEmpty()
            viewModel.sendSms(destination, selectedSender(), text, outgoing, complete)
        } else {
            viewModel.sendTeamMessage(text, outgoing, complete)
        }
    }

    private fun selectedSender(): String = senderIds.getOrNull(binding.sender.selectedItemPosition)
        ?: senderIds.firstOrNull().orEmpty()

    private fun showConversationOptions() {
        val state = viewModel.currentState()
        val room = currentRoom(state)
        val direct = directUserId(state)
        val options = mutableListOf<Option>()
        if (isSms) {
            val muted = viewModel.moderation.isSmsMuted(phone.orEmpty())
            options += Option(R.string.mango9_sms_conversation_info) {
                insightsLoadingDialog?.dismiss()
                insightsLoadingDialog = MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.mango9_sms_conversation_info)
                    .setMessage(R.string.mango9_sms_insights_loading)
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                viewModel.loadConversationInsights(phone.orEmpty())
            }
            options += Option(if (muted) R.string.mango9_sms_unmute else R.string.mango9_sms_mute) {
                viewModel.moderation.setSmsMuted(phone.orEmpty(), !muted)
                renderHeader(viewModel.currentState())
            }
            options += Option(R.string.mango9_chat_report_conversation) { showReportDialog(null, null) }
        } else {
            if (room != null && !room.isDirect) {
                options += Option(R.string.mango9_chat_manage_members) { showGroupMembers(room) }
            }
            options += Option(R.string.mango9_chat_report_conversation) { showReportDialog(null, direct) }
            if (state.messages.any { viewModel.moderation.isMessageHidden(it.id) }) {
                options += Option(R.string.mango9_chat_show_hidden) {
                    viewModel.moderation.restoreMessages(state.messages.map { it.id })
                    render(viewModel.currentState())
                }
            }
            if (direct != null) {
                val blocked = viewModel.moderation.isBlocked(direct)
                options += Option(if (blocked) R.string.mango9_chat_unblock_user else R.string.mango9_chat_block_user) {
                    viewModel.moderation.setBlocked(direct, !blocked)
                    render(viewModel.currentState())
                }
            }
            if (room != null) options += Option(R.string.mango9_chat_delete_conversation) { confirmDelete(room.id) }
        }
        MaterialAlertDialogBuilder(requireContext())
            .setItems(options.map { getString(it.title) }.toTypedArray()) { _, index -> options[index].action() }
            .show()
    }

    private fun showMessageOptions(item: Mango9MessageListItem) {
        val options = mutableListOf<Option>()
        when (item) {
            is Mango9MessageListItem.Team -> {
                if (!item.outgoing) {
                    options += Option(R.string.mango9_chat_hide_message) {
                        viewModel.moderation.setMessageHidden(item.message.id, true)
                        render(viewModel.currentState())
                    }
                    options += Option(R.string.mango9_chat_report_message) {
                        showReportDialog(item.message.id, item.message.fromUserId)
                    }
                }
            }
            is Mango9MessageListItem.Sms -> {
                options += Option(R.string.mango9_chat_hide_message) {
                    viewModel.moderation.setSmsMessageHidden(phone.orEmpty(), item.message.id, true)
                    render(viewModel.currentState())
                }
                options += Option(R.string.mango9_chat_report_message) { showReportDialog(item.message.id, null) }
            }
        }
        if (options.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext())
            .setItems(options.map { getString(it.title) }.toTypedArray()) { _, index -> options[index].action() }
            .show()
    }

    private fun showConversationInsights(insights: Mango9SmsConversationInsights) {
        if (!isAdded) return
        insightsLoadingDialog?.dismiss()
        insightsLoadingDialog = null
        val stats = insights.stats
        val crm = insights.crmMatch
        val calls = insights.localCalls
        val message = buildString {
            appendLine(getString(R.string.mango9_sms_activity_heading))
            appendLine(getString(R.string.mango9_sms_activity_messages, stats.total, stats.sent, stats.received))
            appendLine(getString(R.string.mango9_sms_activity_delivery, stats.attachments, stats.failed))
            stats.firstAvailableMessage?.let {
                appendLine(getString(R.string.mango9_sms_activity_first, formatInsightDate(it.toInstant().epochSecond)))
            }
            stats.latestMessage?.let {
                appendLine(getString(R.string.mango9_sms_activity_latest, formatInsightDate(it.toInstant().epochSecond)))
            }
            if (stats.senderIds.isNotEmpty()) {
                appendLine(getString(R.string.mango9_sms_activity_senders, stats.senderIds.joinToString { formattedPhone(it) }))
            }
            appendLine()
            appendLine(getString(R.string.mango9_sms_crm_heading))
            when {
                crm != null -> {
                    appendLine(getString(R.string.mango9_sms_crm_match, crm.kind.name, crm.name))
                    if (crm.ownerName.isNotBlank()) appendLine(getString(R.string.mango9_sms_crm_owner, crm.ownerName))
                    if (crm.status.isNotBlank()) appendLine(getString(R.string.mango9_sms_crm_status, crm.status))
                    if (crm.createdAt.isNotBlank()) appendLine(getString(R.string.mango9_sms_crm_created, crm.createdAt))
                }
                insights.crmLookupSucceeded -> appendLine(getString(R.string.mango9_sms_crm_no_match))
                else -> appendLine(getString(R.string.mango9_sms_crm_unavailable))
            }
            appendLine()
            appendLine(getString(R.string.mango9_sms_device_calls_heading))
            appendLine(getString(R.string.mango9_sms_device_calls_counts, calls.total, calls.inbound, calls.outbound))
            if (calls.missed > 0) appendLine(getString(R.string.mango9_sms_device_calls_missed, calls.missed))
            if (calls.connectedDurationSeconds > 0) {
                appendLine(getString(R.string.mango9_sms_device_calls_duration, formatDuration(calls.connectedDurationSeconds)))
            }
            calls.lastCallEpochSeconds?.let {
                appendLine(getString(R.string.mango9_sms_device_calls_latest, formatInsightDate(it)))
            }
        }.trim()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.mango9_sms_conversation_info)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun formatInsightDate(epochSeconds: Long): String = Instant.ofEpochSecond(epochSeconds)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))

    private fun formatDuration(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val remaining = seconds % 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${remaining}s"
            else -> "${remaining}s"
        }
    }

    private fun showGroupMembers(room: Mango9ChatRoom) {
        val users = viewModel.currentState().users
        val selected = room.userIds.toMutableSet()
        val checked = users.map { selected.contains(it.id) }.toBooleanArray()
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.mango9_chat_manage_members)
            .setMultiChoiceItems(users.map { it.name }.toTypedArray(), checked) { _, which, enabled ->
                if (enabled) selected.add(users[which].id) else selected.remove(users[which].id)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.mango9_chat_save_members, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (selected.isEmpty()) return@setOnClickListener
                viewModel.updateGroupMembers(room.id, selected) { saved -> if (saved) dialog.dismiss() }
            }
        }
        dialog.show()
    }

    private fun confirmDelete(roomId: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.mango9_chat_delete_title)
            .setMessage(R.string.mango9_chat_delete_warning)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.mango9_chat_delete_conversation) { _, _ ->
                viewModel.deleteConversation(roomId)
                goBack()
            }
            .show()
    }

    private fun showReportDialog(messageId: String?, reportedUserId: Int?) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (messageId == null) R.string.mango9_chat_report_title else R.string.mango9_chat_report_message_title)
            .setMessage(R.string.mango9_chat_report_explanation)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.mango9_chat_continue) { _, _ -> openReportEmail(messageId, reportedUserId) }
            .show()
    }

    private fun openReportEmail(messageId: String?, reportedUserId: Int?) {
        val roomId = viewModel.currentState().activeRoomId ?: requestedRoomId ?: "not available"
        val body = buildString {
            appendLine("Please describe the content or behavior you are reporting:")
            appendLine()
            appendLine("Conversation: $targetName")
            appendLine("Room ID: $roomId")
            if (messageId != null) appendLine("Message ID: $messageId")
            appendLine("Reported user ID: ${reportedUserId ?: "external SMS conversation"}")
            appendLine("App: Mango9 Android")
            appendLine()
            append("Do not include passwords or SIP credentials.")
        }
        val uri = "mailto:support@mango9.com".toUri().buildUpon()
            .appendQueryParameter(
                "subject",
                if (messageId == null) "Mango9 conversation safety report" else "Mango9 message safety report",
            )
            .appendQueryParameter("body", body)
            .build()
        runCatching { startActivity(Intent(Intent.ACTION_SENDTO, uri)) }
    }

    private fun openMedia(media: Mango9ChatMedia) {
        val uri = media.url.toUri()
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, uri).apply { setDataAndType(uri, media.mimeType) })
        }.onFailure { showLocalError(getString(R.string.mango9_chat_attachment_unavailable)) }
    }

    private fun showAttachmentMenu() {
        if (attachments.size >= MAX_ATTACHMENTS) {
            showLocalError(getString(R.string.mango9_chat_attachment_limit))
            return
        }
        val options = arrayOf(
            getString(R.string.mango9_chat_take_photo),
            getString(R.string.mango9_chat_photo_library),
            getString(R.string.mango9_chat_browse_files),
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.mango9_chat_add_attachment)
            .setItems(options) { _, index ->
                when (index) {
                    0 -> requestCamera()
                    1 -> openMediaPicker()
                    2 -> openFilePicker()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun requestCamera() {
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            launchCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCamera() {
        val file = File(requireContext().cacheDir, "mango9-camera-${System.currentTimeMillis()}.jpg")
        try {
            if (!file.createNewFile()) throw IllegalStateException("Camera file already exists")
            val uri = FileProvider.getUriForFile(
                requireContext(),
                getString(R.string.file_provider),
                file,
            )
            pendingCameraFile = file
            cameraCapture.launch(uri)
        } catch (_: Exception) {
            file.delete()
            pendingCameraFile = null
            showLocalError(getString(R.string.mango9_chat_camera_failed))
        }
    }

    private fun openMediaPicker() {
        try {
            mediaPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
            )
        } catch (_: ActivityNotFoundException) {
            showLocalError(getString(R.string.mango9_chat_attachment_unavailable))
        }
    }

    private fun openFilePicker() {
        try {
            filePicker.launch(arrayOf("*/*"))
        } catch (_: ActivityNotFoundException) {
            showLocalError(getString(R.string.mango9_chat_attachment_unavailable))
        }
    }

    private fun addPickedAttachments(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val remaining = (MAX_ATTACHMENTS - attachments.size).coerceAtLeast(0)
        val accepted = uris.take(remaining).mapNotNull(viewModel::attachment)
        attachments += accepted
        when {
            accepted.isEmpty() -> showLocalError(getString(R.string.mango9_chat_attachment_unavailable))
            uris.size > remaining -> showLocalError(getString(R.string.mango9_chat_attachment_limit))
            else -> render(viewModel.currentState())
        }
        renderPendingAttachments()
    }

    private fun toggleRecording() {
        if (recording) {
            finishRecording()
        } else if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    @Suppress("DEPRECATION")
    private fun startRecording() {
        if (attachments.size >= MAX_ATTACHMENTS) {
            showLocalError(getString(R.string.mango9_chat_attachment_limit))
            return
        }
        val output = File(requireContext().cacheDir, "mango9-voice-${System.currentTimeMillis()}.m4a")
        try {
            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44_100)
                setAudioChannels(1)
                setOutputFile(output.absolutePath)
                prepare()
                start()
            }
            voiceFile = output
            recording = true
            binding.recordingStatus.visibility = View.VISIBLE
            binding.microphone.setColorFilter(ContextCompat.getColor(requireContext(), R.color.red_danger_500))
            updateComposerState(viewModel.currentState())
        } catch (_: Exception) {
            recorder?.release()
            recorder = null
            output.delete()
            showLocalError(getString(R.string.mango9_chat_recording_failed))
        }
    }

    private fun finishRecording() {
        val output = voiceFile
        val finished = runCatching { recorder?.stop() }.isSuccess
        recorder?.release()
        recorder = null
        recording = false
        binding.recordingStatus.visibility = View.GONE
        binding.microphone.setColorFilter(ContextCompat.getColor(requireContext(), R.color.gray_main2_700))
        if (finished && output != null && output.length() > 0) {
            attachments += Mango9PendingAttachment(Uri.fromFile(output), output.name, "audio/mp4", output.length())
            temporaryAttachmentFiles += output
        } else {
            output?.delete()
        }
        voiceFile = null
        renderPendingAttachments()
        updateComposerState(viewModel.currentState())
    }

    private fun cancelRecording() {
        if (!recording && recorder == null) return
        runCatching { recorder?.stop() }
        recorder?.release()
        recorder = null
        recording = false
        voiceFile?.delete()
        voiceFile = null
    }

    private fun renderPendingAttachments() {
        binding.pendingScroll.visibility = if (attachments.isEmpty()) View.GONE else View.VISIBLE
        binding.pendingAttachments.removeAllViews()
        attachments.forEachIndexed { index, attachment ->
            binding.pendingAttachments.addView(
                TextView(requireContext()).apply {
                    text = attachment.name
                    contentDescription = getString(R.string.mango9_chat_remove_attachment, attachment.name)
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.gray_main2_700))
                    textSize = 11f
                    setBackgroundResource(R.drawable.mango9_chat_avatar_background)
                    setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.paperclip, 0, R.drawable.x, 0)
                    compoundDrawablePadding = dp(6)
                    setPadding(dp(9), dp(7), dp(9), dp(7))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { marginEnd = dp(7) }
                    setOnClickListener { removeAttachment(index) }
                },
            )
        }
        updateComposerState(viewModel.currentState())
    }

    private fun removeAttachment(index: Int) {
        val attachment = attachments.getOrNull(index) ?: return
        attachments.removeAt(index)
        attachment.uri.path?.let(::File)?.let { file ->
            if (temporaryAttachmentFiles.remove(file)) runCatching { file.delete() }
        }
        renderPendingAttachments()
    }

    private fun consumeSharedFiles(paths: ArrayList<String>) {
        if (paths.isEmpty() || sharedViewModel.pendingShareNeedsRecipientSelection) return
        val selection = Mango9PendingShare.selectFiles(paths, attachments.size, MAX_ATTACHMENTS)
        val imported = selection.acceptedPaths.mapNotNull { path ->
            viewModel.attachment(path)?.also { temporaryAttachmentFiles += File(path) }
        }
        attachments += imported
        sharedViewModel.filesToShareFromIntent.value = arrayListOf()
        when {
            imported.isEmpty() -> showLocalError(getString(R.string.mango9_chat_attachment_unavailable))
            selection.rejectedCount > 0 -> showLocalError(getString(R.string.mango9_chat_attachment_limit))
        }
        renderPendingAttachments()
    }

    private fun consumeSharedText(shared: String) {
        if (shared.isEmpty() || sharedViewModel.pendingShareNeedsRecipientSelection) return
        binding.message.setText(
            Mango9PendingShare.mergeText(binding.message.text?.toString().orEmpty(), shared),
        )
        binding.message.setSelection(binding.message.text?.length ?: 0)
        sharedViewModel.textToShareFromIntent.value = ""
    }

    private fun deleteTemporaryAttachments() {
        temporaryAttachmentFiles.forEach { file -> runCatching { file.delete() } }
        temporaryAttachmentFiles.clear()
    }

    private fun showLocalError(message: String) {
        binding.error.text = message
        binding.error.visibility = View.VISIBLE
    }

    private fun currentRoom(state: Mango9ChatState): Mango9ChatRoom? {
        val id = state.activeRoomId ?: openedRoomId ?: requestedRoomId
        return state.rooms.firstOrNull { it.id == id }
    }

    private fun directUserId(state: Mango9ChatState): Int? {
        if (userId > 0) return userId
        return currentRoom(state)?.takeIf(Mango9ChatRoom::isDirect)?.userIds?.firstOrNull()
    }

    private fun formattedPhone(raw: String): String {
        val digits = raw.filter(Char::isDigit).removePrefix("1")
        return if (digits.length == 10) "(${digits.take(3)}) ${digits.substring(3, 6)}-${digits.takeLast(4)}" else raw
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class Option(val title: Int, val action: () -> Unit)

    companion object {
        private const val MAX_ATTACHMENTS = 12
    }
}
