/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.mango9

import org.junit.Assert.assertEquals
import org.junit.Test

class Mango9AccountCompanyTest {
    @Test
    fun companyUsesMatchingSessionPrefixLikeIos() {
        assertEquals(
            "Manushak",
            Mango9AccountCompany.displayName("Manushak Gevorg", "manushak.mango9.com"),
        )
        assertEquals(
            "MANGO-9",
            Mango9AccountCompany.displayName("MANGO-9 Support", "mango_9.mango9.com"),
        )
    }

    @Test
    fun companyFallsBackToFormattedSipTenant() {
        assertEquals(
            "Manushak",
            Mango9AccountCompany.displayName("Gevorg Stepanian", "manushak.mango9.com"),
        )
        assertEquals(
            "First Church",
            Mango9AccountCompany.displayName(null, "first-church.mango9.com"),
        )
    }

    @Test
    fun missingTenantUsesTheIosFallback() {
        assertEquals("Mango9 PBX", Mango9AccountCompany.displayName(null, null))
    }
}
