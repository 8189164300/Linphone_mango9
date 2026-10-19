/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import android.content.res.Configuration
import android.view.View
import android.view.ContextThemeWrapper
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.R
import org.linphone.ui.main.crm.fragment.Mango9CrmFieldRow
import org.linphone.ui.main.crm.fragment.Mango9CrmFragment

@RunWith(AndroidJUnit4::class)
class Mango9Normalization43InstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun encryptedSessionRefreshCannotRestoreDeletedOrReselectedLoginAcrossStoreInstances() {
        val first = Mango9SessionStore(context)
        val second = Mango9SessionStore(context)
        val originalActive = first.activeIdentity
        val fixture = Mango9Session(
            "fixture", "https://crm.example.invalid", "https://crm.example.invalid/api/v2", "fixture", "fixture", "agent",
            "fixture@example.invalid", "Fixture", "fixture-access", "fixture-refresh", "https://chat.example.invalid/api",
            "wss://chat.example.invalid/socket", 1_900_000_000, "sip:normalization43@crm.example.invalid",
        )
        val identity = fixture.sipIdentity!!
        try {
            first.save(fixture, persist = false, makeActive = true)
            val lease = first.captureSession()!!
            val route = Mango9CrmFragment.recordArguments(Mango9AppointmentContact(17, "Test lead", "lead"), lease)
            assertEquals(lease, Mango9CrmFragment.recordLease(route, second.captureSession()))
            val renewed = fixture.copy(accessToken = "fixture-renewed", refreshToken = "fixture-rotated")
            assertTrue(second.replaceTokens(lease, fixture, renewed))
            assertEquals(renewed, first.currentSession(lease))
            assertFalse(first.replaceTokens(lease, renewed, renewed.copy(userId = "different-user")))
            assertFalse(first.replaceTokens(lease, fixture, renewed))
            second.activate(null)
            second.activate(identity)
            assertNull(first.currentSession(lease))
            assertNull(Mango9CrmFragment.recordLease(route, second.captureSession()))
            val currentLease = second.captureSession()!!
            first.remove(identity)
            assertFalse(second.replaceTokens(currentLease, renewed, fixture))
            assertNull(second.load(identity))
            first.save(fixture, persist = false, makeActive = true)
            assertNull(second.currentSession(currentLease))
            assertNotNull(second.captureSession())
        } finally {
            first.remove(identity)
            first.activate(originalActive?.takeIf { first.hasSession(it) })
        }
    }

    @Test
    fun nativeFieldRowsPairShortFieldsAndStackAtAccessibilitySizesWithoutClipping() {
        instrumentation.runOnMainSync {
            for ((widthDp, fontScale, count) in listOf(Triple(280, 1f, 2), Triple(350, 1f, 3), Triple(200, 1f, 2), Triple(350, 1.8f, 2))) {
                val configured = context.createConfigurationContext(
                    Configuration(context.resources.configuration).apply {
                    this.fontScale = fontScale
                }
                )
                val themed = ContextThemeWrapper(configured, R.style.Theme_Linphone)
                val row = Mango9CrmFieldRow(themed)
                val labels = listOf("First name", "Last name", "City")
                repeat(count) { index ->
                    row.addView(
                        LinearLayout(themed).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(TextView(themed).apply { text = labels[index]; textSize = 12f })
                        addView(TextView(themed).apply { text = "A long but readable field value"; textSize = 16f })
                    }
                    )
                }
                val width = (widthDp * configured.resources.displayMetrics.density).toInt()
                row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                row.layout(0, 0, width, row.measuredHeight)
                val first = row.getChildAt(0)
                val second = row.getChildAt(1)
                if (fontScale >= 1.5f || widthDp < 240) {
                    assertTrue(second.top >= first.bottom)
                    assertEquals(first.left, second.left)
                } else {
                    assertEquals(first.top, second.top)
                    assertTrue(second.left > first.right)
                }
                repeat(count) { index ->
                    val cell = row.getChildAt(index) as LinearLayout
                    assertTrue(cell.width > 0 && cell.height > 0)
                    assertTrue(cell.right <= width && cell.bottom <= row.height)
                    val value = cell.getChildAt(1) as TextView
                    assertTrue(value.layout.lineCount > 0)
                    assertTrue(value.height >= value.layout.height)
                }
            }
        }
    }
}
