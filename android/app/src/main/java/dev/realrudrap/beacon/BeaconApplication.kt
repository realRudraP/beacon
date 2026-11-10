package dev.realrudrap.beacon

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.realrudrap.beacon.data.local.AttendanceDao
import dev.realrudrap.beacon.data.local.CashTransactionDao
import dev.realrudrap.beacon.data.local.LocationCheckDao
import dev.realrudrap.beacon.data.provider.BufferedLogTree
import dev.realrudrap.beacon.data.provider.InMemoryLogBuffer
import dev.realrudrap.beacon.data.provider.WorkerFailureRecorder
import dev.realrudrap.beacon.work.LocationCheckScheduler
import dev.realrudrap.beacon.work.NotificationWorker
import dev.realrudrap.beacon.work.WorkerFailureUploadWorker
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
    @Inject lateinit var locationCheckDao: LocationCheckDao
    @Inject lateinit var locationCheckScheduler: LocationCheckScheduler
    @Inject lateinit var logBuffer: InMemoryLogBuffer
    @Inject lateinit var workerFailureRecorder: WorkerFailureRecorder

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setWorkerExecutionExceptionHandler { info ->
                Timber.tag(WORK_MANAGER_TAG).w(
                    info.throwable,
                    "Worker execution failed: class=%s id=%s attempt=%d tags=%s",
                    info.workerClassName,
                    info.workerParameters.id,
                    info.workerParameters.runAttemptCount,
                    info.workerParameters.tags
                )
                workerFailureRecorder.recordExecutionFailure(info)
            }
            .setWorkerInitializationExceptionHandler { info ->
                Timber.tag(WORK_MANAGER_TAG).w(
                    info.throwable,
                    "Worker initialization failed: class=%s id=%s attempt=%d tags=%s",
                    info.workerClassName,
                    info.workerParameters.id,
                    info.workerParameters.runAttemptCount,
                    info.workerParameters.tags
                )
                workerFailureRecorder.recordInitializationFailure(info)
            }
            .setSchedulingExceptionHandler { throwable ->
                Timber.tag(WORK_MANAGER_TAG).w(throwable, "WorkManager scheduling failed")
            }
            .setInitializationExceptionHandler { throwable ->
                Timber.tag(WORK_MANAGER_TAG).w(throwable, "WorkManager initialization failed")
            }
            .build()
    override fun onCreate() {

        super.onCreate()
        createNotificationChannels()
        Timber.plant(BufferedLogTree(logBuffer))
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.i("BeaconApplication: started")
        WorkerFailureUploadWorker.enqueue(this)
        requeuePreviouslyFailedRecordsOnce()
        rescheduleLocationChecks()
    }

    /**
     * Re-arms alarms for any open shift after app start (the OS clears alarms
     * on reboot, and the process may have been killed after clock-in).
     */
    private fun rescheduleLocationChecks() {
        applicationScope.launch {
            try {
                locationCheckScheduler.rescheduleFromOpenRecord()
            } catch (e: Exception) {
                Timber.e(e, "Failed to reschedule background location checks")
            }
        }
    }

    /**
     * Previous app versions marked records as FAILED on any error, including
     * transient 401s and network failures, which permanently removed them from
     * the sync queue. Requeue them once after upgrading so they can be retried.
     */
    private fun requeuePreviouslyFailedRecordsOnce() {
        val prefs = getSharedPreferences(MAINTENANCE_PREFS, Context.MODE_PRIVATE)

        applicationScope.launch {
            try {
                if (!prefs.getBoolean(KEY_FAILED_RECORDS_REQUEUED, false)) {
                    attendanceDao.requeueFailedRecords()
                    cashTransactionDao.requeueFailedRecords()
                    prefs.edit().putBoolean(KEY_FAILED_RECORDS_REQUEUED, true).apply()
                    Timber.i("Maintenance: requeued previously failed attendance and cash records")
                }
                if (!prefs.getBoolean(KEY_LOCATION_CHECKS_REQUEUED, false)) {
                    locationCheckDao.requeueFailedChecks()
                    prefs.edit().putBoolean(KEY_LOCATION_CHECKS_REQUEUED, true).apply()
                    Timber.i("Maintenance: requeued previously failed location checks")
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to requeue previously FAILED sync records")
            }
        }
    }

    private companion object {
        const val WORK_MANAGER_TAG = "WorkManager"
        const val MAINTENANCE_PREFS = "beacon_maintenance"
        const val KEY_FAILED_RECORDS_REQUEUED = "failed_records_requeued_v1"
        const val KEY_LOCATION_CHECKS_REQUEUED = "location_checks_requeued_v1"
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