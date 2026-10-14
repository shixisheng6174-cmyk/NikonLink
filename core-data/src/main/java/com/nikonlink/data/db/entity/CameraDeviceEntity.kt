package com.nikonlink.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "camera_devices")
data class CameraDeviceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val model: String,
    @ColumnInfo(name = "ble_address")
    val bleAddress: String,
    @ColumnInfo(name = "wifi_ssid")
    val wifiSsid: String? = null,
    @ColumnInfo(name = "wifi_password")
    val wifiPassword: String? = null,
    @ColumnInfo(name = "last_connected")
    val lastConnected: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "firmware_version")
    val firmwareVersion: String? = null,
    @ColumnInfo(name = "protocol")
    val protocol: String = "snapbridge"
)
