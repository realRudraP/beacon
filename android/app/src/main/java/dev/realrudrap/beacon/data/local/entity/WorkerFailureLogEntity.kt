package dev.realrudrap.beacon.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "worker_failure_logs",
    indices = [
        Index(value = ["uploaded"]),
        Index(value = ["createdAt"])
    ]
)
data class WorkerFailureLogEntity(
    @PrimaryKey val id: String,
    val workerClassName: String,
    val failureType: String,
    val workId: String?,
    val runAttemptCount: Int,
    val tags: String,
    val message: String,
    val stackTrace: String?,
    val recentLogs: String?,
    val uploaded: Boolean,
    val createdAt: Long,
)
