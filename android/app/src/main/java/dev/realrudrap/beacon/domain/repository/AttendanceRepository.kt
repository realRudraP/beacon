package dev.realrudrap.beacon.domain.repository

import dev.realrudrap.beacon.data.local.entity.AttendanceEntity
import kotlinx.coroutines.flow.Flow
import java.time.YearMonth

interface AttendanceRepository {
    val todayRecord: Flow<AttendanceEntity?>
    val attendanceHistory: Flow<List<AttendanceEntity>>
    suspend fun clockIn(lat: Double, long: Double, photoLocalPath: String?): Result<Unit>
    suspend fun clockOut(id: String, lat: Double, long: Double, photoLocalPath: String?): Result<Unit>
    suspend fun hasClockedInToday(): Boolean
    suspend fun hasClockedOutToday(): Boolean
    suspend fun getOpenRecord(): AttendanceEntity?
    suspend fun syncPendingRecords(): Result<Unit>
    suspend fun refreshAttendanceHistory(month: YearMonth = YearMonth.now()): Result<Unit>
    suspend fun getRecordsForMonth(startOfMonth: String, endOfMonth: String): List<AttendanceEntity>
}