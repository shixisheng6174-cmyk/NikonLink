package com.nikonlink.connection.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BleGattManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        val CLIENT_CHARACTERISTIC_CONFIG: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val TAG = "NikonLink-GATT"
    }

    sealed class GattEvent {
        data class Bonding(val address: String) : GattEvent()
        data class Bonded(val address: String) : GattEvent()
        data class Connected(val gatt: BluetoothGatt) : GattEvent()
        data class Disconnected(val address: String) : GattEvent()
        data class ServicesDiscovered(val gatt: BluetoothGatt) : GattEvent()
        data class CharacteristicRead(
            val characteristic: BluetoothGattCharacteristic, val value: ByteArray
        ) : GattEvent()
        data class CharacteristicWrite(
            val characteristic: BluetoothGattCharacteristic, val success: Boolean
        ) : GattEvent()
        data class CharacteristicChanged(
            val characteristic: BluetoothGattCharacteristic, val value: ByteArray
        ) : GattEvent()
        data class Error(val message: String) : GattEvent()
    }

    private var activeGatt: BluetoothGatt? = null

    fun connect(device: BluetoothDevice): Flow<GattEvent> = callbackFlow {
        val scope = this  // ProducerScope
        Log.d(TAG, "Starting GATT connect to ${device.name ?: device.address} (no bonding)")

        // Nikon SnapBridge handles auth at app layer — skip OS bonding
        connectGattInternal(device, scope)

        awaitClose {
            Log.d(TAG, "Closing GATT connection")
            activeGatt?.disconnect()
            activeGatt?.close()
            activeGatt = null
        }
    }

    private fun connectGattInternal(device: BluetoothDevice, scope: kotlinx.coroutines.channels.ProducerScope<GattEvent>) {
        Log.d(TAG, "Initiating GATT connect to ${device.address}")
        val gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                Log.d(TAG, "Connection state: $newState status=$status")
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        scope.trySend(GattEvent.Connected(gatt))
                        gatt.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        scope.trySend(GattEvent.Disconnected(gatt.device.address))
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                Log.d(TAG, "Services discovered: status=$status")
                if (status == BluetoothGatt.GATT_SUCCESS)
                    scope.trySend(GattEvent.ServicesDiscovered(gatt))
                else
                    scope.trySend(GattEvent.Error("Service discovery failed: $status"))
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS)
                    scope.trySend(GattEvent.CharacteristicRead(c, value))
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int
            ) {
                scope.trySend(GattEvent.CharacteristicWrite(c, status == BluetoothGatt.GATT_SUCCESS))
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray
            ) {
                scope.trySend(GattEvent.CharacteristicChanged(c, value))
            }
        })
        activeGatt = gatt
    }

    fun enableNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG) ?: return false
        gatt.setCharacteristicNotification(characteristic, true)
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return gatt.writeDescriptor(descriptor)
    }

    fun readCharacteristic(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean =
        gatt.readCharacteristic(characteristic)

    fun writeCharacteristic(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray): Boolean {
        characteristic.value = value
        return gatt.writeCharacteristic(characteristic)
    }

    fun disconnect() {
        activeGatt?.disconnect()
        activeGatt?.close()
        activeGatt = null
    }
}
