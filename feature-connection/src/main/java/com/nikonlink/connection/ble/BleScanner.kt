package com.nikonlink.connection.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * BLE Scanner for Nikon cameras. Scans for all BLE devices
 * and filters by name (Nikon cameras typically advertise as "NIKON*" or "Z *").
 */
class BleScanner {

    companion object {
        private const val TAG = "NikonLink-BLE"
    }

    data class ScanDevice(
        val device: BluetoothDevice,
        val name: String,
        val rssi: Int
    )

    fun scan(): Flow<List<ScanDevice>> = callbackFlow {
        Log.d(TAG, "Starting BLE scan...")
        val adapter: BluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            Log.e(TAG, "BluetoothAdapter is null — Bluetooth not available")
            close(Exception("Bluetooth not available"))
            return@callbackFlow
        }
        if (!adapter.isEnabled) {
            Log.e(TAG, "Bluetooth is disabled")
            close(Exception("Bluetooth is disabled"))
            return@callbackFlow
        }

        val scanner: BluetoothLeScanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "BluetoothLeScanner is null — BLE not supported")
            close(Exception("BLE not supported"))
            return@callbackFlow
        }

        val discoveredDevices = mutableMapOf<String, ScanDevice>()

        // No UUID filter — scan for ALL devices, we'll filter by name
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val name = device.name ?: "(unknown)"
                Log.d(TAG, "Found: $name [${device.address}] RSSI=${result.rssi}")
                discoveredDevices[device.address] = ScanDevice(
                    device = device,
                    name = name,
                    rssi = result.rssi
                )
                trySend(discoveredDevices.values.toList())
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                Log.d(TAG, "Batch scan: ${results?.size} results")
                results?.forEach { result ->
                    val device = result.device
                    val name = device.name ?: "(unknown)"
                    discoveredDevices[device.address] = ScanDevice(
                        device = device,
                        name = name,
                        rssi = result.rssi
                    )
                }
                trySend(discoveredDevices.values.toList())
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE scan failed with error code: $errorCode")
                close(Exception("BLE scan failed: $errorCode"))
            }
        }

        Log.d(TAG, "Starting LE scan (no filter)...")
        scanner.startScan(null, settings, callback)
        trySend(emptyList())

        awaitClose {
            Log.d(TAG, "Stopping BLE scan")
            scanner.stopScan(callback)
        }
    }
}
