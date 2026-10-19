// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException

internal data class Mango9PushRetirement(val id: String, val session: Mango9Session)

/** Persisted network retry. Job extras never contain accounts or credentials. */
class Mango9PushCleanupJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        work = scope.launch {
            val complete = try {
                kotlinx.coroutines.withContext(Dispatchers.IO) { Mango9MessagePushSync.get(applicationContext).retryRemovals() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { false }
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                Mango9SessionStore(applicationContext).finishPushCleanup { empty ->
                    jobFinished(params, !complete || !empty)
                    if (complete && empty) getSystemService(JobScheduler::class.java).cancel(JOB_ID)
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean { work?.cancel(); return true }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        private const val JOB_ID = 0x4d3901

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            // Do not replace an already running/pending retry and reset its backoff.
            if (scheduler.getPendingJob(JOB_ID) != null) return
            check(scheduler.schedule(jobInfo(context)) == JobScheduler.RESULT_SUCCESS) { "Unable to schedule push cleanup" }
        }

        internal fun jobInfo(context: Context): JobInfo =
            JobInfo.Builder(JOB_ID, ComponentName(context, Mango9PushCleanupJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
                .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build()
    }
}
