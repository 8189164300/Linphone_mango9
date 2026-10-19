/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.Timeout

class Mango9MessagePushLifecycleTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(10)

    @Test fun renewsEveryStoredOwnerWithoutSelectingAnAccountOrOpeningChat() = runBlocking {
        val a = pushTestSession(); val b = pushTestSession("2")
        val sessions = Mango9PushTestSessions(a, b)
        val requests = mutableListOf<Request>()
        val engine = engine(sessions, send = { requests += it; 200 })
        assertTrue(engine.refreshAll().map { it.await() }.all { it })
        assertEquals(setOf(a.sipIdentity, b.sipIdentity), requests.map { it.bodyJson().getString("sip_identity") }.toSet())
        assertEquals(a.sipIdentity, sessions.active)
        assertEquals(0, sessions.writes)
        assertTrue(requests.all { it.header("Authorization") == "Bearer chat-bearer" })
    }

    @Test fun sharedOwnerRegistersOncePreferringSelectedAlias() = runBlocking {
        val a = pushTestSession(); val b = pushTestSession("2", a.userId)
        val sessions = Mango9PushTestSessions(a, b).apply { select(b.sipIdentity) }
        val requests = mutableListOf<Request>()
        val engine = engine(sessions, send = { requests += it; 200 })
        assertEquals(listOf(true), engine.refreshAll().map { it.await() })
        assertEquals(b.sipIdentity, requests.single().bodyJson().getString("sip_identity"))
    }

    @Test fun removingAliasRetargetsInactiveSharedOwnerWithoutDeletingRegistration() = runBlocking {
        val a = pushTestSession(); val b = pushTestSession("2", a.userId); val other = pushTestSession("3")
        val sessions = Mango9PushTestSessions(a, b, other).apply { select(other.sipIdentity); remove(a) }
        val requests = mutableListOf<Request>()
        val engine = engine(sessions, send = { requests += it; 200 })
        assertTrue(engine.retire(a)!!.await())
        assertEquals("POST", requests.single().method)
        assertEquals(b.sipIdentity, requests.single().bodyJson().getString("sip_identity"))
        assertEquals(other.sipIdentity, sessions.active)
    }

    @Test fun wireOrderIsOldPostThenDeleteThenReplacementPost() = runBlocking {
        val a = pushTestSession(); val sessions = Mango9PushTestSessions(a)
        val firstPost = CompletableDeferred<Unit>(); val finishPost = CompletableDeferred<Unit>()
        val delete = CompletableDeferred<Unit>(); val finishDelete = CompletableDeferred<Unit>()
        val methods = mutableListOf<String>()
        val engine = engine(sessions, send = {
            methods += it.method
            if (methods.size == 1) { firstPost.complete(Unit); finishPost.await() }
            if (it.method == "DELETE") { delete.complete(Unit); finishDelete.await() }
            200
        })
        val old = engine.refreshAll().single()
        firstPost.await(); sessions.remove(a)
        val cleanup = engine.retire(a)!!
        finishPost.complete(Unit); delete.await()
        sessions.save(a)
        val replacement = engine.refreshAll().single()
        assertEquals(listOf("POST", "DELETE"), methods)
        finishDelete.complete(Unit)
        assertFalse(old.await()); assertTrue(cleanup.await()); assertTrue(replacement.await())
        assertEquals(listOf("POST", "DELETE", "POST"), methods)
        assertEquals(1, sessions.captureLogins().size)
    }

    @Test fun logoutDuringBootstrapPreventsPostAndNeverRestoresRemovedCredentials() = runBlocking {
        val a = pushTestSession(); val sessions = Mango9PushTestSessions(a)
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        var calls = 0
        val requests = mutableListOf<Request>()
        val engine = engine(sessions, bootstrap = {
            if (++calls == 1) { started.complete(Unit); finish.await() }
            "chat-bearer"
        }, send = { requests += it; 200 })
        val registering = engine.refreshAll().single()
        started.await(); sessions.remove(a)
        val deleting = engine.retire(a)!!
        finish.complete(Unit)
        assertFalse(registering.await()); assertTrue(deleting.await())
        assertEquals(listOf("DELETE"), requests.map { it.method })
        assertEquals(setOf("device_id"), requests.single().bodyJson().keys().asSequence().toSet())
        assertTrue(sessions.captureLogins().isEmpty()); assertEquals(0, sessions.writes)
    }

    @Test fun identicalReloginRejectsOldRefreshResult() = runBlocking {
        val a = pushTestSession(); val sessions = Mango9PushTestSessions(a)
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        var sends = 0
        val engine = engine(
            sessions,
            bootstrap = { throw Mango9ApiException.InvalidCredentials },
            refresh = { started.complete(Unit); finish.await(); it.copy(accessToken = "new-access", refreshToken = "new-refresh") },
            send = { sends++; 200 },
        )
        val pending = engine.refreshAll().single()
        started.await(); sessions.save(a); finish.complete(Unit)
        assertFalse(pending.await()); assertEquals(0, sends); assertEquals(0, sessions.writes)
        assertEquals("old-access", sessions.captureLogins().single().session.accessToken)
    }

    @Test fun tokenRotationDropsOldBootstrapAndRegistersLatestToken() = runBlocking {
        val sessions = Mango9PushTestSessions(pushTestSession())
        var currentToken = TOKEN
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        var bootstraps = 0
        val requests = mutableListOf<Request>()
        val engine = engine(sessions, token = { currentToken }, bootstrap = {
            if (++bootstraps == 1) { started.complete(Unit); finish.await() }
            "chat-bearer"
        }, send = { requests += it; 200 })
        val old = engine.refreshAll().single()
        started.await(); currentToken = "$TOKEN-new"
        val latest = engine.refreshAll().single()
        finish.complete(Unit)
        assertFalse(old.await()); assertTrue(latest.await())
        assertEquals(currentToken, requests.single().bodyJson().getString("token"))
    }

    @Test fun duplicateRefreshIsCoalescedButOtherOwnersDoNotWait() = runBlocking {
        val a = pushTestSession(); val b = pushTestSession("2")
        val sessions = Mango9PushTestSessions(a, b)
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val otherSent = CompletableDeferred<Unit>()
        var count = 0
        val engine = engine(sessions, bootstrap = {
            if (it == a) { started.complete(Unit); finish.await() }
            "chat-bearer"
        }, send = { count++; if (it.bodyJson().getString("sip_identity") == b.sipIdentity) otherSent.complete(Unit); 200 })
        val initial = engine.refreshAll()
        val duplicate = engine.refreshAll()
        assertSame(initial.first(), duplicate.first())
        started.await(); otherSent.await(); finish.complete(Unit)
        assertTrue(initial.map { it.await() }.all { it }); assertTrue(duplicate.map { it.await() }.all { it })
        assertEquals(2, count)
    }

    @Test fun failedOwnerDoesNotBlockOthersAndRetriesAreBounded() = runBlocking {
        val a = pushTestSession(); val b = pushTestSession("2")
        val sessions = Mango9PushTestSessions(a, b)
        val pauses = mutableListOf<Long>(); val counts = mutableMapOf<String, Int>()
        val engine = engine(sessions, pause = { pauses += it }, send = {
            val identity = it.bodyJson().getString("sip_identity")
            counts[identity] = (counts[identity] ?: 0) + 1
            if (identity == a.sipIdentity) 503 else 403
        })
        assertEquals(listOf(false, false), engine.refreshAll().map { it.await() })
        assertEquals(4, counts[a.sipIdentity]); assertEquals(1, counts[b.sipIdentity])
        assertEquals(listOf(2_000L, 5_000L, 10_000L), pauses)
    }

    @Test fun expiredPrimaryAliasFallsBackToOtherStoredLoginForSameOwner() = runBlocking {
        val a = pushTestSession(); val b = pushTestSession("2", a.userId)
        val sessions = Mango9PushTestSessions(a, b)
        val requests = mutableListOf<Request>()
        val engine = engine(sessions, bootstrap = {
            if (it.sipIdentity == a.sipIdentity) throw Mango9ApiException.InvalidCredentials
            "chat-bearer"
        }, refresh = { throw Mango9ApiException.Unauthorized }, send = { requests += it; 200 })
        assertTrue(engine.refreshAll().single().await())
        assertEquals(b.sipIdentity, requests.single().bodyJson().getString("sip_identity"))
    }

    @Test fun cancellingScreenAwaiterDoesNotCancelLogoutCleanup() = runBlocking {
        val a = pushTestSession(); val sessions = Mango9PushTestSessions(a).apply { remove(a) }
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val engine = engine(sessions, send = { started.complete(Unit); finish.await(); 200 })
        val cleanup = engine.retire(a)!!
        val waiter = async { cleanup.await() }
        started.await(); waiter.cancelAndJoin(); finish.complete(Unit)
        assertTrue(cleanup.await()); assertNull(sessions.captureSession())
    }

    @Test fun detachedRefreshCannotChangeOwnerAndNoTokenDoesNoRegistration() = runBlocking {
        val a = pushTestSession(); val sessions = Mango9PushTestSessions(a)
        val idle = engine(sessions, token = { null })
        assertTrue(idle.refreshAll().isEmpty())
        sessions.remove(a)
        var sent = false
        val deleting = engine(
            sessions, bootstrap = { throw Mango9ApiException.InvalidCredentials },
            refresh = { it.copy(userId = "different") }, send = { sent = true; 200 }
        )
        assertFalse(deleting.retire(a)!!.await()); assertFalse(sent); assertEquals(0, sessions.writes)
    }

    @Test fun unexpectedStorageFailureIsReportedWithoutEscapingBackgroundWorker() = runBlocking {
        val failure = IllegalStateException("Synthetic unavailable storage")
        val unavailable = object : Mango9LoginAccess by Mango9PushTestSessions() {
            override fun captureLogins(): List<Mango9LoginLease> = throw failure
        }
        var reported: Exception? = null
        runMango9MessagePushWork(onFailure = { reported = it }) { engine(unavailable).refreshAll() }
        assertSame(failure, reported)
    }

    @Test fun workerPropagatesCancellationWithoutReportingItAsAnError() = runBlocking {
        val cancellation = CancellationException("Synthetic cancellation")
        var reported = false
        val result = runCatching {
            runMango9MessagePushWork(onFailure = { reported = true }) { throw cancellation }
        }
        assertSame(cancellation, result.exceptionOrNull())
        assertFalse(reported)
    }

    @Test fun detachedRefreshIsJournaledBeforeUseAndSurvivesFailedDelivery() = runBlocking {
        val original = pushTestSession()
        val renewed = original.copy(accessToken = "rotated-access", refreshToken = "rotated-refresh")
        val sessions = Mango9PushTestSessions(original).apply { remove(original) }
        var persisted = original; var sends = 0
        val lifecycle = engine(sessions, bootstrap = {
            if (it == original) throw Mango9ApiException.InvalidCredentials
            assertEquals(renewed, persisted)
            "chat-bearer"
        }, refresh = { renewed }, send = { sends++; 503 })
        assertFalse(
            lifecycle.retire(original) { before, after ->
            assertEquals(original, before); persisted = after
        }!!.await()
        )
        assertEquals(renewed, persisted); assertEquals(2, sends)
        assertTrue(sessions.captureLogins().isEmpty()); assertEquals(0, sessions.writes)
        assertTrue(engine(sessions).retire(persisted)!!.await())
    }

    @Test fun failedJournalWriteNeverUsesUnpersistedRotatedCredentials() = runBlocking {
        val original = pushTestSession()
        val sessions = Mango9PushTestSessions(original).apply { remove(original) }
        var sends = 0
        val lifecycle = engine(
            sessions, bootstrap = { throw Mango9ApiException.InvalidCredentials },
            refresh = { it.copy(accessToken = "rotated", refreshToken = "rotated") }, send = { sends++; 200 }
        )
        assertFalse(lifecycle.retire(original) { _, _ -> error("fixture disk unavailable") }!!.await())
        assertEquals(0, sends); assertTrue(sessions.captureLogins().isEmpty())
    }

    private fun CoroutineScope.engine(
        sessions: Mango9LoginAccess,
        token: () -> String? = { TOKEN },
        bootstrap: suspend (Mango9Session) -> String = { "chat-bearer" },
        refresh: suspend (Mango9Session) -> Mango9Session = { error("Unexpected refresh") },
        send: suspend (Request) -> Int = { 200 },
        pause: suspend (Long) -> Unit = {},
    ) = Mango9MessagePushLifecycle(sessions, this, token, { "device-test" }, bootstrap, refresh, send, pause)

    private fun Request.bodyJson() = JSONObject(Buffer().also { body!!.writeTo(it) }.readUtf8())

    companion object { private const val TOKEN = "fcm:abcdefghijklmnopqrstuvwxyz-0123456789" }
}
