// core-data/src/main/java/com/nikonlink/data/db/dao/CameraDeviceDao.kt
package com.nikonlink.data.db.dao

import androidx.room.*
import com.nikonlink.data.db.entity.CameraDeviceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CameraDeviceDao {
    @Query("SELECT * FROM camera_devices ORDER BY last_connected DESC")
    fun getAllCameras(): Flow<List<CameraDeviceEntity>>

    @Query("SELECT * FROM camera_devices WHERE id = :id")
    suspend fun getCameraById(id: Long): CameraDeviceEntity?

    @Query("SELECT * FROM camera_devices WHERE ble_address = :bleAddress LIMIT 1")
    suspend fun getCameraByBleAddress(bleAddress: String): CameraDeviceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCamera(camera: CameraDeviceEntity): Long

    @Update
    suspend fun updateCamera(camera: CameraDeviceEntity)

    @Delete
    suspend fun deleteCamera(camera: CameraDeviceEntity)

    @Query("UPDATE camera_devices SET last_connected = :timestamp WHERE id = :id")
    suspend fun updateLastConnected(id: Long, timestamp: Long = System.currentTimeMillis())
}
