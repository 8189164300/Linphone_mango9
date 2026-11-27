// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9
import org.json.JSONObject

internal class Mango9OpportunityDashboard(private val raw: JSONObject) {
 private fun count(key: String): Int {
     val v = raw.get(key); require(v is Number && v.toDouble() == v.toInt().toDouble() && v.toInt() >= 0); return v.toInt()
 }

 val unknownOpen = count("unknownOpen"); val unknownWon = count("unknownWon")
 val open = count("open"); val won = count("won"); val due = count("due"); val overdue = count("overdue"); val today = count("today"); val estimatedWon = count("estimatedWon")

 init {
     require(due == overdue + today && unknownOpen <= open && unknownWon + estimatedWon <= won); amounts("openCurrencies", open); amounts("currencies", won)
 }

 fun amounts(key: String, count: Int): String {
  val seen = mutableSetOf<String>()
  return raw.getJSONArray(key).opportunityObjects().joinToString("\n") {
   val currency = it.getString("currency"); require(currency.matches(Regex("[A-Z]{3}")) && seen.add(currency))
   if (it.isNull("value")){
       "Not set · $currency"
   } else {
       val value = it.get("value"); require(value is String && value.matches(Regex("[0-9]+(\\.[0-9]+)?"))); Mango9OpportunityPresentation.money(value, currency) + " · " + currency
   }
  }.ifEmpty{if (count == 0)"0" else "Not set"}
 }
}
