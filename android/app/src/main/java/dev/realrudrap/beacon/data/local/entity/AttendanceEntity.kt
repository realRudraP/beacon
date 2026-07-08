package dev.realrudrap.beacon.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class AttendanceSyncStatus {
    PENDING, SYNCED, FAILED
}

@Entity(tableName = "attendance_records")
data class AttendanceEntity (
    @PrimaryKey val id: String,
    val clockInTime: String,
    val clockInLat: Double,
    val clockInLong: Double,
    val clockInPhotoLocalPath: String?,
    val clockInPhotoServerUri: String?,
    val clockOutTime: String?,
    val clockOutLat: Double?,
    val clockOutLong: Double?,
    val clockOutPhotoLocalPath: String?,
    val clockOutPhotoServerUri: String?,
    val syncStatus: String,
    val createdAt: Long = System.currentTimeMillis(),
    val serverConfirmedDate: String? = null,
    val serverSyncedAt: Long? = null,
)