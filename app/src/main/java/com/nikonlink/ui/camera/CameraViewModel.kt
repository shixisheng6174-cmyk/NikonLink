package com.nikonlink.ui.camera

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nikonlink.common.ConnectionState
import com.nikonlink.connection.ConnectionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CameraViewModel @Inject constructor(
    private val connectionManager: ConnectionManager,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    data class CameraUiState(
        val connectionState: ConnectionState = ConnectionState.Disconnected,
        val connectedCameraName: String? = null,
        val lastError: String? = null,
        val isOnCameraWifi: Boolean = false,
        val currentWifiSsid: String? = null,
        /** Best guess for the camera's IP: in AP mode the DHCP gateway is the camera. */
        val cameraIpHint: String = "192.168.1.1"
    )

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<String>()
    val events: SharedFlow<String> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            connectionManager.connectionState.collect { state ->
                _uiState.update { it.copy(connectionState = state) }
            }
        }
        viewModelScope.launch {
            connectionManager.lastError.collect { error ->
                _uiState.update { it.copy(lastError = error) }
                if (error != null) {
                    _events.emit("连接出错：$error")
                }
            }
        }
        detectCameraWifi()
    }

    fun connectPtpIpDirectly(ip: String) {
        _uiState.update { it.copy(connectedCameraName = ip) }
        connectionManager.connectPtpIpDirectly(ip)
    }

    fun disconnect() {
        _uiState.update { it.copy(connectedCameraName = null, lastError = null) }
        connectionManager.disconnectAll()
    }

    fun refreshWifiState() {
        detectCameraWifi()
    }

    private fun detectCameraWifi() {
        try {
            val wifiManager =
                appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            // Without ACCESS_FINE_LOCATION the SSID reads as <unknown ssid>,
            // so base the detection on DHCP data, which needs no permission.
            val ssid = wifiManager?.connectionInfo?.ssid?.trim('"') ?: ""
            val dhcp = wifiManager?.dhcpInfo
            val gateway = formatIp(dhcp?.gateway ?: 0)
            val ownIp = formatIp(dhcp?.ipAddress ?: 0)
            _uiState.update {
                it.copy(
                    isOnCameraWifi = ownIp != "0.0.0.0",
                    currentWifiSsid = ssid.takeIf { s -> s.isNotEmpty() && s != "<unknown ssid>" },
                    cameraIpHint = if (gateway != "0.0.0.0") gateway else "192.168.1.1"
                )
            }
        } catch (e: Exception) {
            Log.w("NikonLink", "WiFi detect failed: ${e.message}")
        }
    }

    private fun formatIp(raw: Int): String =
        "${raw and 0xFF}.${(raw shr 8) and 0xFF}.${(raw shr 16) and 0xFF}.${(raw shr 24) and 0xFF}"
}
