/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import android.Manifest
import android.annotation.SuppressLint
import androidx.car.app.HandshakeInfo
import androidx.car.app.OnDoneCallback
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.serialization.Bundleable
import androidx.car.app.testing.ScreenController
import androidx.car.app.testing.TestCarContext
import androidx.car.app.versioning.CarAppApiLevels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.contacts.NativeContactDirectory
import org.linphone.contacts.NativeContactValue
import org.linphone.contacts.NativePhoneValue
import org.linphone.telecom.auto.AndroidAutoScreen
import org.linphone.ui.main.chat.viewmodel.ConversationsListViewModel
import org.linphone.ui.main.history.viewmodel.HistoryListViewModel

@RunWith(AndroidJUnit4::class)
class Mango9ContactConsumersInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun historyConversationsAndCarFavoritesRetainNativeDirectoryWithoutSdkMirror() {
        instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.READ_CONTACTS)
        val directory = NativeContactDirectory.build(
            (0 until 5_000).map { index ->
            NativeContactValue(
                "consumer-$index", "Consumer ${index.toString().padStart(5, '0')}", "consumer-lookup-$index", index < 250,
                null, "Consumer", index.toString().padStart(5, '0'), null, null,
                listOf(NativePhoneValue("+1556${index.toString().padStart(7, '0')}", "mobile")), emptyList(),
            )
        }
        )
        var previous = NativeContactDirectory.EMPTY
        var previousFilter = ""
        val store = ViewModelStore()
        lateinit var history: HistoryListViewModel
        lateinit var conversations: ConversationsListViewModel
        var controller: ScreenController? = null
        lateinit var car: AndroidAutoScreen
        try {
            onCore {
                previous = coreContext.contactsManager.nativeContactsSnapshot()
                previousFilter = corePreferences.contactsFilter
                corePreferences.contactsFilter = ""
                coreContext.contactsManager.installNativeContacts(directory)
            }
            instrumentation.runOnMainSync {
                history = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[HistoryListViewModel::class.java]
                conversations = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[ConversationsListViewModel::class.java]
                history.applyFilter("Consumer")
                conversations.applyFilter("Consumer")
            }
            await {
                history.callLogs.value?.count { it.contactModel?.nativeContact != null } == 100 &&
                    conversations.conversations.value?.count { it.contactModel?.nativeContact != null } == 100
            }
            repeat(21) { page ->
                instrumentation.runOnMainSync {
                    history.revealMoreContacts(history.callLogs.value!!.lastIndex)
                    conversations.revealMoreContacts(conversations.conversations.value!!.lastIndex)
                }
                await {
                    history.callLogs.value?.count { it.contactModel?.nativeContact != null } == (page + 2) * 100 &&
                        conversations.conversations.value?.count { it.contactModel?.nativeContact != null } == (page + 2) * 100
                }
            }
            repeat(6) { cycle ->
                val name = "Consumer ${(4_999 - cycle).toString().padStart(5, '0')}"
                instrumentation.runOnMainSync {
                    repeat(10) { history.applyFilter("Consumer 000$it"); conversations.applyFilter("Consumer 000$it") }
                    history.applyFilter(name)
                    conversations.applyFilter(name)
                }
                await {
                    history.callLogs.value?.singleOrNull { it.contactModel?.isFriend == true }?.contactModel?.name == name &&
                        conversations.conversations.value?.singleOrNull { it.contactModel?.isFriend == true }?.contactModel?.name == name
                }
                instrumentation.runOnMainSync {
                    history.cancelContactSearch(); conversations.cancelContactSearch()
                    history.applyFilter("Consumer"); conversations.applyFilter("Consumer")
                }
                await {
                    history.callLogs.value?.count { it.contactModel?.nativeContact != null } == 100 &&
                        conversations.conversations.value?.count { it.contactModel?.nativeContact != null } == 100
                }
            }
            instrumentation.runOnMainSync {
                val context = connectedCarContext()
                car = AndroidAutoScreen(context)
                controller = ScreenController(car).moveToState(Lifecycle.State.RESUMED)
            }
            await { (car.onGetTemplate() as GridTemplate).let { !it.isLoading && it.singleList?.items?.isNotEmpty() == true } }
            var firstTitles = emptyList<String>()
            var hasNext = false
            var clickFailure: Any? = null
            instrumentation.runOnMainSync {
                val template = car.onGetTemplate() as GridTemplate
                firstTitles = template.singleList!!.items.map { (it as GridItem).title.toString() }
                assertTrue(firstTitles.all { it.startsWith("Consumer ") })
                hasNext = template.header!!.endHeaderActions.isNotEmpty()
                assertTrue("250 favorites must remain reachable through paging", hasNext)
                if (hasNext) {
                    template.header!!.endHeaderActions.last().onClickDelegate!!.sendClick(object : OnDoneCallback {
                    override fun onFailure(response: Bundleable) { clickFailure = response }
                })
                }
            }
            if (hasNext) {
                await {
                val template = car.onGetTemplate() as GridTemplate
                return@await !template.isLoading && template.singleList!!.items.map { (it as GridItem).title.toString() } != firstTitles
            }
            }
            assertNull(clickFailure)
            instrumentation.runOnMainSync { controller!!.moveToState(Lifecycle.State.CREATED) }
            // No call click is invoked. Empty provider reconciliation must clear
            // every consumer, not merely the Contacts tab and New Chat picker.
            onCore { coreContext.contactsManager.installNativeContacts(NativeContactDirectory.EMPTY) }
            instrumentation.runOnMainSync { controller!!.moveToState(Lifecycle.State.RESUMED) }
            await {
                history.callLogs.value?.none { it.contactModel?.nativeContact != null } == true &&
                    conversations.conversations.value?.none { it.contactModel?.nativeContact != null } == true &&
                    (car.onGetTemplate() as GridTemplate).let { !it.isLoading && it.singleList?.items?.isEmpty() == true }
            }
        } finally {
            instrumentation.runOnMainSync { controller?.moveToState(Lifecycle.State.DESTROYED); store.clear() }
            onCore {
                corePreferences.contactsFilter = previousFilter
                coreContext.contactsManager.installNativeContacts(previous)
            }
        }
    }

    // app-testing 1.7.0 attaches FakeHost but does not negotiate its API level.
    // Simulate that framework-only handshake before Screen.onStart; production
    // receives it before Session.onCreateScreen. No restricted API is used by the app.
    @SuppressLint("RestrictedApi")
    private fun connectedCarContext(): TestCarContext =
        TestCarContext.createCarContext(instrumentation.targetContext).also {
            it.updateHandshakeInfo(HandshakeInfo("synthetic.car.host", CarAppApiLevels.getLatest()))
            it.lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED
        }

    private fun onCore(block: () -> Unit) {
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        coreContext.postOnCoreThread {
            try { block() } catch (error: Throwable) { failure = error } finally { done.countDown() }
        }
        assertTrue("Core queue timed out", done.await(15, TimeUnit.SECONDS))
        failure?.let { throw AssertionError(it) }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
        do {
            var matched = false
            instrumentation.runOnMainSync { matched = condition() }
            if (matched) return
            Thread.sleep(50)
        } while (System.nanoTime() < deadline)
        fail("Timed out waiting for contact consumers")
    }
}
