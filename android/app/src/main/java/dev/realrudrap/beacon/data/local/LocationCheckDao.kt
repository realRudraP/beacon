package dev.realrudrap.beacon.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.realrudrap.beacon.data.local.entity.LocationCheckEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LocationCheckDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(check: LocationCheckEntity)

    @Query("SELECT * FROM location_checks WHERE syncStatus = 'PENDING' ORDER BY capturedAt ASC")
    suspend fun getPendingChecks(): List<LocationCheckEntity>

    @Query("SELECT * FROM location_checks WHERE attendanceRecordId = :attendanceRecordId ORDER BY sequenceNumber ASC")
    fun getChecksForAttendance(attendanceRecordId: String): Flow<List<LocationCheckEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM location_checks WHERE attendanceRecordId = :attendanceRecordId AND sequenceNumber = :sequenceNumber)")
    suspend fun exists(attendanceRecordId: String, sequenceNumber: Int): Boolean

    @Query("UPDATE location_checks SET syncStatus = :status WHERE id = :id")
    suspend fun updateSyncStatus(id: String, status: String)

    @Query("UPDATE location_checks SET syncStatus = 'PENDING' WHERE syncStatus = 'FAILED'")
    suspend fun requeueFailedChecks()

    @Query("UPDATE location_checks SET syncStatus = :status WHERE attendanceRecordId = :attendanceRecordId")
    suspend fun updateStatusForAttendance(attendanceRecordId: String, status: String)
}
