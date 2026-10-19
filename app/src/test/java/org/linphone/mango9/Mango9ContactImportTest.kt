/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

class Mango9ContactImportTest {
    @Test fun fiftyThousandContactsUseOneBoundedBatchAtATimeAndCommitOnce() {
        val queue = ArrayDeque<() -> Unit>()
        val values = (0 until 50_000).toList()
        var converted = 0
        var commits = 0
        Mango9ContactBatchImport.run(values, queue::add, { true }, { converted++; it }, {
            assertEquals(values, it); commits++
        }, { throw AssertionError(it) })
        assertEquals(0, converted)
        while (queue.isNotEmpty()) {
            assertEquals(1, queue.size)
            val before = converted
            queue.removeFirst()()
            assertTrue(converted - before in 1..64)
        }
        assertEquals(50_000, converted)
        assertEquals(1, commits)
    }

    @Test fun emptySuccessCommitsButRetiredPartialAndFailedSnapshotsDoNot() {
        val generation = Mango9ContactImportGeneration()
        val queue = ArrayDeque<() -> Unit>()
        val first = generation.begin()
        var commits = 0
        var failures = 0
        Mango9ContactBatchImport.run((1..200).toList(), queue::add, {
            generation.isCurrent(first)
        }, { it }, { commits++ }, { failures++ })
        queue.removeFirst()()
        val next = generation.begin()
        generation.cancel(first) // A retired Loader reset must not cancel its replacement.
        assertTrue(generation.isCurrent(next))
        queue.removeFirst()()
        assertEquals(0, commits)
        Mango9ContactBatchImport.run(emptyList<Int>(), queue::add, {
            generation.isCurrent(next)
        }, { it }, { assertTrue(it.isEmpty()); commits++ }, { failures++ })
        queue.removeFirst()()
        assertEquals(1, commits)
        Mango9ContactBatchImport.run((1..200).toList(), queue::add, {
            true
        }, { if (it == 70) error("fixture"); it }, { commits++ }, { failures++ })
        while (queue.isNotEmpty()) queue.removeFirst()()
        assertEquals(1, commits)
        assertEquals(1, failures)
        generation.cancel(next)
        assertFalse(generation.isCurrent(next))
    }

    @Test fun newConversationPagesRetainAllContactsAndResetOnNewSearch() {
        val page = Mango9ContactPage<Int>()
        val source = (0 until 50_000).toMutableList()
        val displayed = page.replace(source).toMutableList()
        source.clear()
        assertEquals(100, displayed.size)
        assertEquals(50_000, page.total)
        assertTrue(page.reveal(0).isEmpty())
        while (page.limit < page.total) {
            val near = page.limit - 1
            displayed.addAll(page.reveal(near))
            assertTrue(page.reveal(near).isEmpty())
        }
        assertEquals((0 until 50_000).toList(), displayed)
        assertEquals(listOf(4, 5), page.replace(listOf(4, 5)))
        assertEquals(2, page.limit)
        page.clear()
        assertEquals(0, page.total)
        assertTrue(page.reveal(1000).isEmpty())
    }

    @Test fun emptyNonLatinAndAccentedContactNamesHaveSafeHeadings() {
        assertEquals("#", Mango9ContactHeading.of(null))
        assertEquals("#", Mango9ContactHeading.of(""))
        assertEquals("#", Mango9ContactHeading.of("  123"))
        assertEquals("E", Mango9ContactHeading.of("Émile"))
        assertEquals("Ζ", Mango9ContactHeading.of("Ζωή"))
        assertEquals("Ա", Mango9ContactHeading.of("Անի"))
        assertEquals("#", Mango9ContactHeading.of("😊 Contact"))
    }
}
