package dev.realrudrap.beacon.work

import android.content.Context
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
import dev.realrudrap.beacon.data.local.entity.AttendanceEntity
import dev.realrudrap.beacon.data.local.entity.AttendanceSyncStatus
import dev.realrudrap.beacon.data.remote.AttendanceApiService
import dev.realrudrap.beacon.data.remote.dto.MarkAttendanceRequest
import dev.realrudrap.beacon.data.remote.dto.UpdateAttendanceRequest
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

@HiltWorker
class AttendanceSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val attendanceDao: AttendanceDao,
    private val attendanceApiService: AttendanceApiService
) : CoroutineWorker(context, params) {

    private enum class SyncResult { Success, Retry, Failed }

    private sealed interface PhotoResult {
        data class Uploaded(val uri: String?) : PhotoResult
        data object Retry : PhotoResult
        data object Skip : PhotoResult
    }

    override suspend fun doWork(): Result {
        val pending = attendanceDao.getPendingRecords()
        Timber.tag(TAG).d("%d pending record(s)", pending.size)

        if (pending.isEmpty()) return Result.success()

        // Process each record independently — one failure doesn't block others
        var shouldRetry = false
        var syncedAny = false
        var syncedCount = 0
        var retryCount = 0
        var failedCount = 0
        for (record in pending) {
            when (syncRecord(record)) {
                SyncResult.Success -> {
                    syncedAny = true
                    syncedCount++
                }
                SyncResult.Retry -> {
                    shouldRetry = true
                    retryCount++
                }
                SyncResult.Failed -> failedCount++
            }
        }

        Timber.tag(TAG).i(
            "Attendance sync finished: synced=%d retrying=%d failed=%d",
            syncedCount, retryCount, failedCount
        )

        if (syncedAny) {
            // Location checks may now be uploaded once their attendance exists server-side.
            LocationCheckSyncWorker.syncNow(applicationContext)
        }

        return if (shouldRetry) Result.retry() else Result.success()
    }

    private suspend fun syncRecord(record: AttendanceEntity): SyncResult {
        return try {
            // Step 1 — POST attendance record first, no photo URIs yet
            val markRequest = MarkAttendanceRequest(
                id = record.id,
                clockInTime = record.clockInTime,
                clockInLat = record.clockInLat,
                clockInLong = record.clockInLong,
                clockInPhotoUri = null,  // always null on first POST
                clockOutTime = null,     // clock-out handled separately
                clockOutLat = null,
                clockOutLong = null,
                clockOutPhotoUri = null
            )

            val markResponse = attendanceApiService.markAttendance(markRequest)
            if (!markResponse.isSuccessful && markResponse.code() != 409) {
                return handleHttpFailure("markAttendance", record.id, markResponse.code())
            }

            // Step 2 — record exists on server, now upload clock-in photo
            var clockInPhotoUri = record.clockInPhotoServerUri
            if (clockInPhotoUri == null) {
                when (val result = uploadPhoto(record.id, record.clockInPhotoLocalPath, "in")) {
                    is PhotoResult.Uploaded -> {
                        clockInPhotoUri = result.uri
                        result.uri?.let { attendanceDao.updateClockInPhotoServerUri(record.id, it) }
                    }
                    PhotoResult.Retry -> return SyncResult.Retry
                    PhotoResult.Skip -> Unit
                }
            }

            // Step 3 — PATCH with clock-in photo URI if we got one
            // Also handles clock-out if it exists
            val hasClockOut = record.clockOutTime != null
                    && record.clockOutLat != null
                    && record.clockOutLong != null

            var clockOutPhotoUri = record.clockOutPhotoServerUri
            if (hasClockOut && clockOutPhotoUri == null) {
                when (val result = uploadPhoto(record.id, record.clockOutPhotoLocalPath, "out")) {
                    is PhotoResult.Uploaded -> {
                        clockOutPhotoUri = result.uri
                        result.uri?.let { attendanceDao.updateClockOutPhotoServerUri(record.id, it) }
                    }
                    PhotoResult.Retry -> return SyncResult.Retry
                    PhotoResult.Skip -> Unit
                }
            }

            if (clockInPhotoUri != null || hasClockOut) {
                val updateRequest = UpdateAttendanceRequest(
                    clockInPhotoUri = clockInPhotoUri,
                    clockOutTime = record.clockOutTime,
                    clockOutLat = record.clockOutLat,
                    clockOutLong = record.clockOutLong,
                    clockOutPhotoUri = clockOutPhotoUri
                )
                val updateResponse = attendanceApiService.updateAttendance(record.id, updateRequest)
                if (!updateResponse.isSuccessful) {
                    return handleHttpFailure("updateAttendance", record.id, updateResponse.code())
                }
            }

            attendanceDao.updateSyncStatus(record.id, AttendanceSyncStatus.SYNCED.name)
            cleanupLocalPhotos(record)
            Timber.tag(TAG).i("Record %s synced", record.id)
            SyncResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Timber.tag(TAG).w(e, "Network error syncing record %s", record.id)
            SyncResult.Retry
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Unexpected error syncing record %s", record.id)
            attendanceDao.updateSyncStatus(record.id, AttendanceSyncStatus.FAILED.name)
            SyncResult.Failed
        }
    }

    private suspend fun handleHttpFailure(
        operation: String,
        recordId: String,
        code: Int
    ): SyncResult {
        if (isRetryable(code)) {
            if (code == 401 || code == 403) {
                Timber.tag(TAG).e("%s rejected with HTTP %d for record %s", operation, code, recordId)
            } else {
                Timber.tag(TAG).w("%s temporarily failed with HTTP %d for record %s", operation, code, recordId)
            }
            // Leave the record PENDING so the next worker run retries it.
            return SyncResult.Retry
        }

        Timber.tag(TAG).e("%s failed permanently with HTTP %d for record %s", operation, code, recordId)
        attendanceDao.updateSyncStatus(recordId, AttendanceSyncStatus.FAILED.name)
        return SyncResult.Failed
    }

    /**
     * Uploads a photo file to the server and returns the server URI.
     * Network/auth failures return [PhotoResult.Retry] so the record is not
     * silently synced without its photo. Missing files are skipped.
     */
    private suspend fun uploadPhoto(
        recordId: String,
        localPath: String?,
        type: String // "in" or "out"
    ): PhotoResult {
        if (localPath == null) return PhotoResult.Skip
        val file = File(localPath)
        if (!file.exists()) {
            Timber.tag(TAG).w("Photo file missing for record %s type=%s", recordId, type)
            return PhotoResult.Skip
        }

        return try {
            val requestBody = file.asRequestBody("image/jpeg".toMediaTypeOrNull())
            val part = MultipartBody.Part.createFormData("photo", file.name, requestBody)
            val response = attendanceApiService.uploadAttendancePhoto(recordId, type, part)
            if (response.isSuccessful) {
                Timber.tag(TAG).d("Photo uploaded for record %s type=%s (HTTP %d)", recordId, type, response.code())
                PhotoResult.Uploaded(response.body()?.photoUrl)
            } else if (isRetryable(response.code())) {
                Timber.tag(TAG).w("Photo upload retryable for record %s type=%s (HTTP %d)", recordId, type, response.code())
                PhotoResult.Retry
            } else {
                Timber.tag(TAG).e("Photo upload failed for record %s type=%s (HTTP %d)", recordId, type, response.code())
                PhotoResult.Skip
            }
        } catch (e: IOException) {
            Timber.tag(TAG).w(e, "Photo upload network error for record %s type=%s", recordId, type)
            PhotoResult.Retry
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Photo upload error for record %s type=%s", recordId, type)
            PhotoResult.Skip
        }
    }

    private fun isRetryable(code: Int): Boolean {
        return code == 401 || code == 403 || code == 408 || code == 425 ||
                code == 429 || code >= 500
    }

    /**
     * Deletes local photo files after successful sync to free up storage.
     */
    private fun cleanupLocalPhotos(record: AttendanceEntity) {
        record.clockInPhotoLocalPath?.let { path ->
            val file = File(path)
            if (file.exists()) file.delete()
        }
        record.clockOutPhotoLocalPath?.let { path ->
            val file = File(path)
            if (file.exists()) file.delete()
        }
    }

    companion object {
        private const val TAG = "AttendanceSyncWorker"
        private const val PERIODIC_WORK_NAME = "attendance_sync_periodic"
        private const val ONE_TIME_WORK_NAME = "attendance_sync_immediate"

        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /**
         * Schedules periodic sync every 3 hours.
         * Call once at app startup from MainViewModel.
         */
        fun schedulePeriodicSync(workManager: WorkManager) {
            val request = PeriodicWorkRequestBuilder<AttendanceSyncWorker>(3, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            workManager.enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Timber.tag(TAG).i("Periodic attendance sync scheduled")
        }

        /**
         * Triggers an immediate one-time sync.
         * Call after clock-in or clock-out to attempt sync right away.
         */
        fun syncNow(workManager: WorkManager) {
            val request = OneTimeWorkRequestBuilder<AttendanceSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            workManager.enqueueUniqueWork(
                ONE_TIME_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
            Timber.tag(TAG).d("Immediate attendance sync enqueued")
        }
    }
}
