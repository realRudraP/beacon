package dev.realrudrap.beacon.work

import android.content.Context
import androidx.hilt.work.HiltWorker
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
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

@HiltWorker
class AttendanceSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val attendanceDao: AttendanceDao,
    private val attendanceApiService: AttendanceApiService,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val pending = attendanceDao.getPendingRecords()
        Timber.d("AttendanceSyncWorker: ${pending.size} pending records")

        if (pending.isEmpty()) return Result.success()

        // Process each record independently — one failure doesn't block others
        var anyFailed = false
        for (record in pending) {
            val success = syncRecord(record)
            if (!success) anyFailed = true
        }

        return if (anyFailed) Result.retry() else Result.success()
    }

    private suspend fun syncRecord(record: AttendanceEntity): Boolean {
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
                Timber.e("AttendanceSyncWorker: markAttendance failed ${markResponse.code()} for ${record.id}")
                attendanceDao.updateSyncStatus(record.id, AttendanceSyncStatus.FAILED.name)
                return false
            }

            // Step 2 — record exists on server, now upload clock-in photo
            val clockInPhotoUri = record.clockInPhotoServerUri
                ?: uploadPhoto(record.id, record.clockInPhotoLocalPath, "in")
                    ?.also { uri -> attendanceDao.updateClockInPhotoServerUri(record.id, uri) }

            // Step 3 — PATCH with clock-in photo URI if we got one
            // Also handles clock-out if it exists
            val hasClockOut = record.clockOutTime != null
                    && record.clockOutLat != null
                    && record.clockOutLong != null

            val clockOutPhotoUri = if (hasClockOut) {
                record.clockOutPhotoServerUri
                    ?: uploadPhoto(record.id, record.clockOutPhotoLocalPath, "out")
                        ?.also { uri -> attendanceDao.updateClockOutPhotoServerUri(record.id, uri) }
            } else null

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
                    Timber.e("AttendanceSyncWorker: updateAttendance failed ${updateResponse.code()} for ${record.id}")
                    attendanceDao.updateSyncStatus(record.id, AttendanceSyncStatus.FAILED.name)
                    return false
                }
            }

            attendanceDao.updateSyncStatus(record.id, AttendanceSyncStatus.SYNCED.name)
            cleanupLocalPhotos(record)
            Timber.d("AttendanceSyncWorker: record ${record.id} synced successfully")
            true
        } catch (e: Exception) {
            Timber.e(e, "AttendanceSyncWorker: exception syncing record ${record.id}")
            attendanceDao.updateSyncStatus(record.id, AttendanceSyncStatus.FAILED.name)
            false
        }
    }

    /**
     * Uploads a photo file to the server and returns the server URI.
     * Returns null if the file doesn't exist or upload fails.
     */
    private suspend fun uploadPhoto(
        recordId: String,
        localPath: String?,
        type: String // "in" or "out"
    ): String? {
        if (localPath == null) return null
        val file = File(localPath)
        if (!file.exists()) {
            Timber.w("AttendanceSyncWorker: photo file not found at $localPath")
            return null
        }

        return try {
            val requestBody = file.asRequestBody("image/jpeg".toMediaTypeOrNull())
            val part = MultipartBody.Part.createFormData("photo", file.name, requestBody)
            val response = attendanceApiService.uploadAttendancePhoto(recordId, type, part)
            if (response.isSuccessful) {
                response.body()?.photoUrl
            } else {
                Timber.e("AttendanceSyncWorker: photo upload failed ${response.code()} for $recordId type=$type")
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "AttendanceSyncWorker: photo upload exception for $recordId type=$type")
            null
        }
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
                .build()
            workManager.enqueueUniqueWork(
                ONE_TIME_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}