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
import dev.realrudrap.beacon.domain.provider.CrashReportingProvider
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
    private val attendanceApiService: AttendanceApiService,
    private val crashReporter: CrashReportingProvider,
) : CoroutineWorker(context, params) {

    private enum class SyncResult { Success, Retry, Failed }

    private sealed interface PhotoResult {
        data class Uploaded(val uri: String?) : PhotoResult
        data object Retry : PhotoResult
        data object Skip : PhotoResult
    }

    override suspend fun doWork(): Result {
        val pending = attendanceDao.getPendingRecords()
        Timber.d("AttendanceSyncWorker: ${pending.size} pending records")
        crashReporter.log("ATTENDANCE_SYNC pending=${pending.size}")

        if (pending.isEmpty()) return Result.success()

        // Process each record independently — one failure doesn't block others
        var shouldRetry = false
        var syncedAny = false
        for (record in pending) {
            when (syncRecord(record)) {
                SyncResult.Success -> syncedAny = true
                SyncResult.Retry -> shouldRetry = true
                SyncResult.Failed -> Unit
            }
        }

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
            Timber.d("AttendanceSyncWorker: record ${record.id} synced successfully")
            crashReporter.log("ATTENDANCE_SYNC success id=${record.id}")
            SyncResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Timber.e(e, "AttendanceSyncWorker: network exception syncing record ${record.id}")
            crashReporter.log("ATTENDANCE_SYNC network_error id=${record.id} error=${e.javaClass.simpleName}")
            SyncResult.Retry
        } catch (e: Exception) {
            Timber.e(e, "AttendanceSyncWorker: exception syncing record ${record.id}")
            crashReporter.log("ATTENDANCE_SYNC failed id=${record.id} error=${e.javaClass.simpleName}")
            crashReporter.recordException(e)
            attendanceDao.updateSyncStatus(record.id, AttendanceSyncStatus.FAILED.name)
            SyncResult.Failed
        }
    }

    private suspend fun handleHttpFailure(
        operation: String,
        recordId: String,
        code: Int
    ): SyncResult {
        val message = "ATTENDANCE_SYNC $operation id=$recordId http=$code"
        Timber.e("AttendanceSyncWorker: $operation failed $code for $recordId")
        crashReporter.log(message)

        if (isRetryable(code)) {
            if (code == 401 || code == 403) {
                crashReporter.recordException(SyncAuthException(message))
            }
            // Leave the record PENDING so the next worker run retries it.
            return SyncResult.Retry
        }

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
            Timber.w("AttendanceSyncWorker: photo file not found at $localPath")
            crashReporter.log("ATTENDANCE_SYNC photo_missing id=$recordId type=$type")
            return PhotoResult.Skip
        }

        return try {
            val requestBody = file.asRequestBody("image/jpeg".toMediaTypeOrNull())
            val part = MultipartBody.Part.createFormData("photo", file.name, requestBody)
            val response = attendanceApiService.uploadAttendancePhoto(recordId, type, part)
            if (response.isSuccessful) {
                PhotoResult.Uploaded(response.body()?.photoUrl)
            } else if (isRetryable(response.code())) {
                Timber.e("AttendanceSyncWorker: photo upload retryable ${response.code()} for $recordId type=$type")
                crashReporter.log("ATTENDANCE_SYNC photo_retry id=$recordId type=$type http=${response.code()}")
                PhotoResult.Retry
            } else {
                Timber.e("AttendanceSyncWorker: photo upload failed ${response.code()} for $recordId type=$type")
                crashReporter.log("ATTENDANCE_SYNC photo_failed id=$recordId type=$type http=${response.code()}")
                PhotoResult.Skip
            }
        } catch (e: IOException) {
            Timber.e(e, "AttendanceSyncWorker: photo upload network exception for $recordId type=$type")
            crashReporter.log("ATTENDANCE_SYNC photo_network_error id=$recordId type=$type")
            PhotoResult.Retry
        } catch (e: Exception) {
            Timber.e(e, "AttendanceSyncWorker: photo upload exception for $recordId type=$type")
            crashReporter.recordException(e)
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

    private class SyncAuthException(message: String) : Exception(message)

    companion object {
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
        }
    }
}
