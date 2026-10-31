/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import android.content.Intent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.R
import org.linphone.databinding.Mango9CrmFragmentBinding
import org.linphone.ui.main.MainActivity

@RunWith(AndroidJUnit4::class)
class Mango9CrmWorkspaceInstrumentedTest {
    @Test fun calendarUsesTheSameWorkspaceRowAndDistinctIosIconRoles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                val binding = Mango9CrmFragmentBinding.inflate(activity.layoutInflater)
                val rows = listOf(binding.leadsRow, binding.clientsRow, binding.teamChatRow, binding.calendarRow, binding.appointmentsRow)
                val colors = listOf(R.color.mango9_crm_leads, R.color.mango9_crm_clients, R.color.mango9_crm_conversations, R.color.mango9_crm_calendar, R.color.mango9_crm_activities)
                val density = activity.resources.displayMetrics.density
                val textSizes = mutableListOf<Float>()
                for ((index, row) in rows.withIndex()) {
                    assertTrue(row.isClickable); assertTrue(row.isFocusable)
                    assertEquals(rows.first().paddingStart, row.paddingStart)
                    assertEquals(rows.first().minimumHeight, row.minimumHeight)
                    val icon = row.getChildAt(0) as AppCompatImageView
                    val tint = icon.imageTintList ?: icon.supportImageTintList
                    assertNotNull("Each shortcut needs its category color", tint)
                    assertEquals(activity.getColor(colors[index]), tint!!.defaultColor)
                    assertEquals((45 * density + 0.5f).toInt(), icon.layoutParams.width)
                    assertTrue(row.getChildAt(row.childCount - 1) is ImageView)
                    val labels = row.getChildAt(1) as LinearLayout
                    textSizes.add((labels.getChildAt(0) as TextView).textSize)
                    assertEquals(2, labels.childCount)
                    row.measure(View.MeasureSpec.makeMeasureSpec((320 * density).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    assertTrue(row.measuredHeight >= (70 * density).toInt())
                }
                assertEquals(1, textSizes.distinct().size)
                assertEquals(5, colors.map(activity::getColor).distinct().size)
                val parent = binding.calendarRow.parent as LinearLayout
                assertEquals(parent.indexOfChild(binding.calendarRow) + 1, parent.indexOfChild(binding.appointmentsRow))
                val gap = (12 * density).toInt()
                val bitmap = android.graphics.Bitmap.createBitmap(rows.first().measuredWidth,
                    rows.sumOf { it.measuredHeight } + gap * (rows.size - 1), android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                canvas.drawColor(activity.getColor(R.color.mango9_calendar_background))
                for (row in rows) {
                    row.layout(0, 0, row.measuredWidth, row.measuredHeight)
                    row.draw(canvas); canvas.translate(0f, row.height + gap.toFloat())
                }
                java.io.File(context.getExternalFilesDir(null), "crm-workspace-rows.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
                binding.unbind()
            }
        }
    }
}
