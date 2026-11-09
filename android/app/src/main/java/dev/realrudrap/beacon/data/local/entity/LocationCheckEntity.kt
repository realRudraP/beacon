package dev.realrudrap.beacon.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "location_checks",
    indices = [
        Index(value = ["attendanceRecordId", "sequenceNumber"], unique = true),
        Index(value = ["syncStatus"])
    ]
)
data class LocationCheckEntity(
    @PrimaryKey val id: String,
    val attendanceRecordId: String,
    val capturedAt: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double?,
    val sequenceNumber: Int,
    val syncStatus: String,
    val createdAt: Long = System.currentTimeMillis(),
)
