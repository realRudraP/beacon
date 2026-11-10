package dev.realrudrap.beacon.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Re-arms location-check alarms after a reboot or app update. AlarmManager
 * alarms do not survive process death / reboot on their own.
 */
@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {
    @Inject
    lateinit var scheduler: LocationCheckScheduler

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        Timber.tag(TAG).i("Received %s; rescheduling location checks", action)
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                scheduler.rescheduleFromOpenRecord()
                Timber.tag(TAG).d("Location check reschedule after boot complete")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to reschedule location checks after boot")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootCompletedReceiver"
    }
}
