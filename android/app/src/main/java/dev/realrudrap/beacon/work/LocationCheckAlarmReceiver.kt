package dev.realrudrap.beacon.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * Triggered by AlarmManager for each checkpoint. Delegates the actual capture to
 * WorkManager so the process gets a proper execution window while the device is
 * idle / in Doze.
 */
class LocationCheckAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LocationCheckScheduler.ACTION_LOCATION_CHECK) return

        val attendanceRecordId = intent.getStringExtra(EXTRA_ATTENDANCE_ID)
        val sequence = intent.getIntExtra(EXTRA_SEQUENCE, -1)
        if (attendanceRecordId.isNullOrBlank() || sequence !in 1..LocationCheckScheduler.CHECKPOINT_COUNT) {
            Timber.tag(TAG).w("Ignoring malformed location check alarm")
            return
        }

        Timber.tag(TAG).d("Received location check alarm for attendance=%s sequence=%d", attendanceRecordId, sequence)
        LocationCheckCaptureWorker.enqueue(context, attendanceRecordId, sequence)
    }

    companion object {
        private const val TAG = "LocationCheckAlarm"
        const val EXTRA_ATTENDANCE_ID = "attendance_record_id"
        const val EXTRA_SEQUENCE = "sequence_number"
    }
}
