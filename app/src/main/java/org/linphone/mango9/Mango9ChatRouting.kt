/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.mango9

import android.content.Context
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.Address
import org.linphone.core.tools.Log

/** Routes Mango9 teammate addresses away from SIP chat and into the CRM chat transport. */
object Mango9ChatRouting {
    private val mutableOpenRequests = MutableSharedFlow<Mango9ChatTarget>(extraBufferCapacity = 8)
    val openRequests = mutableOpenRequests.asSharedFlow()

    fun openIfNeeded(remote: Address): Boolean {
        val target = coreContext.contactsManager.mango9ChatTarget(remote) ?: return false
        Log.i("[Mango9 Chat Routing] Routing [${remote.asStringUriOnly()}] to Mango9 user [${target.userId}]")
        mutableOpenRequests.tryEmit(target)
        return true
    }

    fun open(target: Mango9ChatTarget) {
        mutableOpenRequests.tryEmit(target)
    }
}

/**
 * Switches the CRM session, teammate directory, and chat socket as one account-scoped operation.
 * A persisted directory from another account is cleared before any network request is made.
 */
object Mango9AccountContextSync {
    private const val TAG = "[Mango9 Account Context]"
    private val mutex = Mutex()

    suspend fun refresh(context: Context, requestedIdentity: String? = null) = mutex.withLock {
        val appContext = context.applicationContext
        val identity = Mango9SessionStore.normalizedIdentity(requestedIdentity ?: defaultAccountIdentity())
        val sessions = Mango9SessionStore(appContext)
        sessions.activate(identity)

        onCoreThread {
            coreContext.contactsManager.activateMango9TeamIdentity(identity)
        }

        if (identity == null || sessions.load(identity) == null) {
            onCoreThread {
                coreContext.contactsManager.clearMango9Team(identity)
            }
            Mango9ChatStore.get(appContext).disconnect(clearData = true)
            return@withLock
        }

        try {
            val members = Mango9CrmRepository(appContext).teamMembers()
            if (!sessions.isActive(identity) || defaultAccountIdentity() != identity) return@withLock
            onCoreThread {
                coreContext.contactsManager.syncMango9Team(identity, members)
            }
            Mango9ChatStore.get(appContext).connect(force = true)
        } catch (error: Exception) {
            Log.e("$TAG Failed to refresh the active Mango9 account context: $error")
        }
    }

    suspend fun activateStoredAccount(context: Context, requestedIdentity: String): Boolean {
        val identity = Mango9SessionStore.normalizedIdentity(requestedIdentity) ?: return false
        val sessions = Mango9SessionStore(context.applicationContext)
        if (!sessions.hasSession(identity)) return false
        val accountFound = onCoreThread {
            val account = coreContext.core.accountList.firstOrNull {
                Mango9SessionStore.normalizedIdentity(
                    it.params.identityAddress?.asStringUriOnly(),
                ) == identity
            }
            if (account != null) coreContext.core.defaultAccount = account
            account != null
        }
        if (!accountFound) return false
        refresh(context, identity)
        return sessions.isActive(identity)
    }

    private suspend fun defaultAccountIdentity(): String? = suspendCancellableCoroutine { continuation ->
        coreContext.postOnCoreThread { core ->
            val identity = Mango9SessionStore.normalizedIdentity(
                core.defaultAccount?.params?.identityAddress?.asStringUriOnly(),
            )
            if (continuation.isActive) continuation.resume(identity)
        }
    }

    private suspend fun <T> onCoreThread(action: () -> T): T = suspendCancellableCoroutine { continuation ->
        coreContext.postOnCoreThread {
            val result = action()
            if (continuation.isActive) continuation.resume(result)
        }
    }
}
