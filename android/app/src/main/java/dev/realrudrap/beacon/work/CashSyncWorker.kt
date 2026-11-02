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
import dev.realrudrap.beacon.data.local.CashTransactionDao
import dev.realrudrap.beacon.data.local.entity.AttendanceSyncStatus
import dev.realrudrap.beacon.data.local.entity.CashTransactionEntity
import dev.realrudrap.beacon.data.remote.CashApiService
import dev.realrudrap.beacon.data.remote.dto.CreateCashTransactionRequest
import dev.realrudrap.beacon.domain.provider.CrashReportingProvider
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit

@HiltWorker
class CashSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val cashTransactionDao: CashTransactionDao,
    private val cashApiService: CashApiService,
    private val crashReporter: CrashReportingProvider
) : CoroutineWorker(context, params) {

    private enum class SyncResult { Success, Retry, Failed }

    override suspend fun doWork(): Result {
        val pending = cashTransactionDao.getPendingTransactions()
        crashReporter.log("CASH_SYNC pending=${pending.size}")

        if (pending.isEmpty()) return Result.success()

        var shouldRetry = false
        for (item in pending) {
            when (syncTransaction(item)) {
                SyncResult.Success -> Unit
                SyncResult.Retry -> shouldRetry = true
                SyncResult.Failed -> Unit
            }
        }

        return if (shouldRetry) Result.retry() else Result.success()
    }

    private suspend fun syncTransaction(item: CashTransactionEntity): SyncResult {
        return try {
            val request = CreateCashTransactionRequest(
                id = item.id,
                type = item.type,
                amount = item.amount,
                category = item.category,
                description = item.description,
                receiptPhotoUri = item.receiptPhotoServerUri,
                createdAt = item.createdAt
            )

            val response = cashApiService.createTransaction(request)
            if (response.isSuccessful || response.code() == 409) {
                cashTransactionDao.updateSyncStatus(item.id, AttendanceSyncStatus.SYNCED.name)
                crashReporter.log("CASH_SYNC success id=${item.id}")
                SyncResult.Success
            } else if (isRetryable(response.code())) {
                val message = "CASH_SYNC create id=${item.id} http=${response.code()}"
                Timber.e("CashSyncWorker retryable failure for ${item.id} with code ${response.code()}")
                crashReporter.log(message)
                if (response.code() == 401 || response.code() == 403) {
                    crashReporter.recordException(SyncAuthException(message))
                }
                // Leave the record PENDING so the next worker run retries it.
                SyncResult.Retry
            } else {
                Timber.e("CashSyncWorker failed for ${item.id} with code ${response.code()}")
                crashReporter.log("CASH_SYNC failed id=${item.id} http=${response.code()}")
                cashTransactionDao.updateSyncStatus(item.id, AttendanceSyncStatus.FAILED.name)
                SyncResult.Failed
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Timber.e(e, "CashSyncWorker network exception for ${item.id}")
            crashReporter.log("CASH_SYNC network_error id=${item.id} error=${e.javaClass.simpleName}")
            SyncResult.Retry
        } catch (e: Exception) {
            Timber.e(e, "Exception syncing cash transaction ${item.id}")
            crashReporter.log("CASH_SYNC failed id=${item.id} error=${e.javaClass.simpleName}")
            crashReporter.recordException(e)
            cashTransactionDao.updateSyncStatus(item.id, AttendanceSyncStatus.FAILED.name)
            SyncResult.Failed
        }
    }

    private fun isRetryable(code: Int): Boolean {
        return code == 401 || code == 403 || code == 408 || code == 425 ||
                code == 429 || code >= 500
    }

    private class SyncAuthException(message: String) : Exception(message)

    companion object {
        private const val WORK_NAME = "cash_sync_immediate"

        fun syncNow(workManager: WorkManager) {
            val request = OneTimeWorkRequestBuilder<CashSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
