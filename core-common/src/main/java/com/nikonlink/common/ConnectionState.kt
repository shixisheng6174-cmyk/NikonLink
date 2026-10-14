// core-common/src/main/java/com/nikonlink/common/ConnectionState.kt
package com.nikonlink.common

enum class ConnectionState {
    Disconnected,
    BLEScanning,
    BLEConnecting,
    BLEConnected,
    WiFiHandshake,
    WiFiConnecting,
    WiFiConnected,
    WiFiTransfer;

    val isBleConnected: Boolean
        get() = this == BLEConnected || this == WiFiHandshake

    val isWifiConnected: Boolean
        get() = this == WiFiConnected || this == WiFiTransfer

    val isHighSpeedAvailable: Boolean
        get() = isWifiConnected
}
