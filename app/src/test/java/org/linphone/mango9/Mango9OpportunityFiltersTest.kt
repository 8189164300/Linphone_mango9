package org.linphone.mango9

import org.junit.Assert.*
import org.junit.Test

class Mango9OpportunityFiltersTest {
    @Test fun rangesUseExactServerKeysAndCreatorIsNotOwner() {
        val values =
            mapOf("createdBy" to "11", "owner" to "12", "closeFrom" to "2026-11-20", "closeTo" to "2026-11-30", "minValue" to "0", "maxValue" to "1000.50", "currency" to "USD")
        assertEquals(values, Mango9OpportunityFilters.query(values)); assertEquals(5, Mango9OpportunityFilters.count(values))
    }

    @Test fun invalidRangesCannotBeApplied() {
        for (v in listOf(mapOf("minValue" to "2", "maxValue" to "1"), mapOf("maxValue" to "1.999"), mapOf("minValue" to "-1"), mapOf("closeFrom" to "2026-02-30"), mapOf("createdFrom" to "2026-11-21", "createdTo" to "2026-11-20"))) {
            assertTrue(runCatching { Mango9OpportunityFilters.query(v) }.isFailure)
        }
        assertEquals("999999999999.99", Mango9OpportunityFilters.query(mapOf("minValue" to "999999999999.98", "maxValue" to "999999999999.99"))["maxValue"])
    }

    @Test fun resetDoesNotRetainOptionalFilters() {
        assertTrue(Mango9OpportunityFilters.query(mapOf("currency" to "", "archived" to "0")).isEmpty())
        assertEquals(0, Mango9OpportunityFilters.count(emptyMap()))
    }
}
