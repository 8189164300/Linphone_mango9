// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Mango9OpportunityDashboardTest {
 private fun valid() = JSONObject("""{"open":3,"won":2,"due":3,"overdue":2,"today":1,"unknownOpen":1,"unknownWon":0,"estimatedWon":1,"openCurrencies":[{"currency":"USD","value":"1250.25"},{"currency":"EUR","value":null}],"currencies":[{"currency":"USD","value":"300.00"}]}""")

 @Test fun preservesCurrenciesAndUnknownAmounts() {
     val d = Mango9OpportunityDashboard(valid()); assertEquals(3, d.open); assertTrue(d.amounts("openCurrencies", 3).contains("EUR")); assertTrue(d.amounts("openCurrencies", 3).contains("Not set")); assertEquals(1, d.estimatedWon)
 }

 @Test fun rejectsInvalidCounts() {
     for (key in listOf("open", "won", "due", "overdue", "today", "unknownOpen", "unknownWon", "estimatedWon"))for (value in listOf(-1, 1.5, "2", JSONObject.NULL))assertThrows(Exception::class.java){
         Mango9OpportunityDashboard(valid().put(key, value))
     }
 }

 @Test fun rejectsInconsistentTotals() {
     for (pair in listOf("due" to 4, "unknownOpen" to 4, "unknownWon" to 2))assertThrows(Exception::class.java){
         Mango9OpportunityDashboard(valid().put(pair.first, pair.second))
     }
 }

 @Test fun rejectsInvalidCurrencyAmounts() {
     for (value in listOf("-5", "NaN", 5))assertThrows(Exception::class.java){
         val raw = valid(); raw.getJSONArray("currencies").getJSONObject(0).put("value", value); Mango9OpportunityDashboard(raw)
     }
 }
}
