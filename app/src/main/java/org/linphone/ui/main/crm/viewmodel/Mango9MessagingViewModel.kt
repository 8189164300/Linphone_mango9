/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.ui.main.crm.viewmodel

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import java.util.UUID
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.mango9.Mango9ChatModerationStore
import org.linphone.mango9.Mango9ChatRoom
import org.linphone.mango9.Mango9ChatState
import org.linphone.mango9.Mango9ChatStore
import org.linphone.mango9.Mango9ConversationInsightsRepository
import org.linphone.mango9.Mango9SmsConversationInsights
import org.linphone.mango9.Mango9SessionStore
import org.linphone.mango9.Mango9SessionLease
import org.linphone.mango9.Mango9ApiException
import org.linphone.mango9.Mango9ScopedMessageRoute
import org.linphone.mango9.Mango9MediaAsset
import org.linphone.mango9.Mango9MessageComposer
import org.linphone.ui.GenericViewModel
import org.linphone.utils.Event

class Mango9MessagingViewModel : GenericViewModel() {
    private val store = Mango9ChatStore.get(coreContext.context)
    private val insights = Mango9ConversationInsightsRepository(coreContext.context)
    private val sessions = Mango9SessionStore(coreContext.context)
    private var conversationBound = false
    private var conversationSession: Mango9SessionLease? = null
    val moderation = Mango9ChatModerationStore(coreContext.context)
    private var presentedSession = store.currentConnectionSession()
    val state = MutableLiveData(scopedState(store.state.value))
    internal val composer by lazy { Mango9MessageComposer(coreContext.context, viewModelScope, ::isCurrentConversation) }
    val openingConversation = MutableLiveData(false)
    private val conversationOwner = UUID.randomUUID().toString()
    private var openingJob: Job? = null
    private var openingAttempt = 0L
    private var openedDocument: Mango9MediaAsset? = null
    private var inlineAudio: Mango9InlineAudioController? = null

    internal fun audioController(): Mango9InlineAudioController = inlineAudio ?: Mango9InlineAudioController(
        viewModelScope, ::isCurrentConversation,
        { media -> org.linphone.mango9.Mango9MediaCache.acquire(coreContext.context, media, requireNotNull(mediaSession())) },
        Mango9PlatformInlineAudioFocus(coreContext.context),
    ).also { inlineAudio = it }

    val openedRoomEvent = MutableLiveData<Event<Mango9ScopedMessageRoute<Mango9ChatRoom>>>()
    val conversationInsightsEvent = MutableLiveData<Event<Mango9SmsConversationInsights>>()

    init {
        viewModelScope.launch {
            combine(store.state, Mango9SessionStore.contextChanges) { snapshot, _ -> scopedState(snapshot) }
                .collect { snapshot ->
                    if (conversationBound && !isCurrentConversation()) {
                        composer.dispose()
                        inlineAudio?.clear()
                        releaseOpenedDocument()
                        openingJob?.cancel()
                        store.closeOwnedConversation(conversationOwner)
                        store.closeOwnedSmsConversation(conversationOwner)
                    }
                    presentedSession = store.currentConnectionSession()
                    state.value = snapshot
                }
        }
    }

    fun bindConversation(lease: Mango9SessionLease?) {
        if (conversationBound) return
        conversationBound = true
        conversationSession = lease
        state.value = scopedState(store.state.value)
    }

    fun isCurrentConversation(): Boolean = conversationSession?.let { sessions.currentSession(it) != null } == true

    internal fun mediaSession(): Mango9SessionLease? = conversationSession?.takeIf { isCurrentConversation() }

    internal fun holdOpenedDocument(asset: Mango9MediaAsset) {
        releaseOpenedDocument()
        openedDocument = asset
    }

    internal fun releaseOpenedDocument() { openedDocument?.close(); openedDocument = null }

    override fun onCleared() {
        composer.dispose()
        store.closeOwnedConversation(conversationOwner)
        store.closeOwnedSmsConversation(conversationOwner)
        inlineAudio?.dispose(); releaseOpenedDocument(); super.onCleared()
    }

    private fun scopedState(snapshot: Mango9ChatState): Mango9ChatState = when {
        !conversationBound -> if (snapshot.connectedIdentity != null && store.currentConnectionSession() == null) Mango9ChatState() else snapshot
        !isCurrentConversation() -> Mango9ChatState(errorMessage = Mango9ApiException.Unauthorized.userMessage)
        snapshot.connectedIdentity != null && (
            store.currentConnectionSession() == null ||
            snapshot.connectedIdentity != conversationSession?.session?.sipIdentity
        ) -> Mango9ChatState()
        else -> snapshot
    }

