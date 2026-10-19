// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.ChatRoom
import org.linphone.core.ChatRoomListenerStub
import org.linphone.core.GlobalState
import org.linphone.utils.LinphoneUtils

/** Core-thread callbacks retain their request, account and login, not the newest UI selection. */
internal class Mango9ChatRoomNavigation(
    private val completed: (Mango9NativeConversationRoute?) -> Unit,
) {
    private val pending = mutableMapOf<Long, Pair<ChatRoom, ChatRoomListenerStub>>()

    @Volatile private var disposed = false

    fun watch(room: ChatRoom, request: String?) {
        if (disposed) return
        val core = coreContext.core
        val account = room.account ?: return
        val sessions = Mango9SessionStore(coreContext.context)
        val stamp = sessions.selectionStamp()

        fun current() = !disposed && core.globalState == GlobalState.On &&
            core.defaultAccount?.nativePointer == account.nativePointer &&
            core.accountList.any { it.nativePointer == account.nativePointer } && sessions.selectionStamp() == stamp
        pending.remove(room.nativePointer)?.let { it.first.removeListener(it.second) }

        fun finish(success: Boolean) {
            pending.remove(room.nativePointer)?.let { it.first.removeListener(it.second) }
            if (current()) completed(if (success) Mango9NativeConversationRoute(LinphoneUtils.getConversationId(room), request) else null)
        }
        if (room.state == ChatRoom.State.Created) { finish(true); return }
        if (room.state == ChatRoom.State.CreationFailed) { finish(false); return }
        val listener = object : ChatRoomListenerStub() {
            override fun onStateChanged(chatRoom: ChatRoom, newState: ChatRoom.State?) {
                if (newState == ChatRoom.State.Created) {
                    finish(true)
                } else if (newState == ChatRoom.State.CreationFailed || !current()) finish(false)
            }
        }
        pending[room.nativePointer] = room to listener
        room.addListener(listener)
    }

    fun dispose() {
        disposed = true
        coreContext.postOnCoreThread {
            pending.values.forEach { it.first.removeListener(it.second) }
            pending.clear()
        }
    }
}
