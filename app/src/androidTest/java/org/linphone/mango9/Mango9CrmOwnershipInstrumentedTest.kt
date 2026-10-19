/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.ui.main.crm.viewmodel.Mango9CrmViewModel

/** Real main-thread ViewModel/lifecycle. Synthetic repository; never sends CRM requests. */
@RunWith(AndroidJUnit4::class)
class Mango9CrmOwnershipInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    internal class Repository : Mango9CrmScreenAccess {
        val generations = Mango9SessionGenerations()
        var session: Mango9Session? = Mango9Session(
            "fixture", "https://crm.example.invalid", "https://crm.example.invalid/api/v2", "42", "1", "agent",
            "crm@example.invalid", "Synthetic CRM", "test-access", "test-refresh", "", "", 1_900_000_000, "sip:42@crm.example.invalid",
        )
        val changes = MutableStateFlow(0L)
        val calls = mutableListOf<String>()
        val writes = mutableListOf<Pair<Mango9SessionLease, Map<String, String>>>()
        var holdDashboard: CompletableDeferred<Unit>? = null
        var holdRecords: CompletableDeferred<Unit>? = null
        var holdDetail: CompletableDeferred<Unit>? = null
        var holdWrite: CompletableDeferred<Unit>? = null
        var wrongDetail = false
        var failWrite = false
        var pagedRecords = false
        var failRecords = false
        val schema = Mango9CrmSchema(
            "leads", "v1", listOf(Mango9CrmSchema.Section("personal", "Personal information")),
            listOf(Mango9CrmSchema.Field("lead_status", null, null, "Status", "select", "personal", false, true, true, false, listOf("New", "Contacted"))),
            listOf("New", "Contacted"),
        )

        fun row(id: Int = 7, name: String = "Synthetic record") = Mango9CrmRecord(id, 42, "", name, "15550102020", "", "New", "", "2030-01-01")

        fun detail(id: Int = 7) = Mango9CrmRecordDetail(row(id), "v1", mapOf("lead_status" to "New", "first_name" to "Synthetic"))

        fun replaceLogin() { generations.loginChanged(session!!.sipIdentity!!); changes.value++ }

        fun switchAccount() {
            generations.selectionChanged()
            session = session!!.copy(sipIdentity = "sip:84@crm.example.invalid", userId = "84", displayName = "Other CRM")
            changes.value++
        }

        override fun captureSession() = session?.let(generations::capture)

        override fun isCurrent(lease: Mango9SessionLease) = generations.matches(lease, session?.sipIdentity)

        override suspend fun dashboard(lease: Mango9SessionLease): Mango9CrmDashboard {
            calls += "dashboard:${lease.session.userId}"; holdDashboard?.await()
            return Mango9CrmDashboard(0.0, 0.0, 0.0, 12, 8)
        }

        override suspend fun schema(lease: Mango9SessionLease, kind: Mango9RecordKind) = schema.also {
            calls += "schema:${kind.path}"
        }

        override suspend fun groups(lease: Mango9SessionLease, kind: Mango9RecordKind) = listOf(Mango9CrmGroup("5", "Synthetic group"))

        override suspend fun record(lease: Mango9SessionLease, kind: Mango9RecordKind, id: Int): Mango9CrmRecordDetail {
            calls += "detail:${kind.path}:$id"; holdDetail?.await()
            return detail(if (wrongDetail) id + 1 else id)
        }

        override suspend fun records(
            lease: Mango9SessionLease, kind: Mango9RecordKind, search: String, status: String,
                                     groupId: String, dateFilter: String, page: Int
        ): Mango9CrmRecordPage {
            calls += "records:${lease.session.userId}:${kind.path}:$page:$search:$status:$groupId:$dateFilter"
            holdRecords?.await()
            if (failRecords) throw Mango9ApiException.Network
            if (pagedRecords) {
                return Mango9CrmRecordPage(
                listOf(row(id = page, name = "$search/$status/$page")), Mango9CrmPagination(3, page, 1, 3)
            )
            }
            return Mango9CrmRecordPage(listOf(row(name = "${lease.session.userId} ${kind.path}")), Mango9CrmPagination(1, 1, 50, 1))
        }

        override suspend fun createRecord(
            lease: Mango9SessionLease, kind: Mango9RecordKind, firstName: String, lastName: String,
                                          phone: String, email: String, groupId: String
        ): Mango9CrmRecordDetail {
            calls += "create:${kind.path}"; writes += lease to mapOf("first_name" to firstName, "group_id" to groupId)
            holdWrite?.await(); return detail()
        }

        override suspend fun updateRecord(lease: Mango9SessionLease, kind: Mango9RecordKind, id: Int, values: Map<String, String>): Mango9CrmRecordDetail {
            calls += "update:${kind.path}:$id"; writes += lease to values
            holdWrite?.await()
            if (failWrite) throw Mango9ApiException.Network
            return detail(id).copy(values = detail(id).values + values)
        }

