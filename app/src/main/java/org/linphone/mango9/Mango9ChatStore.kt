/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.mango9

import android.content.Context
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.BufferedSink
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
    private val moderation = Mango9ChatModerationStore(appContext)
    private val messageCommands = Mango9MessageCommands(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectMutex = Mutex()
    private val conversationOwnership = Mango9ConversationOwnership()
    private val smsOwnership = Mango9ConversationOwnership()

    @Volatile private var directoryReady = false
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()
    private val uploadClient = client.newBuilder()
        .readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS).build()
    private val pendingCalls = ConcurrentHashMap<String, PendingCall>()
    private val pendingSmsStatuses = ConcurrentHashMap<String, Int>()
    private val smsCache = ConcurrentHashMap<Mango9SmsHistoryKey, List<Mango9SmsMessage>>()
    private val typingExpiryJobs = ConcurrentHashMap<Int, Job>()
    private val mutableState = MutableStateFlow(Mango9ChatState())

    val state: StateFlow<Mango9ChatState> = mutableState.asStateFlow()

    @Volatile private var socket: WebSocket? = null

    @Volatile private var intentionallyDisconnected = false

    @Volatile private var connectionGeneration = 0L

    @Volatile private var connectionSession: Mango9SessionLease? = null

    @Volatile private var connectingIdentity: String? = null

    @Volatile private var chatToken: String? = null

    @Volatile private var chatTokenExpiresAtMillis = 0L

    @Volatile private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private var reconnectNotBefore = 0L
    private var connectedAt = 0L

    fun currentConnectionSession(): Mango9SessionLease? =
        connectionSession?.takeIf { sessions.currentSession(it) != null }

    suspend fun connect(
        force: Boolean = false,
        expectedSession: Mango9SessionLease? = sessions.captureSession(),
        automaticRetry: Boolean = false,
    ) = connectMutex.withLock {
        val lease = expectedSession ?: run {
            updateError(Mango9ApiException.Unauthorized.userMessage)
            return@withLock
        }
        if (sessions.currentSession(lease) == null) return@withLock
        if (!automaticRetry) reconnectAttempts = 0
        val session = lease.session
        val identity = Mango9SessionStore.normalizedIdentity(session.sipIdentity)
            ?: run {
                updateError(Mango9ApiException.Unauthorized.userMessage)
                return@withLock
            }
        if (!sessions.isActive(identity)) return@withLock
        val sameLogin = connectionSession?.let { sessions.currentSession(it) != null } == true
        if (!force && sameLogin && state.value.isConnected && state.value.connectedIdentity == identity) return@withLock
        if (!force && sameLogin && connectingIdentity == identity) return@withLock

        val preserveConversation = sameLogin && state.value.connectedIdentity == identity
        disconnectInternal(clearData = !preserveConversation, intentional = false)
        val generation = ++connectionGeneration
        connectionSession = lease
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
            val bootstrap = crm.chatBootstrap(lease)
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
            connectedAt = System.nanoTime()
        } catch (error: Exception) {
            if (error is CancellationException && !kotlinx.coroutines.currentCoroutineContext().isActive) {
                if (connectionGeneration == generation) {
                    connectionGeneration++
                    disconnectInternal(clearData = true, intentional = true)
                }
                throw error
            }
            if (connectionGeneration != generation) return@withLock
            connectingIdentity = null
            mutableState.update {
                it.copy(
                    connection = Mango9ChatConnectionState.Disconnected,
                    connectedIdentity = if (preserveConversation) identity else null,
                    errorMessage = userMessage(error),
                )
            }
            socket?.cancel(); socket = null
            failPending(Mango9ChatException(DISCONNECTED))
            if (error == Mango9ApiException.SessionExpired || error == Mango9ApiException.Unauthorized || error == Mango9ApiException.Forbidden) return@withLock
            if (error is Mango9ApiException.RetryAfter) reconnectNotBefore = System.nanoTime() + error.seconds * 1_000_000_000L
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
            if (error is CancellationException) throw error
            Log.w("[Mango9 Chat] Connected, but conversation refresh failed: ${error.message}")
            updateError(userMessage(error))
        }
        registerRemotePushTokenAsync()
    }

    fun disconnect(clearData: Boolean = true) {
        if (clearData) {
            conversationOwnership.invalidate()
            smsOwnership.invalidate()
        }
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
        Mango9MessagePushSync.get(appContext).refreshAsync()
    }

    suspend fun registerRemotePushTokenIfAvailable(): Boolean = Mango9MessagePushSync.get(appContext).refresh()

    /** Called inside the session store's removal boundary, before a replacement login can register. */
    fun retireRemotePushToken(identityValue: String, sessionValue: Mango9Session) {
        val identity = Mango9SessionStore.normalizedIdentity(identityValue) ?: return
        if (identity != sessionValue.sipIdentity) return
        Mango9MessagePushSync.get(appContext).retire(sessionValue)
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
        val lease = sessions.captureSession() ?: return
        connect(expectedSession = lease)
        if (!state.value.isConnected || sessions.currentSession(lease) == null) return
        runCatching { loadDirectory() }.onFailure {
            if (it is CancellationException) throw it
            if (sessions.currentSession(lease) != null) updateError(userMessage(it))
        }
    }

    suspend fun refreshSmsDirectory() {
        val lease = sessions.captureSession() ?: return
        connect(expectedSession = lease)
        if (!state.value.isConnected || sessions.currentSession(lease) == null) return
        runCatching { loadSmsDirectory() }.onFailure {
            if (it is CancellationException) throw it
            if (sessions.currentSession(lease) != null) updateError(userMessage(it))
        }
    }

    suspend fun openDirectConversation(
        userId: Int,
        fallbackName: String = "",
        owner: String = UUID.randomUUID().toString(),
        expectedSession: Mango9SessionLease? = sessions.captureSession(),
    ) {
        val session = expectedSession?.takeIf { sessions.currentSession(it) != null } ?: return
        val lease = conversationOwnership.claim(owner, sessions.activeIdentity)
        closeSmsConversation()
        mutableState.update { current ->
            val existingRoomId = current.rooms.firstOrNull { it.isDirect && it.userIds.contains(userId) }?.id
            val updated = existingRoomId?.let(current::markRoomReadLocally) ?: current
            updated.copy(activeRoomId = null, messages = emptyList(), errorMessage = null)
        }
        connect(expectedSession = session)
        if (!state.value.isConnected || !ownsOpen(lease, session)) return
        try {
            if (!directoryReady) loadDirectory()
            if (!ownsOpen(lease, session)) return
            var room = state.value.rooms.firstOrNull { it.isDirect && it.userIds.contains(userId) }
            if (room == null) {
                room = parseRoom(rpc("createChatGroup", JSONArray().put(JSONArray().put(userId.toString()))))
                    ?: throw Mango9ChatException(INVALID_RESPONSE)
                if (!ownsOpen(lease, session)) return
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
            loadOwnedRoom(room.id, lease, session)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (ownsOpen(lease, session)) updateError(userMessage(error))
        }
    }

    suspend fun openRoom(
        roomId: String,
        owner: String = UUID.randomUUID().toString(),
        expectedSession: Mango9SessionLease? = sessions.captureSession(),
    ) {
        val session = expectedSession?.takeIf { sessions.currentSession(it) != null } ?: return
        val lease = conversationOwnership.claim(owner, sessions.activeIdentity)
        closeSmsConversation()
        mutableState.update { it.markRoomReadLocally(roomId) }
        connect(expectedSession = session)
        if (!state.value.isConnected || !ownsOpen(lease, session)) return
        loadOwnedRoom(roomId, lease, session)
    }

    private fun ownsOpen(lease: Mango9ConversationOwnership.Lease, session: Mango9SessionLease): Boolean =
        sessions.currentSession(session) != null && conversationOwnership.owns(lease, sessions.activeIdentity)

    private suspend fun loadOwnedRoom(roomId: String, lease: Mango9ConversationOwnership.Lease, session: Mango9SessionLease) {
        if (!ownsOpen(lease, session)) return
        mutableState.update {
            it.markRoomReadLocally(roomId).copy(activeRoomId = roomId, messages = emptyList(), errorMessage = null)
        }
        try {
            loadChatMessages(roomId)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (ownsOpen(lease, session)) updateError(userMessage(error))
        }
    }

    fun closeOwnedConversation(owner: String) {
        if (conversationOwnership.release(owner)) closeConversation()
    }

    fun ownsConversation(owner: String): Boolean =
        conversationOwnership.ownsOwner(owner, sessions.activeIdentity)

    fun closeConversation(roomId: String? = null) {
        val current = state.value.activeRoomId
        if (roomId != null && roomId != current) return
        conversationOwnership.invalidate()
        mutableState.update { it.copy(activeRoomId = null, messages = emptyList()) }
    }

    suspend fun createGroup(userIds: Set<Int>, expectedSession: Mango9SessionLease? = sessions.captureSession()): Mango9ChatRoom? {
        val session = expectedSession?.takeIf { sessions.currentSession(it) != null } ?: return null
        if (userIds.size < 2) {
            updateError("Choose at least two teammates for a group.")
            return null
        }
        connect(expectedSession = session)
        if (!state.value.isConnected || sessions.currentSession(session) == null) return null
        return try {
            val users = JSONArray().apply { userIds.sorted().forEach { put(it.toString()) } }
            val room = parseRoom(rpc("createChatGroup", JSONArray().put(users)))
                ?: throw Mango9ChatException(INVALID_RESPONSE)
            if (sessions.currentSession(session) == null) return null
            mutableState.update { current ->
                current.copy(rooms = listOf(room) + current.rooms.filterNot { it.id == room.id })
            }
            room
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (sessions.currentSession(session) != null) updateError(userMessage(error))
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

    suspend fun sendChatMessage(
        text: String, attachments: List<Mango9PendingAttachment>,
        expectedSession: Mango9SessionLease? = sessions.captureSession(),
    ): Boolean {
        val lease = expectedSession?.takeIf { sessions.currentSession(it) != null } ?: return false
        val identity = lease.session.sipIdentity ?: return false
        val generation = connectionGeneration
        val body = text.trim()
        val roomId = state.value.activeRoomId ?: return false
        if (body.isEmpty() && attachments.isEmpty()) return false
        updateError(null)
        return try {
            val files = upload(attachments, lease, generation)
            ensureCurrent(generation, identity)
            rpc(
                "sendChatMessage",
                JSONArray().put(roomId).put(body).put(JSONArray(files)).put(UUID.randomUUID().toString().lowercase()),
            )
            ensureCurrent(generation, identity)
            moderation.setConversationDeleted(roomId, false)
            mutableState.update { current ->
                if (!isCurrentConnection(generation, identity)) {
                    current
                } else {
                    current.copy(
                    rooms = Mango9ConversationRecency.roomActivity(current.rooms, roomId, java.time.Instant.now().toString(), body)
                )
                }
            }
            runCatching { loadChatMessages(roomId) }.onFailure { error ->
                Log.w("[Mango9 Chat] Message sent, but conversation refresh failed: ${error.message}")
            }
            if (isCurrentConnection(generation, identity)) updateError(null)
            true
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (isCurrentConnection(generation, identity)) updateError(userMessage(error))
            false
        }
    }

    fun notifyTyping() {
        val roomId = state.value.activeRoomId ?: return
        val identity = sessions.activeIdentity ?: return
        val generation = connectionGeneration
        scope.launch {
            if (isCurrentConnection(generation, identity) && state.value.activeRoomId == roomId) {
                runCatching { rpc("notifyTyping", JSONArray().put(roomId)) }
            }
        }
    }

    suspend fun openSmsConversation(
        phone: String,
        owner: String = UUID.randomUUID().toString(),
        expectedSession: Mango9SessionLease? = sessions.captureSession(),
    ) {
        val session = expectedSession?.takeIf { sessions.currentSession(it) != null } ?: return
        closeConversation()
        val lease = smsOwnership.claim(owner, session.session.sipIdentity)
        val normalized = normalizedPhone(phone)
        if (normalized.isEmpty()) {
            updateError("Enter a valid mobile number.")
            return
        }
        mutableState.update {
            it.markSmsReadLocally(normalized).copy(
                activeSmsPhone = normalized,
                smsMessages = smsCache[Mango9SmsHistoryKey.from(session, normalized)].orEmpty(),
                errorMessage = null,
            )
        }
        connect(expectedSession = session)
        if (!state.value.isConnected || sessions.currentSession(session) == null ||
            !smsOwnership.owns(lease, sessions.activeIdentity)
        ) {
                return
            }
        mutableState.update { it.markSmsReadLocally(normalized).copy(activeSmsPhone = normalized) }
        try {
            loadSmsMessages(normalized)
            if (sessions.currentSession(session) != null && smsOwnership.owns(lease, sessions.activeIdentity) &&
                state.value.smsSenders.isEmpty()
            ) {
                    loadSmsDirectory()
                }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (sessions.currentSession(session) != null && smsOwnership.owns(lease, sessions.activeIdentity)) {
                updateError(userMessage(error))
            }
        }
    }

    fun closeOwnedSmsConversation(owner: String) {
        if (smsOwnership.release(owner)) closeSmsConversation()
    }

    fun ownsSmsConversation(owner: String): Boolean = smsOwnership.ownsOwner(owner, sessions.activeIdentity)

    fun closeSmsConversation(phone: String? = null) {
        if (phone != null && normalizedPhone(phone) != state.value.activeSmsPhone) return
        smsOwnership.invalidate()
        mutableState.update { it.copy(activeSmsPhone = null, smsMessages = emptyList()) }
    }

    suspend fun refreshSmsConversation() {
        state.value.activeSmsPhone?.let { phone ->
            runCatching { loadSmsMessages(phone) }.onFailure { updateError(userMessage(it)) }
        }
    }

    suspend fun refreshOwnedHistory(owner: String, lease: Mango9SessionLease) {
        if (sessions.currentSession(lease) == null || !state.value.isConnected) return
        try {
            if (ownsConversation(owner)) {
                state.value.activeRoomId?.let { loadChatMessages(it) }
            } else if (ownsSmsConversation(owner)) state.value.activeSmsPhone?.let { loadSmsMessages(it) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (sessions.currentSession(lease) != null && (ownsConversation(owner) || ownsSmsConversation(owner))) updateError(userMessage(e))
        }
    }

    suspend fun sendSms(
        phone: String,
        senderId: String,
        text: String,
        attachments: List<Mango9PendingAttachment>,
        expectedSession: Mango9SessionLease? = sessions.captureSession(),
    ): Boolean {
        val body = text.trim()
        if ((body.isEmpty() && attachments.isEmpty()) || senderId.isBlank()) return false
        val lease = expectedSession?.takeIf { sessions.currentSession(it) != null } ?: return false
        val identity = lease.session.sipIdentity ?: return false
        updateError(null)
        connect(expectedSession = lease)
        if (!state.value.isConnected || sessions.currentSession(lease) == null) return false
        val generation = connectionGeneration
        val mmsFiles = mutableListOf<Mango9PreparedAttachment>()
        return try {
            val normalized = normalizedPhone(phone)
            if (normalized.length < 10) {
                throw Mango9ChatException("Enter a valid mobile number.")
            }
            if (attachments.isNotEmpty()) {
                val budget = Mango9MmsPolicy.perAttachmentBudget(attachments.size)
                val preparer = Mango9AttachmentPreparer(appContext, mmsByteLimit = budget)
                for (attachment in attachments) {
                    ensureCurrent(generation, identity)
                    mmsFiles.add(preparer.prepare(attachment.uri, attachment.mimeType))
                }
                Mango9MmsPolicy.validateSizes(mmsFiles.map { it.attachment.size })
            }
            ensureCurrent(generation, identity)
            val files = upload(mmsFiles.map { it.attachment }, lease, generation)
            ensureCurrent(generation, identity)
            val messageId = UUID.randomUUID().toString().lowercase()
            val receipt = rpc(
                "sendSmsMessage",
                JSONArray()
                    .put(normalized)
                    .put(normalizedPhone(senderId))
                    .put(body)
                    .put(JSONArray(files))
                    .put(messageId),
            )
            ensureCurrent(generation, identity)
            val confirmation = receipt as? JSONObject
            if (confirmation?.optString("msgId") != messageId ||
                confirmation.optString("messageId").isBlank()
            ) {
                throw Mango9ChatException("SMS submission could not be confirmed. Your draft has been kept. Check the conversation before retrying.")
            }
            mutableState.update { current ->
                if (!isCurrentConnection(generation, identity)) {
                    current
                } else {
                    current.copy(
                    smsParties = Mango9ConversationRecency.smsActivity(current.smsParties, normalized, java.time.Instant.now().toString(), body)
                )
                }
            }
            updateError(null)
            // The send request has already been accepted. Do not keep the composer blocked while
            // two fallback refresh RPCs run; live WebSocket events normally update both views.
            scope.launch {
                if (!isCurrentConnection(generation, identity)) return@launch
                runCatching { loadSmsMessages(normalized) }.onFailure { error ->
                    Log.w("[Mango9 Chat] SMS sent, but conversation refresh failed: ${error.message}")
                }
                if (!isCurrentConnection(generation, identity)) return@launch
                runCatching { loadSmsDirectory() }.onFailure { error ->
                    Log.w("[Mango9 Chat] SMS sent, but directory refresh failed: ${error.message}")
                }
                delay(SMS_STATUS_RECONCILE_DELAY_MS)
                if (!isCurrentConnection(generation, identity)) return@launch
                runCatching { loadSmsMessages(normalized) }.onFailure { error ->
                    Log.w("[Mango9 Chat] SMS delivery refresh failed: ${error.message}")
                }
            }
            true
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (isCurrentConnection(generation, identity)) updateError(userMessage(error))
            false
        } finally {
            mmsFiles.forEach { it.close() }
        }
    }

    suspend fun sendSmsFromNotification(
        phone: String, text: String,
        expectedSession: Mango9SessionLease? = sessions.captureSession(),
    ): Boolean {
        val lease = expectedSession?.takeIf { sessions.currentSession(it) != null } ?: return false
        if (text.isBlank()) return false
        connect(expectedSession = lease)
        if (!state.value.isConnected || sessions.currentSession(lease) == null) return false
        if (state.value.smsSenders.isEmpty()) {
            runCatching { loadSmsDirectory() }.onFailure {
                if (it is CancellationException) throw it
                if (sessions.currentSession(lease) != null) updateError(userMessage(it))
            }
        }
        if (sessions.currentSession(lease) == null) return false
        val senderId = state.value.smsSenders.firstOrNull()?.senderId ?: run {
            updateError("No Mango9 SMS sender number is available.")
            return false
        }
        return sendSms(phone, senderId, text, emptyList(), lease)
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
        if (room.groupName.isNotBlank()) return room.groupName
        val names = room.userIds.map(::userName).filterNot { it == "Teammate" }
        return names.takeIf(List<String>::isNotEmpty)?.joinToString(", ") ?: "Team group"
    }

    fun userName(userId: Int): String = when (userId) {
        state.value.currentUserId -> "You"
        else -> state.value.users.firstOrNull { it.id == userId }?.name ?: "Teammate"
    }

    suspend fun groupDetails(roomId: String, lease: Mango9SessionLease): JSONObject {
        require(sessions.currentSession(lease) != null && roomId.toIntOrNull()?.let { it > 0 } == true)
        val result = rpc("getChatGroupDetails", JSONArray().put(roomId.toInt())) as? JSONObject
            ?: throw Mango9ChatException("Group details could not be confirmed.")
        if (sessions.currentSession(lease) == null) throw Mango9ApiException.Unauthorized
        require(result.optString("id") == roomId)
        return result
    }

    suspend fun renameGroup(roomId: String, name: String, previous: String, lease: Mango9SessionLease) {
        val clean = name.trim()
        require(clean.isNotEmpty() && clean.codePointCount(0, clean.length) <= 100 && clean.none { it.isISOControl() })
        if (sessions.currentSession(lease) == null) throw Mango9ApiException.Unauthorized
        val result = rpc("renameChatGroup", JSONArray().put(roomId.toInt()).put(clean).put(previous)) as? JSONObject
            ?: throw Mango9ChatException("Group name could not be confirmed.")
        if (sessions.currentSession(lease) == null) throw Mango9ApiException.Unauthorized
        require(result.optString("id") == roomId && result.optString("name") == clean)
        mutableState.update { current ->
            current.copy(
                rooms = current.rooms.map {
            if (it.id == roomId) it.copy(groupName = clean) else it
        }
            )
        }
    }

    internal fun applyReaction(channel: Mango9MessageChannel, id: String, snapshot: Mango9StaffReactions) {
        mutableState.update { current ->
            when (channel) {
                Mango9MessageChannel.team -> current.copy(
                    messages = current.messages.map {
                    if (it.id == id && snapshot.version >= it.reactions.version) it.copy(reactions = snapshot) else it
                }
                )
                Mango9MessageChannel.sms -> current.copy(
                    smsMessages = current.smsMessages.map {
                    if (it.id == id && snapshot.version >= it.reactions.version) it.copy(reactions = snapshot) else it
                }
                )
                else -> current
            }
        }
    }

    private suspend fun loadDirectory() {
        val generation = connectionGeneration
        val identity = sessions.activeIdentity ?: throw Mango9ChatException(DISCONNECTED)
        val initialRoomIds = state.value.rooms.map { it.id }.toSet()
        val rawUsers = rpc("getAllUsers", JSONArray())
        val rawRooms = rpc("getAllRooms", JSONArray())
        ensureCurrent(generation, identity)
        val currentUser = state.value.currentUserId
        val users = array(rawUsers).objects().mapNotNull(::parseUser)
            .filterNot { it.id == currentUser }
            .sortedBy { it.name.lowercase() }
        val allRooms = array(rawRooms).objects().mapNotNull(::parseRoom).sortedByDescending(Mango9ChatRoom::latest)
        val lease = currentConnectionSession() ?: throw Mango9ApiException.Unauthorized
        val hidden = messageCommands.visibility(lease, Mango9MessageChannel.team, null, allRooms.map { it.id }, false, true)
        ensureCurrent(generation, identity)
        val rooms = allRooms.filterNot { it.id in hidden }
        rooms.filter { it.unread > 0 && moderation.isConversationDeleted(it.id) }.forEach {
            moderation.setConversationDeleted(it.id, false)
        }
        mutableState.update { current ->
            val observed = current.rooms.associateBy { it.id }
            val merged = rooms.map { Mango9ConversationRecency.roomSnapshot(it, observed[it.id]) } +
                current.rooms.filter { it.id !in initialRoomIds && it.id !in allRooms.map { room -> room.id } && it.id !in hidden }
            val refreshed = current.copy(users = users, rooms = Mango9ConversationRecency.rooms(merged), errorMessage = null)
            current.activeRoomId?.let(refreshed::markRoomReadLocally) ?: refreshed
        }
        directoryReady = true
        // SMS/presence outages must not prevent Team Chat directory/history loading.
        scope.launch {
            runCatching {
                ensureCurrent(generation, identity)
                loadSmsDirectory()
            }.onFailure { if (it is CancellationException) throw it }
        }
        scope.launch {
            runCatching {
                ensureCurrent(generation, identity)
                val presence = rpc("getPresence", JSONArray())
                ensureCurrent(generation, identity)
                applyPresence(array(presence))
            }.onFailure { if (it is CancellationException) throw it }
        }
    }

    private suspend fun loadSmsDirectory() {
        val generation = connectionGeneration
        val identity = sessions.activeIdentity ?: throw Mango9ChatException(DISCONNECTED)
        val initialPhones = state.value.smsParties.map { it.phone }.toSet()
        val parties = array(rpc("getUserSmsParties", JSONArray())).objects().mapNotNull(::parseSmsParty)
            .sortedByDescending(Mango9SmsParty::latest)
        val senders = array(rpc("getSmsSenders", JSONArray())).objects().mapNotNull(::parseSmsSender)
        val lease = currentConnectionSession() ?: throw Mango9ApiException.Unauthorized
        val hidden = messageCommands.visibility(lease, Mango9MessageChannel.sms, null, parties.map { it.phone }, false, true)
        ensureCurrent(generation, identity)
        mutableState.update { current ->
            val observed = current.smsParties.associateBy { it.phone }
            val merged = parties.filterNot {
                it.phone in hidden
            }.map { Mango9ConversationRecency.smsSnapshot(it, observed[it.phone]) } +
                current.smsParties.filter {
                    it.phone !in initialPhones && it.phone !in parties.map { party -> party.phone } && it.phone !in hidden
                }
            val refreshed = current.copy(smsParties = Mango9ConversationRecency.sms(merged), smsSenders = senders)
            current.activeSmsPhone?.let(refreshed::markSmsReadLocally) ?: refreshed
        }
    }

    private suspend fun loadChatMessages(roomId: String) {
        val generation = connectionGeneration
        val identity = sessions.activeIdentity ?: throw Mango9ChatException(DISCONNECTED)
        val owner = conversationOwnership.snapshot() ?: return
        val result = rpc("getChatMessages", JSONArray().put(roomId).put(0)) as? JSONObject
            ?: throw Mango9ChatException(INVALID_RESPONSE)
        val messages = result.optJSONArray("list").orEmpty().objects().mapNotNull(::parseMessage)
            .sortedBy(Mango9ChatMessage::time)
        val lease = currentConnectionSession() ?: throw Mango9ApiException.Unauthorized
        val hidden = messageCommands.visibility(
            lease, Mango9MessageChannel.team, roomId,
            (messages + state.value.messages.filter { it.roomId == roomId }).map { it.id }, false
        )
        ensureCurrent(generation, identity)
        if (state.value.activeRoomId != roomId || !conversationOwnership.owns(owner, identity)) return
        mutableState.update { current ->
            if (current.activeRoomId != roomId || !isCurrentConnection(generation, identity) ||
                !conversationOwnership.owns(owner, identity)
            ) {
                current
            } else {
                current.copy(
                    messages = mergeChatMessages(messages, current.messages).filterNot { it.id in hidden },
                    rooms = messages.maxByOrNull { Mango9ConversationRecency.time(it.time) }?.let {
                        Mango9ConversationRecency.roomActivity(current.rooms, roomId, it.time, it.text)
                    } ?: current.rooms,
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
        ensureCurrent(generation, identity)
        mutableState.update { current ->
            if (current.activeRoomId == roomId && conversationOwnership.owns(owner, identity)) current.markRoomReadLocally(roomId) else current
        }
    }

    private suspend fun loadSmsMessages(phone: String) {
        val normalized = normalizedPhone(phone)
        val generation = connectionGeneration
        val lease = currentConnectionSession() ?: throw Mango9ChatException(DISCONNECTED)
        val identity = lease.session.sipIdentity ?: throw Mango9ChatException(DISCONNECTED)
        val key = Mango9SmsHistoryKey.from(lease, normalized)
        val owner = smsOwnership.snapshot()
        val result = rpc("getSmsMessages", JSONArray().put(normalized).put(0).put("json")) as? JSONObject
            ?: throw Mango9ChatException(INVALID_RESPONSE)
        ensureCurrent(generation, identity)
        val messages = result.optJSONArray("list").orEmpty().objects().mapNotNull(::parseSmsMessage)
            .map(::applyPendingSmsStatus)
            .sortedBy(Mango9SmsMessage::time)
        val hidden = messageCommands.visibility(
            lease, Mango9MessageChannel.sms, normalized,
            (messages + smsCache[key].orEmpty() + state.value.smsMessages.filter { it.phone == normalized }).map { it.id }, false
        )
        ensureCurrent(generation, identity)
        val merged = mergeSmsMessages(messages, smsCache[key].orEmpty()).filterNot { it.id in hidden }
        smsCache[key] = merged
        merged.maxByOrNull { Mango9ConversationRecency.time(it.time) }?.let { latest ->
            mutableState.update { current ->
                if (!isCurrentConnection(generation, identity)) {
                    current
                } else {
                    current.copy(
                    smsParties = Mango9ConversationRecency.smsActivity(current.smsParties, normalized, latest.time, latest.text)
                )
                }
            }
        }
        if (state.value.activeSmsPhone == normalized) {
            mutableState.update { current ->
                if (current.activeSmsPhone == normalized && isCurrentConnection(generation, identity) &&
                    owner != null && smsOwnership.owns(owner, identity)
                ) {
                    current.copy(
                        smsMessages = mergeSmsMessages(merged, current.smsMessages).filterNot {
                        it.id in hidden
                    },
                        errorMessage = null
                    )
                } else {
                    current
                }
            }
        }
    }

    private suspend fun rpc(method: String, params: JSONArray): Any {
        val identity = sessions.activeIdentity ?: throw Mango9ChatException(DISCONNECTED)
        val generation = connectionGeneration
        ensureCurrent(generation, identity)
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
            withTimeout(RPC_TIMEOUT_MS) { deferred.await() }.also { ensureCurrent(generation, identity) }
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
            if (!isCurrentConnection(generation, identity)) {
                webSocket.close(1000, null)
                opened.completeExceptionally(Mango9ChatException(DISCONNECTED))
                return
            }
            opened.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrentConnection(generation, identity) || text.length > MAX_SOCKET_MESSAGE_CHARS) return
            handleSocketText(text, identity)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (isCurrentConnection(generation, identity) && !intentionallyDisconnected) handleSocketFailure(generation)
        }

        override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
            opened.completeExceptionally(error)
            if (isCurrentConnection(generation, identity) && !intentionallyDisconnected) handleSocketFailure(generation)
        }
    }

    private fun handleSocketText(text: String, identity: String) {
        if (!sessions.isActive(identity)) return
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        val id = if (json.isNull("id")) null else json.optString("id").takeIf(String::isNotBlank)
        if (json.optString("method").isBlank() && id != null) {
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
            "updateChatReaction", "updateSmsReaction" -> params.optJSONObject(0)?.let { snapshot ->
                val channel = if (method == "updateSmsReaction") Mango9MessageChannel.sms else Mango9MessageChannel.team
                snapshot.string("messageId")?.let { applyReaction(channel, it, Mango9StaffReactions.parse(snapshot)) }
            }
        }
        if (id != null) {
            socket?.send(
                JSONObject().put("jsonrpc", "2.0").put("id", json.get("id"))
                .put("result", JSONObject.NULL).toString()
            )
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
                room.copy(
                    latest = if (Mango9ConversationRecency.newer(message.time, room.latest)) message.time else room.latest,
                    lastMessage = if (Mango9ConversationRecency.time(message.time) >= Mango9ConversationRecency.time(room.latest)) message.text else room.lastMessage,
                    unread = unread,
                )
            }.let(Mango9ConversationRecency::rooms)
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
            val merged = Mango9ConversationRecency.roomSnapshot(room, current.rooms.firstOrNull { it.id == room.id })
            current.copy(rooms = Mango9ConversationRecency.rooms(current.rooms.filterNot { it.id == room.id } + merged))
        }
    }

    private fun upsertSmsMessage(message: Mango9SmsMessage) {
        val lease = currentConnectionSession() ?: return
        val resolved = applyPendingSmsStatus(message)
        val key = Mango9SmsHistoryKey.from(lease, resolved.phone)
        val cached = mergeSmsMessages(listOf(resolved), smsCache[key].orEmpty())
        smsCache[key] = cached
        mutableState.update { current ->
            if (sessions.currentSession(lease) == null) return@update current
            val updated = current.copy(
                smsMessages = if (current.activeSmsPhone == normalizedPhone(resolved.phone)) cached else current.smsMessages,
                smsParties = Mango9ConversationRecency.smsActivity(current.smsParties, resolved.phone, resolved.time, resolved.text),
                errorMessage = null,
            )
            if (current.activeSmsPhone == normalizedPhone(resolved.phone)) {
                updated.markSmsReadLocally(resolved.phone)
            } else {
                updated
            }
        }
        scope.launch {
            if (sessions.currentSession(lease) != null) runCatching { loadSmsDirectory() }
        }
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

    private suspend fun upload(
        attachments: List<Mango9PendingAttachment>, lease: Mango9SessionLease, generation: Long,
    ): List<String> {
        if (attachments.isEmpty()) return emptyList()
        if (attachments.size > MAX_ATTACHMENTS) throw Mango9ChatException("You can attach up to $MAX_ATTACHMENTS files.")
        val identity = lease.session.sipIdentity ?: throw Mango9ChatException(DISCONNECTED)
        val sendingJob = currentCoroutineContext()

        fun checkOwner() {
            sendingJob.ensureActive()
            if (sessions.currentSession(lease) == null) throw CancellationException("The sending account changed.")
            ensureCurrent(generation, identity)
        }
        checkOwner()
        refreshUploadTokenIfNeeded(lease, generation)
        checkOwner()
        val session = sessions.currentSession(lease) ?: throw Mango9ChatException(DISCONNECTED)
        val base = Mango9Configuration.verifiedHttpsUrl(session.smsChatApi)
            ?: throw Mango9ChatException("The Mango9 attachment endpoint is not configured.")
        val uploadUrl = base.toString().trimEnd('/') + "/upload"
        val token = chatToken ?: throw Mango9ChatException(DISCONNECTED)
        return withContext(Dispatchers.IO) {
            attachments.map { attachment ->
                checkOwner()
                if (attachment.size <= 0 || attachment.size > MAX_ATTACHMENT_BYTES) {
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
                val prepared = uploadClient.newCall(prepare).mango9UploadResult { response ->
                    if (!response.isSuccessful) throw Mango9ChatException("The attachment upload could not be prepared.")
                    val bytes = response.mango9UploadBytes(MAX_HTTP_RESPONSE_BYTES)
                    JSONObject(bytes.toString(Charsets.UTF_8))
                }
                checkOwner()
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
                    .put(Mango9AttachmentRequestBody(appContext, attachment, ::checkOwner))
                    .build()
                uploadClient.newCall(put).mango9UploadResult { response ->
                    if (!response.isSuccessful) throw Mango9ChatException("The attachment could not be uploaded.")
                }
                checkOwner()
                verifiedGetUrl
            }
        }
    }

    private suspend fun refreshUploadTokenIfNeeded(lease: Mango9SessionLease, generation: Long) {
        val identity = lease.session.sipIdentity ?: throw Mango9ChatException(DISCONNECTED)
        ensureCurrent(generation, identity)
        if (chatToken != null && chatTokenExpiresAtMillis - System.currentTimeMillis() > 60_000L) return
        val bootstrap = crm.chatBootstrap(lease)
        ensureCurrent(generation, identity)
        chatToken = bootstrap.token
        chatTokenExpiresAtMillis = System.currentTimeMillis() + bootstrap.expiresIn.coerceAtLeast(0) * 1000L
    }

    private fun handleSocketFailure(generation: Long) {
        if (connectionGeneration != generation) return
        if (connectedAt != 0L && System.nanoTime() - connectedAt > 30_000_000_000L) reconnectAttempts = 0
        connectedAt = 0
        socket?.cancel(); socket = null
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
        if (intentionallyDisconnected || reconnectJob?.isActive == true || sessions.load() == null || reconnectAttempts >= 6) return
        reconnectAttempts++
        val backoff = kotlin.random.Random.nextLong(1000, (1000L shl reconnectAttempts).coerceAtMost(30_000) + 1)
        val pause = maxOf(backoff, (reconnectNotBefore - System.nanoTime()) / 1_000_000L)
        reconnectJob = scope.launch {
            delay(pause)
            reconnectJob = null
            connect(force = true, automaticRetry = true)
        }
    }

    private fun disconnectInternal(clearData: Boolean, intentional: Boolean) {
        directoryReady = false
        intentionallyDisconnected = intentional
        reconnectJob?.cancel()
        reconnectJob = null
        connectingIdentity = null
        socket?.close(1000, null)
        socket = null
        failPending(Mango9ChatException(DISCONNECTED))
        chatToken = null
        connectionSession = null
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
        if (!isCurrentConnection(generation, identity)) throw Mango9ChatException(DISCONNECTED)
    }

    private fun isCurrentConnection(generation: Long, identity: String): Boolean {
        if (connectionGeneration != generation) return false
        val lease = connectionSession ?: return false
        return lease.session.sipIdentity == identity && sessions.currentSession(lease) != null
    }

    private fun ensureActiveIdentity() {
        val identity = state.value.connectedIdentity ?: throw Mango9ChatException(DISCONNECTED)
        ensureCurrent(connectionGeneration, identity)
    }

    private fun updateError(message: String?) {
        mutableState.update { it.copy(errorMessage = message) }
    }

    private fun userMessage(error: Throwable): String = when (error) {
        is Mango9AttachmentFailure -> error.userMessage
        is Mango9ApiException -> error.userMessage
        is Mango9ChatException -> error.message ?: DISCONNECTED
        else -> "Mango9 messaging is temporarily unavailable. Please try again."
    }

    private val deviceId: String
        get() = Mango9MessageDeviceIdentity.get(appContext)

    private data class PendingCall(
        val method: String,
        val deferred: CompletableDeferred<Any>,
        val identity: String,
    )

    private class Mango9ChatException(message: String) : Exception(message)

    companion object {
        private const val WEBSOCKET_PROTOCOL_HEADER = "Sec-WebSocket-Protocol"
        private const val RPC_TIMEOUT_MS = 20_000L
        private const val RECONNECT_DELAY_MS = 2_000L
        private const val SMS_STATUS_RECONCILE_DELAY_MS = 5_000L
        private const val TYPING_EXPIRY_MS = 3_500L
        private const val MAX_SOCKET_MESSAGE_CHARS = 2 * 1024 * 1024
        private const val MAX_HTTP_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val MAX_ATTACHMENTS = 12
        private const val MAX_ATTACHMENT_BYTES = 50L * 1024L * 1024L
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
                json.optString("name").takeUnless { it == "null" }.orEmpty(),
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
                Mango9StaffReactions.parse(json),
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
                Mango9StaffReactions.parse(json),
            )
        }

        internal fun mergeChatMessages(
            serverMessages: List<Mango9ChatMessage>,
            liveMessages: List<Mango9ChatMessage>,
        ): List<Mango9ChatMessage> = (serverMessages + liveMessages)
            .groupBy(Mango9ChatMessage::id).values.map { versions ->
                versions.last().copy(reactions = versions.maxBy { it.reactions.version }.reactions)
            }
            .sortedBy(Mango9ChatMessage::time)

        internal fun mergeSmsMessages(
            serverMessages: List<Mango9SmsMessage>,
            liveMessages: List<Mango9SmsMessage>,
        ): List<Mango9SmsMessage> = (serverMessages + liveMessages)
            .groupBy(Mango9SmsMessage::id)
            .values
            .map { versions ->
                versions.last().copy(
                    reactions = versions.maxBy { it.reactions.version }.reactions,
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
