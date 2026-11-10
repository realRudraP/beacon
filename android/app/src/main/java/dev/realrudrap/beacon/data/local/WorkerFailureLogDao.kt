package dev.realrudrap.beacon.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.realrudrap.beacon.data.local.entity.WorkerFailureLogEntity

@Dao
interface WorkerFailureLogDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(log: WorkerFailureLogEntity)

    @Query("SELECT * FROM worker_failure_logs WHERE uploaded = 0 ORDER BY createdAt ASC")
    suspend fun getPending(): List<WorkerFailureLogEntity>

    @Query("UPDATE worker_failure_logs SET uploaded = 1 WHERE id = :id")
    suspend fun markUploaded(id: String)

    @Query("DELETE FROM worker_failure_logs WHERE uploaded = 1 AND createdAt < :cutoff")
    suspend fun deleteUploadedBefore(cutoff: Long)

    @Query(
        "DELETE FROM worker_failure_logs WHERE id NOT IN " +
                "(SELECT id FROM worker_failure_logs ORDER BY createdAt DESC LIMIT :keep)"
    )
    suspend fun trimToMostRecent(keep: Int)
}
