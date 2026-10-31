// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Factory
import org.linphone.core.Privacy

class Mango9CallerHistoryDeviceTest {
    /** Actual SDK callbacks and database reopen, with SIP confined to loopback and no accounts. */
    @Test fun nativeCallHistoryRetainsIdentityAndPrivacyAcrossRestartWithoutChangingRouting() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName.endsWith(".debug"))
        val root = File(context.cacheDir, "caller-fixture-${UUID.randomUUID()}").apply { mkdirs() }
        val latch = CountDownLatch(1); var failure: Throwable? = null
        coreContext.postOnCoreThread {
            var receiver: Core? = null; var caller: Core? = null
            try {
                fun open(name: String): Core {
                    val config = Factory.instance().createConfig(File(root, "$name.linphonerc").path)
                    config.setString("storage", "uri", File(root, "$name.db").path)
                    config.setString("storage", "call_logs_db_uri", "null")
                    config.setString("storage", "friends_db_uri", "null")
                    config.setString("storage", "zrtp_secrets_db_uri", "null")
                    config.setString("lime", "x3dh_db_path", File(root, "$name-x3dh.db").path)
                    return Factory.instance().createCoreWithConfig(config, context).apply {
                        isAutoIterateEnabled = false
                        transports = Factory.instance().createTransports().apply { udpPort = -1; tcpPort = 0; tlsPort = 0; dtlsPort = 0 }
                        primaryContact = "\"Fixture caller\" <sip:fixture-$name@127.0.0.1>"
                        ring = null; ringback = null; isNativeRingingEnabled = false
                        assertTrue(accountList.isEmpty())
                        assertEquals(0, start())
                        // Standalone loopback cores have no application call service. Prevent
                        // the SDK fixture from starting the normal app's foreground service.
                        // Notification/service integration is covered by its dedicated tests.
                        org.linphone.core.tools.AndroidPlatformHelper.instance().setServiceRunning(true)
                        isNetworkReachable = true
                    }
                }
                receiver = open("receiver"); caller = open("caller")
                val incoming = mutableListOf<Call>()
                var preserveForeign = false
                val listener = object : CoreListenerStub() {
                    override fun onCallStateChanged(core: Core, call: Call, state: Call.State, message: String) {
                        if (state == Call.State.IncomingReceived) {
                            if (preserveForeign) call.callLog.refKey = "foreign-fixture-reference"
                            incoming.add(call)
                        }
                        if (state in setOf(Call.State.End, Call.State.Error, Call.State.Released)) {
                            Mango9PushCallerIdentityCache.finish(call)
                        } else {
                            Mango9PushCallerIdentityCache.capture(call)
                        }
                    }
                }
                receiver!!.addListener(listener)

                fun settle(check: () -> Boolean) {
                    val deadline = SystemClock.elapsedRealtime() + 5000
                    while (!check() && SystemClock.elapsedRealtime() < deadline) {
                        receiver!!.iterate(); caller!!.iterate(); SystemClock.sleep(10)
                    }
                    assertTrue("Loopback SIP did not reach the expected state", check())
                }
                val expected = Mango9CallerPresentation("fixture-caller", "Fixture caller", "sip")
                var originalRoute = ""
                for (index in 0..2) {
                    preserveForeign = index == 1
                    val destination = Factory.instance().createAddress("sip:fixture-receiver@127.0.0.1:${receiver!!.transportsUsed.udpPort}")!!
                    val params = caller!!.createCallParams(null)!!.apply {
                        isVideoEnabled = false; if (index == 2) privacy = Privacy.Id.toInt()
                    }
                    assertNotNull(caller!!.inviteAddressWithParams(destination, params))
                    settle { incoming.size > index }
                    val call = incoming[index]
                    if (index == 0) {
                        originalRoute = call.callLog.remoteAddress.asStringUriOnly()
                        assertEquals(expected, Mango9CallHistoryPresentation.decode(call.callLog.refKey))
                    }
                    if (index == 1) assertEquals("foreign-fixture-reference", call.callLog.refKey)
                    if (index == 2) assertEquals(Mango9CallerPresentation.withheld, Mango9CallHistoryPresentation.decode(call.callLog.refKey))
                    call.terminate()
                    settle { receiver!!.callsNb == 0 && caller!!.callsNb == 0 }
                }
                receiver!!.removeListener(listener); receiver!!.stop(); receiver = open("receiver")
                val logs = receiver!!.callLogs
                assertEquals(3, logs.size)
                val restored = logs.single { Mango9CallHistoryPresentation.decode(it.refKey)?.source == "sip" }
                assertEquals(expected, Mango9CallHistoryPresentation.decode(restored.refKey))
                assertEquals(originalRoute, restored.remoteAddress.asStringUriOnly())
                val privateLog = logs.single { Mango9CallHistoryPresentation.decode(it.refKey)?.source == "withheld" }
                val privateModel = org.linphone.ui.main.history.model.CallLogModel(privateLog)
                assertEquals("Private caller", privateModel.avatarModel.contactName)
                assertEquals("Private caller", privateModel.displayedAddress)
                assertFalse(privateModel.matchesCallerFilter("fixture-caller"))
                Mango9CallHistoryPresentation.persist(restored, Mango9CallerPresentation("+12025550123", "Saved push caller", "push"))
                val displayModel = org.linphone.ui.main.history.model.CallLogModel(restored)
                assertEquals("Saved push caller", displayModel.avatarModel.contactName)
                assertEquals(originalRoute, displayModel.sipUri)
                assertTrue(displayModel.matchesCallerFilter("2025550123"))
                assertEquals(
                    Mango9CallerPresentation.withheld,
                    Mango9CallHistoryPresentation.decode(
                        logs.single {
                    Mango9CallHistoryPresentation.decode(it.refKey)?.source == "withheld"
                }.refKey
                    )
                )
                assertTrue(logs.any { it.refKey == "foreign-fixture-reference" })
                assertTrue(caller!!.callLogs.all { Mango9CallHistoryPresentation.decode(it.refKey) == null })
            } catch (error: Throwable) { failure = error } finally {
                receiver?.stop(); caller?.stop()
                org.linphone.core.tools.AndroidPlatformHelper.instance().setServiceRunning(false)
                // Only this test's exact synthetic cache directory, never the app history.
                root.deleteRecursively(); latch.countDown()
            }
        }
        assertTrue("SDK fixture timed out", latch.await(35, TimeUnit.SECONDS))
        failure?.let { throw AssertionError(it) }
    }
}
