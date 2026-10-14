package com.nikonlink.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transfer_tasks")
data class TransferTaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "camera_id")
    val cameraId: Long,
    @ColumnInfo(name = "remote_path")
    val remotePath: String,
    @ColumnInfo(name = "file_name")
    val fileName: String,
    @ColumnInfo(name = "file_size")
    val fileSize: Long = 0,
    @ColumnInfo(name = "file_type")
    val fileType: String,
    @ColumnInfo(name = "status")
    val status: String = "pending",
    @ColumnInfo(name = "progress_bytes")
    val progressBytes: Long = 0,
    @ColumnInfo(name = "local_uri")
    val localUri: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "completed_at")
    val completedAt: Long? = null,
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null
)
