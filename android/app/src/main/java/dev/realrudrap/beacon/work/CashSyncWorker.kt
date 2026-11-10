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
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit

@HiltWorker
class CashSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val cashTransactionDao: CashTransactionDao,
    private val cashApiService: CashApiService
) : CoroutineWorker(context, params) {

    private enum class SyncResult { Success, Retry, Failed }

    override suspend fun doWork(): Result {
        val pending = cashTransactionDao.getPendingTransactions()
        Timber.tag(TAG).d("%d pending cash transaction(s)", pending.size)

        if (pending.isEmpty()) return Result.success()

        var shouldRetry = false
        var syncedCount = 0
        var retryCount = 0
        var failedCount = 0
        for (item in pending) {
            when (syncTransaction(item)) {
                SyncResult.Success -> syncedCount++
                SyncResult.Retry -> {
                    shouldRetry = true
                    retryCount++
                }
                SyncResult.Failed -> failedCount++
            }
        }

        Timber.tag(TAG).i(
            "Cash sync finished: synced=%d retrying=%d failed=%d",
            syncedCount, retryCount, failedCount
        )
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
                Timber.tag(TAG).i("Cash transaction %s synced (HTTP %d)", item.id, response.code())
                SyncResult.Success
            } else if (isRetryable(response.code())) {
                if (response.code() == 401 || response.code() == 403) {
                    Timber.tag(TAG).e("Cash transaction %s rejected with HTTP %d", item.id, response.code())
                } else {
                    Timber.tag(TAG).w("Cash transaction %s temporarily failed with HTTP %d", item.id, response.code())
                }
                // Leave the record PENDING so the next worker run retries it.
                SyncResult.Retry
            } else {
                Timber.tag(TAG).e("Cash transaction %s failed permanently with HTTP %d", item.id, response.code())
                cashTransactionDao.updateSyncStatus(item.id, AttendanceSyncStatus.FAILED.name)
                SyncResult.Failed
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Timber.tag(TAG).w(e, "Network error syncing cash transaction %s", item.id)
            SyncResult.Retry
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Unexpected error syncing cash transaction %s", item.id)
            cashTransactionDao.updateSyncStatus(item.id, AttendanceSyncStatus.FAILED.name)
            SyncResult.Failed
        }
    }

    private fun isRetryable(code: Int): Boolean {
        return code == 401 || code == 403 || code == 408 || code == 425 ||
                code == 429 || code >= 500
    }

    companion object {
        private const val TAG = "CashSyncWorker"
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
            Timber.tag(TAG).d("Immediate cash sync enqueued")
        }
    }
}
