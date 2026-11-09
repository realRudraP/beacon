package dev.realrudrap.beacon.data.repository

import dev.realrudrap.beacon.data.local.AttendanceDao
import dev.realrudrap.beacon.data.local.entity.AttendanceEntity
import dev.realrudrap.beacon.data.local.entity.AttendanceSyncStatus
import dev.realrudrap.beacon.data.remote.AttendanceApiService
import dev.realrudrap.beacon.data.remote.dto.AttendanceResponse
import dev.realrudrap.beacon.domain.repository.AttendanceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import java.time.YearMonth

class AttendanceRepositoryImpl @Inject constructor(
    private val attendanceDao: AttendanceDao,
    private val attendanceApiService: AttendanceApiService // Injected network service
) : AttendanceRepository {

    override val todayRecord: Flow<AttendanceEntity?> = attendanceDao.getTodayRecord()

    override val attendanceHistory: Flow<List<AttendanceEntity>> = attendanceDao.getAllRecordsFlow()

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

    override suspend fun getOpenRecord(): AttendanceEntity? = attendanceDao.getOpenRecord()

    override suspend fun hasClockedInToday(): Boolean {
        return attendanceDao.getTodayRecord().first() != null
    }

    override suspend fun hasClockedOutToday(): Boolean {
        return attendanceDao.getTodayRecord().first()?.clockOutTime != null
    }

    override suspend fun syncPendingRecords(): Result<Unit> = Result.success(Unit)

    override suspend fun refreshAttendanceHistory(month: YearMonth): Result<Unit> {
        return try {
            val fromDate = month.atDay(1)
            val toDate = month.atEndOfMonth()
            val formatter = DateTimeFormatter.ISO_LOCAL_DATE

            val response = attendanceApiService.getAttendance(
                fromDate = fromDate.format(formatter),
                toDate = toDate.format(formatter)
            )

            if (response.isSuccessful) {
                val serverRecords = response.body() ?: emptyList()
                val entities = serverRecords.map { it.toEntity() }
                attendanceDao.upsertServerRecords(entities)
                Result.success(Unit)
            } else {
                Timber.e("Failed to fetch attendance history: code=${response.code()}")
                Result.failure(Exception("Server returned ${response.code()}"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Exception fetching attendance history for $month")
            Result.failure(e)
        }
    }
    override suspend fun getRecordsForMonth(
        startOfMonth: String,
        endOfMonth: String
    ): List<AttendanceEntity> {
        return attendanceDao.getRecordsForMonth(startOfMonth, endOfMonth)
    }

    private fun AttendanceResponse.toEntity(): AttendanceEntity {
        val cleanClockOut = if (this.checkOut?.startsWith("0001-01-01") == true) null else this.checkOut

        return AttendanceEntity(
            id = this.id,
            clockInTime = this.checkIn,
            clockInLat = this.checkInLat ?: 0.0,
            clockInLong = this.checkInLong ?: 0.0,
            clockInPhotoLocalPath = null,
            clockInPhotoServerUri = this.checkInPhotoUri,
            clockOutTime = cleanClockOut,
            clockOutLat = this.checkOutLat,
            clockOutLong = this.checkOutLong,
            clockOutPhotoLocalPath = null,
            clockOutPhotoServerUri = this.checkOutPhotoUri,
            syncStatus = AttendanceSyncStatus.SYNCED.name,
            serverConfirmedDate = this.date,
            serverSyncedAt = System.currentTimeMillis()
        )
    }
}