// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import androidx.test.platform.app.InstrumentationRegistry

/** Espresso's app-main-thread idleness does not cover native window/IME transitions. */
internal object Mango9UiTestSynchronization {
    fun awaitNativeWindow() {
        val runner = InstrumentationRegistry.getInstrumentation()
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
        // Wait for accessibility/window events to settle, not a disabled-animation test device.
        // Keep a finite deadline: a continuously changing screen must fail, not hang the suite.
        runner.uiAutomation.waitForIdle(500, 5000)
        runner.waitForIdleSync()
    }
}
