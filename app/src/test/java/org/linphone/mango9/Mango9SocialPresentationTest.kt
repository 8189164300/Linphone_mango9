// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Mango9SocialPresentationTest {
    private fun thread(index: Int, activity: String = "2026-10-17T12:00:00Z") = Mango9SocialThread.parse(
        JSONObject()
        .put("id", "00000000-0000-0000-0000-${index.toString().padStart(12, '0')}")
        .put("version", 1).put("sourceId", "fixture-$index").put("platform", "instagram")
        .put("activityAt", activity).put("connectionCurrent", true).put("canReply", true)
    )

    @Test fun localDraftingDoesNotBypassProviderPermission() {
        val original = thread(1)
        assertTrue(original.canDraft); assertTrue(original.canSend)
        for (restricted in listOf(original.copy(canReply = false), original.copy(connected = false))) {
            assertTrue(restricted.canDraft)
            assertFalse(restricted.canSend)
        }
        for (unsupported in listOf(original.copy(deleted = true), original.copy(platform = "unsupported"))) {
            assertFalse(unsupported.canDraft); assertFalse(unsupported.canSend)
        }
    }

    @Test fun refreshingInboxRetainsLoadedPagesAndUpdatesExistingRows() {
        val previous = (1..125).map { thread(it) }
        val updated = thread(80, "2026-10-17T14:00:00Z").copy(summary = "new text", version = 2)
        val page = Mango9SocialPage(listOf(updated, thread(126, "2026-10-17T13:00:00Z")), "head-cursor")
        val merged = Mango9SocialPages.inbox(previous, page, false)
        assertEquals(126, merged.size)
        assertEquals(updated, merged.first())
        assertEquals(126, merged.map { it.id }.distinct().size)
        assertEquals("older-cursor", Mango9SocialPages.cursor("older-cursor", page, false, true))
        assertNull(Mango9SocialPages.cursor(null, page, false, true))
        assertEquals("head-cursor", Mango9SocialPages.cursor(null, page, false, false))
    }

    @Test fun aCompleteFreshPageIsAuthoritativeAndPaginationNeverLoops() {
        val page = Mango9SocialPage(listOf(thread(2)), null)
        assertEquals(listOf(thread(2)), Mango9SocialPages.inbox(listOf(thread(1)), page, false))
        assertEquals(2, Mango9SocialPages.inbox(listOf(thread(1)), page, true).size)
        assertNull(Mango9SocialPages.cursor("old", page, false, true))
        assertNull(Mango9SocialPages.cursor("same", Mango9SocialPage(emptyList<Int>(), "same"), true, true))
    }

    @Test fun attachmentPickerOnlyOffersSupportedProviderFileTypes() {
        assertArrayEquals(arrayOf("image/jpeg", "image/png"), Mango9SocialMediaPolicy.mimeTypes("tiktok"))
        for (platform in listOf("facebook", "instagram")) {
            assertArrayEquals(arrayOf("image/jpeg", "image/png", "video/mp4"), Mango9SocialMediaPolicy.mimeTypes(platform))
            assertFalse(Mango9SocialMediaPolicy.mimeTypes(platform).contains("*/*"))
        }
    }

    @Test fun sendOutcomesDoNotMislabelFailureOrAssumeUnknownIsSafeToRetry() {
        for (value in listOf("sent", "confirmed_sent")) {
            assertEquals(Mango9SocialSendOutcome.Sent, Mango9SocialSendOutcome.of(value))
            assertNull(Mango9SocialSendOutcome.of(value).notice)
        }
        for (value in listOf("failed", "confirmed_unsent")) assertEquals(Mango9SocialSendOutcome.Failed, Mango9SocialSendOutcome.of(value))
        for (value in listOf("unknown", "pending", "sending", "unexpected", "")) assertEquals(Mango9SocialSendOutcome.Unknown, Mango9SocialSendOutcome.of(value))
        assertNotEquals(Mango9SocialSendOutcome.Failed.notice, Mango9SocialSendOutcome.Unknown.notice)
    }

    private fun message(id: Int, text: String) = Mango9SocialMessage.parse(
        JSONObject().put("id", id).put("text", text).put("createdAt", "2026-10-17T12:00:00Z")
    )

    @Test fun previewUsesVisibleHistoryAndRestorationIsAuthoritative() {
        val messages = listOf(message(2, "Newest"), message(1, "Earlier"))
        assertEquals("Newest", Mango9SocialPreview.text(messages, emptySet(), false))
        assertEquals("Earlier", Mango9SocialPreview.text(messages, setOf("2"), false))
        assertEquals("No visible messages", Mango9SocialPreview.text(messages, setOf("1", "2"), false))
        assertEquals("Newest", Mango9SocialPreview.text(messages, emptySet(), false))
        assertEquals("Earlier", Mango9SocialPreview.text(listOf(messages[0].copy(deleted = true), messages[1]), emptySet(), false))
    }

    @Test fun previewNeverLeaksUnavailableTextAndHandlesAttachmentOnlyHistory() {
        val media = message(1, "").copy(attachments = listOf(Mango9SocialMessage.Media(0, "image", "photo.jpg")))
        assertEquals("Attachment", Mango9SocialPreview.text(listOf(media), emptySet(), false))
        assertEquals("Content unavailable", Mango9SocialPreview.text(listOf(message(2, "Removed").copy(unavailable = true)), emptySet(), false))
        assertEquals("Open conversation for earlier messages", Mango9SocialPreview.text(listOf(media), setOf("1"), true))
        assertEquals("No visible messages", Mango9SocialPreview.text(emptyList(), emptySet(), false))
    }

    @Test fun unverifiedListRowNeverExposesProviderSummary() {
        val row = org.linphone.ui.main.crm.adapter.Mango9MessagingListItem.Social(thread(1).copy(summary = "Hidden text"))
        assertEquals("Loading preview…", row.preview)
        assertNotEquals(row.thread.summary, row.preview)
    }

}
