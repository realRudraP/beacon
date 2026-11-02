package dev.realrudrap.beacon

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.realrudrap.beacon.data.local.AttendanceDao
import dev.realrudrap.beacon.data.local.CashTransactionDao
import dev.realrudrap.beacon.data.provider.FirebaseCrashlyticsTree
import dev.realrudrap.beacon.work.NotificationWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class BeaconApplication: Application(), Configuration.Provider{
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var attendanceDao: AttendanceDao
    @Inject lateinit var cashTransactionDao: CashTransactionDao

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
    override fun onCreate() {

        super.onCreate()
        createNotificationChannels()

        Timber.plant(Timber.DebugTree())
        Timber.plant(FirebaseCrashlyticsTree())
        requeuePreviouslyFailedRecordsOnce()
    }

    /**
     * Previous app versions marked records as FAILED on any error, including
     * transient 401s and network failures, which permanently removed them from
     * the sync queue. Requeue them once after upgrading so they can be retried.
     */
    private fun requeuePreviouslyFailedRecordsOnce() {
        val prefs = getSharedPreferences(MAINTENANCE_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_FAILED_RECORDS_REQUEUED, false)) return

        applicationScope.launch {
            try {
                attendanceDao.requeueFailedRecords()
                cashTransactionDao.requeueFailedRecords()
                prefs.edit().putBoolean(KEY_FAILED_RECORDS_REQUEUED, true).apply()
            } catch (e: Exception) {
                Timber.e(e, "Failed to requeue previously FAILED sync records")
            }
        }
    }

    private companion object {
        const val MAINTENANCE_PREFS = "beacon_maintenance"
        const val KEY_FAILED_RECORDS_REQUEUED = "failed_records_requeued_v1"
    }
    private fun createNotificationChannels() {
        val channel = NotificationChannel(
            NotificationWorker.CHANNEL_ID,
            "Beacon Notifications",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Attendance and work reminders"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}