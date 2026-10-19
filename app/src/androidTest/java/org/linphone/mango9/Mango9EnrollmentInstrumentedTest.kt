/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.ui.assistant.viewmodel.Mango9LoginViewModel

@RunWith(AndroidJUnit4::class)
class Mango9EnrollmentInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val sessions = Mango9SessionStore(instrumentation.targetContext)

    @Test fun cancelledEmailResponseCannotChangeModeOrClearANewerLoginSpinner() = withModel { model, backend ->
        main {
            model.email.value = "fixture@example.invalid"
            model.showEmailLogin()
            model.requestEmailCode()
            assertTrue(model.inProgress.value == true)
            model.showPasswordLogin()
            model.username.value = "fixture@example.invalid"
            model.password.value = "fixture-only"
            model.signIn()
            assertTrue(model.inProgress.value == true)
        }
        backend.code.complete(60)
        finish(backend.codeJob!!)
        main {
            assertTrue(model.passwordMode.value == true)
            assertFalse(model.emailVerificationMode.value == true)
            assertTrue(model.inProgress.value == true)
            assertNull(model.errorMessage.value)
            assertNull(model.accountLoggedInEvent.value)
        }
        backend.login.complete(Unit)
        finish(backend.loginJob!!)
        main {
            assertFalse(model.inProgress.value == true)
            assertNotNull(model.accountLoggedInEvent.value)
            assertEquals("", model.password.value)
        }
    }

    @Test fun leavingTheLoginScreenRejectsALateSuccessWithoutShowingANetworkError() = withModel { model, backend ->
        main {
            model.username.value = "fixture@example.invalid"
            model.password.value = "fixture-only"
            model.signIn()
            model.cancelPendingActions()
        }
        backend.login.complete(Unit)
        finish(backend.loginJob!!)
        main {
            assertNull(model.accountLoggedInEvent.value)
            assertNull(model.errorMessage.value)
            assertFalse(model.inProgress.value == true)
        }
    }

    @Test fun realStoreCommitRejectsChangedSelectionAndOldLeaseCannotMutateReplacement() {
        val before = sessions.activeIdentity
        val remember = sessions.rememberLogin
        val a = fixture("enrollment-a"); val b = fixture("enrollment-b")
        try {
            sessions.save(a, persist = false, makeActive = true)
            val selection = sessions.selectionVersion
            sessions.save(b, persist = false, makeActive = true)
            assertNull(sessions.commitLogin(a, a.sipIdentity!!, !remember, selection))
            assertEquals(remember, sessions.rememberLogin)
            assertEquals(b.sipIdentity, sessions.activeIdentity)
            val lease = sessions.commitLogin(a, a.sipIdentity, remember, sessions.selectionVersion)!!
            assertEquals(a, sessions.currentSession(lease))
            sessions.save(a.copy(accessToken = "replacement-access"), persist = false, makeActive = true)
            var wrote = false
            assertTrue(
                runCatching { sessions.withCurrentSession(lease) { wrote = true } }
                .exceptionOrNull() is Mango9ApiException.Unauthorized
            )
            assertFalse(wrote)
            assertEquals("replacement-access", sessions.load(a.sipIdentity)?.accessToken)
        } finally {
            sessions.remove(a.sipIdentity!!); sessions.remove(b.sipIdentity!!)
            sessions.activate(before?.takeIf(sessions::hasSession))
        }
    }

    @Test fun cancelledEnrollmentCannotInstallAnAccountWhenQueuedCoreWorkFinallyRuns() = runBlocking {
        val before = onCore { it.accountList.size to it.authInfoList.size }
        val blocked = CountDownLatch(1); val release = CountDownLatch(1)
        coreContext.postOnCoreThread {
            blocked.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        try {
            assertTrue(blocked.await(5, TimeUnit.SECONDS))
            val attempt = Mango9EnrollmentAttempts().begin()
            val queued = launch(start = CoroutineStart.UNDISPATCHED) {
                attempt.bindToCurrentJob()
                Mango9AccountProvisioner.install(
                    Mango9SipEnrollment("sip:cancelled@fixture.example.invalid", "cancelled", "fixture.example.invalid", "fixture", "digest"),
                    null,
                    stillAllowed = attempt::isCurrent,
                    mutationGuard = { attempt.requireCurrent(it) },
                )
            }
            attempt.cancel()
            release.countDown()
            withTimeout(5_000) { queued.join() }
            assertTrue(queued.isCancelled)
            assertEquals(before, onCore { it.accountList.size to it.authInfoList.size })
        } finally {
            release.countDown()
        }
    }

    private inner class Backend : Mango9LoginActions {
        override val sessions = this@Mango9EnrollmentInstrumentedTest.sessions
        val code = CompletableDeferred<Int>()
        val login = CompletableDeferred<Unit>()
        var codeJob: Job? = null
        var loginJob: Job? = null

        override suspend fun requestLoginCode(email: String): Int {
            codeJob = currentCoroutineContext().job
            return withContext(NonCancellable) { code.await() }
        }

        override suspend fun signIn(username: String, password: String, rememberLogin: Boolean, attempt: Mango9EnrollmentAttempts.Attempt) {
            loginJob = currentCoroutineContext().job
            withContext(NonCancellable) { login.await() }
        }

        override suspend fun verifyLoginCode(email: String, code: String, rememberLogin: Boolean, attempt: Mango9EnrollmentAttempts.Attempt) =
            signIn(email, code, rememberLogin, attempt)

        override suspend fun restore(session: Mango9Session, attempt: Mango9EnrollmentAttempts.Attempt) = error("Unexpected restore")
    }

    private fun withModel(test: (Mango9LoginViewModel, Backend) -> Unit) {
        val backend = Backend(); val store = ViewModelStore()
        lateinit var model: Mango9LoginViewModel
        main {
            model = ViewModelProvider(
                store,
                object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return Mango9LoginViewModel(backend) as T
                }
            }
            )[Mango9LoginViewModel::class.java]
        }
        try {
            test(model, backend)
        } finally {
            main { store.clear() }
            backend.code.complete(0); backend.login.complete(Unit)
            backend.codeJob?.let(::finish); backend.loginJob?.let(::finish)
        }
    }

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun finish(job: Job) = runBlocking { withTimeout(5_000) { job.join() } }

    private suspend fun <T> onCore(action: (org.linphone.core.Core) -> T): T = suspendCancellableCoroutine { continuation ->
        coreContext.postOnCoreThread { core -> if (continuation.isActive) continuation.resume(action(core)) }
    }

    private fun fixture(id: String) = Mango9Session(
        crmId = "fixture", crmBaseUrl = "https://crm.example.invalid", crmApiBaseUrl = "https://crm.example.invalid/api",
        userId = id, parentClientId = "tenant", role = "agent", loginId = "fixture@example.invalid", displayName = "Fixture",
        accessToken = "fixture-access", refreshToken = "fixture-refresh", smsChatApi = "https://chat.example.invalid/api",
        connectWebsocket = "wss://chat.example.invalid/socket", enrollmentExpiresAtEpochSeconds = 1_900_000_000,
        sipIdentity = "sip:$id@fixture.example.invalid",
    )
}
