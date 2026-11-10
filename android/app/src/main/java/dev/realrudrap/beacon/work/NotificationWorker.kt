package dev.realrudrap.beacon.work

import android.Manifest
import dev.realrudrap.beacon.R
import android.content.Context
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.realrudrap.beacon.domain.model.NotificationConditions
import dev.realrudrap.beacon.domain.repository.AttendanceRepository
import dev.realrudrap.beacon.domain.repository.ConfigRepository
import dev.realrudrap.beacon.domain.repository.UserRepository
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import timber.log.Timber

@HiltWorker
class NotificationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val configRepository: ConfigRepository,
    private val attendanceRepository: AttendanceRepository,
    private val userRepository: UserRepository,
    private val notificationManager: NotificationManagerCompat,
    private val notificationScheduler: NotificationScheduler
) : CoroutineWorker(context, workerParams){
    companion object {
        private const val TAG = "NotificationWorker"
        const val KEY_RULE_ID = "rule_id"
        const val KEY_TITLE = "title"
        const val KEY_DESCRIPTION = "description"
        const val CHANNEL_ID = "beacon_notifications"
    }
    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    override suspend fun doWork(): Result {
        val ruleId = inputData.getString(KEY_RULE_ID)
        val title = inputData.getString(KEY_TITLE)
        val description = inputData.getString(KEY_DESCRIPTION)
        if (ruleId == null || title == null || description == null) {
            Timber.tag(TAG).w("Notification rule missing required input data")
            return Result.failure()
        }

        val config = configRepository.config.first()
        if (config == null) {
            Timber.tag(TAG).d("No config available; skipping rule %s", ruleId)
            return Result.success()
        }

        val rule = config.notificationRules?.find { it.id == ruleId }
        if (rule == null) {
            Timber.tag(TAG).w("Rule %s no longer exists in config", ruleId)
            return Result.success()
        }

        if (!rule.enabled) {
            Timber.tag(TAG).d("Rule %s is disabled", ruleId)
            return Result.success()
        }

        if (checkConditions(rule.conditions)) {
            postNotification(ruleId, title, description)
        } else {
            Timber.tag(TAG).d("Conditions not met for rule %s", ruleId)
        }

        notificationScheduler.enqueueRule(rule)
        return Result.success()
    }
    private suspend fun checkConditions(conditions: NotificationConditions): Boolean{
        conditions.isWorkingDay?.let{ expected ->
            if (isWorkingDay() != expected) return false
        }
        conditions.isPublicHoliday?.let{ expected ->
            if (isPublicHoliday() != expected) return false
        }
        conditions.hasClockedInToday?.let { expected ->
            if (attendanceRepository.hasClockedInToday() != expected) return false
        }
        conditions.hasClockedOutToday?.let { expected ->
            if (attendanceRepository.hasClockedOutToday() != expected) return false
        }
        // hasPendingSyncRecords — implement when you have offline sync
        return true
    }

    private suspend fun isWorkingDay(): Boolean {
        val user = userRepository.user.first() ?: return true // assume working day if no data
        val todayDayOfWeek = LocalDate.now().dayOfWeek.value % 7 // convert to 0=Sun, 6=Sat
        return todayDayOfWeek !in user.weeklyOffDays
    }
    private suspend fun isPublicHoliday(): Boolean {
        val config = configRepository.config.first() ?: return false
        return config.publicHolidays?.contains(LocalDate.now().toString()) ?: false
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private fun postNotification(ruleId: String, title: String, description: String) {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentTitle(title)
            .setContentText(description)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        runCatching {
            notificationManager.notify(ruleId.hashCode(), notification)
        }.onSuccess {
            Timber.tag(TAG).i("Posted notification for rule %s", ruleId)
        }.onFailure { error ->
            Timber.tag(TAG).w(error, "Failed to post notification for rule %s", ruleId)
        }
    }
}