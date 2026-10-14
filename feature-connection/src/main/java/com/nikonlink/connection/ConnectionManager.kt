package com.nikonlink.connection

import android.bluetooth.BluetoothDevice
import android.util.Log
import com.nikonlink.common.ConnectionState
import com.nikonlink.connection.ble.BleGattManager
import com.nikonlink.connection.ble.BleScanner
import com.nikonlink.connection.ble.SnapBridgeBleProtocol
import com.nikonlink.connection.wifi.PtpIpClient
import com.nikonlink.connection.wifi.WifiConnectionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectionManager @Inject constructor(
    private val bleScanner: BleScanner,
    private val bleGattManager: BleGattManager,
    private val wifiConnectionManager: WifiConnectionManager,
    private val ptpIpClient: PtpIpClient
) {
    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _discoveredCameras = MutableStateFlow<List<BleScanner.ScanDevice>>(emptyList())
    val discoveredCameras: StateFlow<List<BleScanner.ScanDevice>> = _discoveredCameras.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var scanJob: Job? = null
    private var gattJob: Job? = null
    private var wifiJob: Job? = null
    private var heartbeatJob: Job? = null
    private var directConnectJob: Job? = null
    private var currentGatt: android.bluetooth.BluetoothGatt? = null
    private var pendingWifiSsid: String? = null
    private var pendingWifiPassword: String? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            ptpIpClient.connected.collect { connected ->
                if (!connected && _connectionState.value == ConnectionState.WiFiTransfer) {
                    _lastError.value = ptpIpClient.lastError ?: "相机连接已断开，请重新连接"
                    _connectionState.value = ConnectionState.Disconnected
                }
            }
        }
    }

    /** Start scanning for nearby Nikon cameras. */
    fun startScanning() {
        Log.d(TAG, "startScanning called, current state=${_connectionState.value}")
        if (_connectionState.value != ConnectionState.Disconnected) {
            Log.w(TAG, "Cannot start scan — not disconnected (state=${_connectionState.value})")
            return
        }
        _connectionState.value = ConnectionState.BLEScanning

        scanJob?.cancel()
        scanJob = scope.launch {
            bleScanner.scan()
                .catch { e ->
                    Log.e(TAG, "Scan error: ${e.message}", e)
                    _connectionState.value = ConnectionState.Disconnected
                    _discoveredCameras.value = emptyList()
                    _lastError.value = "BLE scan failed: ${e.message}"
                }
                .collect { devices ->
                    Log.d(TAG, "Scan found ${devices.size} device(s)")
                    _discoveredCameras.value = devices
                }
        }
    }

    /** Stop scanning. */
    fun stopScanning() {
        scanJob?.cancel()
        scanJob = null
        if (_connectionState.value == ConnectionState.BLEScanning) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    /** Connect to a specific camera device via BLE. */
    fun connectToCamera(device: BluetoothDevice) {
        stopScanning()
        _connectionState.value = ConnectionState.BLEConnecting

        gattJob?.cancel()
        gattJob = scope.launch {
            bleGattManager.connect(device).collect { event ->
                when (event) {
                    is BleGattManager.GattEvent.Bonding -> {
                        Log.d(TAG, "Pairing with camera...")
                    }
                    is BleGattManager.GattEvent.Bonded -> {
                        Log.d(TAG, "Camera paired, connecting GATT...")
                    }
                    is BleGattManager.GattEvent.Connected -> {
                        currentGatt = event.gatt
                        _connectionState.value = ConnectionState.BLEConnected
                        startHeartbeat()
                    }
                    is BleGattManager.GattEvent.Disconnected -> {
                        currentGatt = null
                        _connectionState.value = ConnectionState.Disconnected
                        stopHeartbeat()
                    }
                    is BleGattManager.GattEvent.ServicesDiscovered -> {
                        // GATT ready — request WiFi credentials from camera
                        Log.d(TAG, "BLE Services discovered, requesting WiFi config")
                        currentGatt = event.gatt
                        requestWifiConfigViaBle(event.gatt)
                    }
                    is BleGattManager.GattEvent.CharacteristicChanged -> {
                        // Parse WiFi config or camera status
                        handleBleNotification(event)
                    }
                    else -> { /* handled by callers */ }
                }
            }
        }
    }

    /**
     * Connect PTP-IP directly when the phone is already on the camera's WiFi network.
     * This skips the BLE handshake — useful when the user has manually connected to camera WiFi.
     */
    fun connectPtpIpDirectly(cameraIp: String = "192.168.1.1") {
        if (directConnectJob?.isActive == true) return
        _lastError.value = null
        _connectionState.value = ConnectionState.WiFiConnecting
        directConnectJob = scope.launch {
            Log.d("NikonLink", "PTP-IP: connecting to $cameraIp:${PtpIpClient.PTP_IP_PORT}...")
            ptpIpClient.connect(cameraIp)
                .onSuccess {
                    Log.d("NikonLink", "PTP-IP: connected successfully")
                    _connectionState.value = ConnectionState.WiFiTransfer
                    _lastError.value = null
                }
                .onFailure { e ->
                    Log.e("NikonLink", "PTP-IP: connection failed: ${e.message}", e)
                    _connectionState.value = ConnectionState.Disconnected
                    _lastError.value = "Connection failed: ${e.message ?: "Unknown error"}"
                }
        }
    }

    fun clearError() {
        _lastError.value = null
    }

    /** Get the PTP-IP client for direct use (e.g., file browsing). */
    fun getPtpIpClient(): PtpIpClient = ptpIpClient

    /** Request WiFi handshake. Camera returns SSID/password via BLE, app connects. */
    fun requestWifiConnection(ssid: String, password: String) {
        if (_connectionState.value != ConnectionState.BLEConnected &&
            _connectionState.value != ConnectionState.WiFiHandshake) return

        _connectionState.value = ConnectionState.WiFiHandshake

        wifiJob?.cancel()
        wifiJob = scope.launch {
            wifiConnectionManager.connectToCamera(ssid, password).collect { event ->
                when (event) {
                    is WifiConnectionManager.WifiEvent.Connected -> {
                        _connectionState.value = ConnectionState.WiFiConnected
                        ptpIpClient.connect("192.168.1.1")
                            .onSuccess { _connectionState.value = ConnectionState.WiFiTransfer }
                            .onFailure { _connectionState.value = ConnectionState.WiFiConnected }
                    }
                    is WifiConnectionManager.WifiEvent.Disconnected -> {
                        _connectionState.value = ConnectionState.BLEConnected
                        ptpIpClient.disconnect()
                    }
                    is WifiConnectionManager.WifiEvent.Error -> {
                        _connectionState.value = ConnectionState.BLEConnected
                    }
                }
            }
        }
    }

    /** Disconnect everything. */
    fun disconnectAll() {
        directConnectJob?.cancel()
        _connectionState.value = ConnectionState.Disconnected
        stopHeartbeat()
        wifiJob?.cancel()
        gattJob?.cancel()
        scanJob?.cancel()
        ptpIpClient.disconnect()
        wifiConnectionManager.disconnect()
        bleGattManager.disconnect()
        currentGatt = null
        _connectionState.value = ConnectionState.Disconnected
    }

    /**
     * Request WiFi configuration from camera via BLE.
     * The camera responds with SSID and password via a BLE notification.
     */
    private fun requestWifiConfigViaBle(gatt: android.bluetooth.BluetoothGatt) {
        val service = gatt.getService(SnapBridgeBleProtocol.SERVICE_UUID) ?: run {
            Log.w(TAG, "SnapBridge service not found")
            return
        }
        val wifiChar = service.getCharacteristic(SnapBridgeBleProtocol.CHAR_WIFI_CONFIG) ?: run {
            Log.w(TAG, "WiFi config characteristic not found")
            return
        }
        // Enable notifications on WiFi config characteristic
        bleGattManager.enableNotification(gatt, wifiChar)
        // Also try reading it directly (some cameras return it on read)
        bleGattManager.readCharacteristic(gatt, wifiChar)
        Log.d(TAG, "Requested WiFi config via BLE")
    }

    /**
     * Handle incoming BLE notifications — parse WiFi credentials or status updates.
     */
    private fun handleBleNotification(event: BleGattManager.GattEvent.CharacteristicChanged) {
        val uuid = event.characteristic.uuid
        Log.d(TAG, "BLE notification: $uuid, ${event.value.size} bytes")
        when (uuid) {
            SnapBridgeBleProtocol.CHAR_WIFI_CONFIG -> {
                val data = String(event.value, Charsets.UTF_8)
                Log.d(TAG, "WiFi config received: $data")
                // Format: "SSID\tPASSWORD" or JSON
                val parts = data.split("\t")
                if (parts.size >= 2) {
                    pendingWifiSsid = parts[0].trim()
                    pendingWifiPassword = parts[1].trim()
                    Log.d(TAG, "Got WiFi: SSID=${pendingWifiSsid}")
                    // Auto-connect to camera WiFi
                    requestWifiConnection(pendingWifiSsid!!, pendingWifiPassword!!)
                }
            }
            SnapBridgeBleProtocol.CHAR_CAMERA_STATUS -> {
                // Camera status update — ignore for now
            }
        }
    }

    companion object {
        private const val TAG = "NikonLink-ConMgr"
    }

    /** Send keep-alive heartbeat via BLE. Auto-reconnect if no response for 5s. */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            var missedBeats = 0
            while (isActive) {
                delay(1000)
                missedBeats++
                if (missedBeats >= 5) {
                    _connectionState.value = ConnectionState.Disconnected
                    stopHeartbeat()
                    startScanning()
                    break
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }
}
