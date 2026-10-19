/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Spinner
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.core.Account
import org.linphone.core.Core
import org.linphone.core.Factory
import org.linphone.ui.main.MainActivity
import org.linphone.ui.main.crm.adapter.Mango9CrmRecordAdapter
import org.linphone.ui.main.crm.fragment.Mango9CrmFragment
import org.linphone.ui.main.crm.viewmodel.Mango9CrmViewModel

/** Actual Activity/Fragment/RecyclerView/dialogs, with synthetic CRM data and a non-registering SDK account. */
@RunWith(AndroidJUnit4::class)
class Mango9CrmPageOwnershipInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun equalRefreshKeepsRowsClickableAndAccountReplacementClosesAllRecordDialogs() {
        listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CAMERA).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
        }
        val sessions = Mango9SessionStore(context); val oldIdentity = sessions.activeIdentity
        var oldAccount: Account? = null; var fixtureAccount: Account? = null
        val repository = Mango9CrmOwnershipInstrumentedTest.Repository()
        lateinit var model: Mango9CrmViewModel
        try {
            core { core ->
                oldAccount = core.defaultAccount
                val params = core.createAccountParams().apply {
                    identityAddress = Factory.instance().createAddress("sip:crm-page@fixture.example.invalid")
                    serverAddress = Factory.instance().createAddress("sip:fixture.example.invalid")
                    isRegisterEnabled = false; pushNotificationAllowed = false
                }
                fixtureAccount = core.createAccount(params); core.addAccount(fixtureAccount); core.defaultAccount = fixtureAccount
            }
            ActivityScenario.launch<MainActivity>(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ).use { scenario ->
                scenario.onActivity { activity ->
                    activity.supportFragmentManager.registerFragmentLifecycleCallbacks(
                        object : FragmentManager.FragmentLifecycleCallbacks() {
                        override fun onFragmentPreCreated(fm: FragmentManager, f: Fragment, savedInstanceState: Bundle?) {
                            if (f is Mango9CrmFragment) {
                                model = ViewModelProvider(
                                    f,
                                    object : ViewModelProvider.Factory {
                                    @Suppress("UNCHECKED_CAST")
                                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                                        Mango9CrmViewModel(repository, repository.changes) as T
                                }
                                )[Mango9CrmViewModel::class.java]
                            }
                        }
                    },
                        true
                    )
                    (activity.supportFragmentManager.findFragmentById(R.id.main_nav_container) as NavHostFragment)
                        .navController.navigate(R.id.action_global_mango9CrmFragment)
                }
                lateinit var page: Mango9CrmFragment
                scenario.onActivity { activity ->
                    page = (activity.supportFragmentManager.findFragmentById(R.id.main_nav_container) as NavHostFragment)
                        .childFragmentManager.primaryNavigationFragment as Mango9CrmFragment
                    page.requireView().findViewById<View>(R.id.leads_row).performClick()
                }
                await {
                    (page.requireView().findViewById<RecyclerView>(R.id.records).adapter as Mango9CrmRecordAdapter).currentList.size == 1
                }
                lateinit var previousRow: Mango9CrmRecord
                scenario.onActivity { previousRow = model.records.value!!.single(); model.refresh() }
                await {
                    val adapter = page.requireView().findViewById<RecyclerView>(R.id.records).adapter as Mango9CrmRecordAdapter
                    adapter.currentList.singleOrNull() === model.records.value!!.single() && model.records.value!!.single() !== previousRow
                }
                scenario.onActivity { model.setGroup("5"); model.setDateFilter("last_7_days"); model.search.value = "Synthetic" }
                await {
                    page.requireView().findViewById<Spinner>(R.id.group_filter).selectedItemPosition == 1 &&
                        page.requireView().findViewById<Spinner>(R.id.date_filter).selectedItemPosition == 2 &&
                        page.requireView().findViewById<EditText>(R.id.search).text.toString() == "Synthetic"
                }
                val retained = model
                scenario.recreate()
                scenario.onActivity { activity ->
                    page = (activity.supportFragmentManager.findFragmentById(R.id.main_nav_container) as NavHostFragment)
                        .childFragmentManager.primaryNavigationFragment as Mango9CrmFragment
                    assertSame(retained, ViewModelProvider(page)[Mango9CrmViewModel::class.java])
                    assertEquals("5", model.selectedGroupId.value); assertEquals("last_7_days", model.selectedDateFilter.value)
                    assertEquals("Synthetic", page.requireView().findViewById<EditText>(R.id.search).text.toString())
                    assertEquals(1, page.requireView().findViewById<Spinner>(R.id.group_filter).selectedItemPosition)
                    assertEquals(2, page.requireView().findViewById<Spinner>(R.id.date_filter).selectedItemPosition)
                }
                await { page.requireView().findViewById<RecyclerView>(R.id.records).findViewHolderForAdapterPosition(0) != null }
                lateinit var detail: AlertDialog; lateinit var edit: AlertDialog; lateinit var confirmation: AlertDialog
                scenario.onActivity {
                    page.requireView().findViewById<RecyclerView>(R.id.records).findViewHolderForAdapterPosition(0)!!.itemView.performClick()
                    detail = dialog(page, "recordDialog")!!; assertTrue(detail.isShowing)
                }
                instrumentation.waitForIdleSync() // AlertDialog installs custom button listeners in onShow.
                scenario.onActivity {
                    detail.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    edit = dialog(page, "recordDialog")!!; assertNotSame(detail, edit); assertTrue(edit.isShowing)
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    edit.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
                    confirmation = dialog(page, "recordChildDialog")!!; assertTrue(confirmation.isShowing)
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    repository.switchAccount()
                }
                await { !edit.isShowing && !confirmation.isShowing && model.activeSession.value?.userId == "84" }
                scenario.onActivity {
                    assertFalse(detail.isShowing)
                    assertEquals("", page.requireView().findViewById<EditText>(R.id.search).text.toString())
                    assertEquals(0, page.requireView().findViewById<Spinner>(R.id.group_filter).selectedItemPosition)
                    assertEquals(0, page.requireView().findViewById<Spinner>(R.id.date_filter).selectedItemPosition)
                    confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    assertFalse(repository.calls.any { it.startsWith("delete:") })
                    page.requireView().findViewById<View>(R.id.leads_row).performClick()
                    page.requireView().findViewById<View>(R.id.add_record).performClick()
                    assertTrue(dialog(page, "recordDialog")!!.isShowing)
                    repository.replaceLogin()
                }
                await { dialog(page, "recordDialog") == null }
                assertTrue(repository.writes.isEmpty())
            }
        } finally {
            core { core ->
                core.defaultAccount = oldAccount
                fixtureAccount?.let { if (core.accountList.any { account -> account === it }) core.removeAccount(it) }
                sessions.activate(oldIdentity)
            }
            Mango9SdkTestCleanup.flush()
        }
    }

    private fun dialog(page: Mango9CrmFragment, name: String) = Mango9CrmFragment::class.java.getDeclaredField(name).let {
        it.isAccessible = true; it.get(page) as? AlertDialog
    }

    private fun await(check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false; instrumentation.runOnMainSync { ready = check() }
            if (ready) return
            SystemClock.sleep(20)
        }
        instrumentation.runOnMainSync { assertTrue("CRM page did not settle", check()) }
    }

    private fun core(action: (Core) -> Unit) {
        val done = CountDownLatch(1); var result: Result<Unit>? = null
        coreContext.postOnCoreThread { result = runCatching { action(it) }; done.countDown() }
        assertTrue(done.await(10, TimeUnit.SECONDS)); result!!.getOrThrow()
    }
}