        override suspend fun deleteRecord(lease: Mango9SessionLease, kind: Mango9RecordKind, id: Int) {
            calls += "delete:${kind.path}:$id"; holdWrite?.await()
        }
    }

    @Test fun accountAndSameIdentityReloginImmediatelyClearDataAndPendingEvents() = fixture { model, repository ->
        ui { model.selectLeads(); model.openRecord(model.records.value!!.single()) }
        ui {
            assertNotNull(model.detailLoadedEvent.value); assertNotNull(model.schema.value)
            assertEquals(1, model.records.value!!.size)
            repository.holdDashboard = CompletableDeferred()
            repository.switchAccount()
        }
        await {
            model.activeIdentity.value == repository.session!!.sipIdentity && model.records.value!!.isEmpty()
        }
        ui {
            assertNull(model.schema.value); assertTrue(model.groups.value!!.isEmpty()); assertNull(model.dashboard.value)
            assertNull(model.detailLoadedEvent.value); assertNull(model.createRequestedEvent.value)
            assertTrue(model.showDashboard.value!!)
            repository.holdDashboard!!.complete(Unit); repository.holdDashboard = null
            model.selectLeads(); model.requestCreate()
            assertNotNull(model.editorOwner(Mango9RecordKind.Lead, null))
            repository.replaceLogin()
        }
        await { model.showDashboard.value == true && model.createRequestedEvent.value == null }
        ui { assertNull(model.editorOwner(Mango9RecordKind.Lead, null)); assertTrue(model.records.value!!.isEmpty()) }
    }

    @Test fun oldRecyclerRowCannotOpenUnderNewEntityEvenWhenIdsMatch() = fixture { model, repository ->
        lateinit var old: Mango9CrmRecord
        ui {
            model.selectLeads(); old = model.records.value!!.single()
            repository.holdRecords = CompletableDeferred()
            model.selectClients()
            assertTrue(model.records.value!!.isEmpty())
            model.openRecord(old)
            assertFalse(repository.calls.any { it.startsWith("detail:") })
            repository.holdRecords!!.complete(Unit)
        }
        await { model.records.value?.singleOrNull()?.name == "42 clients" }
        ui {
            model.openRecord(old)
            assertFalse(repository.calls.any { it.startsWith("detail:") })
            model.openRecord(model.records.value!!.single())
            assertEquals(listOf("detail:clients:7"), repository.calls.filter { it.startsWith("detail:") })
        }
    }

    @Test fun staleQueuedDetailAndWrongIdCannotAuthorizeAnEditor() = fixture { model, repository ->
        val release = CompletableDeferred<Unit>()
        ui {
            repository.holdDetail = release
            model.selectLeads(); model.openRecord(model.records.value!!.single())
            repository.replaceLogin()
        }
        await { model.showDashboard.value == true }
        ui { release.complete(Unit) }
        instrumentation.waitForIdleSync()
        ui {
            assertNull(model.detailLoadedEvent.value); assertNull(model.editorOwner(Mango9RecordKind.Lead, 7))
            repository.holdDetail = null; repository.wrongDetail = true
            model.selectLeads(); model.openRecord(model.records.value!!.single())
            assertNull(model.detailLoadedEvent.value); assertNull(model.editorOwner(Mango9RecordKind.Lead, 7))
            assertNotNull(model.errorMessage.value)
        }
    }

    @Test fun duplicateSaveIsSingleFlightAndLateSuccessCannotRefreshAnotherAccount() = fixture { model, repository ->
        val release = CompletableDeferred<Unit>(); var completions = 0
        lateinit var owner: Mango9CrmEditorOwner
        ui {
            model.selectLeads(); model.openRecord(model.records.value!!.single())
            owner = model.editorOwner(Mango9RecordKind.Lead, 7)!!
            repository.holdWrite = release
            val values = mutableMapOf("first_name" to "Submitted")
            model.updateRecord(owner, values) { _, _ -> completions++ }
            values["first_name"] = "Changed later"
            model.updateRecord(owner, values) { _, _ -> completions++ }
            assertEquals(1, repository.writes.size); assertEquals("Submitted", repository.writes.single().second["first_name"])
            repository.switchAccount()
        }
        await { model.activeIdentity.value == repository.session!!.sipIdentity }
        val before = repository.calls.toList()
        ui { release.complete(Unit) }
        instrumentation.waitForIdleSync()
        ui {
            assertEquals(0, completions); assertEquals(before, repository.calls)
            model.deleteRecord(owner) { _, _ -> completions++ }
            assertFalse(repository.calls.any { it.startsWith("delete:") })
            assertEquals(0, completions)
        }
    }

    @Test fun currentCreateUpdateDeleteAndStatusShortcutUseCapturedEntityAndSparseValues() = fixture { model, repository ->
        var completed = 0
        ui {
            model.selectClients(); model.requestCreate()
            val create = model.editorOwner(Mango9RecordKind.Client, null)!!
            model.createRecord(create, "Synthetic", "Client", "15550102020", "", "5") { ok, error ->
                assertTrue(ok); assertNull(error); completed++
            }
            model.openRecord(model.records.value!!.single())
            val owner = model.editorOwner(Mango9RecordKind.Client, 7)!!
            model.updateRecord(owner, mapOf("first_name" to "Revised")) { ok, error -> assertTrue(ok); assertNull(error); completed++ }
            model.deleteRecord(owner) { ok, error -> assertTrue(ok); assertNull(error); completed++ }
            assertEquals(3, completed)
            assertTrue(repository.calls.containsAll(listOf("create:clients", "update:clients:7", "delete:clients:7")))
            model.openRecord(model.records.value!!.single())
            val shortcut = model.quickStatus(Mango9RecordKind.Client, repository.detail())
            runBlocking { assertTrue(shortcut.change("Contacted")) }
            assertEquals(mapOf("lead_status" to "Contacted"), repository.writes.last().second)
            assertEquals("Synthetic", shortcut.detail.values["first_name"])
            repository.replaceLogin()
            runBlocking {
                try { shortcut.change("New"); fail("Old status action accepted") } catch (_: Mango9ApiException.Unauthorized) { }
            }
        }
    }

    @Test fun replacedPresentationRejectsStaleConfirmationAndFailureCallback() = fixture { model, repository ->
        val release = CompletableDeferred<Unit>(); var completions = 0
        ui {
            model.selectLeads(); model.openRecord(model.records.value!!.single())
            val original = model.editorOwner(Mango9RecordKind.Lead, 7)!!
            repository.holdWrite = release; repository.failWrite = true
            model.updateRecord(original, mapOf("first_name" to "Draft")) { _, _ -> completions++ }
            model.openRecord(model.records.value!!.single())
            assertFalse(model.isEditorCurrent(original))
            model.deleteRecord(original) { _, _ -> completions++ }
            release.complete(Unit)
        }
        instrumentation.waitForIdleSync()
        ui {
            assertEquals(0, completions); assertFalse(repository.calls.any { it.startsWith("delete:") })
            assertNull(model.errorMessage.value)
        }
    }

    @Test fun paginationCannotOvertakeAFilteredFirstPage() = fixture { model, repository ->
        val release = CompletableDeferred<Unit>()
        ui {
            repository.pagedRecords = true; model.selectLeads(); model.loadMore()
            assertEquals(listOf("//1", "//2"), model.records.value!!.map { it.name })
            repository.holdRecords = release; model.setStatus("Contacted")
            val requests = repository.calls.toList()
            model.loadMore(); model.loadMore()
            assertEquals(requests, repository.calls)
            assertTrue(model.loading.value!!)
            release.complete(Unit); repository.holdRecords = null
        }
        await { model.records.value?.singleOrNull()?.name == "/Contacted/1" }
        ui {
            model.loadMore()
            assertEquals(listOf("/Contacted/1", "/Contacted/2"), model.records.value!!.map { it.name })
        }
    }

    @Test fun editingSearchRejectsOldPageEvenBeforeTheDebounceExpires() = fixture { model, repository ->
        val release = CompletableDeferred<Unit>()
        ui {
            repository.pagedRecords = true; model.selectLeads()
            repository.holdRecords = release; model.loadMore()
            model.search.value = "New query"
            val requests = repository.calls.toList()
            model.loadMore()
            assertEquals(requests, repository.calls)
            release.complete(Unit); repository.holdRecords = null
        }
        // The old page may finish before the 350ms search delay, but may never
        // append to the visible list. The new first page must replace it.
        instrumentation.waitForIdleSync()
        ui { assertTrue(model.records.value!!.all { it.id == 1 }) }
        await { model.records.value?.singleOrNull()?.name == "New query//1" && model.loading.value == false }
        ui {
            model.loadMore()
            assertEquals(listOf("New query//1", "New query//2"), model.records.value!!.map { it.name })
        }
    }

    @Test fun failedFirstPageDoesNotReusePreviousPaginationAndRetryStartsAtOne() = fixture { model, repository ->
        ui {
            repository.pagedRecords = true; model.selectLeads(); model.loadMore()
            repository.failRecords = true; model.setGroup("5")
            assertNotNull(model.errorMessage.value)
            val requests = repository.calls.toList()
            model.loadMore()
            assertEquals(requests, repository.calls)
            repository.failRecords = false; model.refresh()
            assertEquals(1, model.records.value!!.size)
            assertTrue(repository.calls.last().startsWith("records:42:leads:1:"))
            model.loadMore()
            assertEquals(listOf(1, 2), model.records.value!!.map { it.id })
        }
    }

    private fun fixture(action: (Mango9CrmViewModel, Repository) -> Unit) {
        val repository = Repository(); val store = ViewModelStore()
        lateinit var model: Mango9CrmViewModel
        ui { model = Mango9CrmViewModel(repository, repository.changes); store.put("crm", model) }
        try { action(model, repository) } finally { ui { store.clear() } }
    }

    private fun ui(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun await(check: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < end) {
            var ready = false; ui { ready = check() }; if (ready) return
            SystemClock.sleep(20)
        }
        ui { assertTrue("CRM state did not settle", check()) }
    }
}
