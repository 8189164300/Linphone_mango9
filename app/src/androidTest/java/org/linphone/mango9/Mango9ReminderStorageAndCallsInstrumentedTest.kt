/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import android.os.Build
import android.os.SystemClock
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.DatagramSocket
import java.net.InetAddress
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.Account
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.Factory
import org.linphone.core.GlobalState

/** Native AtomicFile persistence and the real SDK listener, not SDK doubles. */
@RunWith(AndroidJUnit4::class)
class Mango9ReminderStorageAndCallsInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun atomicHistoryKeepsNewestBoundedSnapshotAcrossReaderReplacement() = storage { file, scope ->
        val start = Instant.parse("2030-01-01T00:00:00Z")
        val history = Mango9ReminderFileHistory(file, scope)
        withContext(Dispatchers.Main) {
            history.ready()
            for (index in 0..400) history.record(key(index), start.plusSeconds(index.toLong()))
            // Many updates may be conflated, but the latest complete map must win.
            repeat(100) { history.record(key(400), start.plusSeconds(401L + it)) }
            withTimeout(10_000) { history.awaitWrites() }
        }
        val json = JSONObject(file.readText())
        assertEquals(256, json.length())
        assertEquals(start.epochSecond + 500, json.getLong(key(400)))
        assertTrue(json.keys().asSequence().all { it.matches(Regex("[0-9a-f]{64}")) })
        val replacement = Mango9ReminderFileHistory(file, scope)
        withContext(Dispatchers.Main) {
            replacement.ready()
            assertTrue(replacement.allows(key(0), start.plusSeconds(500), 0))
            assertFalse(replacement.allows(key(400), start.plusSeconds(559), 1))
            assertTrue(replacement.allows(key(400), start.plusSeconds(560), 1))
            assertFalse(replacement.allows(key(400), start.plusSeconds(9_000), 0))
        }
    }

    @Test fun malformedOversizedOrUntrustedHistoryCannotBreakReminderStartup() = storage { file, scope ->
        val now = Instant.parse("2030-01-01T00:00:00Z")
        for (contents in listOf("not JSON", "x".repeat(32_769), """{"private-title":123,"${key(1)}":-1}""")) {
            file.writeText(contents)
            val history = Mango9ReminderFileHistory(file, scope)
            withContext(Dispatchers.Main) {
                history.ready()
                assertTrue(history.allows(key(1), now, 0))
                history.record(key(2), now)
                withTimeout(10_000) { history.awaitWrites() }
            }
            assertEquals(setOf(key(2)), JSONObject(file.readText()).keys().asSequence().toSet())
        }
    }

    @Test fun nativeCallListenerBlocksBeforeMainThreadCallbackAndUnblocksOnlyAfterLastCall() {
        assumeTrue("Loopback fixture is emulator-only", Build.MODEL.contains("sdk", true))
        var ready = false
        await { core { ready = it.globalState == GlobalState.On }; ready }
        var gate: Mango9ReminderCallGate? = null
        var account: Account? = null
        var call: Call? = null
        var restore: () -> Unit = {}
        val peer = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        try {
            instrumentation.runOnMainSync { gate = Mango9ReminderCallGate {} }
            await { gate?.blocked == false }
            core { sdk ->
                assertTrue("Do not touch real accounts", sdk.accountList.isEmpty())
                assertEquals("Do not touch live calls", 0, sdk.callsNb)
                val previous = sdk.defaultAccount
                val previousFiles = sdk.useFiles
                val previousNetwork = sdk.isNetworkReachable
                restore = {
                    sdk.defaultAccount = previous
                    account?.let { if (sdk.accountList.any { item -> item === it }) sdk.removeAccount(it) }
                    sdk.useFiles = previousFiles; sdk.isNetworkReachable = previousNetwork; sdk.config.sync()
                }
                sdk.useFiles = true; sdk.isNetworkReachable = true
                val params = sdk.createAccountParams().apply {
                    identityAddress = Factory.instance().createAddress("sip:reminder-call@127.0.0.1")
                    serverAddress = Factory.instance().createAddress("sip:127.0.0.1:${peer.localPort};transport=udp")
                    isRegisterEnabled = false; pushNotificationAllowed = false
                }
                account = sdk.createAccount(params); sdk.addAccount(account!!); sdk.defaultAccount = account
                val target = Factory.instance().createAddress("sip:peer@127.0.0.1:${peer.localPort};transport=udp")!!
                val selectedAccount = account
                val outgoing = sdk.createCallParams(null)!!.apply { this.account = selectedAccount; isVideoEnabled = false }
                call = sdk.inviteAddressWithParams(target, outgoing)
                assertNotNull(call)
                assertTrue("Atomic block must precede the queued UI callback", gate!!.blocked)
            }
            core { call?.terminate() }
            await { !gate!!.blocked }
            instrumentation.runOnMainSync { gate!!.close() }
            core { /* drain owned listener removal */ }
            assertTrue("Closed gate always fails closed", gate!!.blocked)
        } finally {
            instrumentation.runOnMainSync { gate?.close() }
            core { sdk ->
                call?.let { item -> if (sdk.calls.any { it === item }) item.terminate(); item.callLog?.let(sdk::removeCallLog) }
                restore()
            }
            peer.close(); Mango9SdkTestCleanup.flush()
        }
    }

    @Test fun closingBeforeCoreRegistrationCannotInstallAnOrphanListener() {
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        coreContext.postOnCoreThread { started.countDown(); release.await(10, TimeUnit.SECONDS) }
        assertTrue(started.await(10, TimeUnit.SECONDS))
        var gate: Mango9ReminderCallGate? = null; var callbacks = 0
        try {
            instrumentation.runOnMainSync { gate = Mango9ReminderCallGate { callbacks++ }; gate!!.close() }
        } finally { release.countDown() }
        core { /* drain canceled registration */ }
        instrumentation.waitForIdleSync()
        assertTrue(gate!!.blocked); assertEquals(0, callbacks)
    }

    private fun storage(test: suspend (File, CoroutineScope) -> Unit) = runBlocking {
        val file = File.createTempFile("mango9-cadence-test-", ".json", context.noBackupFilesDir)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try { test(file, scope) } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); AtomicFile(file).delete() }
    }

    private fun key(value: Int) = "%064x".format(value)

    private fun core(action: (Core) -> Unit) {
        val done = CountDownLatch(1); var result: Result<Unit>? = null
        coreContext.postOnCoreThread { sdk -> result = runCatching { action(sdk) }; done.countDown() }
        assertTrue(done.await(15, TimeUnit.SECONDS)); result!!.getOrThrow()
    }

    private fun await(condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < until) { if (condition()) return; SystemClock.sleep(20) }
        fail("Native reminder condition timed out")
    }
}
