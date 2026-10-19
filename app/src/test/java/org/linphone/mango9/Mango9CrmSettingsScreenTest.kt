/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import java.io.IOException
import java.time.ZoneId
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
import org.linphone.mango9.Mango9CalendarTestFixtures.Sessions

class Mango9CrmSettingsScreenTest {
    private val fixture = Mango9CalendarTestFixtures

    private fun response(settings: JSONObject = JSONObject(fixture.settingsJson)) =
        Mango9CalendarResponse(200, fixture.envelope(settings.toString()))

    private fun screen(scope: CoroutineScope, sessions: Sessions = Sessions(), transport: Mango9CalendarTransport) =
        Mango9CrmSettingsScreen(Mango9CalendarRepository(sessions, { error("No refresh") }, transport), scope)

    private suspend fun settled(screen: Mango9CrmSettingsScreen) { while (screen.state.value.busy) yield() }

    @Test fun expansionLoadsOnlyOnDemandAndKeepsDirtyDraftAcrossCollapse() = runBlocking {
        val calls = arrayListOf<Request>()
        val screen = screen(this) { calls.add(it); response() }
        assertTrue(calls.isEmpty()); assertFalse(screen.state.value.canSave)
        screen.setExpanded(true); settled(screen)
        assertEquals(1, calls.size); assertEquals("GET", calls.single().method)
        screen.edit(screen.capture()) { it.copy(smsPush = true, reminderMinutes = 30) }
        screen.setExpanded(false); screen.setExpanded(true); settled(screen)
        assertEquals(1, calls.size); assertTrue(screen.state.value.draft!!.smsPush)
        assertEquals(30, screen.state.value.draft!!.reminderMinutes); assertTrue(screen.state.value.canSave)
    }

    @Test fun reopeningDuringReadDoesNotCancelOrDuplicateIt() = runBlocking {
        val release = CompletableDeferred<Unit>(); var reads = 0
        val screen = screen(this) { reads++; release.await(); response() }
        screen.setExpanded(true); yield(); screen.setExpanded(false); screen.setExpanded(true)
        assertTrue(screen.state.value.busy); assertEquals(1, reads)
        release.complete(Unit); settled(screen)
        assertNotNull(screen.state.value.draft); assertFalse(screen.state.value.canSave)
    }

    @Test fun saveUsesRevisionAndOnlyAuthoritativeReturnedStateNotSubmittedDraft() = runBlocking {
        val calls = arrayListOf<Request>(); val version = Mango9CalendarChanges.version.value
        val screen = screen(this) {
            calls.add(it)
            response(
                JSONObject(fixture.settingsJson).apply {
                if (it.method == "PATCH") put("revision", "saved-v2").put("snooze_minutes", 30)
            }
            )
        }
        screen.reload()!!.join(); assertNull(screen.save())
        screen.edit(screen.capture()) { it.copy(smsPush = true, snoozeMinutes = 60) }
        screen.save()!!.join()
        val write = calls.last()
        assertEquals("PATCH", write.method); assertEquals("\"settings-fixture\"", write.header("If-Match"))
        assertEquals("/api/v2/mobile/crm/settings", write.url.encodedPath)
        val sent = JSONObject(Buffer().also { write.body!!.writeTo(it) }.readUtf8())
        assertTrue(sent.getBoolean("sms_push")); assertEquals(60, sent.getInt("snooze_minutes"))
        assertFalse(sent.has("account_timezone")); assertFalse(sent.has("revision"))
        assertEquals(30, screen.state.value.draft!!.snoozeMinutes)
        assertFalse(screen.state.value.draft!!.smsPush); assertEquals("saved-v2", screen.state.value.draft!!.revision)
        assertEquals(screen.state.value.draft, screen.state.value.baseline)
        assertTrue(screen.state.value.saved); assertFalse(screen.state.value.canSave)
        assertEquals(version + 1, Mango9CalendarChanges.version.value)
    }

    @Test fun busySaveRejectsDoubleTapReloadAndDraftMutation() = runBlocking {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var writes = 0
        val screen = screen(this) {
            if (it.method == "PATCH") { writes++; started.complete(Unit); release.await() }
            response()
        }
        screen.reload()!!.join(); screen.edit(screen.capture()) { it.copy(smsPush = true) }
        val save = screen.save()!!; started.await()
        assertNull(screen.save()); assertNull(screen.reload())
        screen.edit(screen.capture()) { it.copy(reminderMinutes = 120) }
        assertEquals(15, screen.state.value.draft!!.reminderMinutes)
        release.complete(Unit); save.join(); assertEquals(1, writes)
    }

