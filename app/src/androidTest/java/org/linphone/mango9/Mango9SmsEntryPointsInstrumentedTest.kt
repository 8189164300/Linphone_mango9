/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.core.Account
import org.linphone.core.Factory
import org.linphone.ui.main.chat.viewmodel.ConversationsListViewModel
import org.linphone.ui.main.chat.viewmodel.StartConversationViewModel

@RunWith(AndroidJUnit4::class)
class Mango9SmsEntryPointsInstrumentedTest {
    @org.junit.After fun persistSdkFixtureCleanup() = Mango9SdkTestCleanup.flush()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun newChatAndConversationSuggestionsRoutePhoneNumbersWithoutCreatingSipRooms() {
        val sessions = Mango9SessionStore(instrumentation.targetContext)
        val originalIdentity = sessions.activeIdentity
        val identity = "sip:sms-entry-fixture@crm.example.invalid"
        val session = Mango9Session(
            "fixture", "https://crm.example.invalid", "https://crm.example.invalid/api/v2", "fixture", "fixture", "agent",
            "fixture@example.invalid", "Fixture", "fixture-access", "fixture-refresh", "https://chat.example.invalid/api",
            "wss://chat.example.invalid/socket", 1_900_000_000, identity,
        )
        assertNull("Do not replace an existing fixture account", sessions.load(identity))
        val store = ViewModelStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val routed = CopyOnWriteArrayList<Mango9SmsTarget>()
        val completed = CountDownLatch(2)
        var account: Account? = null
        var originalAccount: Account? = null
        var originalFilter = ""
        lateinit var newChat: StartConversationViewModel
        lateinit var conversations: ConversationsListViewModel
        try {
            onCore {
                val core = coreContext.core
                originalAccount = core.defaultAccount
                originalFilter = corePreferences.contactsFilter
                assertTrue(core.accountList.none { it.params.identityAddress?.asStringUriOnly() == identity })
                val params = core.createAccountParams().apply {
                    identityAddress = Factory.instance().createAddress(identity)
                    serverAddress = Factory.instance().createAddress("sip:crm.example.invalid")
                    isRegisterEnabled = false
                    pushNotificationAllowed = false
                    instantMessagingEncryptionMandatory = false
                }
                account = core.createAccount(params)
                core.addAccount(account!!)
                core.defaultAccount = account
            }
            sessions.save(session, persist = false, makeActive = true)
            instrumentation.runOnMainSync {
                newChat = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[StartConversationViewModel::class.java]
                conversations = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[ConversationsListViewModel::class.java]
            }
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                Mango9SmsRouting.openRequests.take(2).collect { routed.add(it.target); completed.countDown() }
            }
            onCore {
                val before = coreContext.core.chatRooms.size
                val phone = Factory.instance().createAddress("sip:+12025550142@crm.example.invalid")!!
                newChat.createOneToOneChatRoomWith(phone)
                conversations.createOneToOneChatRoomWith(phone)
                assertEquals("SMS navigation must not create a SIP room", before, coreContext.core.chatRooms.size)
            }
            assertTrue("Both entry points must emit an SMS route", completed.await(10, TimeUnit.SECONDS))
            assertEquals(listOf("12025550142", "12025550142"), routed.map { it.phone })
        } finally {
            scope.cancel()
            instrumentation.runOnMainSync { store.clear() }
            sessions.remove(identity)
            sessions.activate(originalIdentity)
            onCore {
                coreContext.core.defaultAccount = originalAccount
                account?.let { coreContext.core.removeAccount(it) }
                corePreferences.contactsFilter = originalFilter
            }
        }
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
}
