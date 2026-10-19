/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import java.io.IOException
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test

/** Real socket transport checks on loopback only; production URL construction still requires HTTPS. */
class Mango9CalendarTransportTest {
    @Test fun serverRetryAfterCannotReplayCalendarWrites() = runBlocking {
        for (method in listOf("POST", "PATCH", "DELETE")) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "0").body("{}").build())
                server.enqueue(MockResponse.Builder().code(200).body("{}").build())
                server.start()
                val request = Request.Builder().url(server.url("/calendar/events/91"))
                    .method(method, if (method == "DELETE") null else "{}".toRequestBody("application/json".toMediaType())).build()
                val result = runCatching { Mango9CalendarHttpTransport().execute(request) }
                assertTrue("An automatic replay must fail before a second write", result.exceptionOrNull() is IOException)
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun redirectsAreReturnedWithoutForwardingTheBearer() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(307).addHeader("Location", "/other").body("{}").build())
            server.enqueue(MockResponse.Builder().code(200).body("{}").build())
            server.start()
            val request = Request.Builder().url(server.url("/events"))
                .header("Authorization", "Bearer synthetic-test-token").build()
            val result = Mango9CalendarHttpTransport().execute(request)
            assertEquals(307, result.status); assertEquals(1, server.requestCount)
        }
    }

    @Test fun responseSizeIsBoundedEvenWithoutContentLength() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().chunkedBody("x".repeat(2 * 1024 * 1024 + 1), 8192).build())
            server.start()
            val result =
                runCatching { Mango9CalendarHttpTransport().execute(Request.Builder().url(server.url("/events")).build()) }
            assertEquals("invalid_response", (result.exceptionOrNull() as Mango9CalendarFailure).code)
            assertEquals(1, server.requestCount)
        }
    }
}
