/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.mango9

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import coil3.imageLoader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class Mango9ParityBehaviorInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    @After
    fun resetStores() {
        Mango9PushCallerIdentityCache.clear()
        context.getSharedPreferences(
            Mango9LineIdentityStore.PREFERENCES_FILE,
            Context.MODE_PRIVATE,
        ).edit().clear().commit()
        context.getSharedPreferences(
            Mango9MessagePushTokenStore.PREFERENCES_FILE,
            Context.MODE_PRIVATE,
        ).edit().clear().commit()
    }

    @Test
    fun parsesFlexisipCallerIdentityFromPush() {
        val payload =
            """{"aps":{"loc-key":"IC_MSG","loc-args":["8189164300"],"call-id":"call-123"},"from-uri":"sip:8189164300@manushak.mango9.com","display-name":"8189164300"}"""

        assertEquals(
            Mango9PushCallerIdentity("call-123", "+18189164300", "8189164300"),
            Mango9PushCallerIdentity.parse(payload),
        )
    }

    @Test
    fun parsesCallerIdentityFromNestedAlertLocationArguments() {
        val payload =
            """{"aps":{"alert":{"loc-key":"IC_MSG","loc-args":["sip:+18184885588@manushak.mango9.com"]},"call-id":"call-456"}}"""

        assertEquals(
            Mango9PushCallerIdentity("call-456", "+18184885588", "818-488-5588"),
            Mango9PushCallerIdentity.parse(payload),
        )
    }

    @Test
    fun rejectsMissingAndAnonymousCallerIdentity() {
        assertNull(Mango9PushCallerIdentity.parse("""{"aps":{"call-id":"call-789"}}"""))
        listOf(
            """{"aps":{"call-id":"call-999"},"from-uri":"sip:anonymous@anonymous.invalid"}""",
            """{"aps":{"call-id":"call-998"},"from-uri":"sip:anonymous@anonymous.invite"}""",
            """{"aps":{"call-id":"call-997"},"from-uri":"anonimous@anonimous.invite"}""",
        ).forEach { assertNull(Mango9PushCallerIdentity.parse(it)) }
    }

    @Test
    fun callerIdentityCacheIsCallIdScopedAndExpires() {
        val first =
            """{"aps":{"call-id":"call-a"},"from-uri":"sip:8189164300@example.com"}"""
        val second =
            """{"aps":{"call-id":"call-b"},"from-uri":"sip:8184885588@example.com"}"""
        Mango9PushCallerIdentityCache.cache(first, nowMillis = 1_000)
        Mango9PushCallerIdentityCache.cache(second, nowMillis = 2_000)

        assertEquals("+18189164300", Mango9PushCallerIdentityCache.get("call-a", 2_000)?.handle)
        assertEquals("+18184885588", Mango9PushCallerIdentityCache.get("call-b", 2_000)?.handle)
        assertNull(Mango9PushCallerIdentityCache.get("call-a", 121_000))
    }

    @Test
    fun lineIdentityCacheIsSeparatedBySipAccount() {
        val store = Mango9LineIdentityStore(context)
        val first = "sip:100@tenant-a.example.com"
        val second = "sip:100@tenant-b.example.com"
        store.activate(first)
        store.save(Mango9LineIdentity("100", "2025550101"), first)
        store.save(Mango9LineIdentity("200", "2025550199"), second)

        assertEquals("100", store.load(first)?.extensionNumber)
        assertEquals("202-555-0101", store.load(first)?.activeNumber)
        assertEquals("200", store.load(second)?.extensionNumber)
        assertEquals("202-555-0199", store.load(second)?.activeNumber)
        assertFalse(store.load(first) == store.load(second))

        store.activate(second)
        val active = context.getSharedPreferences(
            Mango9LineIdentityStore.PREFERENCES_FILE,
            Context.MODE_PRIVATE,
        )
        assertEquals("200", active.getString(Mango9LineIdentityStore.KEY_ACTIVE_EXTENSION, null))
        assertEquals("202-555-0199", active.getString(Mango9LineIdentityStore.KEY_ACTIVE_NUMBER, null))
        assertTrue(store.load(first) != null)
    }

    @Test
    fun messagePushTokenIsValidatedAndStoredOnlyOnDevice() {
        val store = Mango9MessagePushTokenStore(context)
        val token = "fcm:abcdefghijklmnopqrstuvwxyz-0123456789"

        assertTrue(store.save(token))
        assertEquals(token, store.load())
        assertFalse(store.save("short"))
        assertEquals(token, store.load())
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun applicationImageLoaderIncludesHttpsFetcherForMango9Media() {
        val fetchers = context.imageLoader.components.fetcherFactories
            .map { (factory, _) -> factory.javaClass.name }

        assertTrue(
            "Expected Coil's network fetcher, found: $fetchers",
            fetchers.any { it == "coil3.network.NetworkFetcher\$Factory" },
        )
    }
}
