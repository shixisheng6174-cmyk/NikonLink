// core-data/src/main/java/com/nikonlink/data/db/NikonLinkDatabase.kt
package com.nikonlink.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.nikonlink.data.db.dao.CameraDeviceDao
import com.nikonlink.data.db.dao.TransferTaskDao
import com.nikonlink.data.db.entity.CameraDeviceEntity
import com.nikonlink.data.db.entity.TransferTaskEntity

@Database(
    entities = [CameraDeviceEntity::class, TransferTaskEntity::class],
    version = 1,
    exportSchema = false
)
abstract class NikonLinkDatabase : RoomDatabase() {
    abstract fun cameraDeviceDao(): CameraDeviceDao
    abstract fun transferTaskDao(): TransferTaskDao
}
