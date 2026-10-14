// core-data/src/main/java/com/nikonlink/data/db/dao/TransferTaskDao.kt
package com.nikonlink.data.db.dao

import androidx.room.*
import com.nikonlink.data.db.entity.TransferTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferTaskDao {
    @Query("SELECT * FROM transfer_tasks ORDER BY created_at DESC")
    fun getAllTasks(): Flow<List<TransferTaskEntity>>

    @Query("SELECT * FROM transfer_tasks WHERE status = 'pending' ORDER BY created_at ASC")
    suspend fun getPendingTasks(): List<TransferTaskEntity>

    @Query("SELECT * FROM transfer_tasks WHERE status = 'transferring'")
    suspend fun getActiveTasks(): List<TransferTaskEntity>

    @Query("SELECT * FROM transfer_tasks WHERE id = :id")
    suspend fun getTaskById(id: Long): TransferTaskEntity?

    @Query("SELECT * FROM transfer_tasks WHERE remote_path = :remotePath AND file_name = :fileName LIMIT 1")
    suspend fun getDuplicateTask(remotePath: String, fileName: String): TransferTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: TransferTaskEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTasks(tasks: List<TransferTaskEntity>)

    @Update
    suspend fun updateTask(task: TransferTaskEntity)

    @Query("UPDATE transfer_tasks SET status = :status, progress_bytes = :progressBytes WHERE id = :id")
    suspend fun updateProgress(id: Long, status: String, progressBytes: Long)

    @Query("DELETE FROM transfer_tasks WHERE status = 'completed'")
    suspend fun clearCompleted()
}
