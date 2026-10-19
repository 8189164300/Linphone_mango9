/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.Account
import org.linphone.core.Core
import org.linphone.core.Factory
import org.linphone.ui.main.MainActivity

/** Run separately on the owned emulator with RECORD_AUDIO revoked before starting instrumentation. */
@RunWith(AndroidJUnit4::class)
class Mango9MicrophoneDialogInstrumentedTest {
    @org.junit.After fun persistSdkFixtureCleanup() = Mango9SdkTestCleanup.flush()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun nativeDenyRetryRotateAndGrantKeepOneOriginalIntent() {
        assertFalse("Run this suite with microphone permission initially denied", Mango9OutgoingCalls.hasMicrophone(context))
        listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CAMERA).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val sessions = Mango9SessionStore(context)
        val oldIdentity = sessions.activeIdentity
        var oldDefault: Account? = null
        var account: Account? = null
        val results = CopyOnWriteArrayList<Boolean>()
        val actions = AtomicInteger()
        try {
            core { core ->
                oldDefault = core.defaultAccount
                val params = core.createAccountParams().apply {
                    identityAddress = Factory.instance().createAddress("sip:permission-ui@fixture.example.invalid")
                    serverAddress = Factory.instance().createAddress("sip:fixture.example.invalid")
                    isRegisterEnabled = false; pushNotificationAllowed = false
                }
                account = core.createAccount(params)
                core.addAccount(account); core.defaultAccount = account
            }
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                instrumentation.waitForIdleSync()

                fun request() = core {
                    coreContext.withOutgoingCallMicrophone(requireNotNull(account), onResult = { results.add(it) }) {
                        actions.incrementAndGet(); true
                    }
                }
                request()
                node {
                    it.viewIdResourceName?.endsWith("/permission_deny_button") == true
                }.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                await { results.size == 1 }
                assertEquals(listOf(false), results.toList()); assertEquals(0, actions.get())
                node { it.text?.toString() == "Microphone access is off" }
                node { it.text?.toString()?.contains("other person cannot hear you") == true }
                node { it.text?.toString().equals("Cancel", ignoreCase = true) }.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                instrumentation.waitForIdleSync()

                request()
                node { it.text?.toString() == "Microphone access for calls" }
                scenario.recreate()
                node { it.text?.toString() == "Microphone access for calls" }
                node { it.text?.toString().equals("Continue", ignoreCase = true) }.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                node {
                    it.viewIdResourceName?.endsWith("/permission_allow_foreground_only_button") == true
                }.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                await { results.size == 2 }
                assertEquals(listOf(false, true), results.toList())
                assertEquals(1, actions.get())
                assertTrue(Mango9OutgoingCalls.hasMicrophone(context))

                request() // Live granted path requires no second system sheet.
                await { results.size == 3 }
                assertEquals(listOf(false, true, true), results.toList())
                assertEquals(2, actions.get())
            }
        } finally {
            core { core ->
                core.defaultAccount = oldDefault
                account?.let { if (core.accountList.any { candidate -> candidate === it }) core.removeAccount(it) }
                sessions.activate(oldIdentity)
            }
        }
    }

    private fun node(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        await {
            fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                node ?: return null
                if (predicate(node)) return node
                for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
                return null
            }
            found = find(instrumentation.uiAutomation.rootInActiveWindow)
            found != null
        }
        return found!!
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(100)
        }
        assertTrue("Expected native permission state was not reached", predicate())
    }

    private fun core(action: (Core) -> Unit) {
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        coreContext.postOnCoreThread { core ->
            try { action(core) } catch (error: Throwable) { failure = error } finally { done.countDown() }
        }
        assertTrue("Core callback timed out", done.await(10, TimeUnit.SECONDS))
        failure?.let { throw it }
    }
}
