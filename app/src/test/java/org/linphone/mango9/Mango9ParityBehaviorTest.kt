/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.mango9

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.linphone.ui.main.crm.adapter.Mango9MessagingListItem
import org.linphone.ui.main.crm.adapter.Mango9MessagingListItems

class Mango9ParityBehaviorTest {
    @Test
    fun registrationErrorDistinguishesPushFromCredentials() {
        val pushFailure = Mango9RegistrationFailure("555 Push Notification Service Not Supported")
        val credentialFailure = Mango9RegistrationFailure("403 Forbidden")

        assertEquals(Mango9RegistrationFailure.Kind.PushConfiguration, pushFailure.kind)
        assertEquals(555, pushFailure.sipCode)
        assertTrue(pushFailure.userMessage("700").contains("call notifications"))
        assertEquals(Mango9RegistrationFailure.Kind.Credentials, credentialFailure.kind)
        assertTrue(credentialFailure.userMessage("700").contains("authenticate"))
    }

    @Test
    fun registrationErrorUsesFriendlyRetryWording() {
        val networkFailure = Mango9RegistrationFailure("408 Request Timeout")
        val serverFailure = Mango9RegistrationFailure("503 Service Unavailable")

        assertEquals(Mango9RegistrationFailure.Kind.Network, networkFailure.kind)
        assertTrue(networkFailure.userMessage("100").contains("keep retrying"))
        assertEquals(Mango9RegistrationFailure.Kind.ServiceUnavailable, serverFailure.kind)
        assertTrue(serverFailure.userMessage(null).contains("retry automatically"))
    }

    @Test
    fun sipIdentityNormalizationSeparatesTenantAccounts() {
        assertEquals(
            "sip:100@tenant-a.example.com",
            Mango9SessionStore.normalizedIdentity("<SIP:100@TENANT-A.EXAMPLE.COM>;transport=tls"),
        )
        assertNotEquals(
            Mango9SessionStore.normalizedIdentity("sip:100@tenant-a.example.com"),
            Mango9SessionStore.normalizedIdentity("sip:100@tenant-b.example.com"),
        )
        assertEquals(
            "sip:100@tenant-a.example.com",
            Mango9SessionStore.normalizedIdentity(
                "\"Tenant User\" <SIP:100@TENANT-A.EXAMPLE.COM>;transport=tls",
            ),
        )
    }

    @Test
    fun teamChatM4aAttachmentUsesAudioPlayer() {
        val media = Mango9ChatMedia.parse(
            "https://cdn.example.com/messages/voice.m4a" +
                "?signature=test&ffName=Team%20voice.m4a" +
                "&ffType=audio%2Fmp4&ffExt=m4a",
        ).single()

        assertEquals(Mango9ChatMedia.Kind.Audio, media.kind)
        assertEquals("Team voice.m4a", media.name)
        assertEquals("audio/mp4", media.mimeType)
        assertTrue(media.url.substringBefore('?').endsWith(".m4a"))
    }

    @Test
    fun accountLineLabelKeepsExtensionVisibleWithoutDid() {
        assertEquals(
            "Ext 700",
            Mango9LineIdentityStore.accountLineLabel("700", "", "Gevorg Stepanyan"),
        )
        assertEquals(
            "818-900-7897 · Ext 700",
            Mango9LineIdentityStore.accountLineLabel(
                "700",
                Mango9LineIdentityStore.formatPhoneNumber("18189007897"),
                "Gevorg Stepanyan",
            ),
        )
    }

    @Test
    fun latestRequestGateRejectsAnOlderResponse() {
        val gate = Mango9LatestRequestGate()
        val firstRequest = gate.next()
        val latestRequest = gate.next()

        assertTrue(!gate.isLatest(firstRequest))
        assertTrue(gate.isLatest(latestRequest))
        gate.invalidate()
        assertTrue(!gate.isLatest(latestRequest))
    }

    @Test
    fun openingTeamConversationClearsOnlyThatRoomImmediately() {
        val first = Mango9ChatRoom("first", listOf(1), "", "", 3, true)
        val second = Mango9ChatRoom("second", listOf(2), "", "", 2, true)

        val updated = Mango9ChatState(rooms = listOf(first, second)).markRoomReadLocally("first")

        assertEquals(0, updated.rooms.first { it.id == "first" }.unread)
        assertEquals(2, updated.rooms.first { it.id == "second" }.unread)
    }

