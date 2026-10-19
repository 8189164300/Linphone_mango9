/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.R
import org.linphone.ui.call.view.VuMeterView

/** Render the real control: accessible state alone cannot prove visible meter pixels. */
@RunWith(AndroidJUnit4::class)
class Mango9VuMeterInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun zeroSizedAndCollapsedViewsAreSafe() = onMain { view ->
        for ((width, height) in listOf(0 to 0, 0 to 100, 100 to 0, 1 to 1)) {
            layout(view, width, height)
            view.setVuMeterPercentage(1f)
            render(view) { bitmap ->
                if (width == 0 || height == 0) assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
            }
        }
    }

    @Test fun levelFillsFromBottomInsideTheCircle() = onMain { view ->
        layout(view, 100, 100)
        view.setVuMeterPercentage(.5f)
        render(view) { bitmap ->
            assertEquals(0, Color.alpha(bitmap.getPixel(50, 25)))
            val expected = ContextCompat.getColor(view.context, R.color.mango9_call_meter)
            val pixel = bitmap.getPixel(50, 75)
            // Compare with Android's own premultiplied representation, rather
            // than assuming exact RGB survives low-alpha bitmap storage.
            val reference = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            try {
                Canvas(reference).drawColor(expected)
                assertEquals(reference.getPixel(0, 0), pixel)
            } finally { reference.recycle() }
            assertEquals(0, Color.alpha(bitmap.getPixel(0, 99)))
        }
        view.setVuMeterPercentage(1f)
        render(view) { assertTrue(Color.alpha(it.getPixel(50, 25)) > 0) }
        view.setVuMeterPercentage(0f)
        render(view) { assertEquals(0, Color.alpha(it.getPixel(50, 75))) }
    }

    @Test fun invalidReadingsClearAndFiniteReadingsClamp() = onMain { view ->
        layout(view, 100, 100)
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1f)) {
            view.setVuMeterPercentage(1f)
            view.setVuMeterPercentage(value)
            render(view) { assertEquals("Invalid reading $value", 0, Color.alpha(it.getPixel(50, 75))) }
        }
        view.setVuMeterPercentage(2f)
        render(view) { assertTrue(Color.alpha(it.getPixel(50, 10)) > 0) }
    }

    @Test fun resizingKeepsTheOverlayCenteredAndRound() = onMain { view ->
        view.setVuMeterPercentage(.5f)
        for ((width, height) in listOf(100 to 100, 200 to 100, 100 to 200, 100 to 100)) {
            layout(view, width, height)
            render(view) { bitmap ->
                assertEquals(0, Color.alpha(bitmap.getPixel(width / 2, height / 2 - 20)))
                assertTrue(Color.alpha(bitmap.getPixel(width / 2, height / 2 + 20)) > 0)
                assertEquals(0, Color.alpha(bitmap.getPixel(1, height - 2)))
            }
        }
    }

    @Test fun overlayPreservesBackgroundClickAndCanvasState() = onMain { view ->
        layout(view, 100, 100)
        view.setBackgroundColor(Color.BLUE)
        var clicks = 0
        view.setOnClickListener { clicks++ }
        view.setVuMeterPercentage(.5f)
        val bitmap = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val originalClip = canvas.clipBounds
            val originalSave = canvas.saveCount
            view.draw(canvas)
            assertEquals(originalSave, canvas.saveCount)
            assertEquals(originalClip, canvas.clipBounds)
            assertEquals(Color.BLUE, bitmap.getPixel(0, 0))
            assertNotEquals(Color.BLUE, bitmap.getPixel(50, 75))
            assertTrue(view.performClick()); assertEquals(1, clicks)
        } finally { bitmap.recycle() }
    }

    @Test fun repeatedFramesDoNotRetainOldLevels() = onMain { view ->
        layout(view, 100, 100)
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            repeat(1_000) { frame ->
                bitmap.eraseColor(Color.TRANSPARENT)
                val visible = frame % 2 == 0
                view.setVuMeterPercentage(if (visible) 1f else 0f)
                view.draw(canvas)
                assertEquals(visible, Color.alpha(bitmap.getPixel(50, 50)) > 0)
            }
        } finally { bitmap.recycle() }
    }

    @Test fun selectedMeterUsesLightOverlayAndDisabledMeterIsEmpty() = onMain { view ->
        layout(view, 100, 100)
        view.setVuMeterPercentage(1f)
        view.isSelected = true
        render(view) {
            assertEquals(ContextCompat.getColor(view.context, R.color.mango9_call_meter_selected), it.getPixel(50, 50))
        }
        view.isEnabled = false
        render(view) { assertEquals(0, Color.alpha(it.getPixel(50, 50))) }
    }

    private fun onMain(block: (VuMeterView) -> Unit) = instrumentation.runOnMainSync {
        block(VuMeterView(instrumentation.targetContext))
    }

    private fun layout(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    private fun render(view: View, verify: (Bitmap) -> Unit) {
        val bitmap = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        try { view.draw(Canvas(bitmap)); verify(bitmap) } finally { bitmap.recycle() }
    }
}
