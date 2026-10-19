/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.linphone.ui.main.crm.adapter.Mango9ConversationAttachment
import org.linphone.ui.main.crm.adapter.Mango9MessageListItem

class Mango9ConversationSearchTest {
    @Test fun emptyAndWhitespaceDoNotSelectEveryMessage() {
        assertTrue(Mango9ConversationSearch.matchingIds(listOf("a" to "hello"), "  ").isEmpty())
        assertTrue(Mango9ConversationSearch.ranges("hello", "").isEmpty())
        assertNull(Mango9ConversationSearch.select(emptyList(), null))
    }

    @Test fun literalCaseInsensitiveMatchingPreservesEmojiAndUnicodeOffsets() {
        val text = "🙂 RÉSUMÉ [a.*] résumé"
        assertEquals(listOf("a"), Mango9ConversationSearch.matchingIds(listOf("a" to text, "b" to "other"), " résumé "))
        assertEquals(listOf(3..8, 16..21), Mango9ConversationSearch.ranges(text, "RÉSUMÉ"))
        assertEquals(listOf(10..14), Mango9ConversationSearch.ranges(text, "[a.*]"))
        assertEquals(listOf(0..1, 2..3), Mango9ConversationSearch.ranges("aaaaa", "aa"))
    }

    @Test fun navigationStartsNewestWrapsBothDirectionsAndSurvivesRemoval() {
        val ids = listOf("team:1", "sms:1", "sms:2")
        assertEquals("sms:2", Mango9ConversationSearch.select(ids, null))
        assertEquals("sms:1", Mango9ConversationSearch.select(ids, "sms:2", -1))
        assertEquals("sms:2", Mango9ConversationSearch.select(ids, "team:1", -1))
        assertEquals("team:1", Mango9ConversationSearch.select(ids, "sms:2", 1))
        assertEquals("sms:2", Mango9ConversationSearch.select(ids, "removed"))
        assertEquals("only", Mango9ConversationSearch.select(listOf("only"), "only", -1))
    }

    @Test fun largeHistoryHasNoArbitraryResultTruncationAndChecksCancellation() {
        val entries = (0 until 50_000).map { "$it" to "Message $it" }
        var checkpoints = 0
        assertEquals(50_000, Mango9ConversationSearch.matchingIds(entries, "message") { checkpoints++ }.size)
        assertTrue(checkpoints > 100)
        assertEquals(listOf("49999"), Mango9ConversationSearch.matchingIds(entries, "49999"))
        assertThrows(CancellationException::class.java) {
            Mango9ConversationSearch.matchingIds(entries, "message") { throw CancellationException() }
        }
    }

    @Test fun attachmentBrowserSeparatesKindsNewestFirstWithoutCollapsingRepeatedSends() {
        val old = sms("1", "https://example.com/a.mp4,https://example.com/report.pdf,invalid")
        val latest = sms("2", "https://example.com/a.mp4,https://example.com/voice.m4a,https://example.com/photo.jpg")
        val media = Mango9ConversationAttachment.collect(listOf(old, latest), false)
        assertEquals(listOf("sms:2", "sms:2", "sms:2", "sms:1"), media.map { it.messageId })
        assertEquals(listOf(0, 1, 2, 0), media.map { it.index })
        assertEquals(
            listOf(Mango9ChatMedia.Kind.Video, Mango9ChatMedia.Kind.Audio, Mango9ChatMedia.Kind.Image, Mango9ChatMedia.Kind.Video),
            media.map {
            it.media.kind
        }
        )
        val documents = Mango9ConversationAttachment.collect(listOf(old, latest), true)
        assertEquals("report.pdf", documents.single().media.name)
        assertTrue(Mango9ConversationAttachment.collect(emptyList(), true).isEmpty())
        assertThrows(CancellationException::class.java) {
            Mango9ConversationAttachment.collect(listOf(old), false) { throw CancellationException() }
        }
    }

    private fun sms(id: String, files: String) = Mango9MessageListItem.Sms(
        Mango9SmsMessage(id, "2025550100", "", "", "", 2, true, files),
    )
}
