/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.mango9

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.edit
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import org.linphone.core.tools.Log

/**
 * Account-scoped Mango9 messaging client.
 *
 * Team Chat and SMS use the CRM JSON-RPC service and never create a SIP ChatRoom. Every response is
 * discarded if the active SIP identity changed while a request was in flight.
 */
class Mango9ChatStore private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val sessions = Mango9SessionStore(appContext)
    private val crm = Mango9CrmRepository(appContext)
    private val api = Mango9ApiClient(appContext)
    private val moderation = Mango9ChatModerationStore(appContext)
    private val preferences = appContext.getSharedPreferences(DEVICE_PREFERENCES, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectMutex = Mutex()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()
    private val pushClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()
    private val pendingCalls = ConcurrentHashMap<String, PendingCall>()
    private val pendingSmsStatuses = ConcurrentHashMap<String, Int>()
    private val smsCache = ConcurrentHashMap<String, List<Mango9SmsMessage>>()
    private val typingExpiryJobs = ConcurrentHashMap<Int, Job>()
    private val mutableState = MutableStateFlow(Mango9ChatState())

    val state: StateFlow<Mango9ChatState> = mutableState.asStateFlow()

    @Volatile private var socket: WebSocket? = null

    @Volatile private var intentionallyDisconnected = false

    @Volatile private var connectionGeneration = 0L

    @Volatile private var connectingIdentity: String? = null

    @Volatile private var chatToken: String? = null

    @Volatile private var chatTokenExpiresAtMillis = 0L

    @Volatile private var reconnectJob: Job? = null

    suspend fun connect(force: Boolean = false) = connectMutex.withLock {
        val session = sessions.load() ?: run {
            updateError(Mango9ApiException.Unauthorized.userMessage)
            return@withLock
        }
        val identity = Mango9SessionStore.normalizedIdentity(session.sipIdentity)
            ?: run {
                updateError(Mango9ApiException.Unauthorized.userMessage)
                return@withLock
            }
        if (!sessions.isActive(identity)) return@withLock
        if (!force && state.value.isConnected && state.value.connectedIdentity == identity) return@withLock
        if (!force && connectingIdentity == identity) return@withLock

        val preserveConversation = state.value.connectedIdentity == identity
        disconnectInternal(clearData = !preserveConversation, intentional = false)
        val generation = ++connectionGeneration
        intentionallyDisconnected = false
        connectingIdentity = identity
        mutableState.update {
            it.copy(
                connection = Mango9ChatConnectionState.Connecting,
                connectedIdentity = if (preserveConversation) identity else null,
                errorMessage = null,
            )
        }

        try {
            val bootstrap = crm.chatBootstrap()
            ensureCurrent(generation, identity)
            val endpoint = URI(bootstrap.websocketUrl)
            if (endpoint.scheme.lowercase() != "wss" || endpoint.host.isNullOrBlank()) {
                throw Mango9ChatException("The Mango9 chat endpoint is not configured.")
            }
            chatToken = bootstrap.token
            chatTokenExpiresAtMillis = System.currentTimeMillis() + bootstrap.expiresIn.coerceAtLeast(0) * 1000L
            val opened = CompletableDeferred<Unit>()
            val request = Request.Builder()
                .url(bootstrap.websocketUrl)
                .header(WEBSOCKET_PROTOCOL_HEADER, "${bootstrap.token}#$deviceId")
                .build()
            socket = client.newWebSocket(request, listener(generation, identity, opened))
            withTimeout(RPC_TIMEOUT_MS) { opened.await() }
            ensureCurrent(generation, identity)
            mutableState.update {
                it.copy(
                    connection = Mango9ChatConnectionState.Connected,
                    connectedIdentity = identity,
                    currentUserId = bootstrap.userId,
                    errorMessage = null,
                )
            }
            connectingIdentity = null
        } catch (error: Exception) {
            if (connectionGeneration != generation) return@withLock
            connectingIdentity = null
            mutableState.update {
                it.copy(
                    connection = Mango9ChatConnectionState.Disconnected,
                    connectedIdentity = if (preserveConversation) identity else null,
                    errorMessage = userMessage(error),
                )
            }
            scheduleReconnect()
            return@withLock
        }

        val activeRoomId = state.value.activeRoomId
        val activeSmsPhone = state.value.activeSmsPhone
        runCatching {
            loadDirectory()
            when {
                activeRoomId != null -> loadChatMessages(activeRoomId)
                activeSmsPhone != null -> loadSmsMessages(activeSmsPhone)
            }
        }.onFailure { error ->
            Log.w("[Mango9 Chat] Connected, but conversation refresh failed: ${error.message}")
            updateError(userMessage(error))
        }
        registerRemotePushTokenAsync()
    }

    fun disconnect(clearData: Boolean = true) {
        connectionGeneration++
        disconnectInternal(clearData, intentional = true)
    }

    fun disconnectIfConnected(identityValue: String) {
        val identity = Mango9SessionStore.normalizedIdentity(identityValue) ?: return
        if (state.value.connectedIdentity == identity || connectingIdentity == identity) {
            disconnect(clearData = true)
        }
    }

    fun registerRemotePushTokenAsync() {
        scope.launch { registerRemotePushTokenIfAvailable() }
    }

    suspend fun registerRemotePushTokenIfAvailable(): Boolean = withContext(Dispatchers.IO) {
        val snapshot = state.value
        val identity = snapshot.connectedIdentity ?: return@withContext false
        if (!snapshot.isConnected || !sessions.isActive(identity)) return@withContext false
        val session = sessions.load(identity) ?: return@withContext false
        val token = Mango9MessagePushTokenStore(appContext).load() ?: return@withContext false
        val authorization = chatToken ?: return@withContext false
        var lastStatusCode: Int? = null
        for (attempt in 0 until PUSH_REGISTRATION_ATTEMPTS) {
            if (!sessions.isActive(identity) || state.value.connectedIdentity != identity) {
                return@withContext false
            }
            val request = Mango9MessagePushRegistration.registerRequest(
                session,
                authorization,
                token,
                deviceId,
                identity,
            ) ?: return@withContext false
            val statusCode = runCatching {
                pushClient.newCall(request).execute().use { response -> response.code }
            }.getOrNull()
            lastStatusCode = statusCode
            if (statusCode != null && statusCode in 200..299) {
                Log.i("[Mango9 Message Push] Registration refreshed for the active account")
                return@withContext true
            }
            val retryable = statusCode?.let { it == 408 || it == 425 || it == 429 || it >= 500 } ?: true
            if (!retryable || attempt == PUSH_REGISTRATION_ATTEMPTS - 1) break
            delay(PUSH_REGISTRATION_RETRY_DELAYS_MS[attempt])
        }
        Log.w(
            "[Mango9 Message Push] Registration was rejected or unavailable" +
                (lastStatusCode?.let { " (status $it)" } ?: ""),
        )
        false
    }

    suspend fun unregisterRemotePushToken(
        identityValue: String,
        sessionValue: Mango9Session,
    ): Boolean = withContext(Dispatchers.IO) {
        val identity = Mango9SessionStore.normalizedIdentity(identityValue) ?: return@withContext false
        var session = sessionValue.associatedWith(identity)
        repeat(2) { attempt ->
            val bootstrap = try {
                api.chatBootstrap(session)
            } catch (_: Mango9ApiException.InvalidCredentials) {
                try {
                    session = api.refresh(session).associatedWith(identity)
                    api.chatBootstrap(session)
                } catch (_: Exception) {
                    null
                }
            } catch (_: Exception) {
                null
            }
            val request = bootstrap?.let {
                Mango9MessagePushRegistration.unregisterRequest(session, it.token, deviceId)
            }
            val removed = request != null && runCatching {
                pushClient.newCall(request).execute().use { response -> response.isSuccessful }
            }.getOrDefault(false)
            if (removed) {
                Log.i("[Mango9 Message Push] Registration removed for the signed-out account")
                return@withContext true
            }
            if (attempt == 0) delay(500)
        }
        Log.w("[Mango9 Message Push] Registration could not be removed for the signed-out account")
        false
    }

    fun clearError() {
        updateError(null)
    }

    fun teamUnreadCount(snapshot: Mango9ChatState = state.value): Int = snapshot.rooms
        .filterNot { moderation.isConversationDeleted(it.id) }
        .filter { room ->
            !room.isDirect || room.userIds.firstOrNull()?.let(moderation::isBlocked) != true
        }
        .sumOf { it.unread.coerceAtLeast(0) }

    fun smsUnreadCount(snapshot: Mango9ChatState = state.value): Int =
        snapshot.smsParties.sumOf { it.unread.coerceAtLeast(0) }

    fun inboxPreviewRoom(snapshot: Mango9ChatState = state.value): Mango9ChatRoom? {
        val visibleRooms = snapshot.rooms.filterNot { moderation.isConversationDeleted(it.id) }
        return visibleRooms.filter { it.unread > 0 }.maxByOrNull(Mango9ChatRoom::latest)
            ?: visibleRooms.maxByOrNull(Mango9ChatRoom::latest)
    }

    suspend fun refreshDirectory() {
        connect()
        if (!state.value.isConnected) return
        runCatching { loadDirectory() }.onFailure { updateError(userMessage(it)) }
    }

    suspend fun refreshSmsDirectory() {
        connect()
        if (!state.value.isConnected) return
        runCatching { loadSmsDirectory() }.onFailure { updateError(userMessage(it)) }
    }

    suspend fun openDirectConversation(userId: Int, fallbackName: String = "") {
        closeSmsConversation()
        mutableState.update { current ->
            val existingRoomId = current.rooms.firstOrNull { it.isDirect && it.userIds.contains(userId) }?.id
            val updated = existingRoomId?.let(current::markRoomReadLocally) ?: current
            updated.copy(activeRoomId = null, messages = emptyList(), errorMessage = null)
        }
        connect()
        if (!state.value.isConnected) return
        try {
            var room = state.value.rooms.firstOrNull { it.isDirect && it.userIds.contains(userId) }
            if (room == null) {
                room = parseRoom(rpc("createChatGroup", JSONArray().put(JSONArray().put(userId.toString()))))
                    ?: throw Mango9ChatException(INVALID_RESPONSE)
                mutableState.update { current ->
                    current.copy(rooms = (current.rooms + room).distinctBy(Mango9ChatRoom::id))
                }
            }
            if (fallbackName.isNotBlank() && state.value.users.none { it.id == userId }) {
                mutableState.update { current ->
                    current.copy(
                        users = current.users + Mango9ChatUser(userId, fallbackName, "", "agent"),
                    )
                }
            }
            openRoom(room.id)
        } catch (error: Exception) {
            updateError(userMessage(error))
        }
    }

    suspend fun openRoom(roomId: String) {
        closeSmsConversation()
        mutableState.update { it.markRoomReadLocally(roomId) }
        connect()
        if (!state.value.isConnected) return
        mutableState.update {
            it.markRoomReadLocally(roomId).copy(activeRoomId = roomId, messages = emptyList(), errorMessage = null)
        }
        try {
            loadChatMessages(roomId)
        } catch (error: Exception) {
            updateError(userMessage(error))
        }
    }

    fun closeConversation(roomId: String? = null) {
        val current = state.value.activeRoomId
        if (roomId != null && roomId != current) return
        mutableState.update { it.copy(activeRoomId = null, messages = emptyList()) }
    }

    suspend fun createGroup(userIds: Set<Int>): Mango9ChatRoom? {
        if (userIds.size < 2) {
            updateError("Choose at least two teammates for a group.")
            return null
        }
        connect()
        if (!state.value.isConnected) return null
        return try {
            val users = JSONArray().apply { userIds.sorted().forEach { put(it.toString()) } }
            val room = parseRoom(rpc("createChatGroup", JSONArray().put(users)))
                ?: throw Mango9ChatException(INVALID_RESPONSE)
            mutableState.update { current ->
                current.copy(rooms = listOf(room) + current.rooms.filterNot { it.id == room.id })
            }
            room
        } catch (error: Exception) {
            updateError(userMessage(error))
            null
        }
    }

    suspend fun updateGroupMembers(roomId: String, userIds: Set<Int>): Boolean {
        val room = state.value.rooms.firstOrNull { it.id == roomId && !it.isDirect } ?: return false
        return try {
            (userIds - room.userIds.toSet()).forEach { userId ->
                rpc("addChatGroupUser", JSONArray().put(room.id).put(userId.toString()))
            }
            (room.userIds.toSet() - userIds).forEach { userId ->
                rpc("removeChatGroupUser", JSONArray().put(room.id).put(userId.toString()))
            }
            loadDirectory()
            true
        } catch (error: Exception) {
            updateError(userMessage(error))
            false
        }
    }

    suspend fun sendChatMessage(text: String, attachments: List<Mango9PendingAttachment>): Boolean {
        val body = text.trim()
        val roomId = state.value.activeRoomId ?: return false
        if (body.isEmpty() && attachments.isEmpty()) return false
        updateError(null)
        return try {
            val files = upload(attachments)
            rpc(
                "sendChatMessage",
                JSONArray().put(roomId).put(body).put(JSONArray(files)).put(UUID.randomUUID().toString().lowercase()),
            )
            moderation.setConversationDeleted(roomId, false)
            runCatching { loadChatMessages(roomId) }.onFailure { error ->
                Log.w("[Mango9 Chat] Message sent, but conversation refresh failed: ${error.message}")
            }
            updateError(null)
            true
        } catch (error: Exception) {
            updateError(userMessage(error))
            false
        }
    }

    fun notifyTyping() {
        val roomId = state.value.activeRoomId ?: return
        scope.launch { runCatching { rpc("notifyTyping", JSONArray().put(roomId)) } }
    }

    suspend fun openSmsConversation(phone: String) {
        closeConversation()
        val normalized = normalizedPhone(phone)
        if (normalized.isEmpty()) {
            updateError("Enter a valid mobile number.")
            return
        }
        mutableState.update {
            it.markSmsReadLocally(normalized).copy(
                activeSmsPhone = normalized,
                smsMessages = smsCache[smsCacheKey(normalized)].orEmpty(),
                errorMessage = null,
            )
        }
        connect()
        if (!state.value.isConnected) return
        mutableState.update { it.markSmsReadLocally(normalized).copy(activeSmsPhone = normalized) }
        try {
            loadSmsMessages(normalized)
            if (state.value.smsSenders.isEmpty()) loadSmsDirectory()
        } catch (error: Exception) {
            updateError(userMessage(error))
        }
    }

    fun closeSmsConversation(phone: String? = null) {
        if (phone != null && normalizedPhone(phone) != state.value.activeSmsPhone) return
        mutableState.update { it.copy(activeSmsPhone = null, smsMessages = emptyList()) }
    }

    suspend fun refreshSmsConversation() {
        state.value.activeSmsPhone?.let { phone ->
            runCatching { loadSmsMessages(phone) }.onFailure { updateError(userMessage(it)) }
        }
    }

    suspend fun sendSms(
        phone: String,
        senderId: String,
        text: String,
        attachments: List<Mango9PendingAttachment>,
    ): Boolean {
        val body = text.trim()
        if ((body.isEmpty() && attachments.isEmpty()) || senderId.isBlank()) return false
        updateError(null)
        connect()
        if (!state.value.isConnected) return false
        return try {
            val normalized = normalizedPhone(phone)
            if (normalized.length < 10) {
                throw Mango9ChatException("Enter a valid mobile number.")
            }
            val files = upload(attachments)
            rpc(
                "sendSmsMessage",
                JSONArray()
                    .put(normalized)
                    .put(normalizedPhone(senderId))
                    .put(body)
                    .put(JSONArray(files))
                    .put(UUID.randomUUID().toString().lowercase()),
            )
            updateError(null)
            // The send request has already been accepted. Do not keep the composer blocked while
            // two fallback refresh RPCs run; live WebSocket events normally update both views.
            scope.launch {
                runCatching { loadSmsMessages(normalized) }.onFailure { error ->
                    Log.w("[Mango9 Chat] SMS sent, but conversation refresh failed: ${error.message}")
                }
                runCatching { loadSmsDirectory() }.onFailure { error ->
                    Log.w("[Mango9 Chat] SMS sent, but directory refresh failed: ${error.message}")
                }
                delay(SMS_STATUS_RECONCILE_DELAY_MS)
                runCatching { loadSmsMessages(normalized) }.onFailure { error ->
                    Log.w("[Mango9 Chat] SMS delivery refresh failed: ${error.message}")
                }
            }
            true
        } catch (error: Exception) {
            updateError(userMessage(error))
            false
        }
    }

    suspend fun sendSmsFromNotification(phone: String, text: String): Boolean {
        if (text.isBlank()) return false
        connect()
        if (!state.value.isConnected) return false
        if (state.value.smsSenders.isEmpty()) {
            runCatching { loadSmsDirectory() }.onFailure { updateError(userMessage(it)) }
        }
        val senderId = state.value.smsSenders.firstOrNull()?.senderId ?: run {
            updateError("No Mango9 SMS sender number is available.")
            return false
        }
        return sendSms(phone, senderId, text, emptyList())
    }

    fun deleteConversationLocally(roomId: String) {
        if (state.value.connectedIdentity != sessions.activeIdentity) {
            updateError("The Mango9 chat server is disconnected.")
            return
        }
        moderation.setConversationDeleted(roomId, true)
        state.value.messages.filter { it.roomId == roomId }.forEach {
            moderation.setMessageHidden(it.id, true)
        }
        closeConversation(roomId)
        mutableState.update { current -> current.copy(rooms = current.rooms.filterNot { it.id == roomId }) }
    }

    fun roomTitle(room: Mango9ChatRoom): String {
        if (room.isDirect) return room.userIds.firstOrNull()?.let(::userName) ?: "Teammate"
        val names = room.userIds.map(::userName).filterNot { it == "Teammate" }
        return names.takeIf(List<String>::isNotEmpty)?.joinToString(", ") ?: "Team group"
    }

    fun userName(userId: Int): String = when (userId) {
        state.value.currentUserId -> "You"
        else -> state.value.users.firstOrNull { it.id == userId }?.name ?: "Teammate"
    }

    fun attachment(uri: Uri): Mango9PendingAttachment? {
        if (uri.scheme !in setOf("content", "file")) return null
        val resolver = appContext.contentResolver
        var name = uri.lastPathSegment?.substringAfterLast('/').orEmpty().ifBlank { "Attachment" }
        var size = -1L
        if (uri.scheme == "content") {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.string(OpenableColumns.DISPLAY_NAME) ?: name
                    size = cursor.long(OpenableColumns.SIZE) ?: size
                }
            }
        }
        val mime = resolver.getType(uri).orEmpty().ifBlank { "application/octet-stream" }
        return Mango9PendingAttachment(uri, name.take(MAX_FILE_NAME_LENGTH), mime, size)
    }

    fun attachment(path: String): Mango9PendingAttachment? {
        val file = File(path)
        if (!file.isFile || !file.canRead()) return null
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: "application/octet-stream"
        return Mango9PendingAttachment(
            Uri.fromFile(file),
            file.name.take(MAX_FILE_NAME_LENGTH),
            mime,
            file.length(),
        )
    }

    private suspend fun loadDirectory() {
        val rawUsers = rpc("getAllUsers", JSONArray())
        val rawRooms = rpc("getAllRooms", JSONArray())
        loadSmsDirectory()
        val rawPresence = rpc("getPresence", JSONArray())
        ensureActiveIdentity()
        val currentUser = state.value.currentUserId
        val users = array(rawUsers).objects().mapNotNull(::parseUser)
            .filterNot { it.id == currentUser }
            .sortedBy { it.name.lowercase() }
        val rooms = array(rawRooms).objects().mapNotNull(::parseRoom).sortedByDescending(Mango9ChatRoom::latest)
        rooms.filter { it.unread > 0 && moderation.isConversationDeleted(it.id) }.forEach {
            moderation.setConversationDeleted(it.id, false)
        }
        mutableState.update { current ->
            val refreshed = current.copy(users = users, rooms = rooms, errorMessage = null)
            current.activeRoomId?.let(refreshed::markRoomReadLocally) ?: refreshed
        }
        applyPresence(array(rawPresence))
    }

    private suspend fun loadSmsDirectory() {
        val parties = array(rpc("getUserSmsParties", JSONArray())).objects().mapNotNull(::parseSmsParty)
            .sortedByDescending(Mango9SmsParty::latest)
        val senders = array(rpc("getSmsSenders", JSONArray())).objects().mapNotNull(::parseSmsSender)
        ensureActiveIdentity()
        mutableState.update { current ->
            val refreshed = current.copy(smsParties = parties, smsSenders = senders)
            current.activeSmsPhone?.let(refreshed::markSmsReadLocally) ?: refreshed
        }
    }

    private suspend fun loadChatMessages(roomId: String) {
        val result = rpc("getChatMessages", JSONArray().put(roomId).put(0)) as? JSONObject
            ?: throw Mango9ChatException(INVALID_RESPONSE)
        val messages = result.optJSONArray("list").orEmpty().objects().mapNotNull(::parseMessage)
            .sortedBy(Mango9ChatMessage::time)
        ensureActiveIdentity()
        mutableState.update { current ->
            if (current.activeRoomId != roomId) {
                current
            } else {
                current.copy(
                    messages = mergeChatMessages(messages, current.messages),
                    errorMessage = null,
                )
            }
        }
        val inbound = messages.filter { it.fromUserId != state.value.currentUserId && it.status < 3 }.map { it.id }
        if (inbound.isNotEmpty()) {
            runCatching {
                rpc("notifyChatMessageStatus", JSONArray().put(roomId).put(JSONArray(inbound)).put(3))
            }
        }
        mutableState.update { current ->
            if (current.activeRoomId == roomId) current.markRoomReadLocally(roomId) else current
        }
    }

    private suspend fun loadSmsMessages(phone: String) {
        val normalized = normalizedPhone(phone)
        val result = rpc("getSmsMessages", JSONArray().put(normalized).put(0).put("json")) as? JSONObject
            ?: throw Mango9ChatException(INVALID_RESPONSE)
        val messages = result.optJSONArray("list").orEmpty().objects().mapNotNull(::parseSmsMessage)
            .map(::applyPendingSmsStatus)
            .sortedBy(Mango9SmsMessage::time)
        ensureActiveIdentity()
        val merged = mergeSmsMessages(messages, smsCache[smsCacheKey(normalized)].orEmpty())
        smsCache[smsCacheKey(normalized)] = merged
        if (state.value.activeSmsPhone == normalized) {
            mutableState.update { current ->
                if (current.activeSmsPhone == normalized) {
                    current.copy(smsMessages = mergeSmsMessages(merged, current.smsMessages), errorMessage = null)
                } else {
                    current
                }
            }
        }
    }

    private suspend fun rpc(method: String, params: JSONArray): Any {
        val identity = sessions.activeIdentity ?: throw Mango9ChatException(DISCONNECTED)
        val activeSocket = socket
        if (!state.value.isConnected || state.value.connectedIdentity != identity || activeSocket == null) {
            throw Mango9ChatException(DISCONNECTED)
        }
        val id = UUID.randomUUID().toString().lowercase()
        val deferred = CompletableDeferred<Any>()
        pendingCalls[id] = PendingCall(method, deferred, identity)
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", method)
            .put("params", params)
            .put("id", id)
            .toString()
        if (!activeSocket.send(payload)) {
            pendingCalls.remove(id)
            throw Mango9ChatException(DISCONNECTED)
        }
        return try {
            withTimeout(RPC_TIMEOUT_MS) { deferred.await() }
        } finally {
            pendingCalls.remove(id)
        }
    }

    private fun listener(
        generation: Long,
        identity: String,
        opened: CompletableDeferred<Unit>,
    ) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (connectionGeneration != generation || !sessions.isActive(identity)) {
                webSocket.close(1000, null)
                opened.completeExceptionally(Mango9ChatException(DISCONNECTED))
                return
            }
            opened.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (connectionGeneration != generation || text.length > MAX_SOCKET_MESSAGE_CHARS) return
            handleSocketText(text, identity)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (connectionGeneration == generation && !intentionallyDisconnected) handleSocketFailure(generation)
        }

        override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
            opened.completeExceptionally(error)
            if (connectionGeneration == generation && !intentionallyDisconnected) handleSocketFailure(generation)
        }
    }

    private fun handleSocketText(text: String, identity: String) {
        if (!sessions.isActive(identity)) return
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        val id = json.optString("id").takeIf(String::isNotBlank)
        if (id != null) {
            val pending = pendingCalls.remove(id) ?: return
            if (pending.identity != sessions.activeIdentity) {
                pending.deferred.completeExceptionally(Mango9ChatException(DISCONNECTED))
            } else if (json.has("error")) {
                val message = json.optJSONObject("error")?.optString("message").orEmpty().ifBlank { "Chat request failed." }
                pending.deferred.completeExceptionally(Mango9ChatException("${pending.method}: $message"))
            } else {
                pending.deferred.complete(json.opt("result") ?: "")
            }
            return
        }
        val method = json.optString("method")
        val params = json.optJSONArray("params") ?: JSONArray()
        when (method) {
            "newChatMessage" -> params.opt(0)?.let(::parseMessage)?.let(::upsertMessage)
            "updateChatMessage" -> updateMessageStatuses(array(params.opt(0)))
            "updateChatGroup" -> params.opt(0)?.let(::parseRoom)?.let(::upsertRoom)
            "updatePresence" -> applyPresence(array(params.opt(0)))
            "newSmsMessage" -> params.opt(0)?.let(::parseSmsMessage)?.let(::upsertSmsMessage)
            "updateSmsMessage" -> updateSmsStatus(params.optJSONObject(0))
        }
    }

    private fun upsertMessage(message: Mango9ChatMessage) {
        moderation.setConversationDeleted(message.roomId, false)
        mutableState.update { current ->
            val messages = if (message.roomId == current.activeRoomId) {
                (current.messages.filterNot { it.id == message.id } + message).sortedBy(Mango9ChatMessage::time)
            } else {
                current.messages
            }
            val rooms = current.rooms.map { room ->
                if (room.id != message.roomId) return@map room
                val unread = when {
                    message.roomId == current.activeRoomId -> 0
                    message.fromUserId == current.currentUserId -> room.unread
                    else -> room.unread + 1
                }
                room.copy(latest = message.time, lastMessage = message.text, unread = unread)
            }.sortedByDescending(Mango9ChatRoom::latest)
            current.copy(messages = messages, rooms = rooms, errorMessage = null)
        }
        if (message.roomId == state.value.activeRoomId && message.fromUserId != state.value.currentUserId) {
            scope.launch {
                runCatching {
                    rpc("notifyChatMessageStatus", JSONArray().put(message.roomId).put(JSONArray().put(message.id)).put(3))
                }
            }
        }
    }

    private fun updateMessageStatuses(updates: JSONArray) {
        val statuses = updates.objects().associate { it.optString("id") to it.optInt("status") }
        mutableState.update { current ->
            current.copy(
                messages = current.messages.map { message ->
                statuses[message.id]?.let { message.copy(status = it) } ?: message
            }
            )
        }
    }

    private fun upsertRoom(room: Mango9ChatRoom) {
        mutableState.update { current ->
            current.copy(rooms = (current.rooms.filterNot { it.id == room.id } + room).sortedByDescending(Mango9ChatRoom::latest))
        }
    }

    private fun upsertSmsMessage(message: Mango9SmsMessage) {
        val resolved = applyPendingSmsStatus(message)
        val key = smsCacheKey(resolved.phone)
        val cached = mergeSmsMessages(listOf(resolved), smsCache[key].orEmpty())
        smsCache[key] = cached
        mutableState.update { current ->
            val updated = current.copy(
                smsMessages = if (current.activeSmsPhone == normalizedPhone(resolved.phone)) cached else current.smsMessages,
                errorMessage = null,
            )
            if (current.activeSmsPhone == normalizedPhone(resolved.phone)) {
                updated.markSmsReadLocally(resolved.phone)
            } else {
                updated
            }
        }
        scope.launch { runCatching { loadSmsDirectory() } }
    }

    private fun updateSmsStatus(update: JSONObject?) {
        update ?: return
        val id = update.optString("id")
        val status = update.optInt("status")
        if (id.isBlank()) return
        pendingSmsStatuses[id] = pendingSmsStatuses[id]
            ?.let { Mango9SmsDeliveryPolicy.newest(it, status) }
            ?: status
        val existing = state.value.smsMessages.firstOrNull { it.id == id } ?: return
        upsertSmsMessage(existing)
    }

    private fun applyPendingSmsStatus(message: Mango9SmsMessage): Mango9SmsMessage {
        val pending = pendingSmsStatuses.remove(message.id) ?: return message
        return message.copy(status = Mango9SmsDeliveryPolicy.newest(message.status, pending))
    }

    private fun applyPresence(raw: JSONArray) {
        val online = state.value.onlineUserIds.toMutableSet()
        val typing = state.value.typingUserIds.toMutableSet()
        raw.objects().forEach { item ->
            val userId = item.int("user") ?: return@forEach
            if (item.int("stat") == 2) online.add(userId) else online.remove(userId)
            if ((item.int("typing") ?: 0) > 0) {
                typing.add(userId)
                typingExpiryJobs.remove(userId)?.cancel()
                typingExpiryJobs[userId] = scope.launch {
                    delay(TYPING_EXPIRY_MS)
                    mutableState.update { it.copy(typingUserIds = it.typingUserIds - userId) }
                }
            } else {
                typing.remove(userId)
                typingExpiryJobs.remove(userId)?.cancel()
            }
        }
        mutableState.update { it.copy(onlineUserIds = online, typingUserIds = typing) }
    }

    private suspend fun upload(attachments: List<Mango9PendingAttachment>): List<String> {
        if (attachments.isEmpty()) return emptyList()
        if (attachments.size > MAX_ATTACHMENTS) throw Mango9ChatException("You can attach up to $MAX_ATTACHMENTS files.")
        refreshUploadTokenIfNeeded()
        val session = sessions.load() ?: throw Mango9ChatException(DISCONNECTED)
        val base = Mango9Configuration.verifiedHttpsUrl(session.smsChatApi)
            ?: throw Mango9ChatException("The Mango9 attachment endpoint is not configured.")
        val uploadUrl = base.toString().trimEnd('/') + "/upload"
        val token = chatToken ?: throw Mango9ChatException(DISCONNECTED)
        return withContext(Dispatchers.IO) {
            attachments.map { attachment ->
                if (attachment.size > MAX_ATTACHMENT_BYTES) {
                    throw Mango9ChatException("${attachment.name} is larger than 50 MB.")
                }
                val prepareJson = JSONObject()
                    .put("ext", attachment.name.substringAfterLast('.', ""))
                    .put("name", attachment.name)
                    .put("type", attachment.mimeType)
                    .put("size", attachment.size.coerceAtLeast(0))
                val prepare = Request.Builder()
                    .url(uploadUrl)
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer $token")
                    .post(prepareJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                val prepared = client.newCall(prepare).execute().use { response ->
                    if (!response.isSuccessful) throw Mango9ChatException("The attachment upload could not be prepared.")
                    val bytes = response.body.bytes()
                    if (bytes.size > MAX_HTTP_RESPONSE_BYTES) throw Mango9ChatException(INVALID_RESPONSE)
                    JSONObject(bytes.toString(Charsets.UTF_8))
                }
                val putUrl = prepared.optString("putUrl")
                val getUrl = prepared.optString("getUrl")
                val putUri = runCatching { URI(putUrl) }.getOrNull()
                val verifiedGetUrl = Mango9Configuration.verifiedHttpsUrl(getUrl)?.toString()
                if (putUri?.scheme?.lowercase() != "https" ||
                    putUri.host.isNullOrBlank() ||
                    verifiedGetUrl == null
                ) {
                    throw Mango9ChatException(INVALID_RESPONSE)
                }
                val put = Request.Builder()
                    .url(putUrl)
                    .apply {
                        prepared.optString("contentDisposition").takeIf(String::isNotBlank)?.let {
                            header("Content-Disposition", it)
                        }
                    }
                    .put(ContentUriRequestBody(appContext, attachment))
                    .build()
                client.newCall(put).execute().use { response ->
                    if (!response.isSuccessful) throw Mango9ChatException("The attachment could not be uploaded.")
                }
                verifiedGetUrl
            }
        }
    }

    private suspend fun refreshUploadTokenIfNeeded() {
        if (chatToken != null && chatTokenExpiresAtMillis - System.currentTimeMillis() > 60_000L) return
        val identity = sessions.activeIdentity ?: throw Mango9ChatException(DISCONNECTED)
        val bootstrap = crm.chatBootstrap()
        if (!sessions.isActive(identity)) throw Mango9ChatException(DISCONNECTED)
        chatToken = bootstrap.token
        chatTokenExpiresAtMillis = System.currentTimeMillis() + bootstrap.expiresIn.coerceAtLeast(0) * 1000L
    }

    private fun handleSocketFailure(generation: Long) {
        if (connectionGeneration != generation) return
        socket = null
        failPending(Mango9ChatException(DISCONNECTED))
        mutableState.update {
            it.copy(
                connection = Mango9ChatConnectionState.Disconnected,
                errorMessage = DISCONNECTED,
            )
        }
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (intentionallyDisconnected || reconnectJob?.isActive == true || sessions.load() == null) return
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MS)
            reconnectJob = null
            connect(force = true)
        }
    }

    private fun disconnectInternal(clearData: Boolean, intentional: Boolean) {
        intentionallyDisconnected = intentional
        reconnectJob?.cancel()
        reconnectJob = null
        connectingIdentity = null
        socket?.close(1000, null)
        socket = null
        failPending(Mango9ChatException(DISCONNECTED))
        chatToken = null
        chatTokenExpiresAtMillis = 0
        typingExpiryJobs.values.forEach(Job::cancel)
        typingExpiryJobs.clear()
        if (clearData) pendingSmsStatuses.clear()
        mutableState.update { current ->
            if (clearData) {
                Mango9ChatState(errorMessage = if (intentional) null else current.errorMessage)
            } else {
                current.copy(
                    connection = Mango9ChatConnectionState.Disconnected,
                )
            }
        }
    }

    private fun failPending(error: Exception) {
        pendingCalls.values.forEach { it.deferred.completeExceptionally(error) }
        pendingCalls.clear()
    }

    private fun ensureCurrent(generation: Long, identity: String) {
        if (connectionGeneration != generation || !sessions.isActive(identity)) throw Mango9ChatException(DISCONNECTED)
    }

    private fun ensureActiveIdentity() {
        if (state.value.connectedIdentity != sessions.activeIdentity) throw Mango9ChatException(DISCONNECTED)
    }

    private fun updateError(message: String?) {
        mutableState.update { it.copy(errorMessage = message) }
    }

    private fun userMessage(error: Throwable): String = when (error) {
        is Mango9ApiException -> error.userMessage
        is Mango9ChatException -> error.message ?: DISCONNECTED
        else -> "Mango9 messaging is temporarily unavailable. Please try again."
    }

    private val deviceId: String
        get() = preferences.getString(KEY_DEVICE_ID, null)?.takeIf(String::isNotBlank)
            ?: UUID.randomUUID().toString().lowercase().also { generated ->
                preferences.edit(commit = true) { putString(KEY_DEVICE_ID, generated) }
            }

    private fun smsCacheKey(phone: String): String = "${sessions.activeIdentity ?: "inactive"}|${normalizedPhone(phone)}"

    private data class PendingCall(
        val method: String,
        val deferred: CompletableDeferred<Any>,
        val identity: String,
    )

    private class Mango9ChatException(message: String) : Exception(message)

    private class ContentUriRequestBody(
        private val context: Context,
        private val attachment: Mango9PendingAttachment,
    ) : RequestBody() {
        override fun contentType() = attachment.mimeType.toMediaTypeOrNull()

        override fun contentLength(): Long = attachment.size

        override fun writeTo(sink: BufferedSink) {
            val stream = if (attachment.uri.scheme == "file") {
                attachment.uri.path?.let(::File)?.takeIf(File::isFile)?.inputStream()
            } else {
                context.contentResolver.openInputStream(attachment.uri)
            }
                ?: throw IllegalStateException("The selected attachment is no longer available.")
            stream.source().use(sink::writeAll)
        }
    }

    companion object {
        private const val DEVICE_PREFERENCES = "mango9_device"
        private const val KEY_DEVICE_ID = "chat_client_uuid"
        private const val WEBSOCKET_PROTOCOL_HEADER = "Sec-WebSocket-Protocol"
        private const val RPC_TIMEOUT_MS = 20_000L
        private const val RECONNECT_DELAY_MS = 2_000L
        private const val SMS_STATUS_RECONCILE_DELAY_MS = 5_000L
        private const val PUSH_REGISTRATION_ATTEMPTS = 4
        private val PUSH_REGISTRATION_RETRY_DELAYS_MS = longArrayOf(2_000L, 5_000L, 10_000L)
        private const val TYPING_EXPIRY_MS = 3_500L
        private const val MAX_SOCKET_MESSAGE_CHARS = 2 * 1024 * 1024
        private const val MAX_HTTP_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val MAX_ATTACHMENTS = 12
        private const val MAX_ATTACHMENT_BYTES = 50L * 1024L * 1024L
        private const val MAX_FILE_NAME_LENGTH = 240
        private const val INVALID_RESPONSE = "The Mango9 chat server returned an invalid response."
        private const val DISCONNECTED = "The Mango9 chat server is disconnected."
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()

        @Volatile private var instance: Mango9ChatStore? = null

        fun get(context: Context): Mango9ChatStore = instance ?: synchronized(this) {
            instance ?: Mango9ChatStore(context).also { instance = it }
        }

        fun normalizedPhone(value: String): String = Mango9ChatModerationStore.normalizedPhone(value)

        private fun parseUser(value: Any?): Mango9ChatUser? {
            val json = value as? JSONObject ?: return null
            val id = json.int("id") ?: return null
            return Mango9ChatUser(
                id,
                json.optString("name").trim().ifBlank { "User $id" },
                json.optString("avatar"),
                json.optString("category"),
            )
        }

        private fun parseRoom(value: Any?): Mango9ChatRoom? {
            val json = value as? JSONObject ?: return null
            val id = json.string("id") ?: return null
            return Mango9ChatRoom(
                id,
                json.optJSONArray("users").orEmpty().values().mapNotNull(::intValue),
                json.optString("latest"),
                json.optString("lastMsg"),
                json.int("unread") ?: 0,
                json.int("roomType") == 0,
            )
        }

        private fun parseMessage(value: Any?): Mango9ChatMessage? {
            val json = value as? JSONObject ?: return null
            return Mango9ChatMessage(
                json.string("id") ?: return null,
                json.int("from") ?: return null,
                json.string("room") ?: return null,
                json.optString("msg"),
                json.optString("time"),
                json.int("status") ?: 0,
                json.optString("files"),
            )
        }

        private fun parseSmsParty(value: Any?): Mango9SmsParty? {
            val json = value as? JSONObject ?: return null
            val phone = normalizedPhone(json.string("phone") ?: return null)
            if (phone.isEmpty()) return null
            return Mango9SmsParty(
                phone,
                json.optString("latest"),
                json.optString("lastMsg"),
                json.int("unread") ?: 0,
                json.optString("avatar"),
            )
        }

        private fun parseSmsSender(value: Any?): Mango9SmsSender? {
            val json = value as? JSONObject ?: return null
            val sender = normalizedPhone(json.string("senderId") ?: return null)
            if (sender.isEmpty()) return null
            return Mango9SmsSender(json.int("id") ?: sender.hashCode(), sender)
        }

        private fun parseSmsMessage(value: Any?): Mango9SmsMessage? {
            val json = value as? JSONObject ?: return null
            return Mango9SmsMessage(
                json.string("id") ?: return null,
                normalizedPhone(json.string("phone") ?: return null),
                json.optString("msg"),
                json.optString("time"),
                json.string("did").orEmpty(),
                json.int("status") ?: 0,
                json.optString("dir") == "i",
                json.optString("files"),
            )
        }

        internal fun mergeChatMessages(
            serverMessages: List<Mango9ChatMessage>,
            liveMessages: List<Mango9ChatMessage>,
        ): List<Mango9ChatMessage> = (serverMessages + liveMessages)
            .associateBy(Mango9ChatMessage::id)
            .values
            .sortedBy(Mango9ChatMessage::time)

        internal fun mergeSmsMessages(
            serverMessages: List<Mango9SmsMessage>,
            liveMessages: List<Mango9SmsMessage>,
        ): List<Mango9SmsMessage> = (serverMessages + liveMessages)
            .groupBy(Mango9SmsMessage::id)
            .values
            .map { versions ->
                versions.last().copy(
                    status = versions.map(Mango9SmsMessage::status)
                        .reduce(Mango9SmsDeliveryPolicy::newest),
                )
            }
            .sortedBy(Mango9SmsMessage::time)

        private fun JSONArray?.orEmpty(): JSONArray = this ?: JSONArray()

        private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull(::optJSONObject)

        private fun JSONArray.values(): List<Any> = (0 until length()).mapNotNull(::opt)

        private fun array(value: Any?): JSONArray = value as? JSONArray ?: JSONArray()

        private fun JSONObject.string(key: String): String? = when (val value = opt(key)) {
            null, JSONObject.NULL -> null
            else -> value.toString().takeIf(String::isNotBlank)
        }

        private fun JSONObject.int(key: String): Int? = intValue(opt(key))

        private fun intValue(value: Any?): Int? = when (value) {
            is Int -> value
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }

        private fun Cursor.string(column: String): String? {
            val index = getColumnIndex(column)
            return if (index >= 0 && !isNull(index)) getString(index) else null
        }

        private fun Cursor.long(column: String): Long? {
            val index = getColumnIndex(column)
            return if (index >= 0 && !isNull(index)) getLong(index) else null
        }

        private fun String.toRequestBody(mediaType: okhttp3.MediaType?) =
            object : RequestBody() {
                private val bytes = toByteArray()

                override fun contentType() = mediaType

                override fun contentLength() = bytes.size.toLong()

                override fun writeTo(sink: BufferedSink) {
                    sink.write(bytes)
                }
            }
    }
}
