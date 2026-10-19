/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import java.io.IOException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.linphone.mango9.Mango9CalendarTestFixtures.Sessions

class Mango9CalendarScreenTest {
    private val fixture = Mango9CalendarTestFixtures
    private val date = LocalDate.of(2026, 9, 11)
    private val zone = ZoneId.of("America/Los_Angeles")

    private fun response(request: Request, title: String = "Client consultation"): Mango9CalendarResponse {
        val json = when (request.url.pathSegments.last()) {
            "metadata" -> fixture.metadataJson
            "settings" -> fixture.settingsJson
            "events" -> JSONObject().put("events", JSONArray().put(JSONObject(fixture.eventJson).put("title", title)))
                .put("pagination", JSONObject().put("page", 1).put("limit", 100).put("total", 1).put("has_more", false))
                .put("snapshot", "stable").toString()
            else -> error("Unexpected request")
        }
        return Mango9CalendarResponse(200, fixture.envelope(json))
    }

    private fun screen(scope: CoroutineScope, repo: Mango9CalendarRepository) = Mango9CalendarScreen(
        repo, scope, date = date, deviceZone = zone, deviceFirstWeekday = DayOfWeek.SUNDAY
    )

    @Test fun initialReadUsesSettingsAndSelectedAccountWithoutAnyWrite() = runBlocking {
        val sessions = Sessions(); val calls = ArrayList<Request>()
        val repo = Mango9CalendarRepository(sessions, { error("No refresh") }) { calls.add(it); response(it) }
        val screen = screen(this, repo)
        screen.refresh()!!.join()
        assertEquals(listOf("GET", "GET", "GET"), calls.map { it.method })
        assertEquals(DayOfWeek.MONDAY, screen.state.value.firstWeekday)
        assertEquals(1, screen.state.value.events.size); assertTrue(screen.canCreate())
        assertFalse(screen.state.value.loading); assertNull(screen.state.value.error)
        val plus = screen.creation(fixture.event().startAt, false)!!
        val held = screen.creation(fixture.event().startAt, true)!!
        assertEquals("Asia/Yerevan", plus.zone.id); assertEquals(zone, held.zone)
        assertEquals(plus.start, held.start); assertEquals(0, sessions.writes)
    }

    @Test fun navigationKeepsModeAndDiscardsSlowOldRangeEvenIfTransportIgnoresCancellation() = runBlocking {
        val sessions = Sessions(); var events = 0
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val repo = Mango9CalendarRepository(sessions, { error("No refresh") }) {
            if (it.url.pathSegments.last() == "events" && ++events == 1) {
                started.complete(Unit); withContext(NonCancellable) { release.await() }; response(it, "Old month")
            } else {
                response(it, "New selection")
            }
        }
        val screen = screen(this, repo)
        val old = screen.refresh()!!; started.await()
        screen.chooseMode(Mango9CalendarMode.Day)
        // Wait for the replacement load without initiating a third request.
        while (screen.state.value.loading) yield()
        release.complete(Unit); old.join()
        assertEquals(Mango9CalendarMode.Day, screen.state.value.mode)
        assertEquals("New selection", screen.state.value.events.single().title)
        assertNull(screen.state.value.error)
    }

    @Test fun accountChangeImmediatelyClearsDataAndRejectsCapturedFormIncludingAwayAndBack() = runBlocking {
        val sessions = Sessions()
        val repo = Mango9CalendarRepository(sessions, { error("No refresh") }) { response(it) }
        val screen = screen(this, repo)
        screen.refresh()!!.join(); val creation = screen.creation(fixture.event().startAt, true)!!
        sessions.awayAndBack()
        assertFalse(screen.canCreate()); assertFalse(repo.isCurrent(creation.lease))
        sessions.session = null
        screen.accountChanged()
        assertTrue(screen.state.value.events.isEmpty()); assertNull(screen.state.value.metadata)
        assertFalse(screen.state.value.loading); assertNotNull(screen.state.value.error)
    }

