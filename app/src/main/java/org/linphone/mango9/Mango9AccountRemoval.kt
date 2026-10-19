/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import androidx.annotation.WorkerThread
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.Account
import org.linphone.core.AuthInfo
import org.linphone.core.Core

/** Core-thread-only logout; no network request or screen lifetime can defer local removal. */
internal object Mango9AccountRemoval {
    private class Retirement(val auth: AuthInfo)

    private val retiringAuth = mutableMapOf<Long, Retirement>()

    @WorkerThread
    fun remove(core: Core, account: Account) {
        if (core.accountList.none { it.nativePointer == account.nativePointer }) return
        val auth = account.findAuthInfo()
        // Snapshot only this account's data before removing its association from Core.
        val rooms = account.chatRooms
        val conferences = account.conferenceInformationList
        val callLogs = account.callLogs
        // Core removes it from the public account list immediately and sends authenticated
        // unREGISTER using its deleted-account lifecycle. Do not erase auth before that exchange.
        core.removeAccount(account)
        rooms.forEach(core::deleteChatRoom)
        conferences.forEach(core::deleteConferenceInformation)
        callLogs.forEach(core::removeCallLog)
        if (auth != null) {
            val pointer = auth.nativePointer
            // Retain the wrapper/native object too: a bare pointer can be reused after GC.
            val retirement = Retirement(auth)
            retiringAuth[pointer] = retirement
            coreContext.postOnCoreThreadDelayed({ current ->
                if (retiringAuth[pointer] !== retirement) return@postOnCoreThreadDelayed
                retiringAuth.remove(pointer)
                removeRetiredAuth(current, retirement.auth)
            }, 5_000L)
        }
    }

    @WorkerThread
    internal fun removeRetiredAuth(core: Core, original: AuthInfo) {
        val pointer = original.nativePointer
        // Never resolve auth by the old username at callback time: it may now be a fresh login.
        val retained = core.authInfoList.firstOrNull { it.nativePointer == pointer } ?: return
        if (core.accountList.any { it.findAuthInfo()?.nativePointer == pointer }) return
        core.removeAuthInfo(retained)
    }
}