    fun connect(force: Boolean = false) {
        viewModelScope.launch { store.connect(force) }
    }

    fun refresh() {
        viewModelScope.launch { store.refreshDirectory() }
    }

    suspend fun refreshConversationHistory() {
        val lease = conversationSession ?: return
        if (isCurrentConversation()) store.refreshOwnedHistory(conversationOwner, lease)
    }

    fun createGroup(userIds: Set<Int>, session: Mango9SessionLease) {
        if (sessions.currentSession(session) == null) return
        viewModelScope.launch {
            val room = store.createGroup(userIds, session) ?: return@launch
            if (sessions.currentSession(session) != null) {
                openedRoomEvent.value = Event(Mango9ScopedMessageRoute(room, session))
            }
        }
    }

    fun openTeamConversation(userId: Int, roomId: String?, name: String) {
        if (!isCurrentConversation()) return
        val session = conversationSession
        openingJob?.cancel()
        val attempt = ++openingAttempt
        openingConversation.value = true
        openingJob = viewModelScope.launch {
            try {
                if (roomId.isNullOrBlank()) {
                    store.openDirectConversation(userId, name, conversationOwner, session)
                } else {
                    store.openRoom(roomId, conversationOwner, session)
                }
            } finally {
                if (openingAttempt == attempt) openingConversation.value = false
            }
        }
    }

    fun openSmsConversation(phone: String) {
        if (!isCurrentConversation()) return
        val session = conversationSession
        openingJob?.cancel()
        val attempt = ++openingAttempt
        openingConversation.value = true
        openingJob = viewModelScope.launch {
            try {
                store.openSmsConversation(phone, conversationOwner, session)
            } finally {
                if (openingAttempt == attempt) openingConversation.value = false
            }
        }
    }

    fun closeTeamConversation(roomId: String?) {
        openingJob?.cancel()
        store.closeOwnedConversation(conversationOwner)
    }

    fun closeSmsConversation(phone: String?) {
        openingJob?.cancel()
        store.closeOwnedSmsConversation(conversationOwner)
    }

    fun sendTeamDraft() {
        if (!ownsTeamConversation()) return
        val session = conversationSession
        composer.send { text, attachments ->
            if (ownsTeamConversation()) store.sendChatMessage(text, attachments, session) else false
        }
    }

    fun sendSmsDraft(phone: String, senderId: String) {
        if (!ownsSmsConversation()) return
        val session = conversationSession
        composer.send { text, attachments ->
            if (ownsSmsConversation()) store.sendSms(phone, senderId, text, attachments, session) else false
        }
    }

    fun notifyTyping() {
        if (isCurrentConversation() && store.ownsConversation(conversationOwner)) store.notifyTyping()
    }

    fun ownsTeamConversation(): Boolean = isCurrentConversation() && store.ownsConversation(conversationOwner)

    fun ownsSmsConversation(): Boolean = isCurrentConversation() && store.ownsSmsConversation(conversationOwner)

    fun navigationSession(): Mango9SessionLease? = presentedSession?.takeIf { sessions.currentSession(it) != null }

    fun roomTitle(room: Mango9ChatRoom): String = store.roomTitle(room)

    fun userName(userId: Int): String = store.userName(userId)

    fun updateGroupMembers(roomId: String, userIds: Set<Int>, done: (Boolean) -> Unit) {
        if (!isCurrentConversation()) return
        viewModelScope.launch {
            if (!isCurrentConversation()) return@launch
            val saved = store.updateGroupMembers(roomId, userIds)
            coreContext.postOnMainThread { if (isCurrentConversation()) done(saved) }
        }
    }

    fun deleteConversation(roomId: String) {
        if (!conversationBound || isCurrentConversation()) store.deleteConversationLocally(roomId)
    }

    fun clearError() {
        if (!conversationBound || isCurrentConversation()) store.clearError()
    }

    fun currentState(): Mango9ChatState = scopedState(store.state.value)

    fun loadConversationInsights(phone: String) {
        if (!isCurrentConversation()) return
        val messages = store.state.value.smsMessages.toList()
        viewModelScope.launch {
            val result = insights.load(phone, messages)
            if (isCurrentConversation()) conversationInsightsEvent.value = Event(result)
        }
    }
}