    @Test fun failureClearsRevokedAppointmentsRatherThanDisplayingOldSuccessfulSnapshot() = runBlocking {
        val sessions = Sessions(); var fail = false
        val repo = Mango9CalendarRepository(sessions, { error("No refresh") }) {
            if (fail && it.url.pathSegments.last() == "events") throw IOException("Synthetic connection loss")
            response(it)
        }
        val screen = screen(this, repo); screen.refresh()!!.join()
        assertEquals(1, screen.state.value.events.size)
        fail = true; screen.refresh()!!.join()
        assertTrue(screen.state.value.events.isEmpty()); assertNull(screen.state.value.metadata)
        assertNotNull(screen.state.value.error); assertFalse(screen.canCreate())
    }

    @Test fun unavailableSettingsDoNotFabricatePreferencesOrBlockCalendarReading() = runBlocking {
        val repo = Mango9CalendarRepository(Sessions(), { error("No refresh") }) {
            if (it.url.pathSegments.last() == "settings") Mango9CalendarResponse(404, "{}") else response(it)
        }
        val screen = screen(this, repo); screen.refresh()!!.join()
        assertEquals(1, screen.state.value.events.size)
        assertNull(screen.state.value.preferences); assertTrue(screen.state.value.preferencesError)
        assertEquals(DayOfWeek.SUNDAY, screen.state.value.firstWeekday)
        assertTrue(screen.canCreate())
    }

    @Test fun savedHoursWinAgainstAnOlderInFlightMetadataSnapshot() = runBlocking {
        val sessions = Sessions(); var hold = false
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val repo = Mango9CalendarRepository(sessions, { error("No refresh") }) {
            if (hold && it.url.pathSegments.last() == "events") { started.complete(Unit); release.await() }
            response(it)
        }
        val screen = screen(this, repo); screen.refresh()!!.join()
        hold = true; val job = screen.refresh()!!; started.await()
        val hours = fixture.hours().copy(timezone = "Asia/Yerevan", revision = "new-hours")
        screen.acceptHours(screen.capture()!!, hours)
        release.complete(Unit); job.join()
        assertEquals(hours, screen.state.value.hours)
        assertEquals("Asia/Yerevan", screen.state.value.metadata?.timezone)
        assertNull(screen.creation(date.plusDays(1).atTime(9, 0).atZone(zone).toInstant(), true))
    }

    @Test fun capacityFailureRetainsFreshPermissionsSoDayViewCanRecover() = runBlocking {
        var fail = true
        val repo = Mango9CalendarRepository(Sessions(), { error("No refresh") }) {
            if (fail && it.url.pathSegments.last() == "events") throw Mango9CalendarFailure("too_many_occurrences")
            response(it)
        }
        val screen = screen(this, repo); screen.refresh()!!.join()
        assertTrue(screen.state.value.capacityExceeded); assertNotNull(screen.state.value.metadata)
        assertTrue(screen.state.value.events.isEmpty()); assertTrue(screen.canCreate())
        fail = false; screen.chooseMode(Mango9CalendarMode.Day)
        while (screen.state.value.loading) yield()
        assertFalse(screen.state.value.capacityExceeded); assertNull(screen.state.value.error)
        assertEquals(1, screen.state.value.events.size)
    }

    @Test fun stoppingDoesNotLeaveSpinnerOrPublishCancelledRead() = runBlocking {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val repo = Mango9CalendarRepository(Sessions(), { error("No refresh") }) {
            started.complete(Unit); withContext(NonCancellable) { release.await() }; response(it)
        }
        val screen = screen(this, repo); val job = screen.refresh()!!; started.await()
        screen.stop(); assertFalse(screen.state.value.loading)
        release.complete(Unit); job.join()
        assertTrue(screen.state.value.events.isEmpty()); assertNull(screen.state.value.metadata)
    }

