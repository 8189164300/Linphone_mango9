// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import org.junit.Assert.*
import org.junit.Test
import org.linphone.ui.main.crm.adapter.Mango9MessagingListItems

class Mango9ConversationRecencyTest {
    private fun sms(id: String, time: String) = Mango9SmsParty(id, time, "old", 2, "avatar")

    private fun room(id: String, time: String, direct: Boolean = false) = Mango9ChatRoom(id, listOf(1), time, "old", 2, direct)

    private fun social(id: String, time: String) = Mango9SocialThread(
        id, 1, id, "instagram", id, "old", null, "", time, false, true, true, false, null, null, false
    )

    @Test fun equivalentWireFormatsAndTimeZonesSortByInstant() {
        assertEquals(Mango9ConversationRecency.time("2026-10-19 12:00:00"), Mango9ConversationRecency.time("2026-10-19T05:00:00-07:00"))
        val rows =
            listOf(sms("iso", "2026-10-19T11:00:00Z"), sms("sql", "2026-10-19 12:00:00"), sms("offset", "2026-10-19T06:00:00-07:00"))
        assertEquals(listOf("offset", "sql", "iso"), Mango9ConversationRecency.sms(rows).map { it.phone })
        assertTrue(Mango9ConversationRecency.newer("2026-10-19T12:00:00.123456Z", "2026-10-19T12:00:00Z"))
    }

    @Test fun latestSmsMovesImmediatelyWithoutWaitingForDirectoryAndOldMessagesDoNotBumpIt() {
        val initial = listOf(sms("first", "2026-10-19 12:00:00"), sms("second", "2026-10-18 12:00:00"))
        val active = Mango9ConversationRecency.smsActivity(initial, "second", "2026-10-19T13:00:00Z", "reply")
        assertEquals("second", active.first().phone)
        assertEquals("reply", active.first().lastMessage)
        assertEquals(2, active.first().unread)
        assertEquals("avatar", active.first().avatar)
        assertEquals(active, Mango9ConversationRecency.smsActivity(active, "second", "2026-10-18T13:00:00Z", "older history"))
        assertEquals("new", Mango9ConversationRecency.smsActivity(active, "new", "2026-10-19T14:00:00Z", "attachment").first().phone)
        assertEquals(
            listOf("second", "first"),
            Mango9MessagingListItems.sms(Mango9ChatState(smsParties = active.reversed())) {
            false
        }.map { it.stableId.removePrefix("sms:") }
        )
    }

    @Test fun staleDirectoryDoesNotUndoActivityButStillRefreshesMetadata() {
        val observed = sms("one", "2026-10-19T13:00:00Z").copy(lastMessage = "new")
        val stale = sms("one", "2026-10-19 12:00:00").copy(unread = 0, avatar = "new-avatar")
        val merged = Mango9ConversationRecency.smsSnapshot(stale, observed)
        assertEquals(observed.latest, merged.latest)
        assertEquals("new", merged.lastMessage)
        assertEquals(0, merged.unread)
        assertEquals("new-avatar", merged.avatar)
        val group = room("group", observed.latest)
        val metadata = room("group", stale.latest).copy(groupName = "Renamed", userIds = listOf(2), unread = 0)
        assertEquals(metadata.copy(latest = group.latest), Mango9ConversationRecency.roomSnapshot(metadata, group))
    }

    @Test fun groupsAndDirectChatsShareOneTimelineAndUnusedContactsStayLast() {
        val state = Mango9ChatState(
            users = listOf(Mango9ChatUser(1, "Alice", "", ""), Mango9ChatUser(2, "Unused", "", "")),
            rooms = listOf(room("group", "2026-10-19T11:00:00Z"), room("direct", "2026-10-19 12:00:00", true))
        )

        fun ids(value: Mango9ChatState) = Mango9MessagingListItems.team(value, { false }, { it.id }).map { it.stableId }
        assertEquals(listOf("user:1", "group:group", "user:2"), ids(state))
        val changed = state.copy(rooms = Mango9ConversationRecency.roomActivity(state.rooms, "group", "2026-10-19T13:00:00Z", "new"))
        assertEquals(listOf("group:group", "user:1", "user:2"), ids(changed))
    }

    @Test fun socialHeadAndPaginationCannotRestoreOlderActivity() {
        val recent = social("one", "2026-10-19T13:00:00Z").copy(summary = "new")
        val stale = recent.copy(activityAt = "2026-10-19 11:00:00", summary = "old", canReply = false, version = 2)
        val second = social("two", "2026-10-19 12:00:00")
        for (more in listOf(false, true)) {
            val result = Mango9SocialPages.inbox(listOf(recent, second), Mango9SocialPage(listOf(stale, second), "cursor"), more)
            assertEquals("one", result.first().id)
            assertEquals("new", result.first().summary)
            assertFalse(result.first().canReply)
            assertEquals(2, result.first().version)
        }
    }

    @Test fun socialConversationActivityReachesInboxWithoutCrossingLoginOrAccountSelection() {
        val session =
            Mango9Session("fixture", "https://example.invalid", "https://example.invalid/api/v2", "fixture", "fixture", "agent", "fixture@example.invalid", "Fixture", "fixture-access", "fixture-refresh", "", "", 1_900_000_000, "sip:fixture@example.invalid")
        val owner = Mango9SessionLease(session, 101, 201)
        val rows = listOf(social("first", "2026-10-19T12:00:00Z"), social("second", "2026-10-18T12:00:00Z"))
        Mango9SocialRecency.record(owner, rows.last().copy(activityAt = "2026-10-19T13:00:00Z", summary = "reply"))
        assertEquals("second", Mango9SocialRecency.overlay(owner, rows).first().id)
        assertEquals("first", Mango9SocialRecency.overlay(owner.copy(loginGeneration = 102), rows).first().id)
        assertEquals("first", Mango9SocialRecency.overlay(owner.copy(selectionGeneration = 202), rows).first().id)
        assertEquals(emptyList<Mango9SocialThread>(), Mango9SocialRecency.overlay(owner, emptyList()))
    }

    @Test fun missingOrInvalidTimestampsCannotPinRowsAboveRealMessages() {
        assertEquals(
            listOf("real", "blank", "invalid"),
            Mango9ConversationRecency.sms(listOf(sms("invalid", "null"), sms("real", "2026-10-19"), sms("blank", ""))).map {
            it.phone
        }
        )
    }
}
