/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import android.Manifest
import android.database.MatrixCursor
import android.os.OperationCanceledException
import android.provider.ContactsContract
import android.view.ContextThemeWrapper
import android.view.View
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.R
import org.linphone.contacts.NativeContactSnapshotReader
import org.linphone.contacts.NativeContactDirectory
import org.linphone.contacts.NativeContactValue
import org.linphone.contacts.NativePhoneValue
import org.linphone.contacts.ContactLoader
import org.linphone.core.Core
import org.linphone.core.FriendList
import org.linphone.core.SubscribePolicy
import org.linphone.ui.main.adapter.ConversationsContactsAndSuggestionsListAdapter
import org.linphone.ui.main.chat.viewmodel.StartConversationViewModel
import org.linphone.ui.main.chat.adapter.ConversationsListAdapter
import org.linphone.ui.main.chat.model.ConversationModelWrapper
import org.linphone.ui.main.history.adapter.HistoryListAdapter
import org.linphone.ui.main.history.model.CallLogModelWrapper
import org.linphone.ui.main.contacts.adapter.ContactsListAdapter
import org.linphone.ui.main.contacts.viewmodel.ContactsListViewModel
import org.linphone.ui.main.contacts.viewmodel.ContactViewModel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class Mango9ContactsNormalizationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun fiftyThousandDeviceContactsUseValueRowsAndCurrentNativeActions() {
        instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.READ_CONTACTS)
        val directory = NativeContactDirectory.build(
            (0 until 50_000).map { index ->
            NativeContactValue(
                index.toString(), "Native ${index.toString().padStart(5, '0')}", "normalization-values-$index", index < 250,
                null, "Native", index.toString().padStart(5, '0'), null, null,
                listOf(NativePhoneValue("+1555${index.toString().padStart(7, '0')}", "mobile")),
                listOf("sip:native$index@example.invalid"),
            )
        }
        )
        val store = ViewModelStore()
        var previous = NativeContactDirectory.EMPTY
        var previousFilter = ""
        lateinit var contacts: ContactsListViewModel
        lateinit var picker: StartConversationViewModel
        lateinit var detail: ContactViewModel
        try {
            onCore { core ->
                previous = coreContext.contactsManager.nativeContactsSnapshot()
                previousFilter = corePreferences.contactsFilter
                corePreferences.contactsFilter = ""
                coreContext.contactsManager.installNativeContacts(directory)
                assertNull(core.getFriendListByName(ContactLoader.NATIVE_ADDRESS_BOOK_FRIEND_LIST))
            }
            instrumentation.runOnMainSync {
                contacts = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[ContactsListViewModel::class.java]
                picker = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[StartConversationViewModel::class.java]
            }
            await { contacts.contactsList.value?.size == 100 && picker.modelsList.value?.size == 100 }
            assertTrue(contacts.contactsList.value!!.all { it.nativeContact != null && it.id.startsWith("native:") })
            assertTrue(picker.modelsList.value!!.all { it.nativeContact != null && it.isFriend })
            assertFalse(contacts.showResultsLimitReached.value == true)
            // Pass the old SDK cap using ordinary paging; no per-contact SDK bridge.
            repeat(21) { page ->
                instrumentation.runOnMainSync {
                    contacts.revealMoreContacts((page + 1) * 100 - 1)
                    picker.revealMoreContacts((page + 1) * 100 - 1)
                }
                await { contacts.contactsList.value?.size == (page + 2) * 100 && picker.modelsList.value?.size == (page + 2) * 100 }
            }
            instrumentation.runOnMainSync {
                val themed = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_Linphone)
                val nativeRow = picker.modelsList.value!!.first()
                assertNull(contacts.contactsList.value!!.first().friendForNavigation)
                val chatAdapter = ConversationsListAdapter()
                chatAdapter.submitList(listOf(ConversationModelWrapper(null, nativeRow)))
                assertEquals(1, chatAdapter.getItemViewType(0))
                val historyAdapter = HistoryListAdapter()
                historyAdapter.submitList(listOf(CallLogModelWrapper(null, nativeRow)))
                assertEquals(1, historyAdapter.getItemViewType(0))
                val adapter = ConversationsContactsAndSuggestionsListAdapter()
                adapter.submitList(picker.modelsList.value!!)
                val recycler = RecyclerView(themed).apply { layoutManager = LinearLayoutManager(themed); this.adapter = adapter }
                recycler.measure(View.MeasureSpec.makeMeasureSpec(393, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY))
                recycler.layout(0, 0, 393, 700)
                assertTrue(recycler.childCount in 1..40)
                recycler.adapter = null
            }
            repeat(12) { cycle ->
                val target = "Native ${(49_999 - cycle).toString().padStart(5, '0')}"
                instrumentation.runOnMainSync {
                    contacts.applyFilter(target)
                    picker.applyFilter(target, immediately = true)
                }
                await {
                    contacts.contactsList.value?.singleOrNull()?.contactName == target &&
                        picker.modelsList.value?.singleOrNull { it.isFriend }?.name == target
                }
                instrumentation.runOnMainSync {
                    contacts.cancelContactSearch()
                    picker.cancelContactSearch()
                    contacts.applyFilter("")
                    picker.applyFilter("", immediately = true)
                }
                await { contacts.contactsList.value?.size == 100 && picker.modelsList.value?.size == 100 }
            }
            instrumentation.runOnMainSync {
                contacts.applyFilter("Native 49999")
                picker.applyFilter("Native 49999", immediately = true)
            }
            await {
                contacts.contactsList.value?.singleOrNull()?.id == "native:49999" && picker.modelsList.value?.singleOrNull {
                    it.isFriend
                }?.id == "native:49999"
            }
            val staleRow = picker.modelsList.value!!.single { it.isFriend }
            val equalSnapshot = NativeContactDirectory.build(directory.contacts)
            onCore { core ->
                val address = core.interpretUrl("sip:native49999@example.invalid", false)!!
                val avatar = coreContext.contactsManager.getContactAvatarModelForAddress(address)
                assertEquals("native:49999", avatar.id)
                assertNull("Name lookup must not create an SDK Friend", avatar.friendForNavigation)
                assertEquals("Native 49999", coreContext.contactsManager.findDisplayName(address))
                coreContext.contactsManager.installNativeContacts(equalSnapshot)
                assertSame(directory, coreContext.contactsManager.nativeContactsSnapshot())
                assertSame(avatar, coreContext.contactsManager.getContactAvatarModelForAddress(address))
                val friend = coreContext.contactsManager.resolveNativeFriend("native:49999")!!
                assertEquals("Native 49999", friend.name)
                assertTrue(friend.nativeUri!!.startsWith("content://com.android.contacts/contacts/lookup/"))
                assertFalse(friend.inList())
                assertEquals(1, friend.phoneNumbers.size)
                assertNull(core.getFriendListByName(ContactLoader.NATIVE_ADDRESS_BOOK_FRIEND_LIST))
            }
            instrumentation.runOnMainSync {
                detail = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[ContactViewModel::class.java]
                detail.findContact(null, "native:49999")
            }
            await { detail.contact.value?.id == "native:49999" }
            instrumentation.runOnMainSync { detail.editContact() }
            await { detail.openNativeContactEditor.value != null }
            onCore { coreContext.contactsManager.installNativeContacts(NativeContactDirectory.EMPTY) }
            await {
                contacts.contactsList.value?.isEmpty() == true && picker.modelsList.value?.none {
                    it.isFriend
                } == true && detail.contactUnavailableEvent.value != null
            }
            onCore {
                assertFalse(staleRow.isAvailable)
                assertNull(staleRow.friend)
                assertNull(coreContext.contactsManager.resolveNativeFriend("native:49999"))
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            onCore {
                corePreferences.contactsFilter = previousFilter
                coreContext.contactsManager.installNativeContacts(previous)
            }
        }
    }

    @Test fun cursorSnapshotKeepsIdentityNumbersLabelsAndEmptyAddressBookWithoutSDKObjects() {
        MatrixCursor(NativeContactSnapshotReader.projection).use { cursor ->
            cursor.addRow(arrayOf<Any?>("101", "Same name", ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, 1, "lookup1", null, null, "First", "Last", null))
            cursor.addRow(arrayOf<Any?>("101", "Same name", ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, 1, "lookup1", null, "+12025550142", "2", null, null))
            cursor.addRow(arrayOf<Any?>("101", "Same name", ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, 1, "lookup1", null, "+12025550143", "0", "Աշխատանք", null))
            cursor.addRow(arrayOf<Any?>("102", "Same name", ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, 0, "lookup2", null, "+12025550199", "1", null, null))
            val values = NativeContactSnapshotReader.read(cursor) { false }
            assertEquals(2, values.size)
            assertEquals(listOf("101", "102"), values.map { it.id })
            assertEquals("First", values[0].firstName)
            assertEquals("Last", values[0].lastName)
            assertEquals(listOf("+12025550142", "+12025550143"), values[0].phones.map { it.number })
            assertEquals("Աշխատանք", values[0].phones[1].label)
            assertNull(values[0].photoUri)
            assertEquals(values, NativeContactSnapshotReader.read(cursor) { false })
            try {
                NativeContactSnapshotReader.read(cursor) {
                    true
                }; fail("Cancellation must not publish a partial read")
            } catch (_: OperationCanceledException) { }
        }
        MatrixCursor(NativeContactSnapshotReader.projection).use {
            assertTrue(NativeContactSnapshotReader.read(it) { false }.isEmpty())
        }
    }

    @Test fun fiftyThousandNativeContactValuesStayDistinctAndOverLimitIsNotTruncated() {
        MatrixCursor(NativeContactSnapshotReader.projection, 50_001).use { cursor ->
            repeat(50_000) { index ->
                cursor.addRow(arrayOf<Any?>(index.toString(), "Contact $index", ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, 0, "key$index", null, null, "Contact", index.toString(), null))
            }
            val snapshot = NativeContactSnapshotReader.read(cursor) { false }
            assertEquals(50_000, snapshot.size)
            assertEquals("49999", snapshot.last().id)
            cursor.addRow(arrayOf<Any?>("50000", "Limit", ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, 0, "key50000", null, null, "Limit", null, null))
            try {
                NativeContactSnapshotReader.read(cursor) {
                    false
                }; fail("An over-limit read must not look complete")
            } catch (_: IllegalStateException) { }
        }
    }

    @Test fun newChatPagesTenThousandRealSdkContactsAndRecyclesRowsWithBlankNames() {
        val fixtureName = "Normalization43-contact-fixture"
        var fixture: FriendList? = null
        val store = ViewModelStore()
        lateinit var viewModel: StartConversationViewModel
        try {
            onCore { core ->
                check(core.getFriendListByName(fixtureName) == null)
                fixture = core.createFriendList().apply { displayName = fixtureName; isDatabaseStorageEnabled = false }
            }
            val ready = CountDownLatch(1)
            var importError: Throwable? = null
            Mango9ContactBatchImport.run((0 until 10_000).toList(), { work ->
                coreContext.postOnCoreThread {
                work()
            }
            }, { true }, { index ->
                coreContext.core.createFriend().apply {
                    refKey = "normalization43-$index"
                    name = if (index < 2) "AA duplicate" else "Fixture ${index.toString().padStart(5, '0')}"
                    starred = index < 250
                    address = coreContext.core.interpretUrl("sip:fixture$index@example.invalid", false)
                    isSubscribesEnabled = false
                    incSubscribePolicy = SubscribePolicy.SPDeny
                }
            }, { friends ->
                fixture!!.synchronizeFriendsWith(friends.toTypedArray())
                coreContext.core.addFriendList(fixture!!)
                // The SDK reconciliation uses refKeys to distinguish equal
                // names. Simulate legacy missing IDs only after import.
                friends.take(2).forEach { it.refKey = null }
                ready.countDown()
            }, { importError = it; ready.countDown() })
            assertTrue("SDK fixture import timed out", ready.await(45, TimeUnit.SECONDS))
            importError?.let { throw AssertionError(it) }
            instrumentation.runOnMainSync {
                viewModel = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[StartConversationViewModel::class.java]
            }
            await { viewModel.modelsList.value?.size == 100 }
            onCore { assertEquals(10_000, fixture!!.friends.size) }
            val first = viewModel.modelsList.value!!.map { it.id }
            instrumentation.runOnMainSync {
                val themed = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_Linphone)
                val adapter = ConversationsContactsAndSuggestionsListAdapter()
                adapter.submitList(viewModel.modelsList.value!!)
                val recycler = RecyclerView(themed).apply { layoutManager = LinearLayoutManager(themed); this.adapter = adapter }
                val density = themed.resources.displayMetrics.density
                val width = (393 * density).toInt()
                val height = (700 * density).toInt()
                recycler.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                recycler.layout(0, 0, width, height)
                assertTrue(recycler.childCount in 1..40)
                viewModel.revealMoreContacts(99)
            }
            await { viewModel.modelsList.value?.size == 200 }
            assertEquals(first, viewModel.modelsList.value!!.take(100).map { it.id })
            instrumentation.runOnMainSync {
                repeat(20) { viewModel.applyFilter("Fixture 000${it % 10}") }
                viewModel.applyFilter("Fixture 09999", immediately = true)
            }
            await { viewModel.modelsList.value?.any { it.name == "Fixture 09999" } == true }
            assertEquals(1, viewModel.modelsList.value!!.count { it.friend != null })
            lateinit var contacts: ContactsListViewModel
            instrumentation.runOnMainSync {
                viewModel.cancelContactSearch()
                contacts = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[ContactsListViewModel::class.java]
            }
            await { contacts.contactsList.value?.size == 100 && contacts.favouritesList.value?.size == 100 }
            repeat(12) { cycle ->
                val target = "Fixture ${(9_999 - cycle).toString().padStart(5, '0')}"
                instrumentation.runOnMainSync { contacts.applyFilter(target) }
                await { contacts.contactsList.value?.singleOrNull()?.contactName == target }
                instrumentation.runOnMainSync { contacts.cancelContactSearch(); contacts.applyFilter("") }
                await { contacts.contactsList.value?.size == 100 && contacts.favouritesList.value?.size == 100 }
            }
            await { contacts.showResultsLimitReached.value == true }
            assertEquals(2, contacts.contactsList.value!!.take(2).map { it.id }.distinct().size)
            assertTrue(contacts.contactsList.value!!.take(2).all { it.id.isNotBlank() })
            instrumentation.runOnMainSync {
                val themed = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_Linphone)
                val adapter = ContactsListAdapter()
                adapter.submitList(contacts.contactsList.value!!)
                val recycler = RecyclerView(themed).apply { layoutManager = LinearLayoutManager(themed); this.adapter = adapter }
                val density = themed.resources.displayMetrics.density
                val width = (393 * density).toInt()
                val height = (700 * density).toInt()
                recycler.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                recycler.layout(0, 0, width, height)
                assertTrue(recycler.childCount in 1..40)
                contacts.revealMoreContacts(99)
                contacts.revealMoreContacts(99, favorites = true)
            }
            await { contacts.contactsList.value?.size == 200 && contacts.favouritesList.value?.size == 200 }
            instrumentation.runOnMainSync { contacts.revealMoreContacts(199, favorites = true) }
            await { contacts.favouritesList.value?.size == 250 }
            instrumentation.runOnMainSync {
                repeat(20) { contacts.applyFilter("Fixture 000${it % 10}") }
                contacts.applyFilter("Fixture 09999")
            }
            await { contacts.contactsList.value?.singleOrNull()?.contactName == "Fixture 09999" }
            instrumentation.runOnMainSync {
                contacts.cancelContactSearch()
                contacts.clearFilter()
                contacts.applyFilter("")
                contacts.refreshContacts()
            }
            await { contacts.contactsList.value?.size == 100 && contacts.favouritesList.value?.size == 100 }
            onCore {
                assertTrue(fixture!!.synchronizeFriendsWith(emptyArray()))
                assertEquals(0, fixture!!.friends.size)
                coreContext.contactsManager.notifyContactsListChanged()
            }
            await { contacts.contactsList.value?.isEmpty() == true && contacts.favouritesList.value?.isEmpty() == true }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            fixture?.let { exact -> onCore { core -> core.removeFriendList(exact) } }
        }
    }

    private fun onCore(block: (Core) -> Unit) {
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        coreContext.postOnCoreThread { core ->
            try {
            block(core)
        } catch (error: Throwable) { failure = error } finally { done.countDown() }
        }
        assertTrue("SDK queue timed out", done.await(15, TimeUnit.SECONDS))
        failure?.let { throw AssertionError(it) }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        do {
            var matched = false
            instrumentation.runOnMainSync { matched = condition() }
            if (matched) return
            Thread.sleep(50)
        } while (System.nanoTime() < deadline)
        fail("Timed out waiting for the latest contact snapshot")
    }
}
