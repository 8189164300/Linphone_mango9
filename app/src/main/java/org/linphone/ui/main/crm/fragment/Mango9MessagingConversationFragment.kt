/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.ui.main.crm.fragment

import org.linphone.mango9.humanMemberIds

import org.linphone.mango9.Mango9AttachmentExports
import org.linphone.ui.main.crm.viewmodel.Mango9AttachmentExportViewModel
import org.linphone.utils.hideKeyboard
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
import android.widget.PopupMenu
import android.widget.EditText
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import org.linphone.mango9.Mango9MessageChannel
import org.linphone.mango9.Mango9MessageCommands
import org.linphone.mango9.Mango9MessagingApi
import org.linphone.mango9.Mango9ReactionCapabilities
import org.linphone.mango9.Mango9StaffReactions
import org.linphone.ui.main.crm.viewmodel.Mango9SocialViewModel
import kotlinx.coroutines.delay
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.UiThread
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import org.linphone.R
import org.linphone.mango9.Mango9TeamMessageLayout
import org.linphone.databinding.Mango9MessagingConversationFragmentBinding
import org.linphone.mango9.Mango9ChatConnectionState
import org.linphone.mango9.Mango9ChatMedia
import org.linphone.mango9.Mango9MediaCache
import org.linphone.mango9.Mango9MediaAsset
import org.linphone.ui.fileviewer.MediaViewerActivity
import org.linphone.mango9.Mango9ChatRoom
import org.linphone.mango9.Mango9ChatState
import org.linphone.mango9.Mango9PreparedAttachment
import org.linphone.mango9.Mango9ExternalShare
import org.linphone.mango9.Mango9SmsConversationInsights
import org.linphone.mango9.Mango9NavigationSession
import org.linphone.mango9.Mango9SessionStore
import org.linphone.ui.main.crm.adapter.Mango9MessageAdapter
import org.linphone.ui.main.crm.adapter.Mango9ThumbnailLoader
import org.linphone.ui.main.crm.adapter.Mango9MessageListItem
import org.linphone.ui.main.crm.viewmodel.Mango9MessagingViewModel
import org.linphone.ui.main.fragment.GenericMainFragment

