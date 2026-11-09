package dev.realrudrap.beacon.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import dev.realrudrap.beacon.data.local.entity.AttendanceEntity
import dev.realrudrap.beacon.data.local.entity.AttendanceSyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface AttendanceDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: AttendanceEntity)

    @Update
    suspend fun update(record: AttendanceEntity)

    @Query("SELECT * FROM attendance_records WHERE id = :id LIMIT 1")
    suspend fun getRecordById(id: String): AttendanceEntity?

    @Query("SELECT * FROM attendance_records ORDER BY clockInTime DESC")
    fun getAllRecordsFlow(): Flow<List<AttendanceEntity>>

    @Query("SELECT * FROM attendance_records WHERE date(clockInTime) = date('now')")
    fun getTodayRecord(): Flow<AttendanceEntity?>

    @Query("SELECT * FROM attendance_records WHERE clockOutTime IS NULL ORDER BY clockInTime DESC LIMIT 1")
    suspend fun getOpenRecord(): AttendanceEntity?

    @Query("SELECT * FROM attendance_records WHERE syncStatus = 'PENDING'")
    suspend fun getPendingRecords(): List<AttendanceEntity>

    @Query("UPDATE attendance_records SET syncStatus = 'PENDING' WHERE syncStatus = 'FAILED'")
    suspend fun requeueFailedRecords()

    @Query("SELECT * FROM attendance_records WHERE clockInTime BETWEEN :startOfMonth AND :endOfMonth")
    suspend fun getRecordsForMonth(startOfMonth: String, endOfMonth: String): List<AttendanceEntity>

    @Query("UPDATE attendance_records SET clockOutTime = :clockOutTime, clockOutLat = :lat, clockOutLong = :long, clockOutPhotoLocalPath = :localPath, syncStatus = 'PENDING' WHERE id = :id")
    suspend fun updateClockOut(id: String, clockOutTime: String, lat: Double, long: Double, localPath: String?)

    @Query("UPDATE attendance_records SET clockInPhotoServerUri = :uri WHERE id = :id")
    suspend fun updateClockInPhotoServerUri(id: String, uri: String)

    @Query("UPDATE attendance_records SET clockOutPhotoServerUri = :uri WHERE id = :id")
    suspend fun updateClockOutPhotoServerUri(id: String, uri: String)

    @Query("UPDATE attendance_records SET syncStatus = :status WHERE id = :id")
    suspend fun updateSyncStatus(id: String, status: String)

    /**
     * Safely merges records downloaded from the server into Room.
     * Prevents clobbering records that are currently PENDING sync locally.
     */
    @Transaction
    suspend fun upsertServerRecords(records: List<AttendanceEntity>) {
        for (remote in records) {
            val local = getRecordById(remote.id)
            when {
                local == null -> {
                    insert(remote)
                }
                // Only overwrite if local is already SYNCED (or FAILED retry) to receive server/admin overrides
                local.syncStatus != AttendanceSyncStatus.PENDING.name -> {
                    update(remote.copy(
                        // Retain local photo paths if they exist
                        clockInPhotoLocalPath = local.clockInPhotoLocalPath,
                        clockOutPhotoLocalPath = local.clockOutPhotoLocalPath,
                        createdAt = local.createdAt
                    ))
                }
            }
        }
    }
}