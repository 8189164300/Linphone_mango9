/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.mango9

import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Mango9MessagePushTest {
    @Test
    fun liveChatGatewayPayloadKeepsConversationAccountAndPreviewThroughNotificationTap() {
        val id = "24b39eb4-03da-4cc1-82eb-e9d039d8336a"
        val payload = mapOf(
            "event" to "livechat.message", "category" to "livechat",
            "conversation_id" to id.uppercase(), "crm_id" to "fixture-crm",
            "sip_identity" to "sip:7@tenant.example.invalid", "name" to "Visitor · Website",
            "body" to "Hello 👋", "message_id" to "livechat-123",
            "app_path" to "/inbox/livechat/$id",
        )
        for (data in listOf(payload, mapOf("mango9" to JSONObject(payload).toString()))) {
            val push = requireNotNull(Mango9MessagePush.parse(data))
            assertEquals(Mango9MessagePushTarget.LiveChat(id), push.target)
            assertEquals("fixture-crm", push.crmId)
            assertEquals(payload["sip_identity"], push.sipIdentity)
            assertEquals("Visitor · Website", push.previewTitle)
            assertEquals("Hello 👋", push.previewBody)
            val bound = push.copy(notificationLogin = "local-login-incarnation")
            assertEquals(bound, Mango9MessagePush.fromJson(bound.toJson()))
        }
    }

    @Test
    fun liveChatRejectsMissingAccountAndMalformedConversationInsteadOfOpeningSms() {
        val payload = mapOf(
            "event" to "livechat.message", "crm_id" to "fixture-crm",
            "sip_identity" to "sip:7@tenant.example.invalid", "conversation_id" to "24b39eb4-03da-4cc1-82eb-e9d039d8336a"
        )
        for (key in listOf("crm_id", "sip_identity", "conversation_id")) {
            assertNull(Mango9MessagePush.parse(payload - key))
            assertNull(Mango9MessagePush.parse(payload + (key to "")))
        }
        for (id in listOf("1-1-1-1-1", "42", "../sms", "https://example.invalid/chat")) {
            assertNull(Mango9MessagePush.parse(payload + ("conversation_id" to id)))
            assertNull(Mango9MessagePush.fromJson(JSONObject(payload + ("type" to "livechat") + ("conversation_id" to id)).toString()))
        }
        assertNull(Mango9MessagePush.parse(payload - "conversation_id" + ("app_path" to "/inbox/livechat/24b39eb4-03da-4cc1-82eb-e9d039d8336a")))
        assertNull(Mango9MessagePush.fromJson("""{"type":"livechat","conversation_id":"24b39eb4-03da-4cc1-82eb-e9d039d8336a"}"""))
    }

    @Test
    fun parsesNestedTeamChatTargetAndAccount() {
        val push = Mango9MessagePush.parse(
            mapOf(
                "mango9" to
                    """{"event":"chat.message","sender_user_id":"42","room_id":"room-7","name":"Ani","sip_identity":"sip:700@tenant.example.com","crm_id":"crm-1"}""",
            ),
        )

        assertEquals(
            Mango9MessagePush(
                Mango9MessagePushTarget.Chat(42, "Ani", "room-7"),
                "sip:700@tenant.example.com",
                "crm-1",
            ),
            push,
        )
    }

    @Test
    fun parsesAndNormalizesSmsTarget() {
        val push = Mango9MessagePush.parse(
            mapOf(
                "event" to "sms.received",
                "phone" to "(818) 916-4300",
                "name" to "Caller",
                "crmId" to "crm-2",
            ),
        )

        assertEquals(
            Mango9MessagePush(
                Mango9MessagePushTarget.Sms("18189164300", "Caller"),
                null,
                "crm-2",
            ),
            push,
        )
    }

    @Test
    fun parsesLeadAndRejectsUnusableTargets() {
        assertEquals(
            Mango9MessagePushTarget.Lead(91),
            Mango9MessagePush.parse(
                mapOf("mango9_event" to "lead.assigned", "leadId" to "91", "recipient_user_id" to "42", "crm_id" to "fixture", "sip_identity" to "sip:42@tenant.example.invalid"),
            )?.target,
        )
        assertNull(Mango9MessagePush.parse(mapOf("event" to "chat.message")))
        assertNull(Mango9MessagePush.parse(mapOf("event" to "sms.received", "phone" to "555")))
        assertNull(Mango9MessagePush.parse(mapOf("event" to "lead.created", "lead_id" to "0")))
    }

    @Test
    fun notificationPayloadRoundTripsWithoutLosingRoomOrTenant() {
        val expected = Mango9MessagePush(
            Mango9MessagePushTarget.Chat(19, "Support", "room-19"),
            "sip:19@tenant.example.com",
            "crm-19",
        )

        assertEquals(expected, Mango9MessagePush.fromJson(expected.toJson()))
    }

    @Test
    fun androidCrmGatewayDataKeepsPreviewAndExactScopedDestination() {
        val common = mapOf(
            "crm_id" to "fixture-crm",
            "sip_identity" to "sip:7@tenant.example.invalid",
            "recipient_user_id" to "42",
            "notification_id" to "fixture-message",
            "title" to "Fixture title",
            "body" to "Hello 👍🏽 👨‍👩‍👧‍👦 🇦🇲",
        )
        val cases = listOf(
            (mapOf("event" to "appointment.booked", "event_id" to "91") to Mango9MessagePushTarget.Appointment(91)),
            (mapOf("event" to "lead.created", "lead_id" to "91") to Mango9MessagePushTarget.Lead(91)),
            (mapOf("event" to "leads.imported") to Mango9MessagePushTarget.Leads),
        )
        for ((details, target) in cases) {
            val parsed = requireNotNull(Mango9MessagePush.parse(common + details))
            assertEquals(target, parsed.target)
            assertEquals("fixture-crm", parsed.crmId)
            assertEquals("sip:7@tenant.example.invalid", parsed.sipIdentity)
            assertEquals("42", parsed.recipientUserId)
            assertEquals(common["title"], parsed.previewTitle)
            assertEquals(common["body"], parsed.previewBody)
            assertEquals(parsed, Mango9MessagePush.fromJson(parsed.toJson()))
        }
    }

    @Test
    fun registrationRequestMatchesIosContractAndUsesHttps() {
        val request = Mango9MessagePushRegistration.registerRequest(
            session(),
            chatToken = "short-lived-chat-token",
            pushToken = "fcm:abcdefghijklmnopqrstuvwxyz-0123456789",
            deviceId = "device-7",
            sipIdentity = "sip:700@tenant.example.com",
        ) ?: throw AssertionError("Expected a registration request")
        val json = request.bodyJson()

        assertEquals("POST", request.method)
        assertEquals("https://messages.example.com/api/push/register", request.url.toString())
        assertEquals("Bearer short-lived-chat-token", request.header("Authorization"))
        assertEquals("fcm:abcdefghijklmnopqrstuvwxyz-0123456789", json.getString("token"))
        assertEquals("firebase", json.getString("platform"))
        assertEquals("device-7", json.getString("device_id"))
        assertEquals("crm-7", json.getString("crm_id"))
        assertEquals("sip:700@tenant.example.com", json.getString("sip_identity"))
        assertEquals("production", json.getString("environment"))
        assertFalse(json.has("access_token"))
    }

    @Test
    fun unregistrationContainsOnlyDeviceIdAndRejectsUnsafeEndpoints() {
        val request = Mango9MessagePushRegistration.unregisterRequest(
            session(),
            chatToken = "short-lived-chat-token",
            deviceId = "device-7",
        ) ?: throw AssertionError("Expected an unregistration request")

        assertEquals("DELETE", request.method)
        assertEquals(setOf("device_id"), request.bodyJson().keys().asSequence().toSet())
        assertTrue(Mango9MessagePushTokenStore.isValidToken("fcm:abcdefghijklmnopqrstuvwxyz-0123456789"))
        assertFalse(Mango9MessagePushTokenStore.isValidToken("short"))
        assertNull(
            Mango9MessagePushRegistration.registerRequest(
                session().copy(smsChatApi = "http://messages.example.com/api"),
                "short-lived-chat-token",
                "fcm:abcdefghijklmnopqrstuvwxyz-0123456789",
                "device-7",
                "sip:700@tenant.example.com",
            ),
        )
    }

    private fun session() = Mango9Session(
        crmId = "crm-7",
        crmBaseUrl = "https://crm.example.com",
        crmApiBaseUrl = "https://crm.example.com/app/api/v2",
        userId = "7",
        parentClientId = "1",
        role = "agent",
        loginId = "ani@example.com",
        displayName = "Ani",
        accessToken = "access-token-must-not-leak",
        refreshToken = "refresh-token-must-not-leak",
        smsChatApi = "https://messages.example.com/api",
        connectWebsocket = "wss://messages.example.com/socket",
        enrollmentExpiresAtEpochSeconds = 1_900_000_000,
        sipIdentity = "sip:700@tenant.example.com",
    )

    private fun okhttp3.Request.bodyJson(): JSONObject {
        val buffer = Buffer()
        body?.writeTo(buffer)
        return JSONObject(buffer.readUtf8())
    }
}
