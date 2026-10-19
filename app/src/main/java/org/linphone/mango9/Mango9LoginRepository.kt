/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.mango9

import android.content.Context
import kotlinx.coroutines.CancellationException
import org.linphone.BuildConfig
import org.linphone.core.tools.Log

interface Mango9LoginActions {
    val sessions: Mango9SessionStore

    suspend fun signIn(username: String, password: String, rememberLogin: Boolean, attempt: Mango9EnrollmentAttempts.Attempt)

    suspend fun requestLoginCode(email: String): Int

    suspend fun verifyLoginCode(email: String, code: String, rememberLogin: Boolean, attempt: Mango9EnrollmentAttempts.Attempt)

    suspend fun restore(session: Mango9Session, attempt: Mango9EnrollmentAttempts.Attempt)
}

class Mango9LoginRepository(context: Context) : Mango9LoginActions {
    private companion object {
        private const val TAG = "[Mango9 Login]"
    }

    private val appContext = context.applicationContext
    private val api = Mango9ApiClient(appContext)
    private val lineIdentities = Mango9LineIdentityStore(appContext)
    override val sessions = Mango9SessionStore(appContext)

    private val login = Mango9EnrollmentLogin(
        selectionVersion = { sessions.selectionVersion },
        fetchEnrollment = api::fetchEnrollment,
        commit = { session, enrollment, remember, selection ->
            sessions.commitLogin(session, enrollment.identity, remember, selection)?.also { lease ->
                sessions.withCurrentSession(lease) {
                    lineIdentities.save(Mango9LineIdentity(enrollment.username, null), enrollment.identity)
                }
            }
        },
    )

    override suspend fun signIn(
        username: String,
        password: String,
        rememberLogin: Boolean,
        attempt: Mango9EnrollmentAttempts.Attempt,
    ) {
        completeLogin(login.authenticate(rememberLogin, attempt) { api.signIn(username, password) }, attempt)
    }

    override suspend fun requestLoginCode(email: String): Int = api.requestLoginCode(email)

    override suspend fun verifyLoginCode(
        email: String,
        code: String,
        rememberLogin: Boolean,
        attempt: Mango9EnrollmentAttempts.Attempt,
    ) {
        completeLogin(login.authenticate(rememberLogin, attempt) { api.verifyLoginCode(email, code) }, attempt)
    }

    override suspend fun restore(session: Mango9Session, attempt: Mango9EnrollmentAttempts.Attempt) {
        attempt.bindToCurrentJob()
        val lease = sessions.captureSession() ?: throw Mango9ApiException.Unauthorized
        if (lease.session != session) throw Mango9ApiException.Unauthorized
        val provisioning = attempt.awaitCurrent {
            Mango9AuthorizedRequests(sessions, api::refresh).execute(lease, api::storedProvisioning)
        }
        val currentSession = sessions.currentSession(lease) ?: throw Mango9ApiException.Unauthorized
        val enrollment = Mango9AccountProvisioner.fromPassword(
            provisioning.identity,
            provisioning.username,
            provisioning.password,
            provisioning.domain,
            provisioning.realm,
        )
        if (Mango9SessionStore.normalizedIdentity(enrollment.identity) != currentSession.sipIdentity) {
            throw Mango9ApiException.InvalidResponse
        }
        mutate(attempt, lease) {
            lineIdentities.save(
                Mango9LineIdentity(provisioning.username, provisioning.activeNumber),
                enrollment.identity,
            )
        }
        install(enrollment, lease, attempt)
        attempt.awaitCurrent { connectMessaging(lease) }
    }

    private suspend fun completeLogin(
        connection: Mango9EnrollmentLogin.Connection,
        attempt: Mango9EnrollmentAttempts.Attempt,
    ) {
        val (enrollment, lease) = connection
        try {
            val settings = attempt.awaitCurrent {
                Mango9AuthorizedRequests(sessions, api::refresh).execute(lease, api::callSettings)
            }
            mutate(attempt, lease) {
                lineIdentities.save(
                    Mango9LineIdentity(settings.extension, settings.activeNumber),
                    enrollment.identity,
                )
            }
        } catch (_: Mango9ApiException) {
            // The authenticated CRM session and known extension remain valid.
        }
        // CRM authentication and SIP registration are separate lanes. A valid
        // CRM session is intentionally retained if the SIP proxy is temporarily unavailable.
        try {
            install(enrollment, lease, attempt)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e("$TAG Authentication and enrollment succeeded but SIP installation failed: ${error.javaClass.simpleName}")
            throw error
        }
        attempt.awaitCurrent { connectMessaging(lease) }
    }

    private fun mutate(attempt: Mango9EnrollmentAttempts.Attempt, lease: Mango9SessionLease, action: () -> Unit) {
        attempt.requireCurrent { sessions.withCurrentSession(lease, action) }
    }

    private suspend fun install(
        enrollment: Mango9SipEnrollment,
        lease: Mango9SessionLease,
        attempt: Mango9EnrollmentAttempts.Attempt,
    ) = Mango9AccountProvisioner.install(
        enrollment,
        lease.session.displayName,
        stillAllowed = { attempt.isCurrent() && sessions.currentSession(lease) != null },
        mutationGuard = { action -> mutate(attempt, lease, action) },
    )

    /** Never recapture a different account after registration/network suspension. */
    private suspend fun connectMessaging(lease: Mango9SessionLease) {
        if (sessions.currentSession(lease) == null) throw Mango9ApiException.Unauthorized
        if (BuildConfig.MANGO9_FCM_ENABLED) {
            Mango9MessagePushSync.get(appContext).refreshAsync()
            Mango9FirebaseTokenSync.refresh(appContext)
        }
        Mango9ChatStore.get(appContext).connect(force = true, expectedSession = lease)
        if (sessions.currentSession(lease) == null) throw CancellationException("Account changed while connecting messaging")
    }
}
