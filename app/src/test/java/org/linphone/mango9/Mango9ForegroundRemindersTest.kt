/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.linphone.mango9.Mango9CalendarTestFixtures.Sessions

class Mango9ForegroundRemindersTest {
    private val fixture = Mango9CalendarTestFixtures

    private class History : Mango9ReminderHistory {
        val cadence = Mango9ReminderCadence()
        var records = 0

        override suspend fun ready() {}

        override fun allows(key: String, now: Instant, repeatMinutes: Int) = cadence.allows(key, now, repeatMinutes)

        override fun record(key: String, now: Instant) { records++; cadence.record(key, now) }
    }

    private fun feed(id: Int = 91, revision: String = "reminder-v1", enabled: Boolean = true, server: String = "2026-09-11T15:50:00Z") =
        JSONObject().put("server_time", server).put("settings", JSONObject(fixture.settingsJson).put("in_app_reminders", enabled))
            .put(
                "items",
                JSONArray().put(
                    JSONObject().put("event", JSONObject(fixture.eventJson).put("id", id))
                .put("reminder", JSONObject("""{"enabled":true,"minutes_before":15,"due_at":"2026-09-11T15:45:00Z","dismissed":false,"needs_reenable":false,"revision":"$revision"}"""))
                )
            )

    private fun response(data: JSONObject) = Mango9CalendarResponse(200, fixture.envelope(data.toString()))

    private suspend fun await(ready: () -> Boolean) = withTimeout(5_000) { while (!ready()) yield() }

    @Test fun startsOnlyWhenForegroundAndServerTimeMakesOneReminderDue() = runBlocking {
        var permitted = false; var reads = 0
        val history = History(); val sessions = Sessions()
        val repo = Mango9CalendarRepository(sessions, { error("No refresh") }) { reads++; response(feed()) }
        val screen = Mango9ForegroundReminders(repo, this, history, { permitted })
        try {
            screen.setActive(true); yield(); assertEquals(0, reads)
            permitted = true; screen.setActive(true); await { screen.state.value.item != null }
            assertEquals(91, screen.state.value.item!!.event.id); assertEquals(1, history.records)
            permitted = false; screen.setActive(false)
            assertNull(screen.state.value.item); assertNull(screen.state.value.selection)
            screen.setActive(true); yield(); assertEquals(1, reads)
        } finally { screen.setActive(false) }
    }

    @Test fun periodicPollRetainsVisibleItemWithoutRepeatingCadenceAndExpires() = runBlocking {
        var reads = 0
        val history = History()
        val repo = Mango9CalendarRepository(Sessions(), { error("No refresh") }) { reads++; response(feed()) }
        val screen = Mango9ForegroundReminders(repo, this, history, { true }, pollMillis = 10, visibleMillis = 100)
        try {
            screen.setActive(true); await { reads >= 3 && screen.state.value.item != null }
            assertEquals(1, history.records)
            await { screen.state.value.item == null }
            delay(30); assertNull(screen.state.value.item); assertEquals(1, history.records)
        } finally { screen.setActive(false) }
    }

    @Test fun cadenceSurvivesHostReplacementAndNewReminderRevisionCanAppear() = runBlocking {
        val history = History(); var revision = "reminder-v1"
        val repo = Mango9CalendarRepository(Sessions(), { error("No refresh") }) { response(feed(revision = revision)) }
        val first = Mango9ForegroundReminders(repo, this, history, { true })
        first.setActive(true); await { first.state.value.item != null }; first.setActive(false)
        val second = Mango9ForegroundReminders(repo, this, history, { true }, pollMillis = 10)
        try {
            second.setActive(true); delay(40); assertNull(second.state.value.item)
            revision = "reminder-v2"; second.changed(); await { second.state.value.item != null }
            assertEquals("reminder-v2", second.state.value.item!!.reminder.revision)
        } finally { first.setActive(false); second.setActive(false) }
    }

    @Test fun settingsDisabledOrOfflineClearsVisibleReminderWithoutCachedReplay() = runBlocking {
        var enabled = true; var fail = false; var revision = "r1"; var reads = 0
        val repo = Mango9CalendarRepository(Sessions(), { error("No refresh") }) {
            reads++; if (fail) throw IOException("Offline") else response(feed(revision = revision, enabled = enabled))
        }
        val screen = Mango9ForegroundReminders(repo, this, History(), { true }, pollMillis = 10)
        try {
            screen.setActive(true); await { screen.state.value.item != null }
            enabled = false; await { screen.state.value.item == null }
            revision = "r2"; enabled = true; await { screen.state.value.item != null }
            fail = true; await { screen.state.value.item == null }
            val count = reads; delay(30); assertTrue(reads > count); assertNull(screen.state.value.item)
        } finally { screen.setActive(false) }
    }

    @Test fun oldReadIgnoringCancellationCannotCrossAwayAndBackSelection() = runBlocking {
        val sessions = Sessions(); val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var reads = 0
        val repo = Mango9CalendarRepository(sessions, { error("No refresh") }) {
            if (++reads == 1) { started.complete(Unit); withContext(NonCancellable) { release.await() }; response(feed(91)) } else {
                response(feed(92))
            }
        }
        val screen = Mango9ForegroundReminders(repo, this, History(), { true })
        try {
            screen.setActive(true); started.await(); sessions.awayAndBack(); screen.accountChanged()
            await { screen.state.value.item?.event?.id == 92 }; release.complete(Unit); yield(); yield()
            assertEquals(92, screen.state.value.item!!.event.id)
        } finally { release.complete(Unit); screen.setActive(false) }
    }

