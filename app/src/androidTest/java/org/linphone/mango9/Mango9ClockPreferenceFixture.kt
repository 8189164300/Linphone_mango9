/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.text.format.DateFormat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals

/** Restore the test device's exact original preference, including an unset value. */
internal object Mango9ClockPreferenceFixture {
    fun withPreference(value: String, test: () -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = Settings.System.getString(context.contentResolver, Settings.System.TIME_12_24)
        try { set(value); test() } finally { set(original) }
    }

    fun set(value: String?) {
        require(value == null || value == "12" || value == "24")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val command = if (value == null) "settings delete system time_12_24" else "settings put system time_12_24 $value"
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use {
            it.readText()
        }
        assertEquals(value, Settings.System.getString(instrumentation.targetContext.contentResolver, Settings.System.TIME_12_24))
        if (value != null) assertEquals(value == "24", DateFormat.is24HourFormat(instrumentation.targetContext))
    }
}
