package dev.realrudrap.beacon.work

import android.content.Context
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.realrudrap.beacon.data.local.AttendanceDao
import dev.realrudrap.beacon.data.local.LocationCheckDao
import dev.realrudrap.beacon.data.local.entity.AttendanceSyncStatus
import dev.realrudrap.beacon.data.local.entity.LocationCheckEntity
import dev.realrudrap.beacon.data.remote.AttendanceApiService
import dev.realrudrap.beacon.data.remote.dto.IngestLocationChecksRequest
import dev.realrudrap.beacon.data.remote.dto.LocationCheckPayload
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit

@HiltWorker
class LocationCheckSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val locationCheckDao: LocationCheckDao,
    private val attendanceDao: AttendanceDao,
    private val attendanceApiService: AttendanceApiService
) : CoroutineWorker(context, params) {

    private enum class SyncResult { Success, Retry, Failed }

    override suspend fun doWork(): Result {
        return try {
            syncLocationChecks()
        } catch (e: CancellationException) {
            val stopReason = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) this.stopReason else -1
            Timber.tag(TAG).w(
                "Sync worker stopped: attempt=%d stopReason=%d",
                runAttemptCount,
                stopReason
            )
            throw e
        }
    }

    private suspend fun syncLocationChecks(): Result {
        val pending = locationCheckDao.getPendingChecks()
        Timber.tag(TAG).d("%d pending location check(s)", pending.size)
        if (pending.isEmpty()) return Result.success()

        var shouldRetry = false
        val byAttendance = pending.groupBy { it.attendanceRecordId }

        for ((attendanceRecordId, checks) in byAttendance) {
            val attendance = attendanceDao.getRecordById(attendanceRecordId)
            if (attendance == null) {
                Timber.tag(TAG).e("Orphan location checks for attendance %s; marking failed", attendanceRecordId)
                checks.forEach { locationCheckDao.updateSyncStatus(it.id, AttendanceSyncStatus.FAILED.name) }
                continue
            }

            // Never upload checks before their parent attendance exists on the server.
            if (attendance.syncStatus != AttendanceSyncStatus.SYNCED.name) {
                Timber.tag(TAG).d("Waiting for attendance %s to sync", attendanceRecordId)
                shouldRetry = true
                continue
            }

            checks.chunked(MAX_CHECKS_PER_REQUEST).forEach { chunk ->
                when (syncBatch(chunk)) {
                    SyncResult.Success -> Unit
                    SyncResult.Retry -> shouldRetry = true
                    SyncResult.Failed -> Unit
                }
            }
        }

        return if (shouldRetry) Result.retry() else Result.success()
    }

    private suspend fun syncBatch(checks: List<LocationCheckEntity>): SyncResult {
        val request = IngestLocationChecksRequest(
            checks = checks.map {
                LocationCheckPayload(
                    id = it.id,
                    attendanceRecordId = it.attendanceRecordId,
                    capturedAt = it.capturedAt,
                    latitude = it.latitude,
                    longitude = it.longitude,
                    accuracy = it.accuracy,
                    sequenceNumber = it.sequenceNumber,
                )
            }
        )

        return try {
            val response = attendanceApiService.ingestLocationChecks(request)
            if (!response.isSuccessful) {
                if (isRetryable(response.code())) {
                    if (response.code() == 401 || response.code() == 403) {
                        Timber.tag(TAG).e(
                            "Location check upload rejected with HTTP %d for %d check(s)",
                            response.code(), checks.size
                        )
                    } else {
                        Timber.tag(TAG).w(
                            "Location check upload temporarily failed with HTTP %d for %d check(s)",
                            response.code(), checks.size
                        )
                    }
                    return SyncResult.Retry
                }
                Timber.tag(TAG).e(
                    "Location check upload failed permanently with HTTP %d for %d check(s)",
                    response.code(), checks.size
                )
                checks.forEach { locationCheckDao.updateSyncStatus(it.id, AttendanceSyncStatus.FAILED.name) }
                return SyncResult.Failed
            }

            val body = response.body()
            if (body == null) {
                Timber.tag(TAG).e("Location check upload returned an empty body (HTTP %d)", response.code())
                return SyncResult.Retry
            }

            val storedIds = (body.accepted + body.duplicates).toSet()
            storedIds.forEach { id ->
                locationCheckDao.updateSyncStatus(id, AttendanceSyncStatus.SYNCED.name)
            }

            var retry = false
            body.rejected.forEach { rejected ->
                val transient = rejected.reason.contains("not found", ignoreCase = true) &&
                        runAttemptCount < MAX_TRANSIENT_REJECT_RETRIES
                if (transient) {
                    retry = true
                } else {
                    locationCheckDao.updateSyncStatus(rejected.id, AttendanceSyncStatus.FAILED.name)
                }
                if (transient) {
                    Timber.tag(TAG).w("Location check %s rejected transiently: %s", rejected.id, rejected.reason)
                } else {
                    Timber.tag(TAG).e("Location check %s rejected: %s", rejected.id, rejected.reason)
                }
            }

            Timber.tag(TAG).i(
                "Location check upload complete: accepted=%d duplicates=%d rejected=%d (HTTP %d)",
                body.accepted.size, body.duplicates.size, body.rejected.size, response.code()
            )
            if (retry) SyncResult.Retry else SyncResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Timber.tag(TAG).w(e, "Network error uploading location checks")
            SyncResult.Retry
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Unexpected error uploading location checks")
            checks.forEach { locationCheckDao.updateSyncStatus(it.id, AttendanceSyncStatus.FAILED.name) }
            SyncResult.Failed
        }
    }

    private fun isRetryable(code: Int): Boolean {
        return code == 401 || code == 403 || code == 408 || code == 425 ||
                code == 429 || code >= 500
    }

    companion object {
        private const val TAG = "LocationCheckSync"
        private const val PERIODIC_WORK_NAME = "location_check_sync_periodic"
        private const val ONE_TIME_WORK_NAME = "location_check_sync_immediate"
        private const val MAX_CHECKS_PER_REQUEST = 6
        private const val MAX_TRANSIENT_REJECT_RETRIES = 3

        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun schedulePeriodicSync(workManager: WorkManager) {
            val request = PeriodicWorkRequestBuilder<LocationCheckSyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            workManager.enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Timber.tag(TAG).i("Periodic location check sync scheduled")
        }

        fun syncNow(context: Context) {
            syncNow(WorkManager.getInstance(context))
        }

        fun syncNow(workManager: WorkManager) {
            val request = OneTimeWorkRequestBuilder<LocationCheckSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            workManager.enqueueUniqueWork(
                ONE_TIME_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
            Timber.tag(TAG).d("Immediate location check sync enqueued")
        }
    }
}
