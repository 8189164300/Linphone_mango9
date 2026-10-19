/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Mango9CrmRecordPresentationTest {
    @Test
    fun canonicalStatusCannotBeBypassedUsingAnEditableSummaryAlias() {
        val schema = schema(listOf(field("status", "select"), field("lead_status", "select").copy(editable = false)))
        assertNull(Mango9CrmStatusPolicy.editableField(schema))
        assertTrue(Mango9CrmStatusPolicy.options(schema).isEmpty())
    }

    @Test
    fun respectsVisibilityTypeCustomFlagAndServerOptions() {
        for (field in listOf(field("lead_status", "select").copy(visible = false), field("lead_status"), field("lead_status", "select").copy(custom = true))) {
            assertNull(Mango9CrmStatusPolicy.editableField(schema(listOf(field))))
        }
        val legacy = schema(listOf(field("status", "select").copy(options = emptyList())))
        assertEquals(listOf("New", "Qualified"), Mango9CrmStatusPolicy.options(legacy))
        val authoritative =
            schema(listOf(field("lead_status", "select").copy(options = listOf("Assigned", "Assigned", ""))))
        assertEquals(listOf("Assigned"), Mango9CrmStatusPolicy.options(authoritative))
    }

    @Test
    fun updatesOnlyTheWireStatusKeyAndPreservesOtherFieldsForBothSchemas() = runBlocking {
        for (key in listOf("lead_status", "status")) {
            val initial = detail(key)
            var requests = 0
            val editor = Mango9CrmQuickStatus(initial, schema(listOf(field(key, "select"))), { true }) { body ->
                requests++
                assertEquals(mapOf(key to "Qualified"), body)
                initial.copy(record = initial.record.copy(status = "Qualified"), values = mapOf(key to "Qualified", "first_name" to "Unrelated server value"))
            }
            assertFalse(editor.change("New"))
            assertFalse(editor.change("Unknown"))
            assertTrue(editor.change("Qualified"))
            assertEquals(initial.values + (key to "Qualified"), editor.detail.values)
            assertEquals("Qualified", editor.status)
            assertEquals(1, requests)
            assertFalse(editor.saving)
        }
    }

    @Test
    fun wrongRecordAndNetworkFailureKeepConfirmedStatus() = runBlocking {
        val initial = detail()
        for (wrongRecord in listOf(true, false)) {
            val editor = Mango9CrmQuickStatus(initial, schema(listOf(field("lead_status", "select"))), { true }) {
                if (wrongRecord) initial.copy(record = initial.record.copy(id = 999)) else throw Mango9ApiException.Forbidden
            }
            assertTrue(runCatching { editor.change("Qualified") }.isFailure)
            assertEquals(initial, editor.detail)
            assertFalse(editor.saving)
        }
    }

    @Test
    fun duplicateTapAndAccountChangeCannotReplaceConfirmedValues() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var current = true
        var writes = 0
        val editor = Mango9CrmQuickStatus(detail(), schema(listOf(field("lead_status", "select"))), { current }) {
            writes++
            started.complete(Unit)
            finish.await()
            detail().copy(values = mapOf("lead_status" to "Qualified"))
        }
        val first = async { runCatching { editor.change("Qualified") } }
        started.await()
        assertFalse(editor.change("Qualified"))
        current = false
        finish.complete(Unit)
        assertTrue(first.await().isFailure)
        assertEquals("New", editor.status)
        assertFalse(editor.saving)
        assertEquals(1, writes)
        assertTrue(runCatching { editor.change("Qualified") }.isFailure)
        assertEquals(1, writes)
    }

    @Test
    fun pairsNamesAndAddressPreservingFieldsAndSectionBoundaries() {
        val fields =
            listOf(
                field("last_name"), field("email", "email"), field("first_name"), field("address", section = "location"),
            field("city", section = "location"), field("state", section = "location"), field("zip_code", section = "location"), field("country", section = "location")
            )
        val rows = Mango9CrmFieldLayout.rows(fields, emptyMap())
        assertEquals(
            listOf(listOf("first_name", "last_name"), listOf("email"), listOf("address"), listOf("city", "state", "zip_code"), listOf("country")),
            rows.map {
            it.map { it.key }
        }
        )
        assertEquals(fields.map { it.key }.toSet(), rows.flatten().map { it.key }.toSet())
        assertEquals(fields.size, rows.flatten().size)
        assertTrue(rows.all { it.map { it.section }.distinct().size == 1 })
    }

    @Test
    fun longTextAndAccessibilityKeepFullWidthWithoutInventingMissingFields() {
        val fields =
            listOf(field("first_name"), field("last_name", section = "other"), field("notes", "textarea"), field("email", "email"), field("company"))
        val rows = Mango9CrmFieldLayout.rows(fields, mapOf("email" to "long.email.address@example.com"))
        assertTrue(rows.all { it.size == 1 })
        assertEquals(fields, rows.flatten())
        assertEquals(2, Mango9CrmFieldLayout.columns(280f, 1f, 2))
        assertEquals(3, Mango9CrmFieldLayout.columns(350f, 1.3f, 3))
        assertEquals(1, Mango9CrmFieldLayout.columns(200f, 1f, 2))
        assertEquals(1, Mango9CrmFieldLayout.columns(800f, 1.5f, 3))
    }

    private fun field(key: String, type: String = "text", section: String = "personal") = Mango9CrmSchema.Field(
        key, null, null, key, type, section, false, true, true, false, listOf("New", "Qualified"),
    )

    private fun schema(fields: List<Mango9CrmSchema.Field>) = Mango9CrmSchema("leads", "1", emptyList(), fields, listOf("New", "Qualified", "Qualified", ""))

    private fun detail(key: String = "lead_status") = Mango9CrmRecordDetail(
        Mango9CrmRecord(101, 1, "Owner", "Alex Morgan", "", "", "New", "", ""), "1",
        mapOf(key to "New", "first_name" to "Alex", "custom_1" to "Untouched"),
    )
}