    @Test fun callGuardAfterSuspensionBlocksBannerEvenBeforeUiNotification() = runBlocking {
        var permitted = true; val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val repo =
            Mango9CalendarRepository(Sessions(), {
                error("No refresh")
            }) { started.complete(Unit); release.await(); response(feed()) }
        val history = History(); val screen = Mango9ForegroundReminders(repo, this, history, { permitted })
        try {
            screen.setActive(true); started.await(); permitted = false; release.complete(Unit); delay(30)
            assertNull(screen.state.value.item); assertEquals(0, history.records)
        } finally { release.complete(Unit); screen.setActive(false) }
    }

    @Test fun openingRevalidatesMetadataAndAppointmentAndDoesNotDuplicateRequests() = runBlocking {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var metadataReads = 0
        val repo = Mango9CalendarRepository(Sessions(), { error("No refresh") }) { request ->
            when {
                request.url.encodedPath.endsWith("/metadata") -> {
                    metadataReads++; started.complete(Unit); release.await(); response(JSONObject(fixture.metadataJson))
                }
                request.url.encodedPath.endsWith("/events/91") -> response(JSONObject(fixture.eventJson).put("revision", "fresh-event"))
                else -> response(feed())
            }
        }
        val screen = Mango9ForegroundReminders(repo, this, History(), { true })
        try {
            screen.setActive(true); await { screen.state.value.item != null }; screen.open(); started.await(); screen.open()
            assertEquals(1, metadataReads); assertTrue(screen.state.value.opening)
            release.complete(Unit); await { screen.state.value.selection != null && !screen.state.value.opening }
            val selected = screen.state.value.selection!!; assertEquals("fresh-event", selected.event.revision)
            screen.changed(); assertSame(selected, screen.state.value.selection)
            screen.dismissSelection(); assertNull(screen.state.value.selection)
        } finally { release.complete(Unit); screen.setActive(false) }
    }

    @Test fun slowHistoryInitializationRechecksCallGuardBeforeStartingNetworkRead() = runBlocking {
        var permitted = true; var reads = 0
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val history = object : Mango9ReminderHistory {
            override suspend fun ready() { started.complete(Unit); release.await() }

            override fun allows(key: String, now: Instant, repeatMinutes: Int) = error("No candidates expected")

            override fun record(key: String, now: Instant) = error("No presentation expected")
        }
        val repo = Mango9CalendarRepository(Sessions(), { error("No refresh") }) { reads++; response(feed()) }
        val screen = Mango9ForegroundReminders(repo, this, history, { permitted })
        try {
            screen.setActive(true); started.await(); permitted = false; release.complete(Unit); delay(30)
            assertEquals(0, reads); assertNull(screen.state.value.item)
        } finally { release.complete(Unit); screen.setActive(false) }
    }

    @Test fun callAfterMetadataBlocksDetailReadEvenBeforeUiLifecycleCallback() = runBlocking {
        var permitted = true; var details = 0
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val repo = Mango9CalendarRepository(Sessions(), { error("No refresh") }) { request ->
            when {
                request.url.encodedPath.endsWith("/metadata") -> {
                    started.complete(Unit); release.await(); response(JSONObject(fixture.metadataJson))
                }
                request.url.encodedPath.endsWith("/events/91") -> { details++; response(JSONObject(fixture.eventJson)) }
                else -> response(feed())
            }
        }
        val screen = Mango9ForegroundReminders(repo, this, History(), { permitted })
        try {
            screen.setActive(true); await { screen.state.value.item != null }; screen.open(); started.await()
            permitted = false; release.complete(Unit); await { !screen.state.value.opening }
            assertEquals(0, details); assertNull(screen.state.value.item); assertNull(screen.state.value.selection)
        } finally { release.complete(Unit); screen.setActive(false) }
    }

    @Test fun callDuringOpeningClearsSelectionAndErrorCanRetryOnlyWhileEligible() = runBlocking {
        var permitted = true; var fail = true; val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val repo = Mango9CalendarRepository(Sessions(), { error("No refresh") }) { request ->
            when {
                request.url.encodedPath.endsWith("/metadata") -> {
                    if (fail) throw IOException("Synthetic failure")
                    started.complete(Unit); withContext(NonCancellable) { release.await() }; response(JSONObject(fixture.metadataJson))
                }
                request.url.encodedPath.endsWith("/events/91") -> response(JSONObject(fixture.eventJson))
                else -> response(feed())
            }
        }
        val screen = Mango9ForegroundReminders(repo, this, History(), { permitted })
        try {
            screen.setActive(true); await { screen.state.value.item != null }; screen.open(); await { screen.state.value.error }
            fail = false; screen.open(); started.await(); permitted = false; screen.setActive(false); release.complete(Unit); delay(30)
            assertNull(screen.state.value.selection); assertFalse(screen.state.value.opening); assertNull(screen.state.value.item)
        } finally { release.complete(Unit); screen.setActive(false) }
    }
}
