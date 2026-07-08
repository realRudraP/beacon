package dev.realrudrap.beacon

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.realrudrap.beacon.work.NotificationWorker
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class BeaconApplication: Application(), Configuration.Provider{
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
    override fun onCreate() {

        super.onCreate()
        createNotificationChannels()

            Timber.plant(Timber.DebugTree())
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