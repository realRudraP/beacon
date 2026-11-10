package dev.realrudrap.beacon.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.realrudrap.beacon.data.local.AttendanceDao
import dev.realrudrap.beacon.data.local.TokenManager
import dev.realrudrap.beacon.data.local.entity.AttendanceEntity
import dev.realrudrap.beacon.domain.provider.AppLogger
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules background location checks at fixed absolute offsets from the
 * clock-in instant. Alarms are re-armed on boot / app start from the open
 * attendance record and cancelled on clock-out.
 */
@Singleton
class LocationCheckScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val attendanceDao: AttendanceDao,
    private val tokenManager: TokenManager,
    private val logger: AppLogger,
) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun schedule(record: AttendanceEntity) {
        cancelAll()

        if (tokenManager.getRefreshToken() == null) {
            logger.d(TAG, "User is logged out; not scheduling location checks")
            return
        }

        if (record.clockOutTime != null) {
            logger.d(TAG, "Record ${record.id} is already clocked out; no checks scheduled")
            return
        }

        val clockInMillis = runCatching { Instant.parse(record.clockInTime).toEpochMilli() }
            .getOrElse {
                logger.e(TAG, "Could not parse clock-in time ${record.clockInTime}", it)
                return
            }

        val now = System.currentTimeMillis()
        var scheduled = 0
        CHECKPOINT_MINUTES.forEachIndexed { index, offsetMinutes ->
            val sequence = index + 1
            val triggerAt = clockInMillis + offsetMinutes * 60_000L
            if (triggerAt <= now) {
                logger.d(TAG, "Skipping past checkpoint $sequence for ${record.id}")
                return@forEachIndexed
            }
            setAlarm(triggerAt, record.id, sequence)
            scheduled++
        }
        logger.i(TAG, "Scheduled $scheduled location check(s) for ${record.id}")
    }

    suspend fun rescheduleFromOpenRecord() {
        val open = attendanceDao.getOpenRecord()
        if (open == null || open.clockOutTime != null) {
            cancelAll()
            logger.d(TAG, "No open attendance record; location checks cancelled")
            return
        }
        schedule(open)
    }

    fun cancelAll() {
        for (sequence in 1..CHECKPOINT_MINUTES.size) {
            try {
                alarmManager.cancel(pendingIntent(sequence))
            } catch (e: Exception) {
                logger.e(TAG, "Failed to cancel location alarm $sequence", e)
            }
        }
        logger.d(TAG, "Cancelled all location check alarms")
    }

    private fun setAlarm(triggerAtMillis: Long, attendanceRecordId: String, sequence: Int) {
        val pending = pendingIntent(sequence, attendanceRecordId)
        try {
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    alarmManager.canScheduleExactAlarms()
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pending
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pending
                )
            }
            logger.d(TAG, "Scheduled check $sequence for $attendanceRecordId at $triggerAtMillis (exact=$canExact)")
        } catch (e: SecurityException) {
            logger.w(TAG, "Exact alarm not permitted; falling back to inexact for check $sequence", e)
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
        } catch (e: Exception) {
            logger.e(TAG, "Failed to schedule location check $sequence", e)
        }
    }

    private fun pendingIntent(sequence: Int, attendanceRecordId: String? = null): PendingIntent {
        val intent = Intent(context, LocationCheckAlarmReceiver::class.java).apply {
            action = ACTION_LOCATION_CHECK
            if (attendanceRecordId != null) {
                putExtra(LocationCheckAlarmReceiver.EXTRA_ATTENDANCE_ID, attendanceRecordId)
            }
            putExtra(LocationCheckAlarmReceiver.EXTRA_SEQUENCE, sequence)
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_BASE + sequence,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val TAG = "LocationCheckScheduler"
        const val ACTION_LOCATION_CHECK = "dev.realrudrap.beacon.LOCATION_CHECK"
        private const val REQUEST_CODE_BASE = 7300

        /** Checkpoints from clock-in: 90, 180, 270, 360, 450, 540 minutes. */
        val CHECKPOINT_MINUTES = listOf(1,2,3,4,5,90, 180, 270, 360, 450, 540)
        val CHECKPOINT_COUNT = CHECKPOINT_MINUTES.size
        val MAX_WINDOW_MINUTES = CHECKPOINT_MINUTES.last()

        fun checkpointOffsetMillis(sequence: Int): Long? {
            val index = sequence - 1
            if (index !in CHECKPOINT_MINUTES.indices) return null
            return CHECKPOINT_MINUTES[index] * 60_000L
        }
    }
}
