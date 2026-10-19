/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.linphone.LinphoneApplication.Companion.coreContext

/** Flush only after each test has removed its own SDK fixtures and restored prior state. */
internal object Mango9SdkTestCleanup {
    fun flush() {
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        // Removal listeners can queue default-account selection. Drain that turn
        // too before flushing; instrumentation may end immediately afterwards.
        coreContext.postOnCoreThread {
            coreContext.postOnCoreThread { core ->
                try {
                    // Explicit recovery only for a crashed synthetic fixture, never a user account.
                    val identity = androidx.test.platform.app.InstrumentationRegistry.getArguments()
                        .getString("mango9_cleanup_fixture_identity")
                    if (identity != null) {
                        require(
                            identity == "sip:thumbnail@fixture.example.invalid" || identity == "sip:mixer@127.0.0.1" ||
                                Regex("sip:native-draft-[0-9a-f-]{36}@example\\.invalid").matches(identity)
                        )
                        if (identity == "sip:mixer@127.0.0.1") {
                            require(core.callsNb == 0)
                            require(core.accountList.all { it.params.identityAddress?.asStringUriOnly() == identity })
                            require(core.config.getString("misc", "conference_type", null) == "local")
                            core.config.setString("misc", "conference_type", null)
                            core.useFiles = false
                        }
                        core.chatRooms.filter { it.localAddress.asStringUriOnly() == identity }.forEach(core::deleteChatRoom)
                        core.accountList.filter { it.params.identityAddress?.asStringUriOnly() == identity }.forEach {
                            if (core.defaultAccount == it) core.defaultAccount = null
                            core.removeAccount(it)
                        }
                        val sessions = Mango9SessionStore(coreContext.context)
                        if (sessions.activeIdentity == identity) sessions.activate(null)
                    }
                    assertEquals("Could not persist SDK fixture cleanup", 0, core.config.sync())
                } catch (error: Throwable) { failure = error } finally { done.countDown() }
            }
        }
        assertTrue("SDK fixture cleanup timed out", done.await(15, TimeUnit.SECONDS))
        failure?.let { throw AssertionError(it) }
    }
}
