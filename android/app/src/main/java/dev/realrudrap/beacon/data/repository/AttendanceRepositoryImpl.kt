package dev.realrudrap.beacon.data.repository

import dev.realrudrap.beacon.data.local.AttendanceDao
import dev.realrudrap.beacon.data.local.entity.AttendanceEntity
import dev.realrudrap.beacon.data.local.entity.AttendanceSyncStatus
import dev.realrudrap.beacon.domain.repository.AttendanceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

class AttendanceRepositoryImpl @Inject constructor(
    private val attendanceDao: AttendanceDao
) : AttendanceRepository {

    override val todayRecord: Flow<AttendanceEntity?> = attendanceDao.getTodayRecord()

    @OptIn(ExperimentalTime::class)
    override suspend fun clockIn(lat: Double, long: Double, photoLocalPath: String?): Result<Unit> {
        return try {
            val entity = AttendanceEntity(
                id = UUID.randomUUID().toString(),
                clockInTime = Clock.System.now().toString(),
                clockInLat = lat,
                clockInLong = long,
                clockInPhotoLocalPath = photoLocalPath,
                clockInPhotoServerUri = null,
                clockOutTime = null,
                clockOutLat = null,
                clockOutLong = null,
                clockOutPhotoLocalPath = null,
                clockOutPhotoServerUri = null,
                syncStatus = AttendanceSyncStatus.PENDING.name
            )
            attendanceDao.insert(entity)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    @OptIn(ExperimentalTime::class)
    override suspend fun clockOut(id: String, lat: Double, long: Double, photoLocalPath: String?): Result<Unit> {
        return try {
            attendanceDao.updateClockOut(
                id = id,
                clockOutTime = Clock.System.now().toString(),
                lat = lat,
                long = long,
                localPath = photoLocalPath
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun hasClockedInToday(): Boolean {
        return attendanceDao.getTodayRecord().first() != null
    }
    override suspend fun hasClockedOutToday(): Boolean {
        return attendanceDao.getTodayRecord().first()?.clockOutTime != null
    }

    override suspend fun syncPendingRecords(): Result<Unit> = Result.success(Unit)

    override suspend fun getRecordsForMonth(
        startOfMonth: String,
        endOfMonth: String
    ): List<AttendanceEntity> {
        return attendanceDao.getRecordsForMonth(startOfMonth, endOfMonth)
    }
}