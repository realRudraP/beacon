package dev.realrudrap.beacon.data.provider

import android.content.Context
import android.util.Log
import androidx.work.WorkerExceptionInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.realrudrap.beacon.data.local.WorkerFailureLogDao
import dev.realrudrap.beacon.data.local.entity.WorkerFailureLogEntity
import dev.realrudrap.beacon.work.WorkerFailureUploadWorker
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkerFailureRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: WorkerFailureLogDao,
    private val logBuffer: InMemoryLogBuffer,
) {
    fun recordExecutionFailure(info: WorkerExceptionInfo) {
        record(info, FAILURE_TYPE_EXECUTION)
    }

    fun recordInitializationFailure(info: WorkerExceptionInfo) {
        record(info, FAILURE_TYPE_INITIALIZATION)
    }

    private fun record(info: WorkerExceptionInfo, failureType: String) {
        if (info.workerClassName == WorkerFailureUploadWorker::class.java.name) return

        val entity = WorkerFailureLogEntity(
            id = UUID.randomUUID().toString(),
            workerClassName = info.workerClassName,
            failureType = failureType,
            workId = info.workerParameters.id.toString(),
            runAttemptCount = info.workerParameters.runAttemptCount,
            tags = info.workerParameters.tags.joinToString(","),
            message = info.throwable.toString().take(MAX_MESSAGE_CHARACTERS),
            stackTrace = Log.getStackTraceString(info.throwable).take(MAX_STACK_TRACE_CHARACTERS),
            recentLogs = logBuffer.snapshot().takeIf { it.isNotBlank() },
            uploaded = false,
            createdAt = System.currentTimeMillis(),
        )

        try {
            runBlocking {
                dao.insert(entity)
                dao.trimToMostRecent(MAX_PERSISTED_FAILURES)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to persist worker failure for %s", info.workerClassName)
            return
        }

        try {
            WorkerFailureUploadWorker.enqueue(context)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to schedule worker failure upload")
        }
    }

    private companion object {
        const val TAG = "WorkerFailureRecorder"
        const val FAILURE_TYPE_EXECUTION = "execution"
        const val FAILURE_TYPE_INITIALIZATION = "initialization"
        const val MAX_MESSAGE_CHARACTERS = 2_000
        const val MAX_STACK_TRACE_CHARACTERS = 16_000
        const val MAX_PERSISTED_FAILURES = 50
    }
}
