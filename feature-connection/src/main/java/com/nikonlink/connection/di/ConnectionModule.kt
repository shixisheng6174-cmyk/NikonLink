package com.nikonlink.connection.di

import com.nikonlink.connection.ConnectionManager
import com.nikonlink.connection.ble.BleScanner
import com.nikonlink.connection.ble.BleGattManager
import com.nikonlink.connection.ble.SnapBridgeBleProtocol
import com.nikonlink.connection.wifi.WifiConnectionManager
import com.nikonlink.connection.wifi.PtpIpClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext

@Module
@InstallIn(SingletonComponent::class)
object ConnectionModule {

    @Provides
    @Singleton
    fun provideBleScanner(): BleScanner = BleScanner()

    @Provides
    @Singleton
    fun providePtpIpClient(@ApplicationContext context: Context): PtpIpClient = PtpIpClient { host ->
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networks = manager.allNetworks.filter {
            manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        val address = java.net.InetAddress.getByName(host)
        val wifi = networks.firstOrNull { network ->
            manager.getLinkProperties(network)?.routes?.any { !it.isDefaultRoute && it.matches(address) } == true
        } ?: networks.singleOrNull() ?: throw java.io.IOException("请先在系统 WLAN 中连接相机 Wi-Fi")
        // HyperOS can retain mobile data as the default network when the camera has no Internet.
        wifi.socketFactory.createSocket()
    }

    @Provides
    @Singleton
    fun provideSnapBridgeBleProtocol(): SnapBridgeBleProtocol = SnapBridgeBleProtocol()

    @Provides
    @Singleton
    fun provideConnectionManager(
        bleScanner: BleScanner,
        bleGattManager: BleGattManager,
        wifiConnectionManager: WifiConnectionManager,
        ptpIpClient: PtpIpClient
    ): ConnectionManager = ConnectionManager(
        bleScanner, bleGattManager, wifiConnectionManager, ptpIpClient
    )
}
