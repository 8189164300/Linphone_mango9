// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.ui.main.crm.viewmodel

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.mango9.*
import org.linphone.utils.Event

internal data class Mango9LiveState(
    val conversations: List<Mango9LiveConversation> = emptyList(), val sites: List<JSONObject> = emptyList(),
    val preferences: List<JSONObject> = emptyList(),
    val conversation: JSONObject? = null, val messages: List<Mango9LiveMessage> = emptyList(), val agents: List<JSONObject> = emptyList(),
    val restoring: Boolean = false, val loading: Boolean = false, val sending: Boolean = false, val authorized: Boolean = false, val error: String? = null,
    val pending: Boolean = false, val pendingReaction: Boolean = false, val reacting: Boolean = false,
    val hasMore: Boolean = false, val online: Boolean? = null, val available: Boolean = false, val presenceBusy: Boolean = false,
)

/** Results and durable commands belong to the captured login and conversation, including after rotation. */
internal class Mango9LiveChatViewModel : ViewModel() {
    private val context = coreContext.context
    private val sessions = Mango9SessionStore(context)
    private val api = Mango9LiveChatApi(context)
    private val outbox = Mango9LiveChatOutbox(context)
    private var lease: Mango9SessionLease? = null
    private var room = ""
    private var generation = 0
    private var revision = 0
    private var cursor: String? = null
    private var cursorID: String? = null
    private var before: Long? = null
    private var readID = 0L
    private var expanded = false
    private var loading = false
    private var typingAt = 0L
    private var heartbeatAt = 0L
    private var query = emptyMap<String, String>()
    val state = MutableLiveData(Mango9LiveState())
    val confirmedSend = MutableLiveData<Event<String>>()
    var draft = ""
    var attachment: JSONObject? = null
    var pickerKind: String? = null
    var capture: Mango9PreparedAttachment? = null
    var importing = false
    fun importAttachment(uri: android.net.Uri, suppliedName: String? = null, suppliedMime: String? = null, release: Mango9PreparedAttachment? = null) {
        val owner = owner() ?: run { release?.close(); return }
        val stamp = generation
        if (importing) { release?.close(); return }
        importing = true; state.value = current().copy()
        val allowed = Mango9LivePolicy.attachments(current().conversation?.optJSONObject("site"))
        viewModelScope.launch {
            try {
                val payload = withContext(Dispatchers.IO) {
                    var name = suppliedName ?: "Attachment"
                    if (suppliedName == null) context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0) }
                    var mime = suppliedMime ?: context.contentResolver.getType(uri).orEmpty()
                    var bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { stream -> java.io.ByteArrayOutputStream().use { output ->
                        val buffer = ByteArray(8192)
                        while (true) { val count = stream.read(buffer); if (count < 0) break; output.write(buffer, 0, count); if (output.size() > Mango9LivePolicy.MAX_BYTES) break }
                        output.toByteArray()
                    } }
                    require(bytes.size <= Mango9LivePolicy.MAX_BYTES) { "Choose an attachment smaller than 5 MB." }
                    if (mime.startsWith("image/") && mime !in allowed && "image/jpeg" in allowed) {
                        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                        require(options.outWidth > 0 && options.outHeight > 0)
                        options.inSampleSize = 1
                        while (options.outWidth / options.inSampleSize > 2048 || options.outHeight / options.inSampleSize > 2048) options.inSampleSize *= 2
                        options.inJustDecodeBounds = false
                        val bitmap = requireNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options))
                        try { bytes = java.io.ByteArrayOutputStream().use { output -> check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, output)); output.toByteArray() } }
                        finally { bitmap.recycle() }
                        mime = "image/jpeg"; name = name.substringBeforeLast('.') + ".jpg"
                    }
                    Mango9LivePolicy.payload(bytes, name, mime, allowed)
                }
                if (valid(owner, stamp)) { attachment = payload; notice(null) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (valid(owner, stamp)) notice(if (e is IllegalArgumentException) e.message ?: "Unsupported attachment." else "Could not open this attachment. Please choose it again.")
            } finally { release?.close(); if (valid(owner, stamp)) { importing = false; state.value = current().copy() } }
        }
    }
    var selected = mutableSetOf<Long>()
    private var audio: Mango9InlineAudioController? = null
    fun audioController(): Mango9InlineAudioController = audio ?: Mango9InlineAudioController(
        viewModelScope, { owner() != null },
        { media -> Mango9MediaCache.acquire(context, freshMedia(media), requireNotNull(owner())) },
        Mango9PlatformInlineAudioFocus(context),
    ).also { audio = it }
    override fun onCleared() { audio?.dispose(); capture?.close(); super.onCleared() }

    init {
        viewModelScope.launch {
            Mango9SessionStore.contextChanges.collect {
                if (lease != null && owner() == null) bind(null)
            }
        }
    }

    fun current() = state.value ?: Mango9LiveState()
    fun freshMedia(media: Mango9ChatMedia): Mango9ChatMedia {
        val current = owner() ?: throw Mango9ApiException.Unauthorized
        return state.value?.messages.orEmpty().flatMap { it.files }
            .mapNotNull { runCatching { Mango9LivePolicy.media(it, current.session) }.getOrNull() }
            .firstOrNull { it.source == media.source } ?: media
    }
    fun owner() = lease?.takeIf { sessions.currentSession(it) != null }
    private fun valid(owner: Mango9SessionLease, stamp: Int) = owner() == owner && stamp == generation
    fun bind(value: Mango9SessionLease?, id: String = "") {
        val sameLogin = lease?.let { previous -> value != null && sessions.currentSession(previous) != null &&
            previous.loginGeneration == value.loginGeneration && previous.selectionGeneration == value.selectionGeneration &&
            Mango9MessagingApi.account(previous.session) == Mango9MessagingApi.account(value.session) } == true
        if (room == id && (lease == value || sameLogin)) return
        audio?.clear(); capture?.close(); capture = null; importing = false
        generation++; revision++; lease = value; room = id; cursor = null; cursorID = null; before = null
        expanded = false; loading = false; readID = 0; query = emptyMap()
        val available = value?.let { context.getSharedPreferences("mango9_live_device", android.content.Context.MODE_PRIVATE).getBoolean("available:${Mango9MessagingApi.account(it.session)}", false) } ?: false
        state.value = Mango9LiveState(restoring = value != null && id.isNotBlank(), available = available); draft = ""; attachment = null; selected.clear(); pickerKind = null
        val owner = value ?: return
        val stamp = generation
        if (id.isNotBlank()) viewModelScope.launch {
            try {
                val pending = withContext(Dispatchers.IO) { (outbox.load(owner, id) != null) to (outbox.load(owner, "reaction:$id") != null) }
                if (valid(owner, stamp)) state.value = current().copy(restoring = false, pending = pending.first, pendingReaction = pending.second)
            } catch (e: Exception) { if (valid(owner, stamp)) { fail(e); state.value = current().copy(restoring = false) } }
        }
    }
    fun filters(search: String, site: String, status: String, assigned: Boolean, unread: Boolean) {
        val next = buildMap {
            if (search.isNotBlank()) put("search", search)
            if (site.isNotBlank()) put("siteId", site)
            if (status.isNotBlank()) put("status", status)
            if (assigned) put("assignedToMe", "1")
            if (unread) put("unread", "1")
        }
        if (next == query) return
        generation++; loading = false; query = next; cursor = null; cursorID = null; expanded = false
        state.value = current().copy(conversations = emptyList(), error = null, hasMore = false); refresh()
    }
    fun refresh(more: Boolean = false, force: Boolean = false) {
        val owner = owner() ?: return
        if (loading || (more && !current().hasMore) || (room.isEmpty() && expanded && !more && !force)) return
        val stamp = generation; val target = room; val version = revision
        val fields = if (target.isBlank()) query + mapOf("limit" to "100") +
            if (more) mapOf("before" to requireNotNull(cursor), "beforeId" to requireNotNull(cursorID)) else emptyMap()
        else mapOf("id" to target) + if (more && before != null) mapOf("before" to before.toString()) else emptyMap()
        loading = true; state.value = current().copy(loading = true)
        viewModelScope.launch {
            try {
                if (target.isEmpty()) {
                    if (current().sites.isEmpty() || force) {
                        val data = api.request(owner, "sites")
                        if (!valid(owner, stamp)) return@launch
                        state.value = current().copy(sites = data.getJSONArray("sites").liveObjects(), preferences = data.optJSONArray("preferences")?.liveObjects().orEmpty())
                    }
                    val data = api.request(owner, "list", fields)
                    if (!valid(owner, stamp)) return@launch
                    val rows = data.getJSONArray("conversations").liveObjects().map(Mango9LiveConversation::parse)
                    cursor = data.liveText("next").takeIf(String::isNotBlank); cursorID = data.liveText("nextId").takeIf(String::isNotBlank); expanded = more
                    state.value = current().copy(conversations = (if (more) current().conversations + rows else rows).distinctBy { it.id },
                        hasMore = cursor != null && cursorID != null, error = null, authorized = true)
                } else {
                    val data = api.request(owner, "messages", fields)
                    if (!valid(owner, stamp) || version != revision) return@launch
                    val conversation = data.getJSONObject("conversation"); check(conversation.getString("id") == target)
                    val messages = data.getJSONArray("messages").liveObjects().map(Mango9LiveMessage::parse)
                    val hasMore = if (before == null || more) data.optBoolean("hasMore") else current().hasMore
                    if (before == null || more) before = data.optLong("nextBefore").takeIf { it > 0 }
                    val merged = current().messages.associateBy { it.id }.toMutableMap()
                    messages.forEach { item ->
                        val old = merged[item.id]
                        if (old != null && old.reactions.version > item.reactions.version) item.data.put("reactions", old.data.optJSONObject("reactions"))
                        merged[item.id] = item
                    }
                    data.optJSONArray("hiddenMessageIds")?.let { raw ->
                        val hidden = (0 until raw.length()).map { raw.getLong(it) }.toSet()
                        merged.values.forEach { it.data.put("hiddenForMe", it.id in hidden) }
                    }
                    state.value = current().copy(conversation = conversation, messages = merged.values.sortedBy { it.id }, hasMore = hasMore, error = null, authorized = true)
                    messages.lastOrNull()?.takeIf { it.id > readID }?.let {
                        api.request(owner, "read", body = JSONObject().put("id", target).put("messageId", it.id))
                        if (valid(owner, stamp)) readID = it.id
                    }
                    val availability = api.request(owner, "availability", mapOf("siteId" to conversation.getString("site_id")))
                    if (valid(owner, stamp)) state.value = current().copy(agents = availability.getJSONArray("agents").liveObjects(), online = availability.optBoolean("online"))
                }
                if (valid(owner, stamp) && current().available && System.currentTimeMillis() - heartbeatAt >= 30_000) presence(true)
            } catch (e: Exception) { if (valid(owner, stamp)) fail(e) }
            finally { if (valid(owner, stamp)) { loading = false; state.value = current().copy(loading = false) } }
        }
    }
    fun send(text: String, file: JSONObject? = attachment) {
        val owner = owner() ?: return
        if (room.isBlank() || current().sending || current().restoring || !current().authorized) return
        if (!current().pending && (text.length > 10000 || (text.isBlank() && file == null))) return
        val stamp = generation; val target = room; state.value = current().copy(sending = true)
        viewModelScope.launch {
            try {
                val command = withContext(Dispatchers.IO) {
                    (outbox.load(owner, target) ?: JSONObject().put("id", target).put("requestId", UUID.randomUUID().toString()).put("text", text).apply { file?.let { put("file", it) } })
                        .also { outbox.save(owner, target, it) }
                }
                if (!valid(owner, stamp)) return@launch
                state.value = current().copy(pending = true)
                val result = api.request(owner, "send", body = command)
                if (!valid(owner, stamp)) return@launch
                val message = result.getJSONObject("message"); check(message.getString("conversation_id") == target)
                val parsed = Mango9LiveMessage.parse(message); check(parsed.outgoing)
                withContext(Dispatchers.IO) { outbox.save(owner, target, null) }
                if (!valid(owner, stamp)) return@launch
                revision++; draft = ""; attachment = null
                state.value = current().copy(pending = false, messages = (current().messages + parsed).associateBy { it.id }.values.sortedBy { it.id }, error = null)
                confirmedSend.value = Event(command.liveText("text"))
            } catch (e: Exception) {
                if (valid(owner, stamp)) {
                    if (e is Mango9LiveChatFailure && e.status in setOf(400, 401, 403, 404, 409, 413)) {
                        withContext(Dispatchers.IO) { outbox.save(owner, target, null) }
                        if (valid(owner, stamp)) state.value = current().copy(pending = false)
                    }
                    if (valid(owner, stamp)) fail(e)
                }
            } finally { if (valid(owner, stamp)) { state.value = current().copy(sending = false); refresh(force = true) } }
        }
    }
    fun action(operation: String, values: JSONObject) {
        val owner = owner() ?: return
        if (!current().authorized || current().sending) return
        val stamp = generation; val target = room; val body = JSONObject(values.toString()).apply { if (target.isNotBlank()) put("id", target) }
        state.value = current().copy(sending = true)
        viewModelScope.launch {
            try {
                val result = api.request(owner, operation, body = body)
                if (!valid(owner, stamp)) return@launch
                revision++
                if (operation == "assign") current().conversation?.put("assignee_id", result.opt("assigned") ?: JSONObject.NULL)
                if (operation == "status") current().conversation?.put("status", values.getString("status"))
                if (operation == "visibility") {
                    check(result.getString("id") == target && result.getBoolean("hidden"))
                    val ids = values.getJSONArray("messageIds").let { raw -> (0 until raw.length()).map { raw.getLong(it) }.toSet() }
                    val returned = result.getJSONArray("messageIds").let { raw -> (0 until raw.length()).map { raw.getLong(it) }.toSet() }
                    check(returned == ids)
                    current().messages.filter { it.id in ids }.forEach { it.data.put("hiddenForMe", true) }; selected.clear()
                }
                state.value = current().copy(error = null)
            } catch (e: Exception) { if (valid(owner, stamp)) fail(e) }
            finally { if (valid(owner, stamp)) { state.value = current().copy(sending = false); refresh(force = true) } }
        }
    }
    fun react(message: Mango9LiveMessage, emoji: String?) {
        if (!message.reactions.supported || current().pendingReaction || (emoji != null && emoji !in message.reactions.allowed)) return
        reaction(JSONObject().put("id", room).put("messageId", message.id).put("requestId", UUID.randomUUID().toString())
            .put("version", message.reactions.version).put("emoji", emoji ?: JSONObject.NULL))
    }
    fun retryReaction() = reaction(null)
    private fun reaction(command: JSONObject?) {
        val owner = owner() ?: return
        if (!current().authorized || current().reacting || room.isBlank()) return
        val target = room; val stamp = generation
        state.value = current().copy(reacting = true)
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    requireNotNull(command ?: outbox.load(owner, "reaction:$target")).also { outbox.save(owner, "reaction:$target", it) }
                }
                if (!valid(owner, stamp)) return@launch
                state.value = current().copy(pendingReaction = true)
                val result = api.request(owner, "reaction", body = saved)
                if (!valid(owner, stamp)) return@launch
                check(result.getString("id") == target && result.getString("requestId") == saved.getString("requestId") && result.getLong("messageId") == saved.getLong("messageId"))
                val raw = result.getJSONObject("reactions"); val snapshot = Mango9LiveReactions.parse(raw); check(snapshot.supported)
                current().messages.firstOrNull { it.id == saved.getLong("messageId") }?.let {
                    if (snapshot.version >= it.reactions.version) it.data.put("reactions", raw)
                }
                withContext(Dispatchers.IO) { outbox.save(owner, "reaction:$target", null) }
                if (valid(owner, stamp)) { revision++; state.value = current().copy(pendingReaction = false, error = null) }
            } catch (e: Exception) {
                if (valid(owner, stamp)) {
                    if (e is Mango9LiveChatFailure && e.status in setOf(400, 401, 403, 404, 409)) {
                        withContext(Dispatchers.IO) { outbox.save(owner, "reaction:$target", null) }
                        if (valid(owner, stamp)) state.value = current().copy(pendingReaction = false)
                    }
                    if (valid(owner, stamp)) fail(e)
                }
            } finally { if (valid(owner, stamp)) { state.value = current().copy(reacting = false); refresh(force = true) } }
        }
    }
    fun typing(on: Boolean) {
        val owner = owner() ?: return
        if (!current().authorized || !Mango9LivePolicy.capabilities(current().conversation?.optJSONObject("site")).optBoolean("typing") || (on && System.currentTimeMillis() - typingAt < 3000)) return
        val target = room; typingAt = System.currentTimeMillis()
        viewModelScope.launch { runCatching { api.request(owner, "typing", body = JSONObject().put("id", target).put("status", if (on) "on" else "off")) } }
    }
    fun presence(available: Boolean) {
        val owner = owner() ?: return
        if (current().presenceBusy) return
        val metadata = context.getSharedPreferences("mango9_live_device", android.content.Context.MODE_PRIVATE)
        val device = metadata.getString("id", null) ?: UUID.randomUUID().toString().also { metadata.edit().putString("id", it).apply() }
        val stamp = generation; state.value = current().copy(presenceBusy = true)
        viewModelScope.launch {
            try {
                api.request(owner, "presence", body = JSONObject().put("status", if (available) "available" else "away").put("deviceId", device))
                if (valid(owner, stamp)) { metadata.edit().putBoolean("available:${Mango9MessagingApi.account(owner.session)}", available).apply(); heartbeatAt = System.currentTimeMillis(); state.value = current().copy(available = available, error = null) }
            } catch (e: Exception) { if (valid(owner, stamp)) fail(e) }
            finally { if (valid(owner, stamp)) state.value = current().copy(presenceBusy = false) }
        }
    }
    fun notice(message: String?) { state.value = current().copy(error = message) }
    private fun fail(error: Exception) {
        if (error is CancellationException) throw error
        val clear = error is Mango9ApiException.Unauthorized || (error is Mango9LiveChatFailure && error.status in setOf(401, 403, 404))
        state.value = if (clear) Mango9LiveState(error = Mango9LiveChatApi.message(error)) else current().copy(error = Mango9LiveChatApi.message(error))
    }
}
