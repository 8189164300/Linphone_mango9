/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import org.junit.Assert.*
import org.junit.Test

class Mango9CalendarFormStateTest {
    private fun region(tag: String, top: Int, bottom: Int) = Mango9CalendarFormState.Region(tag, top, bottom)

    @Test fun unchangedLayoutKeepsExactOffsetButNarrowerLargerTextKeepsTheSameField() {
        val state = Mango9CalendarFormState()
        val original = listOf(region("notes", 40, 160), region("starts", 190, 250), region("ends", 250, 310))
        state.captureScroll(205, 350, 700, 1f, original)
        assertEquals("starts", state.anchor?.tag)
        assertEquals(205, state.restoredScroll(350, 700, 1f, original))
        val larger = listOf(region("notes", 60, 360), region("starts", 390, 510), region("ends", 510, 630))
        assertEquals(420, state.restoredScroll(280, 1100, 1.8f, larger))
    }

    @Test fun widthChangeKeepsTheSpaceImmediatelyBeforeTheNextField() {
        val state = Mango9CalendarFormState()
        state.captureScroll(180, 350, 700, 1f, listOf(region("starts", 190, 250)))
        assertEquals(295, state.restoredScroll(700, 600, 1f, listOf(region("starts", 300, 330))))
    }

    @Test fun nestedControlWinsOverAContainingTextRegionAndHiddenFieldsFallBackSafely() {
        val state = Mango9CalendarFormState()
        state.captureScroll(205, 350, 700, 1f, listOf(region("outer", 0, 700), region("starts", 190, 250)))
        assertEquals("starts", state.anchor?.tag)
        assertEquals(205, state.restoredScroll(700, 600, 1f, listOf(region("ends", 20, 80))))
    }

    @Test fun clearCannotRestoreAnotherAccountsFieldOrInput() {
        val state = Mango9CalendarFormState()
        state.inputTag = "title"
        state.captureScroll(205, 350, 700, 1f, listOf(region("starts", 190, 250)))
        state.clear()
        assertNull(state.anchor); assertNull(state.inputTag)
        assertEquals(0, state.restoredScroll(700, 600, 1f, listOf(region("starts", 300, 360))))
    }
}