    @Test
    fun openingSmsConversationClearsNormalizedPartyImmediately() {
        val selected = Mango9SmsParty("+1 (818) 916-4300", "", "", 4, "")
        val other = Mango9SmsParty("18189007897", "", "", 1, "")

        val updated = Mango9ChatState(smsParties = listOf(selected, other))
            .markSmsReadLocally("8189164300")

        assertEquals(0, updated.smsParties.first().unread)
        assertEquals(1, updated.smsParties.last().unread)
    }

    @Test
    fun conversationTabsKeepTeamAndSmsUnreadIndicatorsIndependent() {
        val teammate = Mango9ChatUser(7, "Ani Paytyan", "", "agent")
        val directRoom = Mango9ChatRoom("direct", listOf(7), "", "Team message", 3, true)
        val smsParty = Mango9SmsParty("8189164300", "", "SMS message", 4, "")
        val state = Mango9ChatState(
            users = listOf(teammate),
            rooms = listOf(directRoom),
            smsParties = listOf(smsParty),
            onlineUserIds = setOf(teammate.id),
        )

        val teamItems = Mango9MessagingListItems.team(state, { false }) { "Direct chat" }
        val smsItems = Mango9MessagingListItems.sms(state) { false }

        val teamRow = teamItems.single() as Mango9MessagingListItem.User
        val smsRow = smsItems.single() as Mango9MessagingListItem.Sms
        assertEquals(3, teamRow.room?.unread)
        assertEquals(4, smsRow.party.unread)

        val afterSmsOpen = state.markSmsReadLocally("+1 (818) 916-4300")
        val unchangedTeam = Mango9MessagingListItems.team(afterSmsOpen, { false }) { "Direct chat" }
            .single() as Mango9MessagingListItem.User
        val clearedSms = Mango9MessagingListItems.sms(afterSmsOpen) { false }
            .single() as Mango9MessagingListItem.Sms
        assertEquals(3, unchangedTeam.room?.unread)
        assertEquals(0, clearedSms.party.unread)
    }

    @Test
    fun smsConversationStatsUseOnlyAvailableServerMessages() {
        val messages = listOf(
            Mango9SmsMessage("in", "8185550100", "Photo", "2026-08-20 12:30:00", "", 2, true, "https://cdn.example.com/photo.jpg"),
            Mango9SmsMessage("sent", "8185550100", "Hello", "2026-08-21T14:45:00Z", "18185550199", 2, false, ""),
            Mango9SmsMessage("failed", "8185550100", "Retry", "not-a-date", "18185550199", 99, false, ""),
        )

        val stats = Mango9SmsConversationStats.build(messages)

        assertEquals(3, stats.total)
        assertEquals(2, stats.sent)
        assertEquals(1, stats.received)
        assertEquals(1, stats.attachments)
        assertEquals(1, stats.failed)
        assertEquals(listOf("18185550199"), stats.senderIds)
        assertTrue(stats.firstAvailableMessage != null)
        assertTrue(stats.latestMessage != null)
    }

    @Test
    fun crmConversationMatchRequiresAnExactNormalizedPhone() {
        val fuzzy = Mango9CrmRecord(1, 1, "Owner A", "Wrong client", "8185550109", "", "Active", "", "2026-08-01")
        val exact = Mango9CrmRecord(2, 2, "Owner B", "Exact lead", "+1 (818) 555-0100", "", "New", "", "2026-08-02")

        val match = Mango9SmsCrmMatch.exactMatch("818-555-0100", listOf(fuzzy), listOf(exact))

        assertEquals(2, match?.id)
        assertEquals(Mango9RecordKind.Lead, match?.kind)
        assertEquals("Exact lead", match?.name)
    }

    @Test
    fun localCallStatsOnlyCountMatchingDeviceHistory() {
        val facts = listOf(
            Mango9LocalCallFact("+1 (818) 555-0100", false, true, false, 100, 0),
            Mango9LocalCallFact("18185550100", true, false, true, 300, 125),
            Mango9LocalCallFact("18185550101", true, false, true, 500, 999),
        )

        val stats = Mango9LocalCallStats.build("818-555-0100", facts)

        assertEquals(2, stats.total)
        assertEquals(1, stats.inbound)
        assertEquals(1, stats.outbound)
        assertEquals(1, stats.missed)
        assertEquals(125, stats.connectedDurationSeconds)
        assertEquals(300L, stats.lastCallEpochSeconds)
    }
}
