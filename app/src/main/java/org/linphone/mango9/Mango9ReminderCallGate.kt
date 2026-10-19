/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.GlobalState

internal interface Mango9ReminderCalls : AutoCloseable { val blocked: Boolean }

/** Fail closed until Core is ready. The atomic guard closes the delayed UI-callback gap. */
internal class Mango9ReminderCallGate(private val changed: () -> Unit) : Mango9ReminderCalls {
    private val stopped = AtomicBoolean(false)
    private val inCall = AtomicBoolean(true)
    override val blocked: Boolean get() = stopped.get() || inCall.get()
    private val main = Handler(Looper.getMainLooper())
    private var owner: Core? = null // Core-thread owned, never read from UI.
    private val listener = object : CoreListenerStub() {
        override fun onFirstCallStarted(core: Core) = update(core, true)

        override fun onLastCallEnded(core: Core) = sample(core)

        override fun onCallStateChanged(core: Core, call: Call, state: Call.State?, message: String) = sample(core)

        override fun onGlobalStateChanged(core: Core, state: GlobalState?, message: String) = sample(core)
    }
    private val registration = coreContext.postOnCoreThreadCancellable { core ->
        if (!stopped.get()) { owner = core; core.addListener(listener); sample(core) }
    }

    private fun sample(core: Core) {
        if (!stopped.get() && owner === core) update(core, core.globalState != GlobalState.On || core.callsNb > 0)
    }

    private fun update(core: Core, value: Boolean) {
        if (stopped.get() || owner !== core) return
        inCall.set(value)
        main.post { if (!stopped.get()) changed() }
    }

    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        inCall.set(true); registration.cancel()
        coreContext.postOnCoreThread { owner?.removeListener(listener); owner = null }
    }
}
