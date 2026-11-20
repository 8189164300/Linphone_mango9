// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

/** Validated, server-side list filters; never filter only the loaded page. */
internal object Mango9OpportunityFilters {
    val keys =
        setOf("createdBy", "owner", "shared", "currency", "followup", "closeFrom", "closeTo", "createdFrom", "createdTo", "minValue", "maxValue", "source", "archived")

    fun query(input: Map<String, String>): Map<String, String> {
        val result = input.filterKeys {
            it in keys
        }.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() && it != "__all__" }.toMutableMap()
        if (result["archived"] != "1") result.remove("archived")
        for ((from, to, label) in listOf(Triple("closeFrom", "closeTo", "Expected close"), Triple("createdFrom", "createdTo", "Created"))) {
            for (key in listOf(from, to)) result[key]?.let { value ->
                require(
                    runCatching {
                    java.time.LocalDate.parse(value).toString() == value
                }.getOrDefault(false)
                ) { "Choose a valid ${label.lowercase()} date." }
            }
            require(result[from] == null || result[to] == null || result.getValue(from) <= result.getValue(to)) {
                "$label date From must be on or before To."
            }
        }
        for (key in listOf("minValue", "maxValue")) result[key]?.let {
            require(it.matches(Regex("[0-9]{1,12}(\\.[0-9]{1,2})?"))) { "Enter amounts with up to two decimal places." }
        }
        require(result["minValue"] == null || result["maxValue"] == null || result.getValue("minValue").toBigDecimal() <= result.getValue("maxValue").toBigDecimal()) {
            "Minimum amount must not exceed maximum amount."
        }
        return result
    }

    fun count(input: Map<String, String>) = listOf("createdBy", "owner", "shared", "currency", "followup", "source").count {
        !input[it].isNullOrBlank()
    } +
        listOf("closeFrom" to "closeTo", "createdFrom" to "createdTo", "minValue" to "maxValue").count {
            !input[it.first].isNullOrBlank() || !input[it.second].isNullOrBlank()
        } + if (input["archived"] == "1") 1 else 0
}
