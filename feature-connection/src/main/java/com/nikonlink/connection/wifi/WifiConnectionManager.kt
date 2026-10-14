package com.nikonlink.connection.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WifiConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val wifiManager: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager: ConnectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    sealed class WifiEvent {
        data object Connected : WifiEvent()
        data object Disconnected : WifiEvent()
        data class Error(val message: String) : WifiEvent()
    }

    /**
     * Connect to a camera WiFi hotspot.
     * On Android 10+ (API 29), uses WifiNetworkSpecifier for peer-to-peer connection
     * without requiring the app to hold the device's primary WiFi connection.
     */
    fun connectToCamera(ssid: String, password: String): Flow<WifiEvent> = callbackFlow {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val specifier = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(password)
                .build()

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    connectivityManager.bindProcessToNetwork(network)
                    trySend(WifiEvent.Connected)
                }

                override fun onLost(network: Network) {
                    connectivityManager.bindProcessToNetwork(null)
                    trySend(WifiEvent.Disconnected)
                }

                override fun onUnavailable() {
                    trySend(WifiEvent.Error("Camera WiFi unavailable"))
                }
            }

            connectivityManager.requestNetwork(request, callback)

            awaitClose {
                connectivityManager.unregisterNetworkCallback(callback)
                connectivityManager.bindProcessToNetwork(null)
            }
        } else {
            val suggestion = WifiNetworkSuggestion.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(password)
                .build()

            val status = wifiManager.addNetworkSuggestions(listOf(suggestion))
            if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                trySend(WifiEvent.Connected)
            } else {
                trySend(WifiEvent.Error("Failed to add network suggestion: $status"))
            }

            awaitClose {
                wifiManager.removeNetworkSuggestions(listOf(suggestion))
            }
        }
    }

    /** Returns the camera WiFi hotspot IP. Nikon cameras typically use 192.168.1.1. */
    fun getCameraIp(): String = "192.168.1.1"

    /** Disconnect from camera WiFi and release the network binding. */
    fun disconnect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            connectivityManager.bindProcessToNetwork(null)
        }
    }
}
