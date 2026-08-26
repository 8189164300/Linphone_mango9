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
}
