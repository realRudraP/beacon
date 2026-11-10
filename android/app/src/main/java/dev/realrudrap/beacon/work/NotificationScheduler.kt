package dev.realrudrap.beacon.work


import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder

import androidx.work.WorkManager
import androidx.work.workDataOf

import dev.realrudrap.beacon.domain.model.NotificationRule
import timber.log.Timber
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit


class NotificationScheduler @Inject constructor(
    private val workManager: WorkManager
) {
    fun schedule(rules: List<NotificationRule>) {
        workManager.cancelAllWorkByTag(TAG_NOTIFICATION)

        val enabledRules = rules.filter { it.enabled }
        Timber.tag(TAG).i("Scheduling %d notification rule(s)", enabledRules.size)
        enabledRules.forEach { rule ->
            enqueueRule(rule)
        }
    }

    fun enqueueRule(rule: NotificationRule) {
        val targetTime = parseTargetTime(rule.time)
        val delay = computeDelay(targetTime)

        Timber.tag(TAG).d("Scheduling rule %s at %s (delay=%dms)", rule.id, targetTime, delay)

        val data = workDataOf(
            NotificationWorker.KEY_RULE_ID to rule.id,
            NotificationWorker.KEY_TITLE to rule.title,
            NotificationWorker.KEY_DESCRIPTION to rule.description
        )

        val request = OneTimeWorkRequestBuilder<NotificationWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(data)
            .addTag(TAG_NOTIFICATION)
            .addTag(rule.id)
            .build()

        workManager.enqueueUniqueWork(
            rule.id,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private fun parseTargetTime(time: String): LocalTime {
        return try {
            if (time.isBlank()) {
                LocalTime.of(10, 0)
            } else {
                LocalTime.parse(time, DateTimeFormatter.ofPattern("HH:mm"))
            }
        } catch (e: Exception) {
            Timber.tag("NotificationScheduler").w("Invalid time '$time', defaulting to 10:00")
            LocalTime.of(10, 0)
        }
    }

    private fun computeDelay(target: LocalTime): Long {
        val now = LocalDateTime.now()
        var scheduled = LocalDateTime.of(LocalDate.now(), target)

        // If the time has already passed today, schedule for tomorrow
        if (!scheduled.isAfter(now)) {
            scheduled = scheduled.plusDays(1)
        }

        return ChronoUnit.MILLIS.between(now, scheduled)
    }

    fun cancel() {
        workManager.cancelAllWorkByTag(TAG_NOTIFICATION)
        Timber.tag(TAG).d("Cancelled all scheduled notification rules")
    }

    companion object {
        private const val TAG = "NotificationScheduler"
        const val TAG_NOTIFICATION = "beacon_notification"
    }
}