    @Test fun linkedLeadCalendarCannotReuseItsRecordIdAfterAccountChanges() = runBlocking {
        val sessions = Sessions(); var requests = 0
        val repo = Mango9CalendarRepository(sessions, { error("No refresh") }) { requests++; response(it) }
        val screen = Mango9CalendarScreen(repo, this, fixture.event().contact, date, zone)
        screen.refresh()!!.join()
        assertFalse(screen.state.value.contextExpired); assertEquals(1, screen.state.value.events.size)
        val before = requests
        sessions.awayAndBack(); screen.accountChanged()
        assertTrue(screen.state.value.contextExpired); assertTrue(screen.state.value.events.isEmpty())
        assertFalse(screen.canCreate()); assertEquals(before, requests)
        assertNull(screen.refresh()); assertEquals(before, requests)
    }

    @Test fun explicitDaySelectionBeforeFirstReadIsNotReplacedBySavedMonthDefault() = runBlocking {
        val repo = Mango9CalendarRepository(Sessions(), { error("No refresh") }) {
            if (it.url.pathSegments.last() == "settings") {
                Mango9CalendarResponse(
                    200,
                    fixture.envelope(
                        JSONObject(fixture.settingsJson)
                    .put("default_calendar_view", "appointments").toString()
                    )
                )
            } else {
                response(it)
            }
        }
        val screen = screen(this, repo); screen.chooseMode(Mango9CalendarMode.Day)
        while (screen.state.value.loading) yield()
        assertTrue(screen.state.value.calendar); assertEquals(Mango9CalendarMode.Day, screen.state.value.mode)
        assertEquals(1, screen.state.value.events.size)
    }

    @Test fun slowOptionalSettingsDoNotDelayAppointmentsOrCapturedCreationContext() = runBlocking {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val repo = Mango9CalendarRepository(Sessions(), { error("No refresh") }) {
            if (it.url.pathSegments.last() == "settings") { started.complete(Unit); release.await() }
            response(it)
        }
        val screen = screen(this, repo); val job = screen.refresh()!!
        started.await()
        withTimeout(3_000) { while (screen.state.value.loading) yield() }
        assertTrue(job.isActive); assertEquals(1, screen.state.value.events.size)
        assertNull(screen.state.value.preferences); assertTrue(screen.canCreate())
        val original = screen.creation(fixture.event().startAt, false)!!
        assertEquals(zone, original.zone)
        release.complete(Unit); job.join()
        assertEquals(DayOfWeek.MONDAY, screen.state.value.firstWeekday)
        assertEquals("Asia/Yerevan", screen.creation(original.start, false)!!.zone.id)
        assertEquals("An open form must retain its original timezone", zone, original.zone)
    }

    @Test fun lateDefaultCalendarAndWeekdayReadFetchesItsExactRangeWithoutRepeatingMetadata() = runBlocking {
        val release = CompletableDeferred<Unit>(); val nextRange = CompletableDeferred<Unit>()
        val releaseRange = CompletableDeferred<Unit>(); val calls = ArrayList<Request>(); var events = 0
        val repo = Mango9CalendarRepository(Sessions(), { error("No refresh") }) {
            calls.add(it)
            when (it.url.pathSegments.last()) {
                "settings" -> {
                    release.await()
                    Mango9CalendarResponse(200, fixture.envelope(JSONObject(fixture.settingsJson).put("default_calendar_view", "calendar").toString()))
                }
                "events" -> {
                    if (++events == 2) { nextRange.complete(Unit); releaseRange.await() }
                    response(it, "Range $events")
                }
                else -> response(it)
            }
        }
        val screen = screen(this, repo); val job = screen.refresh()!!
        withTimeout(3_000) { while (screen.state.value.loading) yield() }
        assertEquals("Range 1", screen.state.value.events.single().title)
        release.complete(Unit); withTimeout(3_000) { nextRange.await() }
        assertTrue(screen.state.value.calendar); assertEquals(DayOfWeek.MONDAY, screen.state.value.firstWeekday)
        assertTrue(screen.state.value.loading); assertTrue(screen.state.value.events.isEmpty())
        releaseRange.complete(Unit); job.join()
        assertEquals("Range 2", screen.state.value.events.single().title)
        val eventCalls = calls.filter { it.url.pathSegments.last() == "events" }
        assertEquals(2, eventCalls.size); assertNotEquals(eventCalls[0].url, eventCalls[1].url)
        assertEquals(1, calls.count { it.url.pathSegments.last() == "metadata" })
        assertEquals(1, calls.count { it.url.pathSegments.last() == "settings" })
        assertTrue(calls.all { it.method == "GET" })
    }

