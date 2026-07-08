package dev.realrudrap.beacon.domain.repository

import dev.realrudrap.beacon.data.local.entity.AttendanceEntity
import kotlinx.coroutines.flow.Flow

interface AttendanceRepository {
    val todayRecord: Flow<AttendanceEntity?>
    suspend fun clockIn(lat: Double, long: Double, photoLocalPath: String?): Result<Unit>
    suspend fun clockOut(id: String, lat: Double, long: Double, photoLocalPath: String?): Result<Unit>
    suspend fun hasClockedInToday(): Boolean
    suspend fun hasClockedOutToday(): Boolean
    suspend fun syncPendingRecords(): Result<Unit>
    suspend fun getRecordsForMonth(startOfMonth: String, endOfMonth: String): List<AttendanceEntity>
}