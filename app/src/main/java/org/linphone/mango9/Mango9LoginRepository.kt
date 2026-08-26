/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.mango9

import android.content.Context

class Mango9LoginRepository(context: Context) {
    private val api = Mango9ApiClient(context)
    private val lineIdentities = Mango9LineIdentityStore(context)
    val sessions = Mango9SessionStore(context)

    suspend fun signIn(username: String, password: String, rememberLogin: Boolean) {
        completeLogin(api.signIn(username, password), rememberLogin)
    }

    suspend fun requestLoginCode(email: String): Int = api.requestLoginCode(email)

    suspend fun verifyLoginCode(email: String, code: String, rememberLogin: Boolean) {
        completeLogin(api.verifyLoginCode(email, code), rememberLogin)
    }

    suspend fun enrollFromQr(enrollmentUrl: String) {
        val enrollment = api.fetchEnrollment(enrollmentUrl)
        Mango9AccountProvisioner.install(enrollment, displayName = null)
    }

    suspend fun restore(session: Mango9Session) {
        var currentSession = session
        val provisioning = try {
            api.storedProvisioning(currentSession)
        } catch (_: Mango9ApiException.InvalidCredentials) {
            currentSession = api.refresh(currentSession)
            sessions.save(currentSession, persist = true)
            api.storedProvisioning(currentSession)
        } catch (_: Mango9ApiException.Unauthorized) {
            currentSession = api.refresh(currentSession)
            sessions.save(currentSession, persist = true)
            api.storedProvisioning(currentSession)
        }
        val enrollment = Mango9AccountProvisioner.fromPassword(
            provisioning.identity,
            provisioning.username,
            provisioning.password,
            provisioning.domain,
            provisioning.realm,
        )
        val associated = currentSession.associatedWith(enrollment.identity)
        sessions.save(associated, enrollment.identity, persist = true, makeActive = true)
        lineIdentities.save(
            Mango9LineIdentity(provisioning.username, provisioning.activeNumber),
            enrollment.identity,
        )
        Mango9AccountProvisioner.install(enrollment, associated.displayName)
    }

    private suspend fun completeLogin(login: Mango9LoginResponse, rememberLogin: Boolean) {
        val enrollment = api.fetchEnrollment(login.enrollmentUrl)
        val session = login.session.associatedWith(enrollment.identity)
        sessions.rememberLogin = rememberLogin
        sessions.save(
            session,
            enrollment.identity,
            persist = rememberLogin,
            makeActive = true,
        )
        // Preserve the extension even when the independent CRM call-settings
        // request or SIP registration is temporarily unavailable.
        lineIdentities.save(
            Mango9LineIdentity(enrollment.username, null),
            enrollment.identity,
        )
        try {
            val settings = api.callSettings(session)
            if (sessions.isActive(enrollment.identity)) {
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
        Mango9AccountProvisioner.install(enrollment, session.displayName)
    }
}
