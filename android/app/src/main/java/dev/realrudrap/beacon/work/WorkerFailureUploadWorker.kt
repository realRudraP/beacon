package dev.realrudrap.beacon.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.realrudrap.beacon.data.local.WorkerFailureLogDao
import dev.realrudrap.beacon.data.local.entity.WorkerFailureLogEntity
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.protocol.Message
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.time.Instant
import java.util.concurrent.TimeUnit

@HiltWorker
class WorkerFailureUploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val dao: WorkerFailureLogDao,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            dao.deleteUploadedBefore(System.currentTimeMillis() - RETENTION_MILLIS)

            val pending = dao.getPending()
            if (pending.isEmpty()) return Result.success()

            if (!Sentry.isEnabled()) {
                Timber.tag(TAG).w("Sentry is disabled; keeping %d persisted worker failure(s)", pending.size)
                return Result.success()
            }

            pending.forEach { failure ->
                Sentry.captureEvent(failure.toSentryEvent())
                dao.markUploaded(failure.id)
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to upload persisted worker failures")
            Result.retry()
        }
    }

    private fun WorkerFailureLogEntity.toSentryEvent(): SentryEvent {
        return SentryEvent().apply {
            level = SentryLevel.ERROR
            logger = TAG
            message = Message().apply {
                message = "Background worker failed: $workerClassName"
                formatted = this@toSentryEvent.message
            }
            setTag("worker_class", workerClassName)
            setTag("failure_type", failureType)
            workId?.let { setTag("work_id", it) }
            setTag("run_attempt_count", runAttemptCount.toString())
            setExtra("worker_tags", tags)
            setExtra("failure_reason", message)
            setExtra("stack_trace", stackTrace)
            setExtra("recent_logs", recentLogs)
            setExtra("captured_at", Instant.ofEpochMilli(createdAt).toString())
        }
    }

    companion object {
        private const val TAG = "WorkerFailureUpload"
        private const val UNIQUE_WORK_NAME = "worker_failure_upload"
        private const val RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<WorkerFailureUploadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
            Timber.tag(TAG).d("Persisted worker failure upload enqueued")
        }
    }
}
