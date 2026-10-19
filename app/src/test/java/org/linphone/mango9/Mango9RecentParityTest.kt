// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import java.time.Instant
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Mango9RecentParityTest {
    private val fixture = Mango9CalendarTestFixtures

    @Test fun optionalPushPreferencesNeverInventSupportOnAnOlderServer() {
        val old = Mango9CalendarJson.preferences(JSONObject(fixture.settingsJson))
        assertNull(old.socialPush); assertNull(old.leadPush); assertNull(old.eventsPush)
        for (key in listOf("social_push", "lead_push", "events_push")) assertFalse(old.body().has(key))
        val json = JSONObject(fixture.settingsJson).put("social_push", false).put("lead_push", true).put("events_push", false)
        val updated = Mango9CalendarJson.preferences(json)
        assertEquals(false, updated.socialPush); assertEquals(true, updated.leadPush); assertEquals(false, updated.eventsPush)
        assertEquals(false, updated.body().getBoolean("social_push"))
        assertEquals(true, updated.body().getBoolean("lead_push"))
        assertThrows(Mango9CalendarFailure::class.java) { Mango9CalendarJson.preferences(json.put("social_push", "false")) }
    }

    @Test fun dateEditReadsBackPersistedDatesAndNeverReplaysAnUnconfirmedWrite() = runBlocking {
        val event = fixture.event()
        val moved = Mango9AppointmentDraft.from(event).copy(start = event.startAt.plusSeconds(86400), end = event.endAt.plusSeconds(86400))
        for (persisted in listOf(false, true)) {
            val calls = mutableListOf<Request>()
            val repo =
                Mango9CalendarRepository(Mango9CalendarTestFixtures.Sessions(), { error("Unexpected token refresh") }) { request ->
                calls += request
                val json = JSONObject(fixture.eventJson)
                if (request.method == "PATCH" || persisted) json.put("start_at", moved.start.toString()).put("end_at", moved.end.toString())
                Mango9CalendarResponse(200, fixture.envelope(json.toString()))
            }
            val result = runCatching { repo.save(repo.capture()!!, moved, fixture.metadata(), event, event.timezone) }
            assertEquals(listOf("PATCH", "GET"), calls.map { it.method })
            if (persisted) {
                assertEquals(moved.start, result.getOrThrow().startAt)
            } else {
                assertEquals("event_save_unconfirmed", (result.exceptionOrNull() as Mango9CalendarFailure).code)
            }
        }
    }

    @Test fun providerReactionsAreNotNumericStaffMapsAndRemovedTombstonesAreHidden() {
        val emoji = listOf("👍", "❤️", "👍🏽", "👨‍👩‍👧‍👦", "🇦🇲")
        for (value in emoji) {
            val staff = Mango9StaffReactions.parse(JSONObject().put("version", 4).put("reactions", JSONObject().put(value, org.json.JSONArray(listOf(7, 9, 7)))))
            assertEquals(listOf(7, 9), staff.values[value]); assertEquals(4, staff.version)
            val provider = JSONObject().put("self", JSONObject().put("emoji", value).put("action", "added").put("fromMe", true).put("at", "2026-01-01T00:00:00Z"))
            assertEquals(value, Mango9SocialReactions.parse(provider).self)
            assertTrue(Mango9StaffReactions.parse(JSONObject().put("reactions", provider)).values.isEmpty())
            provider.getJSONObject("self").put("action", "removed")
            assertNull(Mango9SocialReactions.parse(provider).self)
            provider.getJSONObject("self").put("action", "added").put("fromMe", false)
            assertNull(Mango9SocialReactions.parse(provider).self)
        }
        assertTrue(Mango9StaffReactions.parse(JSONObject("""{"reactions":{"👍":["7"]}}""")).values.isEmpty())
    }

    @Test fun capabilityGatesDoNotExposeSmsTapbackOrUnsupportedProviderActions() {
        val sms = Mango9ReactionCapabilities(true, "workspace", false, false, false, true, true)
        assertTrue(sms.permits(Mango9MessageChannel.sms))
        assertFalse(sms.permits(Mango9MessageChannel.team))
        assertFalse(sms.copy(recipientVisible = true).permits(Mango9MessageChannel.sms))
        assertFalse(sms.copy(textFallback = true).permits(Mango9MessageChannel.sms))
        val dm = sms.copy(scope = "provider", recipientVisible = true, providerReactions = true, canAdd = false)
        assertFalse(dm.permits(Mango9MessageChannel.social)); assertTrue(dm.permits(Mango9MessageChannel.social, true))
    }

    @Test fun socialMediaPolicyRetainsProviderRestrictions() {
        Mango9SocialMediaPolicy.validate("facebook", "image/png", 180L * 180, "test")
        Mango9SocialMediaPolicy.validate("instagram", "video/mp4", 25L * 1024 * 1024, "test")
        Mango9SocialMediaPolicy.validate("tiktok", "image/png", 3L * 1024 * 1024, "")
        for ((platform, mime, bytes, caption) in listOf(
            listOf("tiktok", "image/png", "1", "not allowed"),
            listOf("tiktok", "video/mp4", "1", ""), listOf("facebook", "application/pdf", "1", ""),
            listOf("instagram", "image/png", (25L * 1024 * 1024 + 1).toString(), "")
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                Mango9SocialMediaPolicy.validate(platform, mime, bytes.toLong(), caption)
            }
        }
    }

    @Test fun removalOnlyProviderCapabilityKeepsTheExistingEmojiAccessible() {
        assertEquals(listOf("👍🏽"), Mango9ReactionChoices.visible(false, true, listOf("👍🏽")))
        assertTrue(Mango9ReactionChoices.visible(false, false, listOf("👍🏽")).isEmpty())
        assertEquals(1, Mango9ReactionChoices.visible(true, true, listOf("❤️")).count { it == "❤️" })
        assertFalse(Mango9ReactionChoices.visible(true, false, listOf("🇦🇲")).contains("🇦🇲"))
        assertTrue(Mango9ReactionChoices.visible(true, true, emptyList(), listOf("🌈")).contains("🌈"))
        assertEquals(listOf("👍🏽"), Mango9ReactionChoices.visible(false, true, listOf("👍🏽"), listOf("🌈")))
    }

    @Test fun reactionScopeIncludesServerOwnerAndAccountWithoutTokens() {
        val original = fixture.session().copy(smsChatApi = "https://messages.example.invalid/api")
        val scope = Mango9MessagingApi.account(original)
        assertEquals(scope, Mango9MessagingApi.account(original.copy(accessToken = "rotated", refreshToken = "rotated")))
        for (other in listOf(original.copy(crmId = "different"), original.copy(userId = "99"), original.copy(parentClientId = "99"), original.copy(crmBaseUrl = "https://other.example.invalid"), original.copy(sipIdentity = "sip:99@example.invalid"))) assertNotEquals(scope, Mango9MessagingApi.account(other))
        val social = Mango9MessagingApi.wire(original, "crm-token", Mango9MessageChannel.social, listOf("inbox"), mapOf("accountId" to "a&b"), null, "GET")
        assertEquals("crm.example.invalid", social.url.host)
        assertEquals("a&b", social.url.queryParameter("accountId"))
        assertEquals("/app/ad-manager/api/social-posting/inbox", social.url.encodedPath)
        val sms = Mango9MessagingApi.wire(original, "chat-token", Mango9MessageChannel.sms, listOf("reactions", "sms", "test"), emptyMap(), null, "GET")
        assertEquals("messages.example.invalid", sms.url.host)
        assertThrows(IllegalArgumentException::class.java) {
            Mango9MessagingApi.wire(original, "secret", Mango9MessageChannel.sms, listOf(".."), emptyMap(), null, "GET")
        }
        assertThrows(Mango9MessagingFailure::class.java) {
            Mango9MessagingApi.decode(Mango9CalendarResponse(403, """{"error":"posting_session_required"}"""))
        }
    }

    @Test fun activitiesAndPostgresTaskDatesMatchIos() {
        assertEquals(Mango9ActivityKind.Appointments, Mango9ActivityKind.of(""))
        assertEquals(Mango9ActivityKind.Tasks, Mango9ActivityKind.of(" Follow Up Call "))
        assertEquals(Mango9ActivityKind.Tasks, Mango9ActivityKind.of("follow_up"))
        assertEquals(Mango9ActivityKind.Events, Mango9ActivityKind.of("meeting"))
        assertEquals(Instant.parse("2026-10-17T16:00:00Z"), Mango9ManagedTask.date("2026-10-17 09:00:00-07"))
        assertEquals(Instant.parse("2026-10-17T16:00:00.123456Z"), Mango9ManagedTask.date("2026-10-17 16:00:00.123456+00"))
        assertNull(Mango9ManagedTask.date("not a date"))
    }

    @Test fun newPushTargetsRequireAccountScopeAndRemainBoundOnRoundTrip() {
        val scope =
            mapOf("crm_id" to "fixture", "sip_identity" to "sip:42@tenant.example.invalid", "recipient_user_id" to "42")
        for (event in listOf("lead.created", "leads.imported", "appointment.booked", "social.message")) {
            val details = when (event) {
                "lead.created" -> mapOf("lead_id" to "91")
                "appointment.booked" -> mapOf("event_id" to "91")
                "social.message" -> mapOf("thread_id" to "12345678-1234-1234-1234-123456789abc")
                else -> emptyMap()
            }
            val data = scope + details + ("event" to event)
            val push = requireNotNull(Mango9MessagePush.parse(data))
            assertEquals(push, Mango9MessagePush.fromJson(push.toJson()))
            assertNull(Mango9MessagePush.parse(data - "crm_id")); assertNull(Mango9MessagePush.parse(data - "sip_identity"))
            if (event != "social.message") assertNull(Mango9MessagePush.parse(data - "recipient_user_id"))
        }
        assertNull(Mango9MessagePush.parse(scope + mapOf("event" to "leads.imported", "lead_id" to "91")))
    }
}
