// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.linphone.core.Privacy

class Mango9IncomingCallerStoreTest {
    private val account = "sip:100@fixture.example.invalid"
    private val pushed = Mango9PushCallerIdentity("call", "+12025550123", "Test caller", account)
    private val sip = Mango9CallerPresentation("+12025550456", "SIP caller", "sip")

    private fun resolve(store: Mango9IncomingCallerStore, now: Long = 1001, token: Long = 1, account: String? = this.account, placeholder: Boolean = true, privacy: Boolean = false) =
        store.resolve("call", token, account, placeholder, if (placeholder) Mango9CallerPresentation.unknown else sip, privacy, now)

    @Test fun boundIdentitySurvivesPendingTtlUntilItsNativeCallEnds() {
        val store = Mango9IncomingCallerStore()
        assertTrue(store.cache(pushed, 1000))
        assertEquals("Test caller", resolve(store).name)
        assertEquals("Test caller", resolve(store, now = 200_000).name)
        assertEquals("Test caller", store.finish("call", 1, 200_001)?.name)
        assertFalse(store.cache(pushed, 200_002))
    }

    @Test fun unboundPushExpiresWithoutAuthorizingAnotherCall() {
        val store = Mango9IncomingCallerStore()
        store.cache(pushed, 1000)
        assertEquals("unknown", resolve(store, now = 121_000).source)
    }

    @Test fun firstPushWinsAndConflictingDuplicatesCannotRenameACall() {
        val store = Mango9IncomingCallerStore()
        store.cache(pushed, 1000)
        assertFalse(store.cache(pushed.copy(displayName = "Replacement"), 1001))
        assertEquals("Test caller", resolve(store, now = 1002).name)
        assertFalse(store.cache(pushed.copy(displayName = "Replacement"), 1003))
        assertEquals("Test caller", resolve(store, now = 1004).name)
    }

    @Test fun recipientMismatchAndMissingAccountFailClosed() {
        for (owner in listOf(null, "sip:700@fixture.example.invalid", "sip:100@other.example.invalid")) {
            val store = Mango9IncomingCallerStore(); store.cache(pushed, 1000)
            assertEquals("unknown", resolve(store, account = owner).source)
        }
    }

    @Test fun accountCanonicalizationPreservesUserCaseAndSeparatesDomains() {
        assertEquals("User@example.invalid", Mango9IncomingCallerStore.accountKey("Caller <sips:User@EXAMPLE.INVALID;transport=tls>"))
        assertNotEquals(Mango9IncomingCallerStore.accountKey("sip:User@example.invalid"), Mango9IncomingCallerStore.accountKey("sip:user@example.invalid"))
        assertNull(Mango9IncomingCallerStore.accountKey("100"))
    }

    @Test fun matchingNativeIdentityCanBindAfterRecipientBecomesKnown() {
        val store = Mango9IncomingCallerStore(); store.cache(pushed, 1000)
        assertEquals("unknown", resolve(store, account = null).source)
        assertEquals("push", resolve(store, now = 1002).source)
    }

    @Test fun anotherNativeCallOrAccountCannotReuseOrRetireBoundIdentity() {
        val store = Mango9IncomingCallerStore(); store.cache(pushed, 1000); resolve(store)
        assertEquals("unknown", resolve(store, token = 2).source)
        assertEquals("unknown", resolve(store, account = "sip:700@fixture.example.invalid").source)
        assertNull(store.finish("call", 2, 1002))
        assertEquals("push", resolve(store, now = 1003).source)
    }

    @Test fun actualSipAlwaysReplacesPushAndPlaceholderNeverDowngradesIt() {
        val store = Mango9IncomingCallerStore(); store.cache(pushed, 1000); resolve(store)
        assertEquals(sip, resolve(store, placeholder = false))
        assertEquals(sip, resolve(store, now = 2000))
        assertFalse(store.cache(pushed, 2001))
    }

    @Test fun explicitPrivacyOverridesPushAndCannotBeRevealedByLatePush() {
        val store = Mango9IncomingCallerStore(); store.cache(pushed, 1000); resolve(store)
        assertEquals(Mango9CallerPresentation.withheld, resolve(store, placeholder = false, privacy = true))
        assertFalse(store.cache(pushed, 1002))
        assertEquals(Mango9CallerPresentation.withheld, resolve(store, now = 1003))
    }

    @Test fun privacyChecksUseExplicitMasksHeadersAndAnonymousIdentity() {
        assertTrue(Mango9IncomingCallerStore.hasPrivacy(Privacy.User.toInt(), null, "100", null))
        assertTrue(Mango9IncomingCallerStore.hasPrivacy(Privacy.Id.toInt(), null, "100", null))
        assertTrue(Mango9IncomingCallerStore.hasPrivacy(0, "session; id", "100", null))
        assertTrue(Mango9IncomingCallerStore.hasPrivacy(0, null, "Anonymous", null))
        assertFalse(Mango9IncomingCallerStore.hasPrivacy(Privacy.Header.toInt(), "none", "100", "Real name"))
    }

    @Test fun latePushCanFillOnlyAMatchingUnauthoritativeCall() {
        val store = Mango9IncomingCallerStore()
        resolve(store)
        assertFalse(store.cache(pushed.copy(recipient = "sip:700@fixture.example.invalid"), 1002))
        assertTrue(store.cache(pushed, 1003))
        assertEquals("push", resolve(store, now = 1004).source)
    }

    @Test fun endedTombstoneIsBoundedButRejectsBackwardClockDuplicates() {
        val store = Mango9IncomingCallerStore(); store.cache(pushed, 1000); resolve(store)
        store.finish("call", 1, 2000)
        assertFalse(store.cache(pushed, 1000))
        assertFalse(store.cache(pushed, 301_999))
        assertTrue(store.cache(pushed, 302_000))
    }

    @Test fun explicitAnonymousFromCannotBorrowAVisiblePushDisplayName() {
        val payload = JSONObject().put("call-id", "call").put("from-uri", "sip:anonymous@anonymous.invalid")
            .put("display-name", "+12025550123").toString()
        assertNull(Mango9PushCallerIdentity.parse(payload))
    }

    @Test fun historyMetadataRoundTripsWithoutChangingOrEmbeddingRouting() {
        for (value in listOf(sip, Mango9CallerPresentation("+12025550123", "Հայերեն 👩🏽‍💻", "push"), Mango9CallerPresentation.withheld)) {
            assertEquals(value, Mango9CallHistoryPresentation.decode(Mango9CallHistoryPresentation.encode(value)))
        }
        assertFalse(Mango9CallHistoryPresentation.canReplace("foreign-reference"))
        assertTrue(Mango9CallHistoryPresentation.canReplace(""))
        assertNull(Mango9CallHistoryPresentation.encode(Mango9CallerPresentation.unknown))
        assertNull(Mango9CallHistoryPresentation.decode("mango9-caller-v1:not-base64"))
        assertNull(Mango9CallHistoryPresentation.encode(sip.copy(name = "x".repeat(513))))
    }

    @Test fun socialSearchPreservesUnicodeAndLimitsCodePoints() {
        assertEquals("👨‍👩‍👧‍👦 👍🏽 🇺🇸", Mango9SocialSearch.normalize("  👨‍👩‍👧‍👦 👍🏽 🇺🇸  "))
        val result = Mango9SocialSearch.normalize("😀".repeat(201))
        assertEquals(200, result.codePointCount(0, result.length))
        assertEquals("😀".repeat(200), result)
    }
}
