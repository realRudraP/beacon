package dev.realrudrap.beacon.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.realrudrap.beacon.data.local.entity.AttendanceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AttendanceDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: AttendanceEntity)

    @Query("SELECT * FROM attendance_records WHERE date(clockInTime) = date('now')")
    fun getTodayRecord(): Flow<AttendanceEntity?>

    @Query("SELECT * FROM attendance_records WHERE syncStatus = 'PENDING'")
    suspend fun getPendingRecords(): List<AttendanceEntity>

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
}