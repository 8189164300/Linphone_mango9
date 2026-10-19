// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import org.junit.Assert.*
import org.junit.Test

class Mango9SelectionStampTest {
    @Test fun accountRoundTripIsNotTheSameOwnership() {
        val epochs = Mango9SessionGenerations()
        val first = epochs.stamp("original")
        epochs.selectionChanged(); epochs.selectionChanged()
        assertNotEquals(first, epochs.stamp("original"))
    }

    @Test fun onlyTheOwnedLoginInvalidatesTheStamp() {
        val epochs = Mango9SessionGenerations()
        val first = epochs.stamp("original")
        epochs.loginChanged("other")
        assertEquals(first, epochs.stamp("original"))
        epochs.loginChanged("original")
        assertNotEquals(first, epochs.stamp("original"))
    }
}