@UiThread
class Mango9MessagingConversationFragment : GenericMainFragment() {
    private lateinit var binding: Mango9MessagingConversationFragmentBinding
    private lateinit var viewModel: Mango9MessagingViewModel
    private lateinit var social: Mango9SocialViewModel
    private var actionPopup: Mango9MessageActionPopup? = null
    private var emojiDialog: androidx.appcompat.app.AlertDialog? = null
    private var reactionJob: Job? = null
    private lateinit var adapter: Mango9MessageAdapter
    private val attachments get() = viewModel.composer.state.value.attachments
    private var senderIds = emptyList<String>()
    private var recorder: MediaRecorder? = null
    private var voiceFile: File? = null
    private var voiceSource: Mango9PreparedAttachment? = null
    private var renderingDraft = false
    private var recording = false
    private var recordingPreparation: Job? = null
    private var openedRoomId: String? = null
    private var mediaOpenJob: Job? = null
    private var insightsLoadingDialog: androidx.appcompat.app.AlertDialog? = null
    private var optionsDialog: Mango9MessageActionPopup? = null
    private var conversationTools: Mango9ConversationTools? = null
    private var thumbnails: Mango9ThumbnailLoader? = null
    private val documentViewer = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        ViewModelProvider(this)[Mango9MessagingViewModel::class.java].releaseOpenedDocument()
    }

    private val exports get() = ViewModelProvider(this)[Mango9AttachmentExportViewModel::class.java]
    private val exportDocument = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        exports.save(if (result.resultCode == android.app.Activity.RESULT_OK) result.data?.data else null)
    }
    private val exportFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> exports.save(uri, folder = true) }

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
    private val isSocial: Boolean get() = type == Mango9MessagingListFragment.TYPE_SOCIAL
    private val channel get() = if (isSocial) Mango9MessageChannel.social else if (isSms) Mango9MessageChannel.sms else Mango9MessageChannel.team

    private val mediaPicker = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = MAX_ATTACHMENTS),
    ) { uris -> if (viewModel.composer.finishRequest("media")) addPickedAttachments(uris) }

    private val socialMediaPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (viewModel.composer.finishRequest("socialMedia") && uri != null) addPickedAttachments(listOf(uri))
    }

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (viewModel.composer.finishRequest("files")) addPickedAttachments(uris) }

    private val socialFilePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (viewModel.composer.finishRequest("socialFile") && uri != null) addPickedAttachments(listOf(uri))
    }

    private val cameraCapture = registerForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        val requested = viewModel.composer.finishRequest("capture")
        viewModel.composer.finishCapture(captured && requested)
    }

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (viewModel.composer.finishRequest("cameraPermission") && view != null) {
            if (granted) launchCamera() else showLocalError(getString(R.string.mango9_chat_camera_required))
        }
    }

    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (viewModel.composer.finishRequest("microphonePermission") && view != null) {
            if (granted) startRecording() else showLocalError(getString(R.string.mango9_chat_microphone_required))
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = Mango9MessagingConversationFragmentBinding.inflate(inflater, container, false)
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val density = resources.displayMetrics.density
        if (landscape) {
            listOf(binding.back, binding.title).forEach { it.layoutParams = it.layoutParams.apply { height = (48 * density).toInt() } }
            binding.statusBar.layoutParams = binding.statusBar.layoutParams.apply { height = (24 * density).toInt() }
            binding.sender.layoutParams = binding.sender.layoutParams.apply { height = (36 * density).toInt() }
        }
        binding.message.maxLines = if (landscape) 2 else 5
        binding.message.imeOptions = binding.message.imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        binding.root.isFocusableInTouchMode = true
        binding.root.requestFocus()
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(this)[Mango9MessagingViewModel::class.java]
        viewModel.bindConversation(Mango9NavigationSession.resolve(requireArguments(), Mango9SessionStore(requireContext())))
        viewModel.composer.setAttachmentLimit(if (isSocial) 1 else if (isSms) 10 else MAX_ATTACHMENTS)
        social = ViewModelProvider(this)[Mango9SocialViewModel::class.java]
        social.bind(viewModel.mediaSession())
        exports.notices.observe(viewLifecycleOwner) { event ->
            event.consume { android.widget.Toast.makeText(requireContext(), it, android.widget.Toast.LENGTH_LONG).show() }
        }
        exports.open.observe(viewLifecycleOwner) { event ->
            event.consume { request ->
                if (Mango9SessionStore(requireContext()).currentSession(request.lease) != null && viewModel.isCurrentConversation()) {
                    runCatching { startActivity(Intent.createChooser(request.intent, "Open with")) }
                        .onFailure { showLocalError("No installed app can open this file.") }
                }
            }
        }
        thumbnails = Mango9ThumbnailLoader.create(requireContext(), viewLifecycleOwner.lifecycleScope, viewModel.mediaSession())
        adapter = Mango9MessageAdapter(::openMedia, ::showMessageOptions)
        adapter.onSelection = { selected ->
            binding.title.text = if (adapter.selectionMode) "${selected.size} selected" else targetName
            binding.composerPanel.visibility = if (adapter.selectionMode) View.GONE else View.VISIBLE
            renderGroupActions(viewModel.currentState())
            if (adapter.selectionMode) binding.more.contentDescription = getString(R.string.mango9_select)
        }
        adapter.setThumbnailLoader(requireNotNull(thumbnails))
        adapter.setAudioController(viewModel.audioController())
        binding.messages.layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        binding.messages.adapter = adapter
        binding.messages.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                val layout = recyclerView.layoutManager as? LinearLayoutManager ?: return
                if (isSocial && dy < 0 && layout.findFirstVisibleItemPosition() <= 3) {
                    requestedRoomId?.let { social.open(it, more = true) }
                }
            }
        })
        val selectionBack = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() { adapter.select(null) }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, selectionBack)
        val priorSelection = adapter.onSelection
        adapter.onSelection = { selected -> priorSelection?.invoke(selected); selectionBack.isEnabled = adapter.selectionMode }
        val searchBack = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() { conversationTools?.closeSearch() }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, searchBack)
        conversationTools = Mango9ConversationTools(
            binding, adapter, viewLifecycleOwner.lifecycleScope,
            { this.view === view && viewModel.isCurrentConversation() }, ::openMedia,
            { searchBack.isEnabled = it }, thumbnails,
        )
        binding.title.text = targetName
        binding.transportLabel.setText(if (isSocial) R.string.mango9_social else if (isSms) R.string.mango9_sms_transport else R.string.mango9_chat_transport)
        binding.sender.visibility = if (isSms) View.VISIBLE else View.GONE
        binding.back.setOnClickListener { if (adapter.selectionMode) adapter.select(null) else goBack() }
        binding.more.setOnClickListener { showConversationOptions() }
        binding.renameGroup.setOnClickListener {
            if (viewModel.isCurrentConversation() && !adapter.selectionMode) currentRoom(viewModel.currentState())?.takeUnless { it.isDirect }?.let { renameGroup(it.id) }
        }
        binding.groupMembers.setOnClickListener {
            if (viewModel.isCurrentConversation() && !adapter.selectionMode) currentRoom(viewModel.currentState())?.takeUnless { it.isDirect }?.let(::showGroupMembers)
        }
        binding.attach.contentDescription = "Add to message"
        binding.attach.setOnClickListener { showAttachmentMenu() }
        Mango9TemplatesFragment.listen(this, { viewModel.mediaSession() }, { viewModel.isCurrentConversation() }, { binding.message })
        binding.loadEarlier.setOnClickListener { requestedRoomId?.let { social.open(it, more = true) } }
        binding.microphone.setOnClickListener { toggleRecording() }
        binding.send.setOnClickListener { sendMessage() }
        binding.message.isSaveEnabled = false
        if (isSocial && viewModel.composer.state.value.text.isEmpty()) {
            requestedRoomId?.let {
            viewModel.composer.changeText(social.restoreDraft(it))
        }
        }
        binding.message.setText(viewModel.composer.state.value.text)
        binding.message.doAfterTextChanged { editable ->
            if (!renderingDraft) viewModel.composer.changeText(editable?.toString().orEmpty())
            if (!isSms && !isSocial && !editable.isNullOrEmpty()) viewModel.notifyTyping()
            updateComposerState(viewModel.state.value ?: Mango9ChatState())
        }
        viewModel.state.observe(viewLifecycleOwner, ::render)
        social.state.observe(viewLifecycleOwner) { if (isSocial) render(viewModel.currentState()) }
        social.confirmedSend.observe(viewLifecycleOwner) { event ->
            event.consume { sent ->
            if (viewModel.isCurrentConversation()) viewModel.composer.confirmSavedSend(sent)
        }
        }
        viewModel.openingConversation.observe(viewLifecycleOwner) { render(viewModel.currentState()) }
        binding.retryHistory.setOnClickListener {
            if (isSocial) {
                requestedRoomId?.let { social.open(it, refreshProvider = true) }
            } else if (isSms) {
                viewModel.openSmsConversation(phone.orEmpty())
            } else {
                viewModel.openTeamConversation(userId, requestedRoomId, targetName)
            }
        }
        viewModel.conversationInsightsEvent.observe(viewLifecycleOwner) { event ->
            event.consume(::showConversationInsights)
        }
        var observedSends = viewModel.composer.state.value.completedSends
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.composer.state.collect { draft ->
                    if (draft.completedSends != observedSends) {
                        observedSends = draft.completedSends
                        if (draft.text.isBlank() && draft.attachments.isEmpty()) {
                            binding.message.hideKeyboard()
                            binding.message.clearFocus()
                        }
                    }
                    if (binding.message.text?.toString() != draft.text) {
                        renderingDraft = true
                        binding.message.setText(draft.text)
                        binding.message.setSelection(draft.text.length)
                        renderingDraft = false
                    }
                    renderPendingAttachments()
                    draft.error?.let(::showLocalError)
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                sharedViewModel.externalShare.state.collect {
                    if (viewModel.isCurrentConversation()) {
                        sharedViewModel.externalShare.claim(
                            arguments?.getString(Mango9ExternalShare.ARG_DELIVERY),
                            Mango9ExternalShare.crmDestination(type, userId, requestedRoomId, phone),
                        )?.let(viewModel.composer::adopt)
                    }
                }
            }
        }

        if (isSocial) {
            requestedRoomId?.let { social.open(it) }
            viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) { requestedRoomId?.let { social.open(it) }; delay(15_000) }
            }
            }
        } else if (isSms) {
            viewModel.openSmsConversation(phone.orEmpty())
        } else {
            viewModel.openTeamConversation(userId, requestedRoomId, targetName)
        }
        if (!isSocial) {
            viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) { viewModel.refreshConversationHistory(); delay(30_000) }
            }
        }
        }
    }

    override fun onStart() {
        super.onStart()
        thumbnails?.setForeground(true)
        if (::viewModel.isInitialized) viewModel.audioController().setForeground(true)
    }

    override fun onStop() {
        actionPopup?.dismiss()
        emojiDialog?.dismiss(); emojiDialog = null
        thumbnails?.setForeground(false)
        if (::viewModel.isInitialized) viewModel.audioController().setForeground(false)
        super.onStop()
    }

    override fun onDestroyView() {
        if (isSocial && ::social.isInitialized) social.retainDraft(viewModel.composer.state.value.text)
        thumbnails?.dispose()
        thumbnails = null
        viewModel.audioController().setForeground(false)
        conversationTools?.dispose()
        conversationTools = null
        optionsDialog?.dismiss()
        actionPopup?.dismiss(); actionPopup = null
        emojiDialog?.dismiss(); emojiDialog = null
        optionsDialog = null
        binding.messages.adapter = null
        mediaOpenJob?.cancel()
        mediaOpenJob = null
        insightsLoadingDialog?.dismiss()
        insightsLoadingDialog = null
        cancelRecording()
        // The navigation ViewModel owns drafts, external picker results and in-flight sends.
        super.onDestroyView()
    }

    private fun render(state: Mango9ChatState) {
        if (!viewModel.isCurrentConversation()) {
            cancelRecording()
            conversationTools?.dispose()
            optionsDialog?.dismiss()
            actionPopup?.dismiss(); actionPopup = null
            emojiDialog?.dismiss(); emojiDialog = null
            insightsLoadingDialog?.dismiss()
            mediaOpenJob?.cancel()
        }
        if (!isSms && !isSocial) openedRoomId = state.activeRoomId ?: openedRoomId
        val socialState = social.current()
        val items = if (isSocial) {
            socialState.messages.map { message ->
            Mango9MessageListItem.Social(message, message.attachments.mapNotNull { socialState.media["${message.id}:${it.index}"] })
        }
        } else if (isSms) {
            smsMessages(state)
        } else {
            teamMessages(state)
        }
        val previousSize = adapter.itemCount
        val layout = binding.messages.layoutManager as LinearLayoutManager
        val wasAtBottom = previousSize == 0 || layout.findLastVisibleItemPosition() >= previousSize - 2
        val owner = binding.root
        val ownerAdapter = adapter
        adapter.submitList(items) {
            if (view !== owner || adapter !== ownerAdapter || !viewModel.isCurrentConversation()) return@submitList
            conversationTools?.submit(items)
            if (conversationTools?.searchOpen != true && wasAtBottom && items.size > previousSize) {
                binding.messages.scrollToPosition(items.lastIndex)
            }
        }
        val opening = if (isSocial) socialState.loading else viewModel.openingConversation.value == true
        binding.empty.visibility = if (items.isEmpty() && !opening && state.errorMessage.isNullOrBlank() && state.connection == Mango9ChatConnectionState.Connected) {
            View.VISIBLE
        } else {
            View.GONE
        }
        binding.loading.visibility = if (
            (opening || state.connection == Mango9ChatConnectionState.Connecting) && items.isEmpty()
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
        val error = viewModel.composer.state.value.error ?: if (isSocial) socialState.error ?: socialState.sendNotice else state.errorMessage
        binding.error.text = error.orEmpty()
        binding.error.visibility = if (error.isNullOrBlank()) View.GONE else View.VISIBLE
        binding.retryHistory.visibility = if (viewModel.isCurrentConversation() && !opening && !state.errorMessage.isNullOrBlank()) View.VISIBLE else View.GONE
        binding.more.isEnabled = viewModel.isCurrentConversation()
        binding.loadEarlier.visibility = if (isSocial && socialState.nextMessage != null) View.VISIBLE else View.GONE
        binding.loadEarlier.isEnabled = viewModel.isCurrentConversation() && !socialState.loading
        if (isSms) renderSmsSenders(state)
        if (isSocial) {
            if (!adapter.selectionMode) binding.title.text = socialState.thread?.title ?: targetName
            binding.status.text = when {
                socialState.pending -> "Delivery unconfirmed — refresh to check"
                socialState.thread == null && socialState.loading -> "Loading conversation…"
                socialState.thread == null -> "Conversation unavailable · tap Retry"
                socialState.thread?.deleted == true -> "Conversation no longer available"
                socialState.thread?.connected != true -> "Social account disconnected · reconnect in CRM"
                socialState.thread?.canReply != true -> "Replies are not enabled for this conversation"
                socialState.thread?.canSend != true -> getString(R.string.mango9_social_draft_only)
                socialState.muted == true -> "Notifications muted"
                else -> socialState.thread?.accountName
            }
            binding.statusDot.visibility = View.GONE
            binding.empty.visibility = if (items.isEmpty() && !opening && error == null) View.VISIBLE else View.GONE
            binding.loading.visibility = if (opening && items.isEmpty()) View.VISIBLE else View.GONE
            binding.retryHistory.visibility = if (error != null || socialState.pending) View.VISIBLE else View.GONE
        } else {
            renderHeader(state)
        }
        updateComposerState(state)
    }

    private fun teamMessages(state: Mango9ChatState): List<Mango9MessageListItem> {
        val expectedRoom = requestedRoomId ?: state.rooms.firstOrNull {
            it.isDirect && it.userIds.contains(userId)
        }?.id
        val blocked = directUserId(state)?.let(viewModel.moderation::isBlocked) == true
        val visible = state.messages.filter { message ->
            message.roomId == expectedRoom && !viewModel.moderation.isMessageHidden(message.id) &&
                (message.fromUserId == state.currentUserId || !viewModel.moderation.isBlocked(message.fromUserId)) &&
                !(blocked && message.fromUserId != state.currentUserId)
        }
        return visible.mapIndexed { index, message ->
            val previous = visible.getOrNull(index - 1)
            val startsSender = Mango9TeamMessageLayout.startsSender(message, previous)
            Mango9MessageListItem.Team(
                message,
                message.fromUserId == state.currentUserId,
                if (currentRoom(state)?.isDirect == false && startsSender && message.fromUserId != state.currentUserId) viewModel.userName(message.fromUserId) else null,
                if (!Mango9TeamMessageLayout.sameDay(message.time, previous?.time)) Mango9TeamMessageLayout.dayLabel(message.time) else null,
                startsSender,
            )
        }
    }

    private fun smsMessages(state: Mango9ChatState): List<Mango9MessageListItem> {
        if (state.activeSmsPhone != org.linphone.mango9.Mango9PhoneNumber.normalized(phone.orEmpty())) return emptyList()
        return state.smsMessages.filterNot { viewModel.moderation.isSmsMessageHidden(phone.orEmpty(), it.id) }
            .map(Mango9MessageListItem::Sms)
    }

    private fun renderGroupActions(state: Mango9ChatState) {
        val group = !isSms && !isSocial && currentRoom(state)?.isDirect == false
        val showActions = group && !adapter.selectionMode
        binding.renameGroup.visibility = if (showActions) View.VISIBLE else View.GONE
        binding.groupMembers.visibility = if (showActions) View.VISIBLE else View.GONE
        binding.renameGroup.isEnabled = viewModel.isCurrentConversation()
        binding.groupMembers.isEnabled = viewModel.isCurrentConversation()
        binding.more.setImageResource(if (group) R.drawable.info else R.drawable.dots_three_vertical)
        binding.more.contentDescription = getString(R.string.mango9_chat_options)
        binding.title.textSize = if (group) 20f else 17f
        binding.status.textSize = if (group) 13f else 11f
        val ink = if (group) androidx.core.content.ContextCompat.getColor(requireContext(), R.color.mango9_team_ink)
            else com.google.android.material.color.MaterialColors.getColor(binding.title, R.attr.color_main1_500)
        binding.title.setTextColor(ink)
        binding.back.imageTintList = android.content.res.ColorStateList.valueOf(ink)
        binding.groupActions.background = if (group) android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 24 * resources.displayMetrics.density
            setColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.mango9_team_card))
        } else null
    }

    private fun renderHeader(state: Mango9ChatState) {
        renderGroupActions(state)
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
        val memberIds = room?.humanMemberIds(state.users).orEmpty()
        val typingNames = memberIds.filter(state.typingUserIds::contains).map(viewModel::userName)
        val online = direct?.let(state.onlineUserIds::contains) == true || memberIds.any(state.onlineUserIds::contains)
        binding.status.text = when {
            blocked -> getString(R.string.mango9_chat_blocked)
            typingNames.isNotEmpty() -> getString(R.string.mango9_chat_typing, typingNames.joinToString(", "))
            room != null && !room.isDirect -> getString(
                R.string.mango9_chat_group_status,
                memberIds.size + 1,
                memberIds.count(state.onlineUserIds::contains),
            )
            online -> getString(R.string.mango9_chat_online)
            else -> getString(R.string.mango9_chat_offline)
        }
        if (direct != null && !blocked && typingNames.isEmpty()) {
            state.users.firstOrNull { it.id == direct }?.let {
                binding.status.text = "${it.roleLabel} · ${binding.status.text}"
            }
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
        val blocked = !isSms && !isSocial && directUserId(state)?.let(viewModel.moderation::isBlocked) == true
        val connected = if (isSocial) social.current().thread?.canSend == true else state.connection == Mango9ChatConnectionState.Connected
        val hasContent = !binding.message.text.isNullOrBlank() || attachments.isNotEmpty()
        val senderAvailable = !isSms || senderIds.isNotEmpty()
        val current = viewModel.isCurrentConversation()
        val draft = viewModel.composer.state.value
        val busy = draft.sending || draft.preparing > 0 || recording || recordingPreparation?.isActive == true ||
            (if (isSocial) (social.current().thread == null && social.current().loading) || social.current().pending else viewModel.openingConversation.value == true) ||
            (if (isSocial) !current else if (isSms) !viewModel.ownsSmsConversation() else !viewModel.ownsTeamConversation())
        binding.send.isEnabled = connected && hasContent && senderAvailable && !blocked && !busy
        binding.message.isEnabled = current && !blocked && !recording
        binding.attach.isEnabled = current && !blocked && !busy
        binding.microphone.isEnabled = current && !blocked && !draft.sending && draft.preparing == 0 &&
            recordingPreparation?.isActive != true
        binding.microphone.visibility = if (isSocial) View.GONE else View.VISIBLE
        if (isSocial) {
            val canDraft = social.current().thread?.canDraft == true
            binding.message.isEnabled = current && canDraft && !draft.sending
            binding.attach.isEnabled = current && !draft.sending && draft.preparing == 0
        }
        binding.attach.alpha = if (binding.attach.isEnabled) 1f else 0.35f
        binding.send.alpha = if (binding.send.isEnabled) 1f else 0.35f
        if (draft.preparing > 0 || recordingPreparation?.isActive == true) {
            binding.recordingStatus.text = getString(R.string.mango9_chat_preparing_attachments)
            binding.recordingStatus.visibility = View.VISIBLE
        } else if (!recording) binding.recordingStatus.visibility = View.GONE
        if (blocked) showLocalError(getString(R.string.mango9_chat_unblock_to_send))
        if (isSms && senderIds.isEmpty() && connected) showLocalError(getString(R.string.mango9_sms_no_sender))
    }

    private fun sendMessage() {
        viewModel.composer.changeText(binding.message.text?.toString().orEmpty())
        if (isSocial) {
            viewModel.composer.send { text, files -> social.send(text, files) }
        } else if (isSms) {
            val destination = viewModel.currentState().activeSmsPhone ?: phone.orEmpty()
            viewModel.sendSmsDraft(destination, selectedSender())
        } else {
            viewModel.sendTeamDraft()
        }
    }

    private fun selectedSender(): String = senderIds.getOrNull(binding.sender.selectedItemPosition)
        ?: senderIds.firstOrNull().orEmpty()

    private fun showConversationOptions() {
        if (adapter.selectionMode) { showSelectionActions(); return }
        if (isSocial) {
            val owner = view ?: return
            val popup = Mango9MessageActionPopup(binding.more)
            optionsDialog?.dismiss(); optionsDialog = popup

            fun entry(label: Int, icon: Int, destructive: Boolean = false, action: () -> Unit) {
                popup.action(getString(label), icon, destructive) {
                    popup.dismiss()
                    if (view === owner && viewModel.isCurrentConversation()) action()
                }
            }
            entry(R.string.conversation_menu_search_in_messages, R.drawable.magnifying_glass) { conversationTools?.openSearch() }
            entry(R.string.conversation_media_list_title, R.drawable.image) { conversationTools?.openAttachments(false) }
            entry(R.string.conversation_document_list_title, R.drawable.file_text) { conversationTools?.openAttachments(true) }
            entry(R.string.mango9_select, R.drawable.check_square_offset) { adapter.select(emptySet()) }
            popup.separator()
            if (social.current().muted != null) {
                entry(if (social.current().muted == true) R.string.mango9_sms_unmute else R.string.mango9_sms_mute, R.drawable.bell) {
                social.mute()
            }
            }
            popup.separator()
            entry(R.string.mango9_delete_for_me, R.drawable.trash, true) { requestedRoomId?.let(::confirmDelete) }
            popup.position()
            return
        }
        val owner = view ?: return
        if (!viewModel.isCurrentConversation()) return
        val state = viewModel.currentState()
        val room = currentRoom(state)
        val direct = directUserId(state)
        val options = mutableListOf<Option>()
        options += Option(R.string.mango9_select) { adapter.select(emptySet()) }
        options += Option(R.string.conversation_menu_search_in_messages) { conversationTools?.openSearch() }
        options += Option(R.string.conversation_media_list_title) { conversationTools?.openAttachments(documents = false) }
        options += Option(R.string.conversation_document_list_title) { conversationTools?.openAttachments(documents = true) }
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
            options += Option(R.string.mango9_delete_for_me) { phone?.let(::confirmDelete) }
        } else {
            if (room != null) {
                val lease = viewModel.mediaSession()
                val notifications = org.linphone.mango9.Mango9ConversationNotifications(requireContext())
                val muted = lease?.let { notifications.cached(it, Mango9MessageChannel.team, room.id) } == true
                options += Option(if (muted) R.string.mango9_sms_unmute else R.string.mango9_sms_mute) {
                    if (lease != null) {
                        viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            val actual = notifications.request(lease, Mango9MessageChannel.team, room.id)
                            notifications.request(lease, Mango9MessageChannel.team, room.id, !actual)
                            if (viewModel.isCurrentConversation()) renderHeader(viewModel.currentState())
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e; if (viewModel.isCurrentConversation()) showLocalError(e.message.orEmpty())
                        }
                    }
                    }
                }
            }
            if (room != null && !room.isDirect) {
                options += Option(R.string.mango9_chat_manage_members) { showGroupMembers(room) }
                options += Option(R.string.mango9_rename_group) { renameGroup(room.id) }
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
        optionsDialog?.dismiss()
        val popup = Mango9MessageActionPopup(binding.more)
        optionsDialog = popup
        options.forEach { option ->
            val destructive = option.title in setOf(R.string.mango9_delete_for_me, R.string.mango9_chat_delete_conversation)
            val icon = when (option.title) {
                R.string.conversation_menu_search_in_messages -> R.drawable.magnifying_glass
                R.string.conversation_media_list_title -> R.drawable.image
                R.string.conversation_document_list_title -> R.drawable.file_text
                R.string.mango9_select -> R.drawable.check_square_offset
                R.string.mango9_chat_manage_members -> R.drawable.users
                R.string.mango9_rename_group -> R.drawable.pencil_simple
                R.string.mango9_sms_mute -> R.drawable.bell_slash
                R.string.mango9_sms_unmute -> R.drawable.bell
                R.string.mango9_chat_show_hidden -> R.drawable.eye
                R.string.mango9_chat_report_conversation, R.string.mango9_chat_block_user, R.string.mango9_chat_unblock_user -> R.drawable.shield_warning
                R.string.mango9_delete_for_me, R.string.mango9_chat_delete_conversation -> R.drawable.trash
                else -> R.drawable.info
            }
            if (destructive || option.title == R.string.mango9_chat_report_conversation || option.title == R.string.mango9_chat_manage_members) popup.separator()
            popup.action(getString(option.title), icon, destructive) {
                popup.dismiss()
                if (view === owner && viewModel.isCurrentConversation()) option.action()
            }
        }
        popup.position()
    }

    private fun showMessageOptions(item: Mango9MessageListItem) {
        val lease = viewModel.mediaSession() ?: return
        val anchor = adapter.heldAnchor?.takeIf { it.isAttachedToWindow } ?: return
        val owner = view
        val targetConversation = conversationId()

        fun current() = view === owner && viewModel.isCurrentConversation() && conversationId() == targetConversation &&
            adapter.currentList.any { it.stableId == item.stableId }
        val popup = Mango9MessageActionPopup(anchor)
        actionPopup?.dismiss(); actionPopup = popup
        anchor.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)

        fun action(label: String, run: () -> Unit) {
            popup.action(label) { popup.dismiss(); if (current()) run() }
        }
        val strip = Mango9ReactionStrip(requireContext()).apply { visibility = View.GONE }
        popup.panel.addView(strip)
        val notice = TextView(requireContext()).apply {
            setText(R.string.mango9_reaction_loading); textSize = 12f
            val padding = (8 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        popup.panel.addView(notice)
        if (isSms) {
            popup.panel.addView(
                TextView(requireContext()).apply {
            setText(R.string.mango9_reactions_local); textSize = 12f
        }
            )
        }
        if (item.text.isNotBlank() && (item !is Mango9MessageListItem.Social || (!item.message.deleted && social.current().thread?.canSend == true))) {
            action(getString(R.string.mango9_reply)) {
            viewModel.composer.changeText("> ${item.text.take(500).replace("\n", "\n> ")}\n\n" + viewModel.composer.state.value.text)
            binding.message.requestFocus()
        }
        }
        if (item.text.isNotBlank() && (item !is Mango9MessageListItem.Social || !item.message.deleted)) {
            action(getString(android.R.string.copy)) {
            (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Message", item.text))
        }
        }
        action(getString(R.string.mango9_select)) { adapter.select(setOf(item.stableId)) }
        if (messageMedia(listOf(item)).isNotEmpty()) action("Export attachment…") { showExportOptions(listOf(item)) }
        val commands = Mango9MessageCommands(requireContext())
        if (reactionJob?.isActive == true) {
            notice.setText(R.string.mango9_reaction_busy)
            popup.position(); return
        }
        if (commands.hasPending(lease, channel, targetConversation, messageId(item))) {
            notice.setText(R.string.mango9_reaction_pending)
            action(getString(R.string.mango9_reaction_check)) { react(item, "", false, 0, checkSaved = true) }
            popup.position(); return
        }
        popup.position()
        val loading = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val snapshot = if (isSocial) null else Mango9MessagingApi(requireContext()).request(lease, channel, listOf("reactions", channel.name, messageId(item)))
                if (snapshot != null) require(snapshot.optString("channel") == channel.name && snapshot.optString("messageId") == messageId(item))
                if (!current() || !popup.isShowing) return@launch
                val capabilities = if (item is Mango9MessageListItem.Social) item.message.capabilities else Mango9ReactionCapabilities.parse(snapshot?.optJSONObject("capabilities"))
                val staff = snapshot?.let(Mango9StaffReactions::parse)
                val version = if (isSocial) social.current().thread?.version ?: 0 else staff?.version ?: 0

                fun chosen(emoji: String) {
                    if (!current() || reactionJob?.isActive == true) return
                    val removing = if (item is Mango9MessageListItem.Social) {
                        item.message.reactions.self == emoji
                    } else {
                        staff?.values?.get(emoji)?.contains(viewModel.currentState().currentUserId) == true
                    }
                    val allowed = if (item is Mango9MessageListItem.Social) {
                        social.current().thread?.canReact(item.message, removing) == true
                    } else {
                        capabilities?.permits(channel, removing) == true
                    }
                    if (allowed) { popup.dismiss(); react(item, emoji, removing, version) } else {
                        showLocalError(getString(R.string.mango9_reaction_unavailable))
                    }
                }
                val mayAdd = if (item is Mango9MessageListItem.Social) {
                    social.current().thread?.canReact(item.message, false) == true
                } else {
                    capabilities?.permits(channel) == true
                }
                val mayRemove = if (item is Mango9MessageListItem.Social) {
                    social.current().thread?.canReact(item.message, true) == true
                } else {
                    capabilities?.permits(channel, true) == true
                }
                val activeEmoji = if (item is Mango9MessageListItem.Social) {
                    listOfNotNull(item.message.reactions.self)
                } else {
                    staff?.values?.filterValues { viewModel.currentState().currentUserId in it }?.keys?.toList().orEmpty()
                }
                val existingEmoji = if (item is Mango9MessageListItem.Social) {
                    listOfNotNull(item.message.reactions.self, item.message.reactions.participant)
                } else {
                    staff?.values?.keys?.toList().orEmpty()
                }
                val choices = org.linphone.mango9.Mango9ReactionChoices.visible(mayAdd, mayRemove, activeEmoji, existingEmoji)
                notice.visibility = if (choices.isEmpty() || isSocial) View.VISIBLE else View.GONE
                notice.setText(if (choices.isEmpty()) R.string.mango9_reaction_unavailable else R.string.mango9_reaction_shared)
                strip.visibility = if (choices.isEmpty()) View.GONE else View.VISIBLE
                strip.bind(choices, activeEmoji.toSet(), mayRemove, mayAdd, ::chosen) {
                    if (!current()) return@bind
                    popup.dismiss()
                    emojiDialog?.dismiss()
                    emojiDialog = Mango9EmojiInput.dialog(requireContext(), ::current, ::chosen).also { it.show() }
                }
                popup.position()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (current() && popup.isShowing) {
                    notice.setText(R.string.mango9_reaction_failed_load)
                    popup.position()
                }
            }
        }
        popup.onDismiss { loading.cancel() }
    }

    private fun messageId(item: Mango9MessageListItem): String = when (item) {
        is Mango9MessageListItem.Team -> item.message.id
        is Mango9MessageListItem.Sms -> item.message.id
        is Mango9MessageListItem.Social -> item.message.id.toString()
    }

    private fun conversationId() = if (isSms) phone.orEmpty() else if (isSocial) requestedRoomId.orEmpty() else openedRoomId ?: requestedRoomId.orEmpty()

    private fun react(item: Mango9MessageListItem, emoji: String, remove: Boolean, version: Int, checkSaved: Boolean = false) {
        if (reactionJob?.isActive == true) return
        val lease = viewModel.mediaSession() ?: return
        val targetConversation = conversationId()
        reactionJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val receipt = Mango9MessageCommands(requireContext()).react(lease, channel, targetConversation, messageId(item), version, emoji, remove, checkSaved)
                if (!viewModel.isCurrentConversation()) return@launch
                when (receipt.optString("state")) {
                    "succeeded" -> Unit
                    "failed" -> showLocalError("Reaction was not saved. Refresh and try again.")
                    else -> showLocalError(getString(R.string.mango9_reaction_pending))
                }
                if (isSocial) {
                    requestedRoomId?.let { social.open(it) }
                } else if (receipt.optString("state") == "succeeded" && receipt.has("reactions")) {
                    org.linphone.mango9.Mango9ChatStore.get(requireContext()).applyReaction(channel, messageId(item), Mango9StaffReactions.parse(receipt))
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e; if (viewModel.isCurrentConversation()) showLocalError(e.message.orEmpty())
            } finally {
                // Reconcile saved history even after a stale version or uncertain command result.
                // Never turn an unknown outcome into a new mutation request.
                if (viewModel.isCurrentConversation()) {
                    if (isSocial) requestedRoomId?.let { social.open(it) } else viewModel.refreshConversationHistory()
                }
            }
        }
    }

    private fun showSelectionActions() {
        val owner = view ?: return
        val selected = adapter.currentList.filter { it.stableId in adapter.selected }
        val popup = Mango9MessageActionPopup(binding.more)
        optionsDialog?.dismiss(); optionsDialog = popup
        popup.action(getString(android.R.string.cancel), R.drawable.caret_left) { popup.dismiss(); adapter.select(null) }
        if (selected.isNotEmpty()) {
            if (messageMedia(selected).isNotEmpty()) {
                popup.action("Export attachments…", R.drawable.download_simple) {
                    popup.dismiss()
                    if (view === owner && viewModel.isCurrentConversation()) showExportOptions(selected)
                }
            }
            popup.action(getString(R.string.mango9_forward), R.drawable.arrow_right) {
                popup.dismiss()
                if (view === owner && viewModel.isCurrentConversation()) forwardMessages(selected)
            }
            popup.separator()
            popup.action(getString(R.string.mango9_delete_for_me), R.drawable.trash, true) {
                popup.dismiss()
                val lease = viewModel.mediaSession()
                if (lease != null && view === owner && viewModel.isCurrentConversation()) {
                    MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.mango9_delete_for_me)
                        .setMessage("Hide the selected messages only for you, across your devices? This does not unsend them.")
                        .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.mango9_delete_for_me) { _, _ ->
                            viewLifecycleOwner.lifecycleScope.launch {
                                try {
                                    Mango9MessageCommands(requireContext()).visibility(lease, channel, conversationId(), selected.map(::messageId), true)
                                    if (!viewModel.isCurrentConversation()) return@launch
                                    adapter.select(null)
                                    adapter.submitList(adapter.currentList.filterNot { item -> selected.any { it.stableId == item.stableId } })
                                    if (isSocial) social.deletedMessages(selected.map(::messageId).toSet()) else binding.retryHistory.performClick()
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    if (viewModel.isCurrentConversation()) showLocalError(e.message.orEmpty())
                                }
                            }
                        }.show()
                }
            }
        }
        popup.position()
    }

    private fun messageMedia(selected: List<Mango9MessageListItem>): List<Mango9ChatMedia> = selected
        .filterNot { it is Mango9MessageListItem.Social && it.message.deleted }
        .flatMap { if (it is Mango9MessageListItem.Social) it.media else Mango9ChatMedia.parse(it.files) }
        .distinctBy { it.url }

    private fun showExportOptions(selected: List<Mango9MessageListItem>) {
        if (!viewModel.isCurrentConversation()) return
        if (exports.busy.value == true) { showLocalError("Finish the current export first."); return }
        val media = messageMedia(selected)
        if (media.isEmpty()) return
        val visual = media.filter { it.kind in setOf(Mango9ChatMedia.Kind.Image, Mango9ChatMedia.Kind.Video) }
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        if (visual.isNotEmpty() && android.os.Build.VERSION.SDK_INT >= 29) {
            labels += if (visual.all {
                it.kind == Mango9ChatMedia.Kind.Image
            }
            ) {
                "Save images to Gallery"
            } else {
                "Save photos/videos to Gallery"
            }
            actions += { prepareExport(visual, "gallery") }
        }
        labels += if (media.size == 1) "Save as file…" else "Save files to a folder…"
        actions += { prepareExport(media, "files") }
        labels += "Open with another app…"
        actions += {
            if (media.size == 1) {
                prepareExport(media, "open")
            } else {
                MaterialAlertDialogBuilder(requireContext()).setTitle("Choose a file to open")
                    .setItems(
                        media.map {
                        Mango9AttachmentExports.fileName(it)
                    }.toTypedArray()
                    ) { _, index -> prepareExport(listOf(media[index]), "open") }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
        }
        MaterialAlertDialogBuilder(requireContext()).setTitle("Export ${media.size} attachment${if (media.size == 1) "" else "s"}")
            .setItems(labels.toTypedArray()) { _, index -> actions[index]() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun prepareExport(media: List<Mango9ChatMedia>, action: String) {
        val lease = viewModel.mediaSession() ?: return
        val owner = view ?: return
        if (exports.busy.value == true) return
        viewLifecycleOwner.lifecycleScope.launch {
            var began = false
            try {
                val fresh = if (isSocial) media.map { social.freshAttachment(it) } else media
                if (view !== owner || !viewModel.isCurrentConversation() || !exports.begin(fresh, lease)) return@launch
                began = true
                when (action) {
                    "gallery" -> exports.save(null, gallery = true)
                    "open" -> exports.openWith()
                    else -> if (fresh.size == 1) exportDocument.launch(Mango9AttachmentExports.createDocumentIntent(fresh.single())) else exportFolder.launch(null)
                }
            } catch (failure: Exception) {
                if (began) exports.cancelPicker()
                if (failure is CancellationException) throw failure
                if (view === owner) showLocalError("Could not prepare the export. Please try again.")
            }
        }
    }

    private fun forwardMessages(selected: List<Mango9MessageListItem>) {
        val lease = viewModel.mediaSession() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val assets = mutableListOf<Mango9MediaAsset>()
            try {
                val media = selected.flatMap { if (it is Mango9MessageListItem.Social) it.media else Mango9ChatMedia.parse(it.files) }
                require(media.size <= Mango9ExternalShare.MAX_FILES) { "Select at most 12 attachments to forward." }
                for (item in media) assets += Mango9MediaCache.acquire(requireContext(), if (isSocial) social.freshAttachment(item) else item, lease)
                if (!viewModel.isCurrentConversation()) return@launch
                val request = sharedViewModel.externalShare.begin(
                    selected.joinToString("\n\n") {
                    it.text
                },
                    assets.map { Uri.fromFile(it.file) }
                ) ?: return@launch
                sharedViewModel.externalShare.state.first { it.id != request || !it.preparing }
                if (viewModel.isCurrentConversation() && sharedViewModel.externalShare.pendingRequestId() == request) {
                    adapter.select(null)
                    findNavController().navigate(R.id.action_global_conversationsListFragment)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (viewModel.isCurrentConversation()) showLocalError(e.message.orEmpty())
            } finally { assets.forEach { it.close() } }
        }
    }

    private fun renameGroup(roomId: String) {
        val lease = viewModel.mediaSession() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val store = org.linphone.mango9.Mango9ChatStore.get(requireContext())
                val details = store.groupDetails(roomId, lease)
                if (!viewModel.isCurrentConversation() || details.opt("canRename") != true) return@launch
                val previous = details.optString("name")
                val input = EditText(requireContext()).apply { setText(previous); maxLines = 1 }
                MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.mango9_rename_group).setView(input)
                    .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok) { _, _ ->
                        viewLifecycleOwner.lifecycleScope.launch {
                            try {
                                store.renameGroup(roomId, input.text.toString(), previous, lease)
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e; if (viewModel.isCurrentConversation()) showLocalError(e.message.orEmpty())
                            }
                        }
                    }.show()
            } catch (e: Exception) {
                if (e is CancellationException) throw e; if (viewModel.isCurrentConversation()) showLocalError(e.message.orEmpty())
            }
        }
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
        val users = viewModel.currentState().users.filter { !it.isSystem && (it.canJoinGroups || room.userIds.contains(it.id)) }
        val selected = room.userIds.toMutableSet()
        val checked = users.map { selected.contains(it.id) }.toBooleanArray()
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.mango9_chat_manage_members)
            .setMultiChoiceItems(users.map { "${it.name} — ${it.roleLabel}" }.toTypedArray(), checked) { _, which, enabled ->
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
        val lease = viewModel.mediaSession() ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.mango9_delete_for_me)
            .setMessage(R.string.mango9_delete_for_me_info)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.mango9_delete_for_me) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        Mango9MessageCommands(requireContext()).visibility(lease, if (isSocial) Mango9MessageChannel.social else if (isSms) Mango9MessageChannel.sms else Mango9MessageChannel.team, null, listOf(roomId), true, true)
                        if (viewModel.isCurrentConversation()) { conversationTools?.closeSearch(); goBack() }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e; if (viewModel.isCurrentConversation()) showLocalError(e.message.orEmpty())
                    }
                }
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
        val owner = view ?: return
        if (!viewModel.isCurrentConversation() || mediaOpenJob?.isActive == true) return
        val lease = viewModel.mediaSession() ?: return
        viewModel.audioController().stop()
        val ownedBinding = binding
        val appContext = requireContext().applicationContext
        mediaOpenJob = viewLifecycleOwner.lifecycleScope.launch {
            ownedBinding.loading.visibility = View.VISIBLE
            var asset: Mango9MediaAsset? = null
            var ticket: String? = null
            try {
                val acquired = Mango9MediaCache.acquire(appContext, if (isSocial) social.freshAttachment(media) else media, lease)
                asset = acquired
                if (view !== owner || !viewModel.isCurrentConversation()) return@launch
                if (media.kind == Mango9ChatMedia.Kind.File) {
                    val uri = FileProvider.getUriForFile(requireContext(), getString(R.string.file_provider), acquired.file)
                    viewModel.holdOpenedDocument(acquired)
                    asset = null
                    documentViewer.launch(
                        Intent(Intent.ACTION_VIEW).setDataAndType(uri, media.mimeType)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    )
                } else {
                    val intent = Intent(requireContext(), MediaViewerActivity::class.java)
                    ticket = Mango9MediaCache.bindViewer(intent, acquired, lease, media)
                    asset = null
                    startActivity(intent)
                    ticket = null
                }
            } catch (error: Exception) {
                viewModel.releaseOpenedDocument()
                if (error is CancellationException) throw error
                if (view === owner && viewModel.isCurrentConversation()) showLocalError(getString(R.string.mango9_chat_attachment_unavailable))
            } finally {
                asset?.close()
                ticket?.let(Mango9MediaCache::discardViewer)
                if (view === owner) ownedBinding.loading.visibility = View.GONE
            }
        }
    }

    private fun showAttachmentMenu() {
        if (!viewModel.isCurrentConversation()) return
        val atLimit = attachments.size >= (if (isSocial) 1 else MAX_ATTACHMENTS) || (isSocial && social.current().thread?.canDraft != true)
        val options = arrayOf(
            getString(R.string.mango9_chat_take_photo),
            getString(R.string.mango9_chat_photo_library),
            getString(R.string.mango9_chat_browse_files),
            "Templates  ›",
        )
        PopupMenu(requireContext(), binding.attach).apply {
            options.forEachIndexed { index, label -> menu.add(0, index, index, label).isEnabled = index == 3 || !atLimit }
            setOnMenuItemClickListener { selected ->
                when (selected.itemId) {
                    0 -> requestCamera()
                    1 -> openMediaPicker()
                    2 -> openFilePicker()
                    3 -> Mango9TemplatesFragment.open(this@Mango9MessagingConversationFragment, viewModel.mediaSession(), binding.message)
                }
                true
            }
        }.show()
    }

    private fun requestCamera() {
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            launchCamera()
        } else {
            if (viewModel.composer.beginRequest("cameraPermission")) cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCamera() {
        val owner = view ?: return
        if (!viewModel.composer.beginRequest("capture")) return
        viewModel.composer.createCapture { file ->
            if (view !== owner || !viewModel.isCurrentConversation()) {
                viewModel.composer.cancelRequest("capture")
                false
            } else {
                try {
                val uri = FileProvider.getUriForFile(requireContext(), getString(R.string.file_provider), file)
                cameraCapture.launch(uri)
                true
            } catch (_: Exception) {
                viewModel.composer.cancelRequest("capture")
                showLocalError(getString(R.string.mango9_chat_camera_failed))
                false
            }
            }
        }
    }

    private fun openMediaPicker() {
        if (isSocial) {
            if (!viewModel.composer.beginRequest("socialMedia")) return
            try {
                socialMediaPicker.launch(
                    PickVisualMediaRequest(
                    if (social.current().thread?.platform == "tiktok") ActivityResultContracts.PickVisualMedia.ImageOnly else ActivityResultContracts.PickVisualMedia.ImageAndVideo
                )
                )
            } catch (_: ActivityNotFoundException) {
                viewModel.composer.cancelRequest("socialMedia")
                showLocalError(getString(R.string.mango9_chat_attachment_unavailable))
            }
            return
        }
        if (!viewModel.composer.beginRequest("media")) return
        try {
            mediaPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
            )
        } catch (_: ActivityNotFoundException) {
            viewModel.composer.cancelRequest("media")
            showLocalError(getString(R.string.mango9_chat_attachment_unavailable))
        }
    }

    private fun openFilePicker() {
        if (isSocial) {
            if (!viewModel.composer.beginRequest("socialFile")) return
            try {
                socialFilePicker.launch(org.linphone.mango9.Mango9SocialMediaPolicy.mimeTypes(social.current().thread?.platform.orEmpty()))
            } catch (_: ActivityNotFoundException) {
                viewModel.composer.cancelRequest("socialFile")
                showLocalError(getString(R.string.mango9_chat_attachment_unavailable))
            }
            return
        }
        if (!viewModel.composer.beginRequest("files")) return
        try {
            filePicker.launch(arrayOf("*/*"))
        } catch (_: ActivityNotFoundException) {
            viewModel.composer.cancelRequest("files")
            showLocalError(getString(R.string.mango9_chat_attachment_unavailable))
        }
    }

    private fun addPickedAttachments(uris: List<Uri>) {
        viewModel.composer.import(uris)
    }

    private fun toggleRecording() {
        if (recording) {
            finishRecording()
        } else if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            if (viewModel.composer.beginRequest("microphonePermission")) microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    @Suppress("DEPRECATION")
    private fun startRecording() {
        if (!viewModel.isCurrentConversation() || viewModel.composer.state.value.sending ||
            viewModel.composer.state.value.preparing > 0 || recording ||
            recordingPreparation?.isActive == true
        ) {
            return
        }
        viewModel.audioController().stop()
        if (attachments.size >= MAX_ATTACHMENTS) {
            showLocalError(getString(R.string.mango9_chat_attachment_limit))
            return
        }
        val ownerView = binding.root
        val appContext = requireContext().applicationContext
        val job = viewLifecycleOwner.lifecycleScope.launch(start = CoroutineStart.LAZY) {
            var source: Mango9PreparedAttachment? = null
            var startingRecorder: MediaRecorder? = null
            try {
                source = Mango9PreparedAttachment.createCapture(appContext, "voice.m4a", "audio/mp4")
                if (view !== ownerView || !viewModel.isCurrentConversation()) return@launch
                val output = File(requireNotNull(source).attachment.uri.path!!)
                // Assign before prepare/start so a failure releases the partially configured recorder.
                startingRecorder = MediaRecorder()
                startingRecorder.apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioSamplingRate(44_100)
                    setAudioChannels(1)
                    setAudioEncodingBitRate(64_000)
                    setMaxDuration(600_000)
                    setMaxFileSize(org.linphone.mango9.Mango9AttachmentPreparer.MAX_BYTES)
                    setOnInfoListener { sender, what, _ ->
                        if (recorder === sender && recording && view === ownerView && what in listOf(
                            MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED,
                            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED,
                        )
                        ) {
                            finishRecording()
                        }
                    }
                    setOutputFile(output.absolutePath)
                    prepare()
                    start()
                }
                recorder = startingRecorder
                startingRecorder = null
                voiceSource = source
                source = null
                voiceFile = output
                recording = true
                binding.recordingStatus.setText(R.string.mango9_chat_recording)
                binding.recordingStatus.visibility = View.VISIBLE
                binding.microphone.setColorFilter(ContextCompat.getColor(requireContext(), R.color.red_danger_500))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                if (view === ownerView && viewModel.isCurrentConversation()) {
                    showLocalError(getString(R.string.mango9_chat_recording_failed))
                }
            } finally {
                startingRecorder?.release()
                source?.close()
                // A canceled old View must not clear a new View's preparation or touch its widgets.
                if (recordingPreparation === coroutineContext[Job]) {
                    recordingPreparation = null
                    if (view === ownerView) updateComposerState(viewModel.currentState())
                }
            }
        }
        recordingPreparation = job
        job.start()
        updateComposerState(viewModel.currentState())
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
            viewModel.composer.import(listOf(Uri.fromFile(output)), voiceSource)
        } else {
            voiceSource?.close()
        }
        voiceSource = null
        voiceFile = null
        renderPendingAttachments()
        updateComposerState(viewModel.currentState())
    }

    private fun cancelRecording() {
        recordingPreparation?.cancel()
        recordingPreparation = null
        if (!recording && recorder == null) return
        runCatching { recorder?.stop() }
        recorder?.release()
        recorder = null
        recording = false
        voiceSource?.close()
        voiceSource = null
        voiceFile = null
    }

    private fun renderPendingAttachments() {
        binding.pendingScroll.visibility = if (attachments.isEmpty()) View.GONE else View.VISIBLE
        binding.pendingAttachments.removeAllViews()
        attachments.forEach { attachment ->
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
                    setOnClickListener { viewModel.composer.remove(attachment.uri) }
                },
            )
        }
        updateComposerState(viewModel.currentState())
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
