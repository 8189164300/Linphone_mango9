// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class Mango9AttachmentUploadTest {
    @Test fun cancellationTerminatesResponseWorkerBeforeReturning() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().body("not yet").bodyDelay(3, TimeUnit.SECONDS).build())
            server.start()
            val call = OkHttpClient().newCall(Request.Builder().url(server.url("/prepare")).build())
            val readerStarted = CompletableDeferred<Unit>()
            val readerStopped = AtomicBoolean(false)
            val job = launch {
                call.mango9UploadResult { response ->
                    readerStarted.complete(Unit)
                    try { response.mango9UploadBytes(64) } finally { readerStopped.set(true) }
                }
            }
            withTimeout(2000) { readerStarted.await() }
            withTimeout(1500) { job.cancelAndJoin() }
            assertTrue(call.isCanceled())
            assertTrue("Source may only close after the HTTP worker stops", readerStopped.get())
        }
    }

    @Test fun oversizedPrepareResponseIsRejectedWithoutReturningPartialJson() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().body("x".repeat(16384)).build())
            server.start()
            val call = OkHttpClient().newCall(Request.Builder().url(server.url("/prepare")).build())
            val error = runCatching { call.mango9UploadResult { it.mango9UploadBytes(1024) } }.exceptionOrNull()
            assertTrue(error is IOException)
            assertTrue(call.isCanceled())
        }
    }

    @Test fun exactBoundedResponseIsReturnedAndSocketReleased() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().body("{}".repeat(32)).build())
            server.start()
            val call = OkHttpClient().newCall(Request.Builder().url(server.url("/prepare")).build())
            assertEquals("{}".repeat(32), call.mango9UploadResult { it.mango9UploadBytes(64) }.decodeToString())
            assertTrue(call.isCanceled())
        }
    }
}
