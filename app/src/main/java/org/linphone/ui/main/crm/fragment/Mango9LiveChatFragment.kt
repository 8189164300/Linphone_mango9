// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.ui.main.crm.fragment

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.DiffUtil
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.linphone.R
import org.linphone.mango9.*
import org.linphone.ui.fileviewer.MediaViewerActivity
import org.linphone.ui.main.crm.adapter.Mango9AudioMessageView
import org.linphone.ui.main.crm.viewmodel.*
import org.linphone.ui.main.fragment.GenericMainFragment
import java.io.File
import java.util.UUID

/** A single full-width native conversation in portrait and landscape. No nested pane or auto-focus. */
class Mango9LiveChatFragment : GenericMainFragment() {
    private val model: Mango9LiveChatViewModel by viewModels()
    private val exports: Mango9AttachmentExportViewModel by viewModels()
    private lateinit var ui: Mango9LiveViews
    private lateinit var root: LinearLayout
    private lateinit var title: TextView
    private lateinit var assigned: TextView
    private lateinit var notice: TextView
    private lateinit var earlier: TextView
    private lateinit var selection: LinearLayout
    private lateinit var list: RecyclerView
    private lateinit var input: EditText
    private lateinit var attach: ImageButton
    private lateinit var mic: ImageButton
    private lateinit var send: ImageButton
    private lateinit var pending: TextView
    private lateinit var adapter: Messages
    private var id = ""
    private var recorder: MediaRecorder? = null
    private var recording: Mango9PreparedAttachment? = null
    private var recordingJob: Job? = null
    private var mediaJob: Job? = null
    private var popup: Mango9MessageActionPopup? = null
    private var rendered = ""
    private var draftApplied = false
    private val photo = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> picked("photo", uri) }
    private val document = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> picked("document", uri) }
    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (model.pickerKind != "camera") return@registerForActivityResult
        model.pickerKind = null
        val source = model.capture; model.capture = null
        if (success && source != null) model.importAttachment(source.attachment.uri, "photo.jpg", "image/jpeg", source) else source?.close()
    }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (model.pickerKind != "microphone") return@registerForActivityResult
        model.pickerKind = null
        if (allowed && view != null && model.owner() != null) record() else model.notice("Allow microphone access in Android settings to record a voice message.")
    }
    private val saveFile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        exports.save(if (result.resultCode == android.app.Activity.RESULT_OK) result.data?.data else null)
    }
    private val saveFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { exports.save(it, folder = true) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        rendered = ""
        ui = Mango9LiveViews(requireContext()); root = ui.column(); root.setBackgroundColor(ui.surface)
        id = arguments?.getString("liveId").orEmpty()
        val header = ui.row(); title = ui.text(if (id.isBlank()) "Forward message" else "Live Chat", 17f, true); ui.oneLine(title)
        header.addView(ui.icon(R.drawable.caret_left, "Back") { dismissKeyboard(); goBack() })
        header.addView(title, LinearLayout.LayoutParams(0, ui.dp(44), 1f).apply { gravity = Gravity.CENTER_VERTICAL }); title.gravity = Gravity.CENTER_VERTICAL
        if (id.isNotBlank()) header.addView(ui.icon(R.drawable.dots_three_vertical, "Conversation options") { conversationMenu(it = header) })
        root.addView(header)
        return root
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = arguments ?: Bundle().also { arguments = it }
        model.bind(Mango9NavigationSession.resolve(args, Mango9SessionStore(requireContext())), id)
        if (id.isBlank()) {
            root.addView(ui.padded(ui.text("Choose a conversation, then review the message and visitor details before sending.", 12f)))
            val inbox = Mango9LiveInbox(this, model) { target -> open(this, target.id, args.getString("forwardToken")) }
            root.addView(inbox.view, LinearLayout.LayoutParams(-1, 0, 1f)); inbox.show(true); return
        }
        assigned = ui.text(size = 12f).apply { setTextColor(ui.secondary); ui.oneLine(this) }; ui.padded(assigned, 16, 2); root.addView(assigned)
        notice = ui.button("") { if (model.current().pendingReaction) model.retryReaction() else model.refresh(force = true) }; root.addView(notice)
        earlier = ui.button("Load earlier messages") { model.refresh(more = true) }; root.addView(earlier)
        selection = ui.row(); root.addView(selection)
        list = RecyclerView(requireContext()).apply {
            layoutManager = LinearLayoutManager(context).apply { stackFromEnd = true }; itemAnimator = null
            clipToPadding = false; setPadding(ui.dp(8), 0, ui.dp(8), ui.dp(8))
        }
        adapter = Messages(); list.adapter = adapter; root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        pending = ui.button("") { if (!model.current().pending && recorder == null) { model.attachment = null; render(model.current()) } }; root.addView(pending)
        val composer = ui.row(); ui.padded(composer, 4, 2)
        attach = ui.icon(R.drawable.paperclip, "Attach image or file") { attachmentMenu() }; composer.addView(attach)
        input = EditText(requireContext()).apply {
            hint = "Message"; textSize = 16f; maxLines = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 2 else 4
            minHeight = ui.dp(44); gravity = Gravity.TOP or Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            background = ui.round(ui.tinted, 18); setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            setText(model.draft)
            doAfterTextChanged { model.draft = it.toString(); model.typing(it?.isNotBlank() == true); updateComposer() }
        }
        composer.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        mic = ui.icon(R.drawable.microphone, "Record voice message") { if (recorder != null) finishRecording() else requestRecording() }; composer.addView(mic)
        send = ui.icon(R.drawable.paper_plane_right, "Send message") { model.send(input.text.toString()) }; composer.addView(send)
        root.addView(composer)
        root.isFocusableInTouchMode = true; root.requestFocus()
        model.state.observe(viewLifecycleOwner) { state ->
            if (!draftApplied && state.authorized) {
                draftApplied = true
                val token = args.getString("forwardToken")
                val draft = token?.let { ForwardDrafts.take(it, model.owner()) }
                if (draft != null) {
                    model.draft = draft.text; input.setText(draft.text)
                    val allowed = Mango9LivePolicy.attachments(state.conversation?.optJSONObject("site"))
                    if (draft.file != null && draft.file.liveText("mime") !in allowed) model.notice("This website does not support the forwarded attachment. The text is ready to review.")
                    else model.attachment = draft.file
                } else if (token != null && savedInstanceState == null) model.notice("This forward draft expired. Please select the message again.")
            }
            render(state)
        }
        model.confirmedSend.observe(viewLifecycleOwner) { event -> event.consume {
            input.setText(""); dismissKeyboard(); model.typing(false)
            list.post { if (adapter.itemCount > 0) list.scrollToPosition(adapter.itemCount - 1) }
        } }
        exports.notices.observe(viewLifecycleOwner) { event -> event.consume(model::notice) }
        exports.open.observe(viewLifecycleOwner) { event -> event.consume { action ->
            if (Mango9SessionStore(requireContext()).currentSession(action.lease) != null) runCatching { startActivity(Intent.createChooser(action.intent, "Open with")) }.onFailure { model.notice("No app can open this file.") }
        } }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    if (model.owner() == null) { model.bind(null); goBack(); break }
                    model.refresh(); delay(5000)
                }
            }
        }
        model.refresh()
    }
    override fun onResume() { super.onResume(); if (id.isNotBlank()) model.audioController().setForeground(true) }
    override fun onPause() {
        recordingJob?.cancel(); recordingJob = null
        if (id.isNotBlank()) { if (recorder != null) finishRecording(); model.audioController().setForeground(false); model.typing(false) }
        popup?.dismiss(); super.onPause()
    }
    override fun onDestroyView() {
        recordingJob?.cancel(); recorder?.release(); recorder = null; recording?.close(); recording = null
        if (::list.isInitialized) list.adapter = null
        popup?.dismiss(); popup = null; super.onDestroyView()
    }
    private fun render(state: Mango9LiveState) {
        if (view == null || !::input.isInitialized) return
        title.text = state.conversation?.liveText("name").orEmpty().ifBlank { "Website visitor" }
        val assignee = state.conversation?.optInt("assignee_id")?.takeIf { it > 0 }
        assigned.text = assignee?.let { "Assigned to " + (state.agents.firstOrNull { a -> a.optInt("id") == it }?.liveText("name")?.takeIf(String::isNotBlank) ?: "Team member") }.orEmpty()
        assigned.visibility = if (assignee == null) View.GONE else View.VISIBLE
        val typing = runCatching { java.time.Instant.parse(state.conversation?.liveText("visitor_typing_until")).isAfter(java.time.Instant.now()) }.getOrDefault(false)
        notice.text = state.error?.let { "$it  Retry" } ?: when { state.pendingReaction -> "Reaction confirmation pending · Retry"; state.restoring -> "Restoring pending messages…"; state.loading && state.messages.isEmpty() -> "Loading conversation…"; typing -> "Visitor is typing…"; else -> "" }
        notice.visibility = if (notice.text.isBlank()) View.GONE else View.VISIBLE
        earlier.visibility = if (state.hasMore) View.VISIBLE else View.GONE
        model.selected.retainAll(state.messages.filterNot { it.hidden }.map { it.id }.toSet())
        val signature = state.messages.joinToString { it.presentationKey } + model.selected.joinToString()
        if (signature != rendered) {
            val atBottom = !list.canScrollVertically(1); val oldLast = adapter.currentList.lastOrNull()?.message?.id
            val rows = state.messages.filterNot { it.hidden }.map { MessageRow(it, it.presentationKey, it.id in model.selected, model.selected.isNotEmpty()) }
            adapter.submitList(rows) { if (atBottom && oldLast != rows.lastOrNull()?.message?.id && adapter.itemCount > 0) list.scrollToPosition(adapter.itemCount - 1) }
            rendered = signature
        }
        selection.removeAllViews(); selection.visibility = if (model.selected.isEmpty()) View.GONE else View.VISIBLE
        if (model.selected.isNotEmpty()) {
            selection.addView(ui.button("Cancel") { model.selected.clear(); render(model.current()) }, LinearLayout.LayoutParams(0, -2, 1f))
            selection.addView(ui.button("${model.selected.size} selected") { actions(selection, selectedMessages()) }, LinearLayout.LayoutParams(0, -2, 1f))
            selection.addView(ui.icon(R.drawable.dots_three_vertical, "Selected message actions") { actions(selection, selectedMessages()) })
        }
        pending.text = when { recorder != null -> "Recording… Tap the microphone to finish"; model.importing -> "Preparing attachment…"; state.pending -> "Send not confirmed. Tap Send to retry the original message."; model.attachment != null -> model.attachment!!.liveText("name") + "  ×"; else -> "" }
        pending.visibility = if (pending.text.isEmpty()) View.GONE else View.VISIBLE
        updateComposer()
    }
    private fun updateComposer() {
        if (!::send.isInitialized) return
        val state = model.current(); val enabled = model.owner() != null && state.authorized && !state.restoring && !state.sending && !model.importing && recorder == null
        val allowed = Mango9LivePolicy.attachments(state.conversation?.optJSONObject("site"))
        input.isEnabled = enabled && !state.pending
        attach.visibility = if (allowed.any { !it.startsWith("audio/") }) View.VISIBLE else View.GONE
        attach.isEnabled = enabled && !state.pending && model.pickerKind == null
        mic.visibility = if ("audio/mp4" in allowed || recorder != null) View.VISIBLE else View.GONE
        mic.isEnabled = (enabled && !state.pending && model.pickerKind == null) || recorder != null
        mic.contentDescription = if (recorder != null) "Finish voice recording" else "Record voice message"
        send.isEnabled = enabled && (state.pending || input.text.isNotBlank() || model.attachment != null)
        send.alpha = if (send.isEnabled) 1f else .4f
    }
    private fun selectedMessages() = model.current().messages.filter { it.id in model.selected && !it.hidden }
    private fun toggle(message: Mango9LiveMessage) { if (!model.selected.add(message.id)) model.selected.remove(message.id); render(model.current()) }
    private fun copy(messages: List<Mango9LiveMessage>) {
        if (model.owner() == null) return
        (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Message", messages.joinToString("\n\n") { it.selectedText }))
    }
    private fun actions(anchor: View, messages: List<Mango9LiveMessage>) {
        if (messages.isEmpty() || model.owner() == null) return
        popup?.dismiss()
        popup = Mango9MessageActionPopup(anchor).apply {
            action("Copy", R.drawable.copy) { copy(messages) }
            action("Select") { model.selected.addAll(messages.map { it.id }); render(model.current()) }
            action("Forward", R.drawable.share_network) { forward(messages) }
            val media = messages.flatMap { message -> message.files.mapNotNull { runCatching { Mango9LivePolicy.media(it, model.owner()!!.session) }.getOrNull() } }
            if (media.isNotEmpty()) action("Export attachments", R.drawable.share_network) { export(media) }
            messages.singleOrNull()?.takeIf { it.reactions.supported }?.let { message ->
                separator(); val row = ui.row()
                message.reactions.allowed.take(8).forEach { emoji -> row.addView(ui.button(emoji) { dismiss(); model.react(message, emoji) }.apply { textSize = 24f }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44))) }
                panel.addView(HorizontalScrollView(requireContext()).apply { addView(row) })
                if (message.reactions.items.any { it.mine }) action("Remove reaction") { model.react(message, null) }
            }
            separator(); action("Delete for me", R.drawable.trash, destructive = true) {
                MaterialAlertDialogBuilder(requireContext()).setTitle("Delete for me?").setMessage("These messages will be hidden from your history. Other participants keep their copies.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ -> model.action("visibility", JSONObject().put("messageIds", JSONArray(messages.map { it.id })).put("hidden", true)) }.show()
            }
            position()
        }
    }
    private fun conversationMenu(it: View) {
        val conversation = model.current().conversation ?: return
        popup?.dismiss(); popup = Mango9MessageActionPopup(it).apply {
            action("Visitor details", R.drawable.info) { details(conversation.optJSONObject("visitorContext") ?: conversation.optJSONObject("context") ?: JSONObject(), true) }
            action("Transfer", R.drawable.users) {
                val agents = model.current().agents
                MaterialAlertDialogBuilder(requireContext()).setTitle("Assign conversation").setItems((listOf("Unassigned") + agents.map { a -> a.liveText("name").ifBlank { "Team member" } }).toTypedArray()) { _, index ->
                    model.action("assign", JSONObject().put("actorId", if (index == 0) JSONObject.NULL else agents[index - 1].getInt("id")))
                }.show()
            }
            val resolved = conversation.liveText("status") == "resolved"
            action(if (resolved) "Reopen" else "Resolve") { model.action("status", JSONObject().put("status", if (resolved) "open" else "resolved")) }
            position()
        }
    }
    private fun details(context: JSONObject, identity: Boolean = false) {
        val text = buildString {
            if (identity) { val c = model.current().conversation; append(c?.liveText("name").orEmpty()); append("\n"); append(c?.liveText("email").orEmpty()); append("\nVisitor-provided identity · Not verified\n\n") }
            append(Mango9LiveVisitorContext.rows(context).joinToString("\n\n") { "${it.first}\n${it.second}" }.ifBlank { "No visitor details are available with the current settings." })
            append("\n\nDetails follow this website’s privacy settings. Location is approximate; device details are browser-reported.")
        }
        val content = ui.text(text).apply { setTextIsSelectable(true); ui.padded(this, 20, 12) }
        MaterialAlertDialogBuilder(requireContext()).setTitle("Visitor details").setView(ScrollView(requireContext()).apply { addView(content) }).setPositiveButton("Done", null).show()
    }
    private fun dismissKeyboard() { if (::input.isInitialized) input.clearFocus(); root.requestFocus(); (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(root.windowToken, 0) }
    private fun picked(kind: String, uri: Uri?) {
        if (model.pickerKind != kind) return
        model.pickerKind = null
        if (uri != null) model.importAttachment(uri)
        if (::input.isInitialized) updateComposer()
    }
    private fun attachmentMenu() {
        if (model.owner() == null || model.current().pending) return
        MaterialAlertDialogBuilder(requireContext()).setTitle("Add attachment").setItems(arrayOf("Take photo", "Photos", "Files")) { _, choice ->
            if (model.pickerKind != null) return@setItems
            runCatching {
                when (choice) {
                    0 -> takePhoto()
                    1 -> { model.pickerKind = "photo"; photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    2 -> { model.pickerKind = "document"; document.launch(Mango9LivePolicy.attachments(model.current().conversation?.optJSONObject("site")).toTypedArray()) }
                }
            }.onFailure { model.pickerKind = null; model.notice("No app is available to choose this attachment.") }
        }.show()
    }
    private fun takePhoto() {
        val owner = model.owner() ?: return; model.pickerKind = "camera"
        viewLifecycleOwner.lifecycleScope.launch {
            var source: Mango9PreparedAttachment? = null
            try {
                source = Mango9PreparedAttachment.createCapture(requireContext(), "photo.jpg", "image/jpeg")
                if (owner != model.owner()) return@launch
                model.capture = source; source = null
                val file = File(requireNotNull(model.capture).attachment.uri.path!!)
                camera.launch(FileProvider.getUriForFile(requireContext(), getString(R.string.file_provider), file))
            } catch (e: Exception) { model.capture?.close(); model.capture = null; model.pickerKind = null; if (e is CancellationException) throw e; model.notice("Could not open the camera.") }
            finally { source?.close() }
        }
    }
    private fun requestRecording() {
        if (model.owner() == null || model.pickerKind != null) return
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) record()
        else { model.pickerKind = "microphone"; permission.launch(Manifest.permission.RECORD_AUDIO) }
    }
    @Suppress("DEPRECATION")
    private fun record() {
        if (recordingJob?.isActive == true || model.current().sending || model.importing || model.owner() == null) return
        model.audioController().stop(); val owner = model.owner()
        recordingJob = viewLifecycleOwner.lifecycleScope.launch {
            var source: Mango9PreparedAttachment? = null; var recorderStarting: MediaRecorder? = null
            try {
                source = Mango9PreparedAttachment.createCapture(requireContext(), "voice.m4a", "audio/mp4")
                if (owner != model.owner()) return@launch
                recorderStarting = MediaRecorder().apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC); setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioSamplingRate(44100); setAudioChannels(1); setAudioEncodingBitRate(64000)
                    setMaxDuration(600000); setMaxFileSize(Mango9LivePolicy.MAX_BYTES.toLong())
                    setOutputFile(source!!.attachment.uri.path)
                    setOnInfoListener { sender, what, _ -> if (sender === recorder && what in setOf(MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED, MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED)) finishRecording() }
                    prepare(); start()
                }
                recording = source; source = null; recorder = recorderStarting; recorderStarting = null; render(model.current())
            } catch (e: Exception) { if (e is CancellationException) throw e; model.notice("Could not start voice recording. Check microphone access.") }
            finally { source?.close(); recorderStarting?.release() }
        }
    }
    private fun finishRecording() {
        val source = recording; recording = null
        val success = runCatching { recorder?.stop() }.isSuccess; recorder?.release(); recorder = null
        if (success && source != null) model.importAttachment(source.attachment.uri, "voice.m4a", "audio/mp4", source) else source?.close()
        if (view != null) render(model.current())
    }
    private fun export(media: List<Mango9ChatMedia>) {
        val owner = model.owner() ?: return
        val choices = mutableListOf("Save as file")
        if (media.all { it.kind == Mango9ChatMedia.Kind.Image }) choices += "Save to images"
        if (media.size == 1) choices += "Open with another app"
        MaterialAlertDialogBuilder(requireContext()).setTitle("Export attachment${if (media.size > 1) "s" else ""}").setItems(choices.toTypedArray()) { _, index ->
            if (!exports.begin(media.map(model::freshMedia), owner)) return@setItems
            runCatching {
                when (choices[index]) {
                    "Save to images" -> exports.save(null, gallery = true)
                    "Open with another app" -> exports.openWith()
                    else -> if (media.size == 1) saveFile.launch(Mango9AttachmentExports.createDocumentIntent(media.single())) else saveFolder.launch(null)
                }
            }.onFailure { exports.cancelPicker(); model.notice("No app can complete this export.") }
        }.show()
    }
    private fun openMedia(media: Mango9ChatMedia) {
        val owner = model.owner() ?: return
        if (media.kind == Mango9ChatMedia.Kind.File) { if (exports.begin(listOf(model.freshMedia(media)), owner)) exports.openWith(); return }
        if (mediaJob?.isActive == true) return
        model.audioController().stop()
        mediaJob = viewLifecycleOwner.lifecycleScope.launch {
            var asset: Mango9MediaAsset? = null; var ticket: String? = null
            try {
                asset = Mango9MediaCache.acquire(requireContext(), model.freshMedia(media), owner)
                if (owner != model.owner()) return@launch
                val intent = Intent(requireContext(), MediaViewerActivity::class.java)
                ticket = Mango9MediaCache.bindViewer(intent, asset!!, owner, media); asset = null
                startActivity(intent); ticket = null
            } catch (e: Exception) { if (e is CancellationException) throw e; model.notice("Could not open this attachment. Refresh and try again.") }
            finally { asset?.close(); ticket?.let(Mango9MediaCache::discardViewer) }
        }
    }
    private fun forward(messages: List<Mango9LiveMessage>) {
        val owner = model.owner() ?: return
        val files = messages.flatMap { it.files }; if (files.size > 1) { model.notice("Forward attachments one message at a time."); return }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val file = files.firstOrNull()?.let { raw ->
                    val media = Mango9LivePolicy.media(raw, owner.session); val asset = Mango9MediaCache.acquire(requireContext(), model.freshMedia(media), owner)
                    try { withContext(Dispatchers.IO) { require(asset.file.length() <= Mango9LivePolicy.MAX_BYTES); Mango9LivePolicy.payload(asset.file.readBytes(), media.name, media.mimeType, listOf(media.mimeType)) } } finally { asset.close() }
                }
                if (owner != model.owner()) return@launch
                val token = ForwardDrafts.put(owner, messages.joinToString("\n\n") { it.selectedText }, file)
                open(this@Mango9LiveChatFragment, "", token)
            } catch (e: Exception) { if (e is CancellationException) throw e; model.notice("Could not prepare this forward. Refresh and try again.") }
        }
    }
    private data class MessageRow(val message: Mango9LiveMessage, val key: String, val selected: Boolean, val selecting: Boolean)
    private inner class Messages : ListAdapter<MessageRow, MessageHolder>(object : DiffUtil.ItemCallback<MessageRow>() {
        override fun areItemsTheSame(a: MessageRow, b: MessageRow) = a.message.id == b.message.id
        override fun areContentsTheSame(a: MessageRow, b: MessageRow) = a.key == b.key && a.selected == b.selected && a.selecting == b.selecting
    }) {
        init { setHasStableIds(true) }
        override fun getItemId(position: Int) = getItem(position).message.id
        override fun onCreateViewHolder(parent: ViewGroup, type: Int) = MessageHolder(ui.column())
        override fun onBindViewHolder(holder: MessageHolder, position: Int) = holder.bind(getItem(position).message)
        override fun onViewRecycled(holder: MessageHolder) { holder.job?.cancel(); holder.root.removeAllViews() }
    }
    private inner class MessageHolder(val root: LinearLayout) : RecyclerView.ViewHolder(root) {
        var job: Job? = null
        fun bind(message: Mango9LiveMessage) {
            job?.cancel(); root.removeAllViews(); root.layoutParams = RecyclerView.LayoutParams(-1, -2)
            ui.padded(root, 4, 5); root.gravity = if (message.outgoing) Gravity.END else Gravity.START
            root.background = if (message.id in model.selected) ui.round(ui.tinted) else null
            val bubble = ui.column(); ui.padded(bubble, 12, 8); bubble.background = ui.round(if (message.outgoing) ui.tinted else (ui.secondary and 0xffffff) or 0x11000000)
            bubble.addView(ui.text(message.sender, 11f, true).apply { setTextColor(ui.secondary) })
            if (message.text.isNotEmpty()) bubble.addView(ui.text(message.text, 16f).apply { maxWidth = ((resources.displayMetrics.widthPixels - ui.dp(60)) * .84f).toInt() })
            val owner = model.owner()
            message.files.forEachIndexed { index, file ->
                val media = owner?.let { runCatching { Mango9LivePolicy.media(file, it.session) }.getOrNull() }
                if (media == null) bubble.addView(ui.text("Attachment unavailable", 12f))
                else when (media.kind) {
                    Mango9ChatMedia.Kind.Audio -> bubble.addView(Mango9AudioMessageView(requireContext()).apply {
                        setInk(ui.ink); bind(Mango9AudioClip(message.id.toString(), index, media), model.audioController())
                        selection(if (model.selected.isEmpty()) null else { { toggle(message) } })
                    })
                    Mango9ChatMedia.Kind.Image -> {
                        val image = ImageView(requireContext()).apply { scaleType = ImageView.ScaleType.CENTER_CROP; contentDescription = media.name; setImageResource(R.drawable.paperclip) }
                        bubble.addView(image, LinearLayout.LayoutParams(ui.dp(180), ui.dp(140)))
                        job = viewLifecycleOwner.lifecycleScope.launch {
                            try {
                                val asset = Mango9MediaCache.acquire(requireContext(), model.freshMedia(media), owner)
                                try {
                                    val bitmap = withContext(Dispatchers.IO) {
                                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(asset.file.path, options)
                                        options.inSampleSize = 1; while (options.outWidth / options.inSampleSize > 720 || options.outHeight / options.inSampleSize > 720) options.inSampleSize *= 2
                                        options.inJustDecodeBounds = false; BitmapFactory.decodeFile(asset.file.path, options)
                                    }
                                    if (model.owner() == owner) image.setImageBitmap(bitmap)
                                } finally { asset.close() }
                            } catch (e: Exception) { if (e is CancellationException) throw e }
                        }
                        image.setOnClickListener { if (model.selected.isEmpty()) openMedia(media) else toggle(message) }
                        image.setOnLongClickListener { actions(root, listOf(message)); true }
                    }
                    else -> bubble.addView(ui.button(media.name) { if (model.selected.isEmpty()) openMedia(media) else toggle(message) })
                }
            }
            root.addView(bubble, LinearLayout.LayoutParams(-2, -2))
            val compact = Mango9LiveVisitorContext.compact(message.context)
            if (compact.isNotEmpty() || Mango9LiveVisitorContext.rows(message.context).isNotEmpty()) {
                val meta = ui.row(); val summary = ui.text(compact.ifBlank { "Visitor details" }, 10f).apply { setTextColor(ui.secondary); ui.oneLine(this) }
                meta.addView(summary, LinearLayout.LayoutParams(0, -2, 1f)); meta.addView(ui.icon(R.drawable.info, "Message visitor details") { if (model.selected.isEmpty()) details(message.context) else toggle(message) })
                root.addView(meta, LinearLayout.LayoutParams((resources.displayMetrics.widthPixels * .84f).toInt(), -2))
            }
            val footer = ui.row(); footer.addView(ui.text(ui.date(message.created) + if (message.outgoing && Mango9LivePolicy.capabilities(model.current().conversation?.optJSONObject("site")).optBoolean("readState")) " · ${message.status}" else "", 10f).apply { setTextColor(ui.secondary) })
            footer.addView(ui.icon(R.drawable.copy, "Copy message") { copy(listOf(message)) }); footer.addView(ui.icon(R.drawable.dots_three_vertical, "Message actions") { actions(root, listOf(message)) })
            root.addView(footer)
            if (message.reactions.items.isNotEmpty()) {
                val reactions = ui.row(); message.reactions.items.forEach { reaction -> reactions.addView(ui.button("${reaction.emoji} ${reaction.count}") { model.react(message, if (reaction.mine) null else reaction.emoji) }.apply { textSize = 15f; isSelected = reaction.mine }) }; root.addView(reactions)
            }
            bubble.setOnClickListener { if (model.selected.isNotEmpty()) toggle(message) }
            bubble.setOnLongClickListener { actions(root, listOf(message)); true }
            root.setOnClickListener { if (model.selected.isNotEmpty()) toggle(message) }
            root.setOnLongClickListener { actions(root, listOf(message)); true }
        }
    }
    companion object {
        internal fun open(fragment: Fragment, id: String, forwardToken: String? = null) {
            val owner = Mango9SessionStore(fragment.requireContext()).captureSession() ?: return
            val args = Bundle().apply { putString("liveId", id); putString("forwardToken", forwardToken); Mango9NavigationSession.bind(this, owner) }
            fragment.findNavController().navigate(R.id.action_global_mango9LiveChatFragment, args)
        }
    }
}

/** Forwarding is a local draft, never an implicit send; no attachment bytes enter navigation state. */
private object ForwardDrafts {
    data class Draft(val owner: Mango9SessionLease, val text: String, val file: JSONObject?, val created: Long = System.currentTimeMillis())
    private val values = mutableMapOf<String, Draft>()
    fun put(owner: Mango9SessionLease, text: String, file: JSONObject?): String {
        values.entries.removeAll { it.value.owner != owner || System.currentTimeMillis() - it.value.created > 3600000 }
        return UUID.randomUUID().toString().also { values[it] = Draft(owner, text, file) }
    }
    fun take(token: String, owner: Mango9SessionLease?): Draft? = values.remove(token)?.takeIf { it.owner == owner && System.currentTimeMillis() - it.created < 3600000 }
}
