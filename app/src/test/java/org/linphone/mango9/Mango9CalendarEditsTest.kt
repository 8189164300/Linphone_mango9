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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Mango9CalendarEditsTest {
    private val fixture = Mango9CalendarTestFixtures

    private fun response(json: String = fixture.eventJson) = Mango9CalendarResponse(200, fixture.envelope(json))

    private fun body(request: Request) = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())

    private fun hoursJson(revision: String = "hours-v1", editable: Boolean = true): String {
        val days = JSONArray((0..6).map { JSONObject().put("weekday", it).put("open", "09:00").put("close", "18:00") })
        return JSONObject().put("configured", true).put("quick_enable", true).put("timezone", "America/Los_Angeles")
            .put("timezone_editable", editable).put("days", days).put("defaults", days).put("revision", revision).toString()
    }

    private fun coordinator(scope: CoroutineScope, sessions: Mango9CalendarTestFixtures.Sessions = Mango9CalendarTestFixtures.Sessions(), transport: Mango9CalendarTransport): Pair<Mango9CalendarRepository, Mango9CalendarEdits> {
        val repo = Mango9CalendarRepository(sessions, { error("No refresh") }, transport)
        return repo to Mango9CalendarEdits(repo, scope)
    }

    private fun appointment(repo: Mango9CalendarRepository, edits: Mango9CalendarEdits) =
        edits.appointment(repo.capture()!!, fixture.metadata(), fixture.event(), null)!!

    private suspend fun settled(edit: Mango9CalendarEdit) { while (edit.progress.value.busy) yield() }

    @Test fun creationKeepsCapturedZoneContactAndDefaultsWithoutInventingStatus() = runBlocking {
        val (repo, edits) = coordinator(this) { error("No read for draft") }
        val lease = repo.capture()!!; val event = fixture.event(); val zone = ZoneId.of("Asia/Yerevan")
        val creation = Mango9AppointmentCreationContext(lease, fixture.metadata(), event.contact, event.startAt, zone)
        val edit = edits.appointment(lease, fixture.metadata(), null, creation)!!
        assertEquals(zone, edit.zone); assertEquals(event.contact, edit.draft.contact)
        assertNull(edit.draft.status); assertEquals("medium", edit.draft.priority); assertFalse(edit.canSave)
        edit.draft = edit.draft.copy(title = "New visit", notes = "Keep typed text")
        assertSame(edit, edits.active); assertTrue(edit.canSave)
        assertNull(edits.appointment(lease, fixture.metadata(), null, creation))
        assertEquals("Keep typed text", (edits.active as Mango9AppointmentEdit).draft.notes)
    }

    @Test fun existingAppointmentCapturesDisplayZoneWithoutChangingServerZoneOrTimestamps() = runBlocking {
        val calls = arrayListOf<Request>()
        val (repo, edits) = coordinator(this) { calls.add(it); response() }
        val lease = repo.capture()!!
        val event = fixture.event().copy(timezone = "UTC")
        val zone = ZoneId.of("Europe/Paris")
        val edit = edits.appointment(lease, fixture.metadata(), event, null, editingZone = zone)!!
        assertEquals(zone, edit.zone)
        assertEquals(event.startAt, edit.draft.start); assertEquals(event.endAt, edit.draft.end)
        assertEquals("UTC", edit.event!!.timezone); assertFalse(edit.canSave)
        assertNull(edits.appointment(lease, fixture.metadata(), event, null, editingZone = ZoneId.of("Asia/Yerevan")))
        assertEquals(zone, (edits.active as Mango9AppointmentEdit).zone)
        edit.draft = edit.draft.copy(title = "Only the title changes")
        edit.save()!!.join()
        val submitted = body(calls.single())
        assertEquals(setOf("title"), submitted.keys().asSequence().toSet())
        assertEquals("Only the title changes", submitted.getString("title"))
        assertTrue(edit.progress.value.completed)
    }

    @Test fun busySaveRejectsCancelSecondSaveAndEditsAndCompletesAtomically() = runBlocking {
        val release = CompletableDeferred<Unit>(); val started = CompletableDeferred<Unit>(); val calls = arrayListOf<Request>()
        val (repo, edits) = coordinator(this) { calls.add(it); started.complete(Unit); release.await(); response() }
        val edit = appointment(repo, edits); assertFalse(edit.canSave)
        edit.draft = edit.draft.copy(title = "Changed once")
        val save = edit.save()!!; started.await()
        assertFalse(edits.dismiss(edit)); assertNull(edit.save())
        edit.draft = edit.draft.copy(title = "Must not replace submitted draft")
        assertEquals("Changed once", edit.draft.title)
        release.complete(Unit); save.join()
        assertEquals(1, calls.size); assertEquals("\"abc123\"", calls.single().header("If-Match"))
        assertEquals(setOf("title"), body(calls.single()).keys().asSequence().toSet())
        assertTrue(edit.progress.value.completed); assertFalse(edit.progress.value.writing)
        assertTrue(edits.dismiss(edit)); assertNull(edits.active)
    }

    @Test fun uncertainAppointmentSaveRemainsBlockedWithDraftRetained() = runBlocking {
        var writes = 0
        val (repo, edits) = coordinator(this) { writes++; throw IOException("Synthetic transport failure") }
        val edit = appointment(repo, edits); edit.draft = edit.draft.copy(title = "Keep this draft")
        edit.save()!!.join()
        assertTrue(edit.progress.value.blocked); assertFalse(edit.progress.value.completed); assertNotNull(edit.progress.value.error)
        assertNull(edit.save()); edit.draft = edit.draft.copy(title = "Rejected edit")
        assertEquals("Keep this draft", edit.draft.title); assertEquals(1, writes)
    }

    @Test fun explicitOverlapCanBeCorrectedButRevisionConflictCannotBlindlyRetry() = runBlocking {
        for (code in listOf("overlap", "event_changed")) {
            val (repo, edits) = coordinator(this) { Mango9CalendarResponse(409, """{"success":false,"error":{"code":"$code"}}""") }
            val edit = appointment(repo, edits); edit.draft = edit.draft.copy(title = "New title")
            edit.save()!!.join()
            assertEquals(code != "overlap", edit.progress.value.blocked)
            assertEquals(code == "overlap", edit.canSave)
            assertFalse(edit.progress.value.completed)
        }
    }

    @Test fun accountReplacementCannotAcceptLateSaveOrRetainOldDraft() = runBlocking {
        val sessions = Mango9CalendarTestFixtures.Sessions(); val release = CompletableDeferred<Unit>(); val started = CompletableDeferred<Unit>()
        val before = Mango9CalendarChanges.version.value
        val (repo, edits) = coordinator(this, sessions) {
            started.complete(Unit); withContext(NonCancellable) { release.await() }; response()
        }
        val edit = appointment(repo, edits); edit.draft = edit.draft.copy(title = "Old account edit")
        val job = edit.save()!!; started.await(); sessions.awayAndBack(); edits.checkAccount()
        assertNull(edits.active); release.complete(Unit); job.join()
        assertFalse(edit.progress.value.completed); assertFalse(edit.canSave)
        assertEquals(before, Mango9CalendarChanges.version.value)
    }

    @Test fun restrictedSharePickerPreservesUnknownIdsAndReminderNeedsAChannel() = runBlocking {
        var writes = 0
        val (repo, edits) = coordinator(this) { writes++; response() }
        val event = fixture.event().copy(sharedByMeUserIds = listOf(43, 99))
        val edit = edits.appointment(repo.capture()!!, fixture.metadata(), event, null)!!
        edit.draft = edit.draft.selectingShares(setOf(43), setOf(200))
        assertEquals(setOf(99), edit.draft.shares)
        edit.draft = edit.draft.copy(reminder = 15)
        assertFalse(edit.canSave); assertNotNull(edit.validation); assertNull(edit.save()); assertEquals(0, writes)
        edit.draft = edit.draft.copy(channels = listOf("email"))
        assertTrue(edit.canSave); edit.save()!!.join(); assertEquals(1, writes)
    }

    @Test fun unchangedLegacyReminderDoesNotPreventUnrelatedPermittedEdits() {
        val event = fixture.event().copy(reminders = listOf(Mango9AppointmentReminder(15, emptyList())))
        val draft = Mango9AppointmentDraft.from(event).copy(title = "Only title")
        assertNull(draft.validationMessage(event, true))
        assertNotNull(draft.copy(reminder = 30).validationMessage(event, true))
        assertNull(draft.copy(reminder = 30).validationMessage(event, false))
    }

    @Test fun hoursUseOriginalRevisionBlockUncertainSaveAndExplicitlyReload() = runBlocking {
        var failed = false; val calls = arrayListOf<Request>()
        val (repo, edits) = coordinator(this) {
            calls.add(it)
            if (it.method == "PATCH") { failed = true; throw IOException("Synthetic uncertain save") }
            response(hoursJson(if (failed) "hours-v2" else "hours-v1"))
        }
        val edit = edits.businessHours(repo.capture()!!)!!; settled(edit)
        assertFalse(edit.canSave); assertNull(edit.save())
        edit.draft = edit.draft!!.copy(timezone = "Europe/Paris", revision = "forged")
        assertEquals("hours-v1", edit.draft!!.revision); assertTrue(edit.timezoneChanged); assertTrue(edit.canSave)
        edit.save()!!.join(); assertTrue(edit.progress.value.blocked); assertNull(edit.save())
        assertEquals("\"hours-v1\"", calls.last().header("If-Match"))
        assertEquals("timezone", body(calls.last()).getString("action"))
        edit.reload()!!.join()
        assertFalse(edit.progress.value.blocked); assertEquals("hours-v2", edit.original!!.revision)
        assertEquals(edit.original, edit.draft); assertFalse(edit.canSave)
    }

    @Test fun readOnlyTimezoneAndBaselineDefaultsCannotBeForgedByDraft() = runBlocking {
        val (repo, edits) = coordinator(this) { response(hoursJson(editable = false)) }
        val edit = edits.businessHours(repo.capture()!!)!!; settled(edit)
        val original = edit.original!!
        edit.draft = original.copy(timezone = "Europe/Paris", timezoneEditable = true, defaults = emptyList())
        assertEquals(original, edit.draft); assertFalse(edit.canSave)
        edit.draft = original.closeAll(); assertTrue(edit.canSave)
        edit.draft = edit.draft!!.useDefaults(); assertEquals(original.days, edit.draft!!.days)
    }

    @Test fun failedRecoveryReadCannotUnlockAnUncertainSave() = runBlocking {
        var fail = false
        val (repo, edits) = coordinator(this) {
            if (fail || it.method == "PATCH") { fail = true; throw IOException("Synthetic unavailable server") }
            response(hoursJson())
        }
        val edit = edits.businessHours(repo.capture()!!)!!; settled(edit)
        edit.draft = edit.draft!!.closeAll()
        edit.save()!!.join(); assertTrue(edit.progress.value.blocked)
        edit.reload()!!.join()
        assertTrue(edit.progress.value.blocked); assertFalse(edit.canSave); assertNull(edit.save())
        assertTrue(edit.draft!!.days.none { it.isOpen })
        fail = false; edit.reload()!!.join()
        assertFalse(edit.progress.value.blocked); assertEquals(edit.original, edit.draft)
    }

    @Test fun dismissedEditorReferencesCannotBeReusedByLateCallbacks() = runBlocking {
        var reads = 0
        val (repo, edits) = coordinator(this) { reads++; response(hoursJson()) }
        val appointment = appointment(repo, edits)
        appointment.draft = appointment.draft.copy(title = "Discarded")
        assertTrue(edits.dismiss(appointment)); assertTrue(appointment.progress.value.closed)
        appointment.draft = appointment.draft.copy(title = "Late callback")
        assertEquals("Discarded", appointment.draft.title); assertNull(appointment.save())
        val hours = edits.businessHours(repo.capture()!!)!!; settled(hours)
        assertTrue(edits.dismiss(hours)); assertTrue(hours.progress.value.closed)
        assertNull(hours.reload()); assertNull(hours.save()); assertEquals(1, reads)
    }
}
