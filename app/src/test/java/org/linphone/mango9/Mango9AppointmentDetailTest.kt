/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import okhttp3.Request
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Mango9AppointmentDetailTest {
    private class Fixture(scope: CoroutineScope) {
        val sessions = Mango9CalendarTestFixtures.Sessions()
        val now = Instant.parse("2026-09-10T12:00:00Z")
        var event = JSONObject(Mango9CalendarTestFixtures.eventJson)
        var reminder =
            JSONObject("""{"enabled":true,"minutes_before":15,"due_at":"2026-09-11T15:45:00Z","dismissed":false,"needs_reenable":false,"revision":"r1"}""")
        val calls = arrayListOf<Request>()
        val writes get() = calls.filter { it.method != "GET" }
        var release: CompletableDeferred<Unit>? = null
        var settingsRelease: CompletableDeferred<Unit>? = null
        var failWrite: String? = null
        var failRead = false
        var serial = 1
        val repository = Mango9CalendarRepository(sessions, { error("No token refresh") }) { request ->
            calls.add(request)
            val path = request.url.pathSegments.last()
            if (request.method != "GET") {
                release?.let { withContext(NonCancellable) { it.await() } }
                failWrite?.let {
                    if (it == "network") throw IOException("Synthetic uncertain write")
                    return@Mango9CalendarRepository Mango9CalendarResponse(409, """{"success":false,"error":{"code":"$it"}}""")
                }
                if (request.method != "DELETE") {
                    val body = body(request)
                    if (path == "reminder") {
                        reminder = JSONObject(reminder.toString()).put("revision", "r${++serial}")
                        if (body.optString("action") == "dismiss") reminder.put("dismissed", true).put("due_at", JSONObject.NULL)
                    } else {
                        event = JSONObject(event.toString()).put("revision", "e${++serial}")
                        if (body.has("status_id")) event.put("status", JSONObject.NULL)
                        for (key in listOf("start_at", "end_at")) if (body.has(key)) event.put(key, body.get(key))
                    }
                }
            } else if (failRead) throw IOException("Synthetic unavailable read")
            val json = when (path) {
                "settings" -> { settingsRelease?.await(); JSONObject(Mango9CalendarTestFixtures.settingsJson) }
                "reminder" -> reminder
                else -> event
            }
            Mango9CalendarResponse(200, Mango9CalendarTestFixtures.envelope(json.toString()))
        }
        val edits = Mango9CalendarEdits(repository, scope)

        fun open(): Mango9AppointmentDetail = edits.detail(
            repository.capture()!!, Mango9CalendarTestFixtures.event(),
            Mango9CalendarTestFixtures.metadata().let {
                it.copy(capabilities = it.capabilities.copy(inAppReminders = true))
            },
                { now }
        )!!
    }

    private suspend fun ready(detail: Mango9AppointmentDetail) {
        while (detail.progress.value.busy || detail.personal.value.loading) yield()
    }

    @Test fun statusKeepsSameDetailAndUsesNewRevisionForFollowingReminder() = runBlocking {
        val f = Fixture(this); val detail = f.open(); ready(detail)
        detail.changeStatus(null)!!.join(); ready(detail)
        assertSame(detail, f.edits.active); assertFalse(detail.progress.value.completed); assertNull(detail.event!!.status)
        assertEquals("e2", detail.event!!.revision)
        detail.updateReminder(Mango9ReminderAction.Dismiss)!!.join()
        assertEquals(2, f.writes.size)
        assertEquals(setOf("status_id"), body(f.writes[0]).keys().asSequence().toSet())
        assertEquals("\"abc123\"", f.writes[0].header("If-Match"))
        assertEquals("\"e2\"", f.writes[1].header("If-Match"))
        assertEquals("r1", body(f.writes[1]).getString("reminder_revision"))
        assertTrue(detail.personal.value.reminder!!.dismissed)
        assertNull(detail.updateReminder(Mango9ReminderAction.Dismiss))
    }

    @Test fun pendingActionBlocksEveryOtherWriteDismissAndReload() = runBlocking {
        val f = Fixture(this); val detail = f.open(); ready(detail)
        f.release = CompletableDeferred()
        val job = detail.changeStatus(null)!!; while (f.writes.isEmpty()) yield()
        assertFalse(f.edits.dismiss(detail)); assertNull(detail.delete()); assertNull(detail.pushBack(15)); assertNull(detail.reload())
        assertNull(detail.updateReminder(Mango9ReminderAction.Configure(30)))
        assertSame(detail, f.edits.active); assertEquals("abc123", detail.event!!.revision)
        f.release!!.complete(Unit); job.join(); assertEquals(1, f.writes.size); assertFalse(detail.progress.value.completed)
    }

    @Test fun overlapCanBeCorrectedButUncertainWriteAndFailedReloadStayBlocked() = runBlocking {
        val f = Fixture(this); val detail = f.open(); ready(detail)
        f.failWrite = "overlap"; detail.pushBack(15)!!.join()
        assertFalse(detail.progress.value.blocked); assertTrue(detail.canSave)
        f.failWrite = "network"; detail.pushBack(30)!!.join()
        assertTrue(detail.progress.value.blocked); assertNull(detail.pushBack(30)); assertNull(detail.changeStatus(null))
        f.failRead = true; detail.reload()!!.join()
        assertTrue(detail.progress.value.blocked); assertFalse(detail.canSave)
        f.failRead = false; f.failWrite = null; f.event.put("revision", "server-reconciled")
        detail.reload()!!.join(); ready(detail)
        assertTrue(detail.canSave); assertEquals("server-reconciled", detail.event!!.revision)
        assertEquals(2, f.writes.size)
        detail.pushBack(5)!!.join()
        assertEquals("\"server-reconciled\"", f.writes.last().header("If-Match"))
        assertEquals(setOf("start_at", "end_at"), body(f.writes.last()).keys().asSequence().toSet())
    }

    @Test fun reminderConflictRequiresFreshEventAndReminderRevisions() = runBlocking {
        val f = Fixture(this); val detail = f.open(); ready(detail)
        f.failWrite = "reminder_changed"; detail.updateReminder(Mango9ReminderAction.Configure(30))!!.join()
        assertTrue(detail.progress.value.blocked)
        detail.refreshReminder(); assertNull(detail.updateReminder(Mango9ReminderAction.Configure(30)))
        f.failWrite = null; f.event.put("revision", "event-reloaded"); f.reminder.put("revision", "reminder-reloaded")
        detail.reload()!!.join(); ready(detail)
        detail.updateReminder(Mango9ReminderAction.Configure(30))!!.join()
        assertEquals("\"event-reloaded\"", f.writes.last().header("If-Match"))
        assertEquals("reminder-reloaded", body(f.writes.last()).getString("reminder_revision"))
        assertEquals(2, f.writes.size)
    }

    @Test fun cancelledOldAccountCannotAcceptLateActionOrReuseReference() = runBlocking {
        val f = Fixture(this); val detail = f.open(); ready(detail)
        val before = Mango9CalendarChanges.version.value
        f.release = CompletableDeferred(); val job = detail.changeStatus(null)!!
        while (f.writes.isEmpty()) yield()
        f.sessions.awayAndBack(); f.edits.checkAccount(); assertNull(f.edits.active)
        f.release!!.complete(Unit); job.join()
        assertTrue(detail.progress.value.closed); assertEquals("abc123", detail.event!!.revision)
        assertEquals(before, Mango9CalendarChanges.version.value)
        assertNull(detail.reload()); assertNull(detail.delete()); assertNull(detail.changeStatus(null))
    }

    @Test fun deleteIsTerminalAndNeverAutomaticallyReplayed() = runBlocking {
        val f = Fixture(this); val detail = f.open(); ready(detail)
        detail.delete()!!.join(); assertTrue(detail.progress.value.completed)
        assertNull(detail.delete()); assertNull(detail.reload()); assertTrue(f.edits.dismiss(detail))
        assertNull(f.edits.active); assertEquals("DELETE", f.writes.single().method)
        assertEquals("\"abc123\"", f.writes.single().header("If-Match"))
    }

    @Test fun optionalDefaultsDoNotBlockActionAndRightsAreCheckedAtModelBoundary() = runBlocking {
        val f = Fixture(this); f.settingsRelease = CompletableDeferred()
        val detail = f.open(); ready(detail)
        assertTrue(detail.personal.value.settingsLoading); assertTrue(detail.canSave)
        detail.updateReminder(Mango9ReminderAction.Dismiss)!!.join()
        assertTrue(detail.personal.value.reminder!!.dismissed)
        f.event.getJSONObject("permissions").put("can_edit", false).put("can_delete", false)
        detail.reload()!!.join(); ready(detail)
        assertNull(detail.changeStatus(null)); assertNull(detail.pushBack(15)); assertNull(detail.delete())
        assertEquals(1, f.writes.size); f.edits.dismiss(detail); f.settingsRelease!!.complete(Unit)
        Unit
    }

    @Test fun failedNormalReloadDisablesActionsUntilFreshDataArrives() = runBlocking {
        val f = Fixture(this); val detail = f.open(); ready(detail)
        f.failRead = true; detail.reload()!!.join()
        assertFalse(detail.canSave); assertNull(detail.delete()); assertNotNull(detail.progress.value.error)
        f.failRead = false; detail.reload()!!.join(); assertTrue(detail.canSave)
    }

    private companion object {
        fun body(request: Request) = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
    }
}