    @Test fun cancelledLateSettingsCannotOverrideANewNavigationOrAccountSelection() = runBlocking {
        val sessions = Sessions(); val release = CompletableDeferred<Unit>(); var settings = 0
        val repo = Mango9CalendarRepository(sessions, { error("No refresh") }) {
            if (it.url.pathSegments.last() == "settings" && ++settings == 1) {
                withContext(NonCancellable) { release.await() }
                Mango9CalendarResponse(200, fixture.envelope(JSONObject(fixture.settingsJson).put("default_calendar_view", "calendar").toString()))
            } else {
                response(it)
            }
        }
        val screen = screen(this, repo); val old = screen.refresh()!!
        withTimeout(3_000) { while (screen.state.value.loading) yield() }
        screen.search("Private old account"); screen.filterStatus(3)
        sessions.awayAndBack(); screen.accountChanged()
        screen.chooseMode(Mango9CalendarMode.Day)
        withTimeout(3_000) { while (screen.state.value.loading) yield() }
        release.complete(Unit); old.join()
        assertEquals("", screen.state.value.query); assertNull(screen.state.value.selectedStatus)
        assertEquals(Mango9CalendarMode.Day, screen.state.value.mode); assertTrue(screen.state.value.calendar)
        assertEquals(1, screen.state.value.events.size)
    }

    @Test fun filtersAndCrowdedDaySurviveStopRefreshButNeverCrossAccounts() = runBlocking {
        val sessions = Sessions()
        val repo = Mango9CalendarRepository(sessions, { error("No refresh") }) { response(it) }
        val screen = screen(this, repo); screen.refresh()!!.join()
        screen.search("Taylor"); screen.filterStatus(0); screen.showAgendaDay(date)
        withTimeout(3_000) { while (screen.state.value.loading) yield() }
        screen.stop(); screen.refresh()!!.join()
        assertEquals("Taylor", screen.state.value.query); assertEquals(0, screen.state.value.selectedStatus)
        assertEquals(date, screen.state.value.agendaDay)
        screen.chooseDate(date); assertNull(screen.state.value.agendaDay)
        screen.showAgendaDay(date)
        sessions.awayAndBack(); screen.accountChanged()
        withTimeout(3_000) { while (screen.state.value.loading) yield() }
        assertEquals("", screen.state.value.query); assertNull(screen.state.value.selectedStatus)
        assertNull(screen.state.value.agendaDay)
    }

    @Test fun noStatusFilterIncludesOnlyUnassignedStatusAndCombinesWithSearchAndDay() {
        val assigned = fixture.event()
        val unassigned = assigned.copy(id = 92, title = "No status fixture", status = null)
        val events = listOf(assigned, unassigned)
        assertEquals(listOf(unassigned), Mango9CalendarPresentation.filteredEvents(events, "fixture", 0, date, zone))
        assertEquals(listOf(assigned), Mango9CalendarPresentation.filteredEvents(events, "", 3, null, zone))
        assertEquals(events, Mango9CalendarPresentation.filteredEvents(events, "", null, null, zone))
        assertTrue(Mango9CalendarPresentation.filteredEvents(events, "", 0, date.plusDays(1), zone).isEmpty())
    }

    @Test fun obsoleteServerStatusSelectionClearsOnSuccessfulRefresh() = runBlocking {
        var removed = false
        val repo = Mango9CalendarRepository(Sessions(), { error("No refresh") }) {
            if (removed && it.url.pathSegments.last() == "metadata") {
                Mango9CalendarResponse(200, fixture.envelope(JSONObject(fixture.metadataJson).put("statuses", JSONArray()).toString()))
            } else {
                response(it)
            }
        }
        val screen = screen(this, repo); screen.refresh()!!.join(); screen.filterStatus(3)
        removed = true; screen.refresh()!!.join()
        assertNull(screen.state.value.selectedStatus)
    }
}
