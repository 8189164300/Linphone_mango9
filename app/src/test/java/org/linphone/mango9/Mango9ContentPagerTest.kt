// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import org.junit.Assert.*
import org.junit.Test

class Mango9ContentPagerTest {
    private class Row(val id: Int)

    private class Fixture {
        val queue = ArrayDeque<() -> Unit>()
        val states = mutableListOf<Mango9ContentPager.Snapshot<Row>>()
        val released = mutableListOf<Row>()
        val ranges = mutableListOf<Pair<Int, Int>>()
        var current = true
        val pager =
            Mango9ContentPager<Int, Row>(3, { queue.add(it) }, { current }, { released.add(it) }, { states.add(it) })

        fun source(begin: Int, end: Int): List<Int> { ranges.add(begin to end); return (begin until end).toList() }

        fun drain() { var count = 0; while (queue.isNotEmpty()) { check(++count < 20_000); queue.removeFirst()() } }
    }

    @Test fun skippedSourceEntriesDoNotRepeatOrStallAndEveryMapYields() {
        val f = Fixture()
        f.pager.reset(20, f::source) { if (it % 4 == 0) Row(it) else null }
        assertTrue(f.states.last().loading)
        assertTrue(f.ranges.isEmpty())
        f.queue.removeFirst()(); assertEquals(listOf(0 to 3), f.ranges)
        assertTrue(f.states.last().items.isEmpty())
        f.drain(); assertEquals(listOf(0, 4, 8), f.states.last().items.map { it.id })
        f.pager.more(); f.pager.more(); f.drain()
        assertEquals(listOf(0, 4, 8, 12, 16), f.states.last().items.map { it.id })
        assertTrue(f.states.last().exhausted)
        assertEquals(listOf(0, 3, 6, 9, 12, 15, 18), f.ranges.map { it.first })
        f.pager.close(); assertEquals(5, f.released.size)
    }

    @Test fun entirelySkippedHistoryFinishesWithoutAnyRenderedCountIncrease() {
        val f = Fixture(); f.pager.reset(200, f::source) { null }; f.drain()
        assertTrue(f.states.last().exhausted); assertFalse(f.states.last().loading)
        assertTrue(f.states.last().items.isEmpty()); assertEquals(67, f.ranges.size)
        f.pager.more(); f.drain(); assertEquals(67, f.ranges.size)
    }

    @Test fun resetReleasesOldRowsAndRejectsQueuedOldGeneration() {
        val f = Fixture(); f.pager.reset(8, f::source) { Row(it) }; f.drain()
        val old = f.states.last().items.toList()
        f.pager.more()
        f.pager.reset(2, f::source) { Row(it + 100) }; f.drain()
        assertEquals(old.toSet(), f.released.toSet())
        assertEquals(listOf(100, 101), f.states.last().items.map { it.id })
        f.pager.close(); f.pager.close(); assertEquals(5, f.released.size)
    }

    @Test fun closeDuringExportDisposesLateRowWithoutPublishingIt() {
        val f = Fixture()
        f.pager.reset(20, f::source) { f.pager.close(); Row(it) }; f.drain()
        assertEquals(1, f.released.size); assertTrue(f.states.all { it.items.isEmpty() })
        assertEquals(1, f.ranges.size)
    }

    @Test fun failedFetchCanRetrySameSourcePositionWithoutDuplicatingAcceptedRows() {
        val f = Fixture(); var fail = true
        f.pager.reset(8, { begin, end -> if (begin == 3 && fail) error("Temporary read failure") else f.source(begin, end) }) {
            Row(it)
        }
        f.drain(); f.pager.more(); f.drain()
        assertTrue(f.states.last().failed); assertFalse(f.states.last().loading)
        fail = false; f.pager.more(); f.drain()
        assertEquals((0..5).toList(), f.states.last().items.map { it.id }); assertFalse(f.states.last().failed)
        f.pager.close()
    }

    @Test fun selectionChangeBeforeQueuedWorkPreventsReadAndPublication() {
        val f = Fixture(); f.pager.reset(10, f::source) { Row(it) }
        f.current = false; f.pager.close(); f.drain()
        assertTrue(f.ranges.isEmpty()); assertTrue(f.released.isEmpty())
    }

    @Test fun emptySdkPageEndsStaleAdvertisedCountWithoutLooping() {
        val f = Fixture(); f.pager.reset(900, { _, _ -> emptyList() }) { Row(it) }; f.drain()
        assertTrue(f.states.last().exhausted); assertFalse(f.states.last().failed)
    }

    @Test fun sourceRestartInsideAQueryCannotOverwriteTheNewGeneration() {
        val f = Fixture()
        f.pager.reset(10, { _, _ ->
            f.pager.reset(2, f::source) { Row(it + 100) }
            listOf(999)
        }) { Row(it) }
        f.drain()
        assertEquals(listOf(100, 101), f.states.last().items.map { it.id })
        assertTrue(f.states.last().exhausted)
        assertFalse(f.states.last().failed)
        f.pager.close(); assertEquals(2, f.released.size)
    }
}
