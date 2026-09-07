package org.linphone.mango9

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.linphone.ui.main.crm.adapter.Mango9MessagingListItems
import org.linphone.ui.main.crm.adapter.Mango9MessagingListItem

class Mango9ConversationOwnershipTest {
    @Test fun nativeViewerUsesMediaTypeEvenForExtensionlessServerNames() {
        val media = Mango9ChatMedia("", "https://example.com/media/123", "123", "audio/mp4", Mango9ChatMedia.Kind.Audio)
        assertEquals("m4a", Mango9MediaCache.extensionFor(media))
        assertEquals("mp4", Mango9MediaCache.extensionFor(media.copy(mimeType = "video/mp4", kind = Mango9ChatMedia.Kind.Video)))
        assertEquals("jpg", Mango9MediaCache.extensionFor(media.copy(mimeType = "image/jpeg", kind = Mango9ChatMedia.Kind.Image)))
    }

    @Test fun oldScreenCannotCloseNewScreenEvenForSameAccount() {
        val ownership = Mango9ConversationOwnership()
        val old = ownership.claim("old-screen", "100@one")
        val current = ownership.claim("new-screen", "100@one")
        assertFalse(ownership.release("old-screen"))
        assertFalse(ownership.owns(old, "100@one"))
        assertTrue(ownership.owns(current, "100@one"))
        assertFalse(ownership.ownsOwner("old-screen", "100@one"))
        assertTrue(ownership.ownsOwner("new-screen", "100@one"))
        assertFalse(ownership.ownsOwner("new-screen", "100@two"))
    }

    @Test fun retryAndAccountSwitchInvalidateOldResult() {
        val ownership = Mango9ConversationOwnership()
        val first = ownership.claim("screen", "100@one")
        val retry = ownership.claim("screen", "100@one")
        assertFalse(ownership.owns(first, "100@one"))
        assertFalse(ownership.owns(retry, "100@two"))
        assertTrue(ownership.release("screen"))
        assertFalse(ownership.owns(retry, "100@one"))
    }

    @Test fun teamRowsMoveByLatestActivityWithinGroupsAndPeople() {
        val users = listOf(
            Mango9ChatUser(1, "Alice", "", ""),
            Mango9ChatUser(2, "Zara", "", ""),
            Mango9ChatUser(3, "Unused", "", ""),
        )
        val rooms = listOf(
            Mango9ChatRoom("old-group", emptyList(), "2026-09-01", "", 0, false),
            Mango9ChatRoom("new-group", emptyList(), "2026-09-06", "", 1, false),
            Mango9ChatRoom("alice", listOf(1), "2026-09-01", "", 0, true),
            Mango9ChatRoom("zara", listOf(2), "2026-09-06", "", 1, true),
        )
        val rows = Mango9MessagingListItems.team(Mango9ChatState(users = users, rooms = rooms), { false }, { it.id })
        assertEquals(
            listOf("new-group", "old-group"),
            rows.filterIsInstance<Mango9MessagingListItem.Group>().map {
            it.room.id
        }
        )
        assertEquals(listOf(2, 1, 3), rows.filterIsInstance<Mango9MessagingListItem.User>().map { it.user.id })
        val updated = rooms.map { if (it.id == "alice") it.copy(latest = "2026-09-07") else it }
        val next = Mango9MessagingListItems.team(Mango9ChatState(users = users, rooms = updated), { false }, { it.id })
        assertEquals(listOf(1, 2, 3), next.filterIsInstance<Mango9MessagingListItem.User>().map { it.user.id })
    }
}
