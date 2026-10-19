// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.linphone.core.ChatRoom
import org.linphone.core.ChatMessage
import org.linphone.core.Content
import org.linphone.core.EventLog

class Mango9NativeMediaHistoryTest {
    @Test fun oldAnchorQueriesItsNeighborsNotTheEntireNewerHistory() {
        val f = Fixture(10000, setOf(10, 12, 14, 9000))
        val anchor = requireNotNull(f.source.anchor("12", "/12-0"))
        assertEquals(listOf("10"), collect(f, anchor.cursor, true).map { it.message })
        assertEquals(listOf("14", "9000"), collect(f, anchor.cursor, false).map { it.message })
        assertTrue(f.largestQuery <= 51)
        assertEquals(0, f.exports)
    }

    @Test fun multipleFilesInOneMessageDoNotRepeatOrDisappearAtPageBoundaries() {
        val f = Fixture(3, setOf(1), contentsPerMessage = 23)
        val middle = requireNotNull(f.source.anchor("1", "/1-11"))
        assertEquals((12..22).toList(), collect(f, middle.cursor, true).map { it.content })
        assertEquals((0..10).reversed().toList(), collect(f, middle.cursor, false).map { it.content })
    }

    @Test fun deletedAnchorEndsSafelyWithoutRetargetingToNewestMessage() {
        val f = Fixture(4, setOf(1))
        val slice = f.source.slice(Mango9NativeMediaHistory.Cursor("missing", 0), true, 8)
        assertTrue(slice.exhausted); assertTrue(slice.entries.isEmpty())
    }

    @Test fun sparseSlicesAdvanceTheSourceCursorEvenWhenTheyContainNoMedia() {
        val f = Fixture(400, setOf(0, 399))
        val first = requireNotNull(f.source.anchor("399", "/399-0"))
        val slice = f.source.slice(first.cursor, true, 8)
        assertTrue(slice.entries.isEmpty()); assertFalse(slice.exhausted)
        assertNotEquals(first.cursor, slice.next)
        assertEquals(listOf("0"), collect(f, first.cursor, true).map { it.message })
    }

    private fun collect(f: Fixture, start: Mango9NativeMediaHistory.Cursor, older: Boolean): List<Mango9NativeMediaHistory.Cursor> {
        var cursor = start
        val result = arrayListOf<Mango9NativeMediaHistory.Cursor>()
        repeat(300) {
            val slice = f.source.slice(cursor, older, 8)
            assertTrue(slice.entries.size <= 8)
            result.addAll(slice.entries.map { it.cursor })
            assertEquals(result.size, result.distinct().size)
            if (slice.exhausted) return result
            assertNotEquals(cursor, slice.next)
            cursor = requireNotNull(slice.next)
        }
        error("History cursor failed to terminate")
    }

    private class Fixture(count: Int, media: Set<Int>, contentsPerMessage: Int = 1) {
        var largestQuery = 0
        var exports = 0
        val events = (0 until count).map { index ->
            val files = if (index in media) {
                Array(contentsPerMessage) { fileIndex ->
                fake<Content> { method, _ ->
                    when (method) {
                    "getFilePath" -> "/$index-$fileIndex"
                    "getName" -> "photo.jpg"
                    "getType" -> "image"
                    "getSubtype" -> "jpeg"
                    "isFile" -> true
                    "isFileTransfer", "isVoiceRecording" -> false
                    "exportPlainFile" -> { exports++; error("History metadata must not export files") }
                    else -> null
                }
                }
            }
            } else {
                emptyArray()
            }
            val message = fake<ChatMessage> { method, _ ->
                when (method) {
                "getMessageId" -> index.toString()
                "getContents" -> files
                "getTime" -> index.toLong()
                "isEphemeral" -> index % 2 == 0
                else -> null
            }
            }
            fake<EventLog> { method, _ -> if (method == "getChatMessage") message else null }
        }
        val room = fake<ChatRoom> { method, args ->
            when (method) {
            "findEventLog" -> args[0].toString().toIntOrNull()?.let { events.getOrNull(it) }
            "getHistoryRangeNear" -> {
                val index = events.indexOf(args[2])
                val before = args[0] as Int; val after = args[1] as Int
                require(before <= 50 && after <= 50)
                val page = events.subList((index - before).coerceAtLeast(0), (index + after + 1).coerceAtMost(events.size)).toTypedArray()
                largestQuery = maxOf(largestQuery, page.size)
                page
            }
            else -> error("Unexpected unbounded SDK query: $method")
        }
        }
        val source = Mango9NativeMediaHistory(room)
    }

    companion object {
        private inline fun <reified T> fake(crossinline call: (String, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { self, method, args ->
                when (method.name) {
                    "equals" -> self === args?.get(0)
                    "hashCode" -> System.identityHashCode(self)
                    "toString" -> "Synthetic SDK interface"
                    else -> call(method.name, args.orEmpty())
                }
            } as T
    }
}