    @Test fun uncertainFailureRequiresExplicitReloadAndDoesNotEmitSuccess() = runBlocking {
        var writes = 0; val version = Mango9CalendarChanges.version.value
        val screen = screen(this) {
            if (it.method == "PATCH") { writes++; throw IOException("Synthetic transport failure") }
            response()
        }
        screen.reload()!!.join(); screen.edit(screen.capture()) { it.copy(teamChatPush = false) }
        screen.save()!!.join(); assertTrue(screen.state.value.blocked)
        assertFalse(screen.state.value.canSave); assertFalse(screen.state.value.saved)
        assertTrue(screen.state.value.error!!.contains("Reload saved CRM settings"))
        screen.setExpanded(false); screen.setExpanded(true); screen.save()
        assertEquals(1, writes); assertEquals(version, Mango9CalendarChanges.version.value)
        screen.reload()!!.join(); assertFalse(screen.state.value.blocked)
        assertTrue(screen.state.value.draft!!.teamChatPush); assertNull(screen.state.value.error)
    }

    @Test fun conflictKeepsDraftButBlocksResubmission() = runBlocking {
        val screen = screen(this) {
            if (it.method == "PATCH") Mango9CalendarResponse(409, """{"error":{"code":"settings_changed"}}""") else response()
        }
        screen.reload()!!.join(); screen.edit(screen.capture()) { it.copy(firstWeekday = 1) }
        screen.save()!!.join()
        assertEquals(1, screen.state.value.draft!!.firstWeekday)
        assertTrue(screen.state.value.error!!.contains("another device")); assertNull(screen.save())
    }

    @Test fun oldReadIgnoringCancellationCannotPublishAcrossAwayAndBack() = runBlocking {
        val sessions = Sessions(); val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var reads = 0
        val screen = screen(this, sessions) {
            if (++reads == 1) { started.complete(Unit); withContext(NonCancellable) { release.await() } }
            response(JSONObject(fixture.settingsJson).put("revision", "read-$reads"))
        }
        screen.setExpanded(true); started.await(); sessions.awayAndBack(); screen.accountChanged(); settled(screen)
        assertEquals("read-2", screen.state.value.draft!!.revision)
        release.complete(Unit); yield(); yield()
        assertEquals("read-2", screen.state.value.draft!!.revision); assertFalse(screen.state.value.busy)
    }

    @Test fun staleSaveAndOpenPickerCannotEditReplacementAccount() = runBlocking {
        val sessions = Sessions(); val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val version = Mango9CalendarChanges.version.value
        val screen = screen(this, sessions) {
            if (it.method == "PATCH") { started.complete(Unit); withContext(NonCancellable) { release.await() } }
            response()
        }
        screen.setExpanded(true); settled(screen); val oldLease = screen.capture()!!
        screen.edit(oldLease) { it.copy(smsPush = true) }; val oldSave = screen.save()!!; started.await()
        sessions.awayAndBack(); screen.accountChanged(); settled(screen)
        screen.edit(oldLease) { it.copy(smsPush = true) }
        assertFalse(screen.state.value.draft!!.smsPush)
        release.complete(Unit); oldSave.join()
        assertFalse(screen.state.value.saved); assertFalse(screen.state.value.busy)
        assertEquals(version, Mango9CalendarChanges.version.value)
    }

    @Test fun accountRemovalClearsDataEvenWithoutAHostCallback() = runBlocking {
        val sessions = Sessions(); val screen = screen(this, sessions) { response() }
        screen.reload()!!.join(); val lease = screen.capture()
        sessions.session = null
        screen.edit(lease) { it.copy(smsPush = true) }
        assertNull(screen.state.value.draft); assertNull(screen.state.value.accountName); assertNull(screen.save())
    }

    @Test fun cancellationOfCurrentWriteRequiresReload() = runBlocking {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val screen = screen(this) {
            if (it.method == "PATCH") { started.complete(Unit); release.await() }
            response()
        }
        screen.reload()!!.join(); screen.edit(screen.capture()) { it.copy(smsPush = true) }
        val save = screen.save()!!; started.await(); save.cancel(); save.join()
        assertTrue(screen.state.value.blocked); assertFalse(screen.state.value.busy); assertNull(screen.save())
    }

    @Test fun failureDoesNotInventPreferencesAndMalformedSettingsAreRejected() = runBlocking {
        val screen = screen(this) { Mango9CalendarResponse(404, "{}") }
        screen.reload()!!.join(); assertNull(screen.state.value.draft); assertTrue(screen.state.value.blocked)
        val base = JSONObject(fixture.settingsJson)
        for ((key, value) in listOf("snooze_minutes" to -1, "repeat_minutes" to 999, "first_weekday" to 9, "new_appointment_timezone" to "Bad/Zone", "default_calendar_view" to "3 days")) {
            assertTrue(runCatching { Mango9CalendarJson.preferences(JSONObject(base.toString()).put(key, value)) }.isFailure)
        }
    }

    @Test fun newEventZoneDefaultsNeverModifyAccountTimezoneOrRevisionInDraft() = runBlocking {
        val screen = screen(this) { response() }
        screen.reload()!!.join()
        screen.edit(screen.capture()) {
            it.copy(newAppointmentTimezone = "device", accountTimezone = "UTC", revision = "forged")
        }
        val draft = screen.state.value.draft!!
        assertEquals("America/Los_Angeles", draft.accountTimezone); assertEquals("settings-fixture", draft.revision)
        assertEquals(ZoneId.of("Europe/Paris"), draft.appointmentZone(ZoneId.of("Europe/Paris")))
    }
}
