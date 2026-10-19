/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.WorkerThread
import androidx.core.content.ContextCompat
import androidx.core.text.isDigitsOnly
import org.linphone.compatibility.Compatibility
import org.linphone.core.Account
import org.linphone.core.Address
import org.linphone.core.CallParams
import org.linphone.core.Core
import org.linphone.core.GlobalState
import org.linphone.core.MediaEncryption
import org.linphone.core.tools.Log
import org.linphone.utils.LinphoneUtils

/** SDK values and all call side effects stay on the core queue, including permission replies. */
internal class Mango9OutgoingCalls(
    private val core: Core,
    private val sessions: Mango9SessionStore,
    private val microphoneGranted: () -> Boolean,
    private val requestMicrophone: (Long) -> Unit,
    private val cannotStart: () -> Unit,
    private val dial: (Address, CallParams) -> Boolean = { address, params ->
        core.inviteAddressWithParams(address, params) != null
    },
    private val pauseCurrentCall: () -> Unit = { core.currentCall?.pause(); Unit },
) {
    companion object {
        fun hasMicrophone(context: Context): Boolean = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private data class Request(
        val account: Account?,
        val identity: String?,
        val defaultAccount: Account?,
        val session: Mango9SessionStore.CallLease,
        val skipNetworkCheck: Boolean,
        val completed: (Boolean) -> Unit,
        val action: () -> Boolean,
    )

    private val pending = Mango9PendingCall<Request>()

    fun isPending(id: Long): Boolean = pending.matches(id)

    @WorkerThread fun start(
        address: Address,
        callParams: CallParams? = null,
        forceZRTP: Boolean = false,
        localAddress: Address? = null,
        skipNetworkReachabilityTest: Boolean = false,
        onResult: (Boolean) -> Unit = {},
    ) {
        if (pending.hasPending()) { onResult(false); return }
        if (core.globalState != GlobalState.On || (!skipNetworkReachabilityTest && !core.isNetworkReachable)) {
            onResult(false)
            return
        }
        // An explicit From account is never replaced by a default account. Also retain a
        // caller-supplied account (e.g. a conference) instead of silently overwriting it.
        val account = if (localAddress != null) {
            core.accountList.find { it.params.identityAddress?.weakEqual(localAddress) == true }
                ?: run { cannotStart(); onResult(false); return }
        } else {
            callParams?.account ?: core.defaultAccount
        }
        if (account != null && core.accountList.none { it === account }) {
            cannotStart(); onResult(false); return
        }
        val params = callParams?.copy() ?: core.createCallParams(null)
        if (params == null) { cannotStart(); onResult(false); return }
        params.account = account
        params.isVideoEnabled = false
        if (forceZRTP) params.mediaEncryption = MediaEncryption.ZRTP
        val target = address.clone()
        withMicrophone(account, skipNetworkReachabilityTest, onResult) {
            params.account = account
            params.recordFile = LinphoneUtils.getRecordingFilePathForAddress(target)
            val username = target.username.orEmpty()
            val domain = target.domain.orEmpty()
            if (account != null && Compatibility.isIpAddress(domain) &&
                username.isNotEmpty() && (username.startsWith("+") || username.isDigitsOnly())
            ) {
                account.params.identityAddress?.domain?.let { target.domain = it }
            }
            pauseCurrentCall()
            dial(target, params)
        }
    }

    /** Group-call creation also uses media; do not create a conference before this gate. */
    @WorkerThread fun withMicrophone(
        account: Account?,
        skipNetworkCheck: Boolean = false,
        onResult: (Boolean) -> Unit = {},
        action: () -> Boolean,
    ) {
        if (pending.hasPending()) { onResult(false); return }
        if (core.globalState != GlobalState.On || (!skipNetworkCheck && !core.isNetworkReachable) ||
            (account != null && core.accountList.none { it === account })
        ) { onResult(false); return }
        val identity = account?.params?.identityAddress?.asStringUriOnly()
        val request = Request(
            account, identity, core.defaultAccount, sessions.captureCallLease(identity),
            skipNetworkCheck, onResult, action,
        )
        if (microphoneGranted()) {
            place(request)
        } else {
            val id = pending.begin(request) ?: run { onResult(false); return }
            requestMicrophone(id)
        }
    }

    @WorkerThread fun complete(id: Long, granted: Boolean) {
        val request = pending.take(id) ?: return
        // Read the live grant again, not only the Activity's result or onboarding flag.
        if (granted && microphoneGranted()) place(request) else request.completed(false)
    }

    @WorkerThread fun cancelAll() { pending.clear()?.completed?.invoke(false) }

    @WorkerThread private fun place(request: Request) {
        val result = sessions.withCurrentCallLease(request.session) {
            if (core.globalState != GlobalState.On || !microphoneGranted() ||
                (!request.skipNetworkCheck && !core.isNetworkReachable) ||
                core.defaultAccount !== request.defaultAccount ||
                (request.account != null && core.accountList.none { it === request.account }) ||
                request.account?.params?.identityAddress?.asStringUriOnly() != request.identity
            ) {
                return@withCurrentCallLease false
            }

            // Nothing above this point may hold a call, create Telecom state, or send INVITE.
            request.action()
        } ?: false
        if (!result) {
            Log.w("[Mango9 Calls] Outgoing request no longer available; no account fallback")
            cannotStart()
        }
        request.completed(result)
    }
}
