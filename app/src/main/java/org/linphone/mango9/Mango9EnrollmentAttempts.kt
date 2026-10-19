/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job

/** Latest enrollment intent wins across assistant screens. Never hold this lock over a suspension. */
class Mango9EnrollmentAttempts {
    private var current: Attempt? = null

    @Synchronized fun begin(): Attempt = Attempt().also { current = it }

    inner class Attempt internal constructor() {
        private var job: Job? = null

        suspend fun bindToCurrentJob() {
            val caller = currentCoroutineContext().job
            requireCurrent {
                check(job == null || job === caller) { "Enrollment attempt already has an owner" }
                job = caller
            }
        }

        fun isCurrent(): Boolean = synchronized(this@Mango9EnrollmentAttempts) {
            current === this && job?.isActive != false
        }

        fun cancel() = synchronized(this@Mango9EnrollmentAttempts) {
            // A late onDestroy from an old screen must not cancel its replacement.
            if (current === this) current = null
        }

        fun <T> ifCurrent(action: () -> T): T? = synchronized(this@Mango9EnrollmentAttempts) {
            if (isCurrent()) action() else null
        }

        fun <T> requireCurrent(action: () -> T): T = synchronized(this@Mango9EnrollmentAttempts) {
            if (!isCurrent()) throw CancellationException("Enrollment attempt is no longer current")
            action()
        }

        suspend fun <T> awaitCurrent(action: suspend () -> T): T {
            currentCoroutineContext().ensureActive()
            requireCurrent { }
            val result = action()
            currentCoroutineContext().ensureActive()
            requireCurrent { }
            return result
        }
    }

    companion object {
        val shared = Mango9EnrollmentAttempts()
    }
}

/** Authentication has no local side effects until the guarded commit after enrollment validation. */
internal class Mango9EnrollmentLogin(
    private val selectionVersion: () -> Long,
    private val fetchEnrollment: suspend (String) -> Mango9SipEnrollment,
    private val commit: (Mango9Session, Mango9SipEnrollment, Boolean, Long) -> Mango9SessionLease?,
) {
    data class Connection(val enrollment: Mango9SipEnrollment, val lease: Mango9SessionLease)

    suspend fun authenticate(
        rememberLogin: Boolean,
        attempt: Mango9EnrollmentAttempts.Attempt,
        authenticate: suspend () -> Mango9LoginResponse,
    ): Connection {
        attempt.bindToCurrentJob()
        val selection = selectionVersion()
        val login = attempt.awaitCurrent(authenticate)
        val enrollment = attempt.awaitCurrent { fetchEnrollment(login.enrollmentUrl) }
        val session = login.session.associatedWith(enrollment.identity)
        val lease = attempt.requireCurrent {
            commit(session, enrollment, rememberLogin, selection)
                ?: throw CancellationException("Account selection changed during enrollment")
        }
        // Once committed, a valid CRM login is retained even if SIP registration fails or
        // the screen closes. Cancellation prevents further side effects, not implicit logout.
        return Connection(enrollment, lease)
    }
}
