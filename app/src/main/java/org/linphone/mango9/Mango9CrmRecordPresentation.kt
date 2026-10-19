/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

internal object Mango9CrmStatusPolicy {
    fun standardField(schema: Mango9CrmSchema?): Mango9CrmSchema.Field? =
        listOf("lead_status", "status").firstNotNullOfOrNull { key ->
            schema?.fields?.firstOrNull { it.key == key && !it.custom }
        }

    fun editableField(schema: Mango9CrmSchema?) = standardField(schema)?.takeIf {
        it.visible && it.editable && it.type == "select"
    }

    fun options(schema: Mango9CrmSchema?): List<String> {
        val field = editableField(schema) ?: return emptyList()
        return field.options.ifEmpty { schema?.statuses.orEmpty() }.filter(String::isNotBlank).distinct()
    }
}

/** Main-thread state. Confirm a status-only write without replacing unrelated detail/draft values. */
internal class Mango9CrmQuickStatus(
    initial: Mango9CrmRecordDetail,
    schema: Mango9CrmSchema?,
    private val isCurrent: () -> Boolean,
    private val write: suspend (Map<String, String>) -> Mango9CrmRecordDetail,
) {
    private val field = Mango9CrmStatusPolicy.editableField(schema)
    private val standardKey = Mango9CrmStatusPolicy.standardField(schema)?.key
    val options = Mango9CrmStatusPolicy.options(schema)
    var detail = initial
        private set
    var saving = false
        private set
    val status: String get() = standardKey?.let { detail.values[it] } ?: detail.record.status

    suspend fun change(value: String): Boolean {
        val key = field?.key ?: return false
        if (saving || value !in options || value == status) return false
        if (!isCurrent()) throw Mango9ApiException.Unauthorized
        saving = true
        try {
            coroutineContext.ensureActive()
            val response = write(mapOf(key to value))
            coroutineContext.ensureActive()
            if (!isCurrent()) throw Mango9ApiException.Unauthorized
            if (response.record.id != detail.record.id) throw Mango9ApiException.InvalidResponse
            val confirmed = response.values[key] ?: response.record.status
            detail = detail.copy(
                record = detail.record.copy(status = confirmed),
                values = detail.values + (key to confirmed),
            )
            return true
        } finally {
            saving = false
        }
    }
}

internal object Mango9CrmFieldLayout {
    private val pairs = listOf(
        listOf("first_name", "last_name"), listOf("city", "state", "zip_code"), listOf("status", "priority"),
    )
    private val compactTypes =
        setOf("text", "string", "select", "number", "integer", "decimal", "date", "boolean", "phone", "email")
    private val fullWidthKeys = setOf("address", "address2", "street_address", "notes", "description")

    fun rows(fields: List<Mango9CrmSchema.Field>, values: Map<String, String>): List<List<Mango9CrmSchema.Field>> {
        fun compact(field: Mango9CrmSchema.Field): Boolean {
            val length = values[field.key].orEmpty().length
            return field.type in compactTypes && field.key !in fullWidthKeys && field.label.length <= 32 &&
                length <= 40 && (field.type != "email" || length <= 22)
        }
        val consumed = mutableSetOf<String>()
        val result = mutableListOf<List<Mango9CrmSchema.Field>>()
        fields.forEachIndexed { index, field ->
            if (field.key in consumed) return@forEachIndexed
            val pair = pairs.firstOrNull { field.key in it }
            val members = if (!field.custom && compact(field) && pair != null) {
                pair.mapNotNull { key ->
                    fields.firstOrNull {
                    it.key == key && it.section == field.section && !it.custom && compact(it) && it.key !in consumed
                }
                }
            } else {
                emptyList()
            }
            val next = fields.drop(index + 1).firstOrNull { it.key !in consumed }
            val row = when {
                members.size >= 2 -> members
                compact(field) && next != null && next.section == field.section && compact(next) &&
                    !pairs.any { next.key in it && !next.custom } -> listOf(field, next)
                else -> listOf(field)
            }
            result += row
            consumed += row.map { it.key }
        }
        return result
    }

    fun columns(widthDp: Float, fontScale: Float, count: Int): Int =
        if (fontScale >= 1.5f || widthDp < 240f) 1 else minOf(count.coerceAtLeast(1), if (widthDp >= 320f) 3 else 2)
}
