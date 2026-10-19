// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Mango9ApiHttpTransportTest {
    @Test fun serverCannotCauseAnAutomaticWriteReplay() = runBlocking {
        for (method in listOf("POST", "PATCH", "DELETE")) MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "0").body("{}").build())
            server.enqueue(MockResponse.Builder().body("{}").build()); server.start()
            val result = runCatching {
                Mango9ApiHttpTransport().execute(
                    Request.Builder().url(server.url("/fixture"))
                .method(method, "{}".toRequestBody()).build()
                )
            }
            assertTrue(result.exceptionOrNull() is IOException); assertEquals(1, server.requestCount)
        }
    }

    @Test fun readRecoversFromTransientFailureWithoutUnboundedReplay() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "0").body("{}").build())
            server.enqueue(MockResponse.Builder().body("recovered").build())
            server.start()
            val result = Mango9ApiHttpTransport().execute(Request.Builder().url(server.url("/dashboard")).build())
            assertEquals("recovered", result.body.toString(Charsets.UTF_8))
            assertEquals(2, server.requestCount)
        }
        MockWebServer().use { server ->
            repeat(3) {
                server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "0").body("{}").build())
            }
            server.start()
            val result = runCatching {
                Mango9ApiHttpTransport().execute(Request.Builder().url(server.url("/dashboard")).build())
            }
            assertTrue(result.exceptionOrNull() is IOException)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun redirectsNeverForwardTheBearer() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(307).addHeader("Location", "/other").body("{}").build())
            server.enqueue(MockResponse.Builder().body("{}").build()); server.start()
            val result = Mango9ApiHttpTransport().execute(
                Request.Builder().url(server.url("/fixture"))
                .header("Authorization", "Bearer synthetic-fixture").build()
            )
            assertEquals(307, result.status); assertEquals(1, server.requestCount)
        }
    }

    @Test fun responseSizeWithoutContentLengthIsBounded() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().chunkedBody("x".repeat(2 * 1024 * 1024 + 1), 8192).build()); server.start()
            val result =
                runCatching { Mango9ApiHttpTransport().execute(Request.Builder().url(server.url("/fixture")).build()) }
            assertEquals(Mango9ApiException.InvalidResponse, result.exceptionOrNull())
        }
    }

    @Test fun cancellationReleasesAnInFlightSlowResponsePromptly() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().body("slow").bodyDelay(3, TimeUnit.SECONDS).build()); server.start()
            val task = async { Mango9ApiHttpTransport().execute(Request.Builder().url(server.url("/fixture")).build()) }
            withContext(Dispatchers.IO) { checkNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
            withTimeout(1000) { task.cancelAndJoin() }; assertEquals(1, server.requestCount)
        }
    }
}
