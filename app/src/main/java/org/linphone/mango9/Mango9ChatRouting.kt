/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.mango9

import android.content.Context
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.suspendCancellableCoroutine
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.Address
import org.linphone.core.tools.Log

data class Mango9ScopedMessageRoute<T>(val target: T, val session: Mango9SessionLease, val shareRequestId: String? = null)

/** Routes Mango9 teammate addresses away from SIP chat and into the CRM chat transport. */
object Mango9ChatRouting {
    private val mutableOpenRequests =
        MutableSharedFlow<Mango9ScopedMessageRoute<Mango9ChatTarget>>(extraBufferCapacity = 8)
    val openRequests = mutableOpenRequests.asSharedFlow()

    fun openIfNeeded(remote: Address, shareRequestId: String? = null): Boolean {
        val target = coreContext.contactsManager.mango9ChatTarget(remote) ?: return false
        Log.i("[Mango9 Chat Routing] Routing [${remote.asStringUriOnly()}] to Mango9 user [${target.userId}]")
        open(target, shareRequestId)
        return true
    }

    fun open(target: Mango9ChatTarget, shareRequestId: String? = null) {
        val session = Mango9SessionStore(coreContext.context).captureSession() ?: return
        mutableOpenRequests.tryEmit(Mango9ScopedMessageRoute(target, session, shareRequestId))
    }
}

/** Routes external call-history and contact phone numbers through Mango9 SMS instead of SIP chat. */
object Mango9SmsRouting {
    private const val TAG = "[Mango9 SMS Routing]"
    private val mutableOpenRequests =
        MutableSharedFlow<Mango9ScopedMessageRoute<Mango9SmsTarget>>(extraBufferCapacity = 8)
    val openRequests = mutableOpenRequests.asSharedFlow()

    fun openIfNeeded(remote: Address, displayName: String? = null, shareRequestId: String? = null): Boolean {
        val sessions = Mango9SessionStore(coreContext.context)
        val identity = Mango9SessionStore.normalizedIdentity(
            coreContext.core.defaultAccount?.params?.identityAddress?.asStringUriOnly(),
        )
        if (!sessions.isActive(identity) || sessions.load(identity) == null) return false
        val session = sessions.captureSession() ?: return false

        val contactName = displayName?.trim().orEmpty().ifBlank {
            coreContext.contactsManager.findContactByAddress(remote)?.name?.trim().orEmpty()
        }
        val target = Mango9SmsRoutePolicy.target(remote.asStringUriOnly(), contactName) ?: return false
        Log.i("$TAG Routing [${remote.asStringUriOnly()}] to Mango9 SMS [${target.phone}]")
        mutableOpenRequests.tryEmit(Mango9ScopedMessageRoute(target, session, shareRequestId))
        return true
    }
}

internal object Mango9SmsRoutePolicy {
    fun target(rawAddress: String, displayName: String?): Mango9SmsTarget? {
        val externalNumber = Mango9CallerIdentity.externalPhoneNumber(rawAddress) ?: return null
        val phone = Mango9PhoneNumber.smsDestination(externalNumber) ?: return null
        val name = displayName?.trim().orEmpty().ifBlank {
            Mango9CallerIdentity.formattedPhoneNumber(phone)
        }
        return Mango9SmsTarget(phone, name)
    }
}

/**
 * Switches the CRM session, teammate directory, and chat socket as one account-scoped operation.
 * A persisted directory from another account is cleared before any network request is made.
 */
object Mango9AccountContextSync {
    private const val TAG = "[Mango9 Account Context]"

    private data class Refresh(val context: Context, val lease: Mango9SessionLease)

    private val refreshes = Mango9LatestAccountRefresh<Refresh>(
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
        directory = { refreshDirectory(it.context, Mango9SessionStore(it.context), it.lease) },
        connect = { request ->
            runMango9MessagePushWork(onFailure = { Log.w("$TAG Chat refresh failed") }) {
                if (Mango9SessionStore(request.context).currentSession(request.lease) != null) {
                    Mango9ChatStore.get(request.context).connect(expectedSession = request.lease)
                }
            }
        },
    )

    suspend fun refresh(context: Context) {
        val appContext = context.applicationContext
        val sessions = Mango9SessionStore(appContext)
        onCoreThread {
            // Read the current native selection at execution time, never reactivate a stale request.
            val identity = defaultAccountIdentity()
            sessions.activate(identity)
            coreContext.contactsManager.activateMango9TeamIdentity(identity)
            if (identity == null || sessions.load(identity) == null) {
                coreContext.contactsManager.clearMango9Team(identity)
                Mango9ChatStore.get(appContext).disconnect(clearData = true)
            }
            // Reserve work in native selection order, before resuming onto a caller's dispatcher.
            // A removed/third-party selection must also cancel the previous CRM request.
            val lease = sessions.captureSession()
            if (lease == null) refreshes.cancel() else refreshes.refresh(Refresh(appContext, lease))
        }
    }

    private suspend fun refreshDirectory(
        context: Context,
        sessions: Mango9SessionStore,
        lease: Mango9SessionLease,
    ) {
        val identity = lease.session.sipIdentity ?: return
        if (sessions.currentSession(lease) == null) return
        try {
            val members = Mango9CrmRepository(context).teamMembers(lease)
            onCoreThread {
                if (sessions.currentSession(lease) != null && defaultAccountIdentity() == identity) {
                    coreContext.contactsManager.syncMango9Team(identity, members)
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.e("$TAG Failed to refresh the active Mango9 account context: $error")
        }
    }

    /** Local selection only: the destination shows loading while messaging connects independently. */
    suspend fun activateStoredAccount(
        context: Context,
        login: Mango9LoginLease,
        ticket: Mango9OpenRequestGate.Ticket,
    ): Mango9SessionLease? {
        val identity = login.session.sipIdentity ?: return null
        val sessions = Mango9SessionStore(context.applicationContext)
        val selection = sessions.selectionVersion
        return onCoreThread {
            ticket.ifCurrent {
                val account = coreContext.core.accountList.firstOrNull {
                    Mango9SessionStore.normalizedIdentity(it.params.identityAddress?.asStringUriOnly()) == identity
                } ?: return@ifCurrent null
                sessions.activateLoginIfCurrent(login, selection) {
                    coreContext.core.defaultAccount = account
                    coreContext.contactsManager.activateMango9TeamIdentity(identity)
                }
            }
        }
    }

    private fun defaultAccountIdentity(): String? = Mango9SessionStore.normalizedIdentity(
        coreContext.core.defaultAccount?.params?.identityAddress?.asStringUriOnly(),
    )

    private suspend fun <T> onCoreThread(action: () -> T): T = suspendCancellableCoroutine { continuation ->
        coreContext.postOnCoreThread {
            if (!continuation.isActive) return@postOnCoreThread
            try {
                val result = action()
                if (continuation.isActive) continuation.resume(result)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    }
}
