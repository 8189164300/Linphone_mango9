/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.linphone.mango9.Mango9CalendarTestFixtures.Sessions

class Mango9CalendarRepositoryTest {
    private val fixture = Mango9CalendarTestFixtures

    private fun body(request: Request): JSONObject = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())

    private fun response(json: String) = Mango9CalendarResponse(200, fixture.envelope(json))

    private fun range(): Mango9CalendarRange = fixture.event().let { Mango9CalendarRange(it.startAt, it.endAt) }

    @Test fun requestsUseProvisionedCrmBaseAndQuotedRevisionNeverProxyOrAnotherAccount() {
        val session = fixture.session().copy(crmApiBaseUrl = "https://crm.example.invalid/app/api/v2/")
        val request = Mango9CalendarWire.request(session, "events/91", "PATCH", body = JSONObject().put("status_id", 3), revision = "abc123")
        assertEquals("https://crm.example.invalid/app/api/v2/mobile/calendar/events/91", request.url.toString())
        assertEquals("Bearer test-access", request.header("Authorization"))
        assertEquals("\"abc123\"", request.header("If-Match"))
        assertEquals(setOf("status_id"), body(request).keys().asSequence().toSet())
        val settings = Mango9CalendarWire.request(session, "settings", crmScope = true)
        assertEquals("/app/api/v2/mobile/crm/settings", settings.url.encodedPath)
        assertNull(settings.body)
        assertThrows(Mango9CalendarFailure::class.java) {
            Mango9CalendarWire.request(session.copy(crmApiBaseUrl = "http://crm.example.invalid"), "events")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Mango9CalendarWire.request(session, "events", revision = "a\"\r\nInjected: b")
        }
    }

    @Test fun decoderKeepsDatesRelationsPermissionsAndOldOptionalCapabilities() {
        val event = fixture.event()
        assertEquals(Instant.parse("2026-09-11T16:00:00Z"), event.startAt)
        assertEquals(101, event.contact?.id); assertEquals(listOf(43), event.sharedByMeUserIds)
        assertTrue(event.permissions.canEdit)
        val metadata = fixture.metadata()
        assertFalse(metadata.capabilities.inAppReminders); assertNull(metadata.businessHoursSettings)
        val invalid = JSONObject(fixture.eventJson).apply { getJSONObject("permissions").remove("can_edit") }
        assertThrows(Mango9CalendarFailure::class.java) { Mango9CalendarJson.appointment(invalid) }
        val page = JSONObject().put("events", JSONArray().put(JSONObject(fixture.eventJson)).put("malformed record"))
            .put("pagination", JSONObject().put("page", 1).put("limit", 100).put("total", 2).put("has_more", false)).put("snapshot", "one")
        assertThrows(Mango9CalendarFailure::class.java) { Mango9CalendarJson.page(page) }
    }

    @Test fun sparseStatusEditDoesNotRewriteTimeContactOwnerOrReminders() {
        val event = fixture.event(); val draft = Mango9AppointmentDraft.from(event)
        assertEquals(0, draft.payload(event, true, event.timezone).length())
        val body = draft.copy(status = null).payload(event, true, event.timezone)
        assertEquals(setOf("status_id"), body.keys().asSequence().toSet()); assertTrue(body.isNull("status_id"))
        val revoked = draft.copy(shares = emptySet(), assignee = 43).payload(event, true, event.timezone)
        assertEquals(0, revoked.getJSONArray("share_user_ids").length()); assertEquals(43, revoked.getInt("assign_to_user_id"))
    }

    @Test fun sharedAndReadOnlyEventsCannotSendOwnerOnlyFields() {
        val event = fixture.event().let {
            it.copy(permissions = it.permissions.copy(canDelete = false, canAssign = false, canChangeContact = false))
        }
        val draft = Mango9AppointmentDraft.from(event).copy(title = "Updated", contact = null, assignee = 90, reminder = 15)
        assertEquals(setOf("title"), draft.payload(event, false, event.timezone).keys().asSequence().toSet())
        val readonly = event.copy(permissions = Mango9AppointmentPermissions(false, false, false, false, false, "shared"))
        assertEquals(0, draft.copy(shares = emptySet()).payload(readonly, false, event.timezone).length())
    }

    @Test fun crudUsesExpectedMethodsRevisionAndAuthoritativeResponses() = runBlocking {
        val sessions = Sessions(); val calls = ArrayList<Request>()
        val repo = Mango9CalendarRepository(sessions, { error("Unexpected refresh") }) { request ->
            calls += request
            response(if (request.method == "DELETE") "{\"deleted\":true}" else fixture.eventJson)
        }
        val lease = repo.capture()!!; val event = repo.appointment(lease, 91)
        repo.save(lease, Mango9AppointmentDraft.from(event), fixture.metadata(), null, event.timezone)
        repo.changeStatus(lease, event, null); repo.delete(lease, event)
        assertEquals(listOf("GET", "POST", "PATCH", "DELETE"), calls.map { it.method })
        assertNull(calls[1].header("If-Match")); assertEquals(101, body(calls[1]).getInt("contact_id"))
        assertEquals("\"abc123\"", calls[2].header("If-Match")); assertEquals("\"abc123\"", calls[3].header("If-Match"))
        assertNull(calls[3].body)
    }

    @Test fun explicitAuthRejectionRefreshesOnceButForbiddenConflictAndUncertainWritesNeverRetry() = runBlocking {
        val sessions = Sessions(); var calls = 0; var refreshes = 0
        val repo = Mango9CalendarRepository(sessions, { refreshes++; it.copy(accessToken = "fresh") }) { request ->
            calls++
            if (calls == 1) {
                Mango9CalendarResponse(401, "{}")
            } else {
                assertEquals("Bearer fresh", request.header("Authorization")); response(fixture.eventJson)
            }
        }
        repo.appointment(repo.capture()!!, 91)
        assertEquals(2, calls); assertEquals(1, refreshes); assertEquals(1, sessions.writes)
        for (status in listOf(403, 409, 500)) {
            calls = 0
            val failed = Mango9CalendarRepository(sessions, { error("Must not refresh") }) {
                calls++; Mango9CalendarResponse(status, """{"success":false,"error":{"code":"event_changed"}}""")
            }
            val result = runCatching { failed.changeStatus(failed.capture()!!, fixture.event(), null) }
            assertEquals(status, (result.exceptionOrNull() as Mango9CalendarFailure).status)
            assertEquals(1, calls)
        }
        calls = 0
        val uncertain =
            Mango9CalendarRepository(sessions, {
                error("Must not refresh")
            }) { calls++; throw IOException("Fixture connection lost") }
        assertTrue(
            runCatching {
            uncertain.save(uncertain.capture()!!, Mango9AppointmentDraft.from(fixture.event()), fixture.metadata(), null, "UTC")
        }.isFailure
        )
        assertEquals(1, calls)
    }

    @Test fun wrongRecordResponseAndOldAccountLeaseCannotBePublished() = runBlocking {
        val sessions = Sessions(); var calls = 0
        val repo =
            Mango9CalendarRepository(sessions, { error("Unexpected refresh") }) { calls++; response(fixture.eventJson) }
        assertEquals(
            "invalid_response",
            (
                runCatching {
            repo.appointment(repo.capture()!!, 92)
        }.exceptionOrNull() as Mango9CalendarFailure
            ).code
        )
        val old = repo.capture()!!; sessions.awayAndBack()
        assertTrue(runCatching { repo.appointment(old, 91) }.exceptionOrNull() is Mango9ApiException.Unauthorized)
        assertEquals(1, calls)
    }

    @Test fun accountChangeDuringReadRejectsOldResponseAndCancellationStopsWork() = runBlocking {
        val sessions = Sessions(); val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val repo = Mango9CalendarRepository(sessions, { error("Unexpected refresh") }) {
            started.complete(Unit); finish.await(); response(fixture.eventJson)
        }
        val read = async { runCatching { repo.appointment(repo.capture()!!, 91) } }
        started.await(); sessions.awayAndBack(); finish.complete(Unit)
        assertTrue(read.await().exceptionOrNull() is Mango9ApiException.Unauthorized)
        val startedAgain = CompletableDeferred<Unit>()
        val waiting = Mango9CalendarRepository(sessions, { error("Unexpected refresh") }) {
            startedAgain.complete(Unit); CompletableDeferred<Unit>().await(); response(fixture.eventJson)
        }
        val job = launch { waiting.appointment(waiting.capture()!!, 91) }
        startedAgain.await(); job.cancelAndJoin(); assertTrue(job.isCancelled)
    }

    @Test fun snapshotsCollectAtomicallyAndRetainContactFilter() = runBlocking {
        var calls = 0
        val result = Mango9CalendarRepository.collect(range(), 101) { query ->
            calls++; assertEquals("101", query["contact_id"])
            if (calls == 2) assertEquals("snapshot", query["snapshot"])
            Mango9AppointmentPage(if (calls == 1) listOf(fixture.event()) else emptyList(), calls, 100, 1, calls == 1, "snapshot")
        }
        assertEquals(2, calls); assertEquals(listOf(91), result.map { it.id })
    }

    @Test fun snapshotRestartDiscardsPartialResultsAndDoesNotLoopForever() = runBlocking {
        var calls = 0
        val result = Mango9CalendarRepository.collect(range(), null) { query ->
            calls++
            if (calls == 2) throw Mango9CalendarFailure("snapshot_changed", 409)
            assertEquals("1", query["page"])
            Mango9AppointmentPage(if (calls == 1) listOf(fixture.event()) else emptyList(), 1, 100, 0, calls == 1, if (calls == 1) "old" else "new")
        }
        assertEquals(3, calls); assertTrue(result.isEmpty())
        calls = 0
        val failure =
            runCatching {
                Mango9CalendarRepository.collect(range(), null) { calls++; throw Mango9CalendarFailure("snapshot_changed", 409) }
            }
        assertTrue(failure.isFailure); assertEquals(2, calls)
    }

    @Test fun oversizedOrBrokenPagesFailAndWindowsHaveNoGaps() = runBlocking {
        for (page in listOf(
            Mango9AppointmentPage(List(101) { fixture.event() }, 1, 100, 101, false, "snapshot"),
            Mango9AppointmentPage(emptyList(), 2, 100, 0, false, "snapshot"), Mango9AppointmentPage(emptyList(), 1, 100, 0, false, "")
        )) {
            assertEquals(
                "invalid_pagination",
                (
                    runCatching {
                Mango9CalendarRepository.collect(range(), null) { page }
            }.exceptionOrNull() as Mango9CalendarFailure
                ).code
            )
        }
        val start = Instant.EPOCH; val end = start.plus(Duration.ofDays(101))
        val windows = Mango9CalendarRepository.windows(Mango9CalendarRange(start, end))
        assertEquals(2, windows.size); assertEquals(start, windows.first().start); assertEquals(end, windows.last().end)
        assertEquals(windows.first().end, windows.last().start); assertTrue(windows.all { it.duration <= Duration.ofDays(93) })
    }

    @Test fun reminderSnoozeAndPushBackUseDifferentBodiesAndRevisions() = runBlocking {
        val sessions = Sessions(); val calls = ArrayList<Request>()
        val event = fixture.event(); val reminder = Mango9PersonalReminder(true, 15, event.startAt, false, false, "reminder-revision")
        val repo = Mango9CalendarRepository(sessions, { error("Unexpected refresh") }) { request ->
            calls += request
            response(
                if (request.url.encodedPath.endsWith("/reminder")) {
                """{"enabled":true,"minutes_before":15,"due_at":"2026-09-11T16:00:00Z","dismissed":false,"needs_reenable":false,"revision":"new-reminder"}"""
            } else {
                fixture.eventJson
            }
            )
        }
        val lease = repo.capture()!!
        repo.updateReminder(lease, event, reminder, Mango9ReminderAction.Snooze(event.startAt))
        repo.pushBack(lease, event, 15)
        assertTrue(calls[0].url.encodedPath.endsWith("/events/91/reminder"))
        assertEquals(setOf("action", "until", "reminder_revision"), body(calls[0]).keys().asSequence().toSet())
        assertEquals("reminder-revision", body(calls[0]).getString("reminder_revision"))
        assertEquals(setOf("start_at", "end_at"), body(calls[1]).keys().asSequence().toSet())
        val pushed = body(calls[1])
        assertEquals(Duration.ofMinutes(30), Duration.between(Instant.parse(pushed.getString("start_at")), Instant.parse(pushed.getString("end_at"))))
        assertEquals("\"abc123\"", calls[0].header("If-Match")); assertEquals("\"abc123\"", calls[1].header("If-Match"))
    }

    @Test fun preferencesSaveReturnsServerConfirmationAndUsesSeparateCrmScope() = runBlocking {
        val sessions = Sessions(); val calls = ArrayList<Request>()
        val repo =
            Mango9CalendarRepository(sessions, { error("Unexpected refresh") }) { calls += it; response(fixture.settingsJson) }
        val lease = repo.capture()!!
        val saved = repo.savePreferences(lease, repo.preferences(lease).copy(smsPush = true))
        assertFalse("Use the confirmed server result, not the optimistic draft", saved.smsPush)
        assertEquals("/api/v2/mobile/crm/settings", calls[1].url.encodedPath)
        assertEquals("\"settings-fixture\"", calls[1].header("If-Match"))
        assertFalse(body(calls[1]).has("user_id")); assertFalse(body(calls[1]).has("revision"))
    }
}
