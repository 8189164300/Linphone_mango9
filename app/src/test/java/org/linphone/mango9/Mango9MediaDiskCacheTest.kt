/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

/** Real loopback transport and Coil journal/snapshots; no app account, public host or personal media. */
class Mango9MediaDiskCacheTest {
    private class Fixture(maxBytes: Long = 1024) : AutoCloseable {
        val root = Files.createTempDirectory("mango9-media-cache-test-").toFile()
        val server = MockWebServer().also { it.start() }
        var now = 10_000L
        val cache = Mango9MediaDiskCache(
            File(root, "disk"), File(root, "open"),
            OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build(),
            maxBytes = maxBytes, maxFileBytes = 64, maxAgeMillis = 1_000, clock = { now },
        )

        fun request(path: String = "/voice") = Request.Builder().url(server.url(path)).build()

        fun body(value: String) { server.enqueue(MockResponse.Builder().body(value).build()) }

        override fun close() { cache.shutdown(); server.close(); root.deleteRecursively() }
    }

    @Test fun concurrentReadersShareOneDownloadAndCloseOnlyTheirOwnFiles() = runBlocking {
        Fixture().use { f ->
            f.server.enqueue(MockResponse.Builder().body("voice bytes").bodyDelay(100, TimeUnit.MILLISECONDS).build())
            val values = (1..20).map {
                async {
                    f.cache.acquire("account", f.request(), "m4a") { true }.use { asset ->
                    assertEquals("m4a", asset.file.extension)
                    asset.file.readText()
                }
                }
            }.awaitAll()
            assertEquals(List(20) { "voice bytes" }, values)
            assertEquals(1, f.server.requestCount)
            assertTrue(File(f.root, "open").listFiles().orEmpty().isEmpty())
            f.cache.acquire("account", f.request(), "m4a") { true }.use { assertEquals("voice bytes", it.file.readText()) }
            assertEquals(1, f.server.requestCount)
        }
    }

    @Test fun identicalUrlsAreIsolatedByOwnerAndExpiryWindow() = runBlocking {
        Fixture().use { f ->
            f.body("account A"); f.body("account B"); f.body("fresh A")
            f.cache.acquire("A", f.request(), "m4a") { true }.use { assertEquals("account A", it.file.readText()) }
            f.cache.acquire("B", f.request(), "m4a") { true }.use { assertEquals("account B", it.file.readText()) }
            f.cache.acquire("A", f.request(), "m4a") { true }.use { assertEquals("account A", it.file.readText()) }
            f.now += 1_000
            f.cache.acquire("A", f.request(), "m4a") { true }.use { assertEquals("fresh A", it.file.readText()) }
            assertEquals(3, f.server.requestCount)
        }
    }

