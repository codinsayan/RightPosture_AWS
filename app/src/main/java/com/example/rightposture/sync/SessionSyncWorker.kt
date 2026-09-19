package com.example.rightposture.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.rightposture.data.RightPostureDatabase
import java.util.concurrent.TimeUnit

/**
 * How a push outcome becomes a WorkManager result.
 *
 * Extracted as a plain function so the decision is testable without a worker, a context or a
 * scheduler. It is the part with actual judgement in it; the worker around it is plumbing.
 */
enum class SyncWorkResult { SUCCESS, RETRY, FAILURE }

fun syncWorkResultFor(outcome: SyncOutcome): SyncWorkResult = when (outcome) {
    // Zero pushed is still success. An empty queue is not a problem to retry.
    is SyncOutcome.Done -> SyncWorkResult.SUCCESS
    // §13.3's ordinary case. The work stays queued and the backoff decides when to look again.
    SyncOutcome.Offline -> SyncWorkResult.RETRY
    // The server understood and refused. Retrying identical rows earns an identical refusal, and
    // the sessions stay unsynced in Room either way - nothing is lost by stopping.
    is SyncOutcome.Failed -> SyncWorkResult.FAILURE
}

/**
 * §13.3's background push.
 *
 * The worker owns none of the logic. It reads the signed-in patient's id out of its input data,
 * hands off to [SessionSync], and maps the answer. That split is deliberate: everything worth
 * testing is in [SessionSync] and [syncWorkResultFor], both of which run in the JVM suite, and none
 * of it needs `work-testing` or an instrumented device to exercise.
 */
class SessionSyncWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val patientId = inputData.getString(KEY_PATIENT_ID) ?: return Result.failure()
        val config = SupabaseConfig.fromBuildConfig()
        // An unconfigured build has nowhere to push to, and that is a supported state rather than
        // an error - §13.3 and 7.1's airplane-mode gate both depend on it.
        if (!config.isConfigured) return Result.success()

        val token = inputData.getString(KEY_ACCESS_TOKEN)
        val client = SupabaseClient(config).apply { accessToken = token }
        val database = RightPostureDatabase.get(applicationContext)
        val sync = SessionSync(client, database.sessions(), database.events())

        return when (syncWorkResultFor(sync.pushPending(patientId))) {
            SyncWorkResult.SUCCESS -> Result.success()
            SyncWorkResult.RETRY -> Result.retry()
            SyncWorkResult.FAILURE -> Result.failure()
        }
    }

    companion object {
        const val KEY_PATIENT_ID = "patientId"
        const val KEY_ACCESS_TOKEN = "accessToken"

        /** One name, so a set that finishes while a push is queued replaces it rather than racing. */
        const val WORK_NAME = "session-sync"

        /**
         * Queues a push.
         *
         * `KEEP` rather than `REPLACE`: the queue is read from Room when the worker runs, so an
         * already-scheduled run will pick up whatever has been added since. Replacing would restart
         * the backoff every time a set finished, which on a patient doing three sets back to back
         * means the push keeps being deferred by the very activity it exists to upload.
         */
        fun enqueue(context: Context, patientId: String, accessToken: String?) {
            val request = OneTimeWorkRequestBuilder<SessionSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                // §13.3's shape, and the same one `retryingSupabase` uses in the foreground: one
                // server hiccup must not become a synchronised burst from every device that saw it.
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(
                    workDataOf(
                        KEY_PATIENT_ID to patientId,
                        KEY_ACCESS_TOKEN to accessToken,
                    ),
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
