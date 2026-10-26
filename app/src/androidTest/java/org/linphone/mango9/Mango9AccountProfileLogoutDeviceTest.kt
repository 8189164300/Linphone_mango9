/* Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later */
package org.linphone.mango9

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.BuildConfig
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.Account
import org.linphone.core.Core
import org.linphone.core.Factory
import org.linphone.ui.main.settings.viewmodel.AccountProfileViewModel

/** Real SDK/profile lifecycle, isolated package, registration disabled, no server credentials. */
@RunWith(AndroidJUnit4::class)
class Mango9AccountProfileLogoutDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before fun requireIsolatedPackage() {
        check(BuildConfig.APPLICATION_ID.endsWith(".debug"))
        check(instrumentation.targetContext.packageName == BuildConfig.APPLICATION_ID)
    }

    private fun onCore(block: (Core) -> Unit) {
        val complete = CountDownLatch(1)
        var failure: Throwable? = null
        coreContext.postOnCoreThread { core ->
            try { block(core) } catch (error: Throwable) { failure = error } finally { complete.countDown() }
        }
        assertTrue("Core operation must finish", complete.await(15, TimeUnit.SECONDS))
        failure?.let { throw it }
    }

    private fun account(core: Core, identity: String): Account {
        val params = core.createAccountParams()
        params.identityAddress = Factory.instance().createAddress(identity)!!.apply { displayName = "Fixture" }
        params.serverAddress = Factory.instance().createAddress("sip:fixture.example.invalid")
        params.isRegisterEnabled = false
        return core.createAccount(params).also { account ->
            assertEquals("Fixture must be accepted by the SDK", 0, core.addAccount(account))
            assertTrue(core.accountList.any { it.nativePointer == account.nativePointer })
        }
    }

    @Test fun reusedProfileCanSignOutAgainAfterSigningBackIn() {
        val identity = "sip:logout-${UUID.randomUUID()}@fixture.example.invalid"
        lateinit var model: AccountProfileViewModel
        instrumentation.runOnMainSync { model = AccountProfileViewModel() }
        try {
            repeat(2) { cycle ->
                onCore { account(it, identity) }
                instrumentation.runOnMainSync {
                    model.findAccountMatchingIdentity(identity)
                    model.deleteAccount()
                }
                onCore { core ->
                    assertFalse("Logout cycle ${cycle + 1} must remove the account",
                        core.accountList.any { it.params.identityAddress?.asStringUriOnly() == identity })
                }
            }
        } finally {
            onCore { core -> core.accountList.filter { it.params.identityAddress?.asStringUriOnly() == identity }.forEach(core::removeAccount) }
        }
    }

    @Test fun leavingRemovedProfileDoesNotRewriteOrReregisterItsAccount() {
        val identity = "sip:logout-${UUID.randomUUID()}@fixture.example.invalid"
        lateinit var model: AccountProfileViewModel
        lateinit var removed: Account
        instrumentation.runOnMainSync { model = AccountProfileViewModel() }
        try {
            onCore { removed = account(it, identity) }
            instrumentation.runOnMainSync { model.findAccountMatchingIdentity(identity) }
            onCore { }
            instrumentation.runOnMainSync {
                model.displayName.value = "Unsaved edit"
                model.deleteAccount()
                model.saveChangesWhenLeaving()
            }
            onCore { core ->
                assertFalse(core.accountList.any { it.nativePointer == removed.nativePointer })
                assertEquals("Fixture", removed.params.identityAddress?.displayName)
            }
        } finally {
            onCore { core -> core.accountList.filter { it.params.identityAddress?.asStringUriOnly() == identity }.forEach(core::removeAccount) }
        }
    }
}
