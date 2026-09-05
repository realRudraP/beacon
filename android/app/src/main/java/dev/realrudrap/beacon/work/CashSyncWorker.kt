package dev.realrudrap.beacon.work

import android.content.Context
import androidx.hilt.work.HiltWorker
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
import timber.log.Timber

@HiltWorker
class CashSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val cashTransactionDao: CashTransactionDao,
    private val cashApiService: CashApiService
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val pending = cashTransactionDao.getPendingTransactions()
        if (pending.isEmpty()) return Result.success()

        var anyFailed = false
        for (item in pending) {
            val success = syncTransaction(item)
            if (!success) anyFailed = true
        }

        return if (anyFailed) Result.retry() else Result.success()
    }

    private suspend fun syncTransaction(item: CashTransactionEntity): Boolean {
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
                true
            } else {
                Timber.e("CashSyncWorker failed for ${item.id} with code ${response.code()}")
                cashTransactionDao.updateSyncStatus(item.id, AttendanceSyncStatus.FAILED.name)
                false
            }
        } catch (e: Exception) {
            Timber.e(e, "Exception syncing cash transaction ${item.id}")
            cashTransactionDao.updateSyncStatus(item.id, AttendanceSyncStatus.FAILED.name)
            false
        }
    }

    companion object {
        private const val WORK_NAME = "cash_sync_immediate"

        fun syncNow(workManager: WorkManager) {
            val request = OneTimeWorkRequestBuilder<CashSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}