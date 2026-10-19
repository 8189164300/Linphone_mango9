/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import android.content.Context
import androidx.annotation.UiThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.linphone.core.tools.Log

/** Refresh a visible account label without selecting an account or overwriting a newer local save. */
@UiThread
internal class Mango9LineIdentityRefresh(
    private val sessions: Mango9SessionStore,
    private val identities: Mango9LineIdentityStore,
    private val scope: CoroutineScope,
    private val load: suspend (Mango9SessionLease) -> Mango9LineIdentity,
) {
    constructor(context: Context, scope: CoroutineScope) : this(
        Mango9SessionStore(context), Mango9LineIdentityStore(context), scope,
        Mango9CrmRepository(context)::lineIdentity,
    )

    private val requests = Mango9LatestRequestGate()
    private var job: Job? = null
    private var pending: Mango9SessionLease? = null

    fun refresh() {
        val lease = sessions.captureSession() ?: run { cancel(); return }
        if (job?.isActive == true && pending?.let { sessions.currentSession(it) != null } == true) return
        cancel()
        val request = requests.next()
        val identity = lease.session.sipIdentity ?: return
        val previous = identities.load(identity)
        pending = lease
        job = scope.launch {
            try {
                val loaded = load(lease)
                if (requests.isLatest(request) && sessions.currentSession(lease) != null) {
                    sessions.withCurrentSession(lease) {
                        if (identities.load(identity) == previous) {
                            identities.save(loaded, identity)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A background label refresh must not replace the user's settings/error UI.
                Log.w("[Mango9 Line] Unable to refresh the selected account label")
            } finally {
                if (requests.isLatest(request)) pending = null
            }
        }
    }

    fun cancel() {
        requests.invalidate()
        job?.cancel()
        job = null
        pending = null
    }
}