    @Test fun disabledCachingNeverReusesOldBytesAndClosesItsTemporaryFiles() = runBlocking {
        Fixture().use { f ->
            f.body("cached"); f.body("uncached one"); f.body("uncached two")
            f.cache.acquire("A", f.request(), "m4a") { true }.close()
            repeat(2) { index ->
                val asset = f.cache.acquire("A", f.request(), "m4a", cacheAllowed = false) { true }
                assertEquals(if (index == 0) "uncached one" else "uncached two", asset.file.readText())
                asset.close()
                assertFalse(asset.file.exists())
            }
            assertEquals(3, f.server.requestCount)
            assertEquals(0L, f.cache.storedBytes)
            assertTrue(File(f.root, "open").listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun canceledUncachedDownloadReturnsItsSlotWithoutLeavingPartialBytes() = runBlocking {
        Fixture().use { f ->
            f.server.enqueue(MockResponse.Builder().body("slow").bodyDelay(2, TimeUnit.SECONDS).build())
            val load = async { f.cache.acquire("A", f.request(), "m4a", cacheAllowed = false) { true } }
            withTimeout(2_000) { while (f.server.requestCount == 0) delay(10) }
            withTimeout(1_000) { load.cancelAndJoin() }
            assertTrue(File(f.root, "open").listFiles().orEmpty().isEmpty())
            repeat(6) { f.body("fresh") }
            withTimeout(2_000) {
                repeat(6) { f.cache.acquire("A", f.request(), "m4a", cacheAllowed = false) { true }.close() }
            }
            assertEquals(0L, f.cache.storedBytes)
        }
    }

    @Test fun declaredAndChunkedOverLimitFilesAreNotCachedAndCanBeRetried() = runBlocking {
        Fixture().use { f ->
            f.body("x".repeat(65))
            f.server.enqueue(MockResponse.Builder().chunkedBody("x".repeat(65), 8).build())
            f.body("valid")
            repeat(2) {
                assertTrue(runCatching { f.cache.acquire("A", f.request(), "m4a") { true } }.isFailure)
            }
            f.cache.acquire("A", f.request(), "m4a") { true }.use { assertEquals("valid", it.file.readText()) }
            assertEquals(3, f.server.requestCount)
        }
    }

    @Test fun emptyAndErrorResponsesNeverBecomeSuccessfulCacheHits() = runBlocking {
        Fixture().use { f ->
            f.body("")
            f.server.enqueue(MockResponse.Builder().code(403).body("not media").build())
            f.body("valid")
            repeat(2) { assertTrue(runCatching { f.cache.acquire("A", f.request(), "wav") { true } }.isFailure) }
            f.cache.acquire("A", f.request(), "wav") { true }.use { assertEquals("valid", it.file.readText()) }
            assertEquals(3, f.server.requestCount)
        }
    }

    @Test fun cancellationAbortsBlockedIoBeforeReusingTheEditor() = runBlocking {
        Fixture().use { f ->
            f.server.enqueue(MockResponse.Builder().body("old").bodyDelay(2, TimeUnit.SECONDS).build())
            f.body("new")
            val first = async { f.cache.acquire("A", f.request(), "m4a") { true } }
            withTimeout(2_000) { while (f.server.requestCount == 0) delay(10) }
            withTimeout(1_000) { first.cancelAndJoin() }
            f.cache.acquire("A", f.request(), "m4a") { true }.use { assertEquals("new", it.file.readText()) }
            assertEquals(2, f.server.requestCount)
            assertTrue(File(f.root, "open").listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun revokedOwnerCannotPublishOrReceiveACompletedDownload() = runBlocking {
        Fixture().use { f ->
            val valid = AtomicBoolean(true)
            f.server.enqueue(MockResponse.Builder().body("old").bodyDelay(100, TimeUnit.MILLISECONDS).build())
            f.body("new")
            val first = async { runCatching { f.cache.acquire("A", f.request(), "m4a", valid::get) } }
            withTimeout(2_000) { while (f.server.requestCount == 0) delay(10) }
            valid.set(false)
            assertSame(Mango9ApiException.Unauthorized, first.await().exceptionOrNull())
            valid.set(true)
            f.cache.acquire("A", f.request(), "m4a", valid::get).use { assertEquals("new", it.file.readText()) }
            assertEquals(2, f.server.requestCount)
        }
    }

    @Test fun evictingAnOwnerPreservesTheHeldFileUntilItsViewerCloses() = runBlocking {
        Fixture().use { f ->
            f.body("held"); f.body("replacement")
            val asset = f.cache.acquire("A", f.request(), "m4a") { true }
            f.cache.evictOwner("A")
            assertEquals("held", asset.file.readText())
            asset.close(); asset.close()
            assertFalse(asset.file.exists())
            f.cache.acquire("A", f.request(), "m4a") { true }.use { assertEquals("replacement", it.file.readText()) }
            assertEquals(2, f.server.requestCount)
        }
    }

    @Test fun lruPrunesReleasedFilesWithoutDeletingActiveViewerFiles() = runBlocking {
        Fixture(maxBytes = 100).use { f ->
            f.body("a".repeat(60)); f.body("b".repeat(60)); f.body("c")
            val first = f.cache.acquire("A", f.request("/a"), "m4a") { true }
            val second = f.cache.acquire("A", f.request("/b"), "m4a") { true }
            try {
                assertEquals(60L, first.file.length())
                assertEquals(60L, second.file.length())
            } finally { first.close(); second.close() }
            withTimeout(2_000) { while (f.cache.storedBytes > 100) delay(10) }
            f.cache.acquire("A", f.request("/a"), "m4a") { true }.use { assertEquals("c", it.file.readText()) }
        }
    }

    @Test fun invalidOwnerAndExtensionNeverStartRequests() = runBlocking {
        Fixture().use { f ->
            assertTrue(runCatching { f.cache.acquire("A", f.request(), "../../bad") { true } }.isFailure)
            assertSame(
                Mango9ApiException.Unauthorized,
                runCatching { f.cache.acquire("A", f.request(), "m4a") { false } }.exceptionOrNull()
            )
            assertEquals(0, f.server.requestCount)
        }
    }

    @Test fun aDecoderFailureInvalidatesThePinnedEntryBeforeRetry() = runBlocking {
        Fixture().use { f ->
            f.body("bad bytes"); f.body("fresh bytes")
            val failed = f.cache.acquire("A", f.request(), "m4a") { true }
            failed.invalidate(); failed.invalidate()
            assertTrue(failed.file.exists())
            failed.close()
            f.cache.acquire("A", f.request(), "m4a") { true }.use { assertEquals("fresh bytes", it.file.readText()) }
            assertEquals(2, f.server.requestCount)
        }
    }

    @Test fun removingAnUnobservedOwnerAndManyTinyEntriesRemainBounded() = runBlocking {
        Fixture(maxBytes = 10_000).use { f ->
            repeat(520) { index ->
                f.body("a")
                f.cache.acquire("A", f.request("/$index"), "wav") { true }.close()
            }
            assertEquals(512, f.cache.trackedEntries)
            f.cache.retainOwners(setOf("B"))
            assertEquals(0, f.cache.trackedEntries)
            f.body("new")
            f.cache.acquire("A", f.request("/519"), "wav") { true }.use { assertEquals("new", it.file.readText()) }
        }
    }

    @Test fun ownerKeysIgnoreTokenRefreshAndSelectionButNotLoginOrPrincipal() {
        val original = Mango9SessionLease(pushTestSession(), 3, 2)
        val key = Mango9MediaCache.ownerKey(original)
        assertEquals(key, Mango9MediaCache.ownerKey(original.copy(selectionGeneration = 9)))
        assertEquals(key, Mango9MediaCache.ownerKey(original.copy(session = original.session.copy(accessToken = "new", refreshToken = "new"))))
        assertNotEquals(key, Mango9MediaCache.ownerKey(original.copy(loginGeneration = 4)))
        assertNotEquals(key, Mango9MediaCache.ownerKey(original.copy(session = original.session.copy(userId = "other"))))
        assertNotEquals(key, Mango9MediaCache.ownerKey(original.copy(session = original.session.copy(crmBaseUrl = "https://other.example.com"))))
        assertTrue(key.matches(Regex("[a-f0-9]{64}")))
    }
}
