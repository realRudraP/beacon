package dev.realrudrap.beacon.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.realrudrap.beacon.data.local.AttendanceDao
import dev.realrudrap.beacon.data.local.LocationCheckDao
import dev.realrudrap.beacon.data.local.entity.AttendanceSyncStatus
import dev.realrudrap.beacon.data.local.entity.LocationCheckEntity
import dev.realrudrap.beacon.data.location.LocationException
import dev.realrudrap.beacon.data.location.LocationProvider
import dev.realrudrap.beacon.domain.provider.AppLogger
import dev.realrudrap.beacon.domain.provider.CrashReportingProvider
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Captures one background location check for an open attendance record.
 * Triggered by [LocationCheckAlarmReceiver].
 */
@HiltWorker
class LocationCheckCaptureWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val attendanceDao: AttendanceDao,
    private val locationCheckDao: LocationCheckDao,
    private val locationProvider: LocationProvider,
    private val logger: AppLogger,
    private val crashReporter: CrashReportingProvider,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val attendanceRecordId = inputData.getString(KEY_ATTENDANCE_ID)
        val sequence = inputData.getInt(KEY_SEQUENCE, -1)
        if (attendanceRecordId.isNullOrBlank() || sequence !in 1..LocationCheckScheduler.CHECKPOINT_COUNT) {
            logger.e(TAG, "Malformed capture request (attendance=$attendanceRecordId, sequence=$sequence)")
            return Result.success()
        }

        val record = attendanceDao.getRecordById(attendanceRecordId)
        if (record == null) {
            logger.d(TAG, "Attendance $attendanceRecordId no longer exists; skipping check $sequence")
            return Result.success()
        }
        if (record.clockOutTime != null) {
            logger.d(TAG, "Attendance $attendanceRecordId clocked out; skipping check $sequence")
            return Result.success()
        }
        if (locationCheckDao.exists(attendanceRecordId, sequence)) {
            logger.d(TAG, "Check $sequence already captured for $attendanceRecordId")
            return Result.success()
        }

        val clockIn = runCatching { Instant.parse(record.clockInTime) }.getOrNull()
        if (clockIn == null) {
            logger.e(TAG, "Could not parse clock-in time ${record.clockInTime}")
            return Result.success()
        }

        val now = Instant.now()
        val windowEnd = clockIn.plus(Duration.ofMinutes(LocationCheckScheduler.MAX_WINDOW_MINUTES.toLong()))
        if (now.isBefore(clockIn) || now.isAfter(windowEnd)) {
            logger.d(TAG, "Check $sequence outside attendance window; skipping")
            return Result.success()
        }

        val locationResult = locationProvider.getFreshLocation()
        if (locationResult.isFailure) {
            val error = (locationResult.exceptionOrNull() as? LocationException)?.error?.toString()
            logger.e(TAG, "Location capture failed for $attendanceRecordId/$sequence: $error")
            crashReporter.log("LOCATION_CHECK capture_failed attendance=$attendanceRecordId sequence=$sequence error=$error")
            return if (runAttemptCount < MAX_CAPTURE_ATTEMPTS - 1) {
                Result.retry()
            } else {
                Result.success()
            }
        }

        val location = locationResult.getOrThrow()
        val entity = LocationCheckEntity(
            id = UUID.randomUUID().toString(),
            attendanceRecordId = attendanceRecordId,
            capturedAt = Instant.now().toString(),
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy.toDouble(),
            sequenceNumber = sequence,
            syncStatus = AttendanceSyncStatus.PENDING.name,
        )

        locationCheckDao.insert(entity)
        logger.d(TAG, "Captured check $sequence for $attendanceRecordId at ${location.latitude},${location.longitude}")
        crashReporter.log("LOCATION_CHECK captured attendance=$attendanceRecordId sequence=$sequence")

        LocationCheckSyncWorker.syncNow(applicationContext)
        return Result.success()
    }

    companion object {
        private const val TAG = "LocationCheckCapture"
        private const val KEY_ATTENDANCE_ID = "attendance_record_id"
        private const val KEY_SEQUENCE = "sequence_number"
        private const val MAX_CAPTURE_ATTEMPTS = 3

        fun enqueue(context: Context, attendanceRecordId: String, sequence: Int) {
            val request = OneTimeWorkRequestBuilder<LocationCheckCaptureWorker>()
                .setInputData(
                    workDataOf(
                        KEY_ATTENDANCE_ID to attendanceRecordId,
                        KEY_SEQUENCE to sequence
                    )
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "location_check_capture_${attendanceRecordId}_$sequence",
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
