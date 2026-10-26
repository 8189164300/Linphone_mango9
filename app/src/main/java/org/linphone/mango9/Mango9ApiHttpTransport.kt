// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import java.io.IOException
import java.net.ProtocolException
import javax.net.ssl.SSLException
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Cancellable, size-bounded transport; never replay writes or forward credentials on redirects. */
internal class Mango9ApiHttpTransport {
    data class Result(val status: Int, val body: ByteArray, val retryAfterSeconds: Long? = null)

    suspend fun execute(request: Request): Result {
        Mango9TokenRefresh.checkBearer(request.header("Authorization"))
        try {
            return executeOnce(request)
        } catch (error: IOException) {
            // A stale pooled connection or brief network transition must not make a
            // read-only CRM overview fail immediately. Never replay a mutation.
            if (request.method !in setOf("GET", "HEAD") ||
                error is SSLException || error is ProtocolException
            ) {
                throw error
            }
            delay(250)
            return executeOnce(request)
        }
    }

    private suspend fun executeOnce(request: Request): Result = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request.newBuilder().tag(Attempt::class.java, Attempt()).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        val body = it.body
                        if (body.contentLength() > MAX_BYTES || body.source().request(MAX_BYTES + 1)) {
                            throw Mango9ApiException.InvalidResponse
                        }
                        Result(it.code, body.bytes(), retryAfter(it.header("Retry-After")))
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    private companion object {
        fun retryAfter(value: String?): Long? {
            if (value == null) return null
            val number = value.toLongOrNull()
            val seconds = number ?: runCatching {
                val date = java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                java.time.Duration.between(java.time.Instant.now(), date.toInstant()).seconds
            }.getOrNull() ?: return null
            return seconds.coerceIn(1, 3600)
        }

        class Attempt { val sent = AtomicBoolean(false) }

        const val MAX_BYTES = 2L * 1024 * 1024
        val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .addNetworkInterceptor { chain ->
                if (chain.request().tag(Attempt::class.java)?.sent?.compareAndSet(false, true) != true) {
                    throw IOException("Request cannot be replayed")
                }
                val response = chain.proceed(chain.request())
                // OkHttp otherwise repeats 503/Retry-After: 0 even with connection retries off.
                // Let the session owner apply bounded cooldown; never replay a mutation here.
                if (response.code == 503 && response.header("Retry-After")?.toLongOrNull() == 0L) {
                    response.newBuilder().header("Retry-After", "1").build()
                } else {
                    response
                }
            }.build()
    }
}
