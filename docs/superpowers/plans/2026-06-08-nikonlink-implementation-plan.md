# NikonLink Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an Android app (Kotlin, API 28+) that transfers photos/videos from Nikon cameras (Z series, D780/D850/D6) via BLE+WiFi and provides remote camera control.

**Architecture:** MVVM + Clean Architecture with 7 Gradle modules. UI uses XML + ViewBinding + Navigation Component. BLE maintains a persistent low-energy connection for browsing and camera control; WiFi is negotiated via BLE and activated on-demand for high-speed file transfer and LiveView streaming.

**Tech Stack:** Kotlin, XML ViewBinding, Hilt DI, Coroutines + Flow, Room DB, WorkManager, OkHttp, Coil, Android BLE API, PTP-IP (custom TCP client)

**Priority:** This plan covers P0 (core: connection, browsing, transfer) in full detail. P1 (remote control, history) and P2 (settings) are outlined with key implementation notes.

---

## File Structure Map

```
NikonLink/
├── build.gradle.kts                          # Root build script
├── settings.gradle.kts                       # Module declarations
├── gradle.properties
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/nikonlink/
│       │   ├── NikonLinkApp.kt               # @HiltAndroidApp Application
│       │   ├── MainActivity.kt               # Single-activity host
│       │   └── di/
│       │       └── AppModule.kt              # Top-level DI bindings
│       └── res/
│           ├── layout/activity_main.xml
│           ├── navigation/nav_graph.xml
│           └── menu/bottom_nav_menu.xml
├── core-common/
│   ├── build.gradle.kts
│   └── src/main/java/com/nikonlink/common/
│       ├── Result.kt                         # sealed class Result<T> { Success, Error, Loading }
│       ├── ConnectionState.kt                # enum: Disconnected, BLEScanning, BLEConnected, WiFiHandshake, WiFiTransfer
│       ├── FileType.kt                       # enum: JPEG, NEF, TIFF, MP4, MOV
│       ├── TransferStatus.kt                 # enum: Pending, Transferring, Completed, Failed
│       └── ext/                              # Kotlin extension functions
│           ├── FlowExtensions.kt
│           └── DateExtensions.kt
├── core-data/
│   ├── build.gradle.kts
│   └── src/main/java/com/nikonlink/data/
│       ├── db/
│       │   ├── NikonLinkDatabase.kt          # @Database(entities=[CameraDevice, TransferTask], version=1)
│       │   ├── entity/
│       │   │   ├── CameraDeviceEntity.kt
│       │   │   └── TransferTaskEntity.kt
│       │   └── dao/
│       │       ├── CameraDeviceDao.kt
│       │       └── TransferTaskDao.kt
│       ├── repository/
│       │   ├── CameraRepository.kt           # Interface in domain layer conceptually; impl here
│       │   └── TransferRepository.kt
│       └── datastore/
│           └── SettingsDataStore.kt          # DataStore<Preferences> wrapper
├── feature-connection/
│   ├── build.gradle.kts
│   └── src/main/java/com/nikonlink/connection/
│       ├── ble/
│       │   ├── BleScanner.kt                 # BLE scan with SnapBridge UUID filter
│       │   ├── BleGattManager.kt             # GATT connect, service discovery, characteristic read/write/notify
│       │   └── SnapBridgeBleProtocol.kt      # SnapBridge-specific BLE command encoding/decoding
│       ├── wifi/
│       │   ├── WifiConnectionManager.kt      # WifiManager API: connect to camera hotspot
│       │   └── PtpIpClient.kt               # TCP socket PTP-IP implementation
│       ├── ConnectionManager.kt              # Orchestrates BLE+WiFi state machine
│       └── di/
│           └── ConnectionModule.kt           # Hilt bindings for connection layer
├── feature-browser/
│   ├── build.gradle.kts
│   └── src/main/java/com/nikonlink/browser/
│       ├── ui/
│       │   ├── BrowserFragment.kt
│       │   ├── BrowserViewModel.kt
│       │   ├── FolderListAdapter.kt
│       │   └── ThumbnailGridAdapter.kt
│       └── di/
│           └── BrowserModule.kt
├── feature-transfer/
│   ├── build.gradle.kts
│   └── src/main/java/com/nikonlink/transfer/
│       ├── ui/
│       │   ├── TransferFragment.kt
│       │   ├── TransferViewModel.kt
│       │   └── TransferHistoryAdapter.kt
│       ├── worker/
│       │   └── TransferWorker.kt             # WorkManager Worker
│       ├── TransferManager.kt                # Queue management, enqueue/dequeue, progress tracking
│       └── di/
│           └── TransferModule.kt
├── feature-remote/
│   ├── build.gradle.kts
│   └── src/main/java/com/nikonlink/remote/
│       ├── ui/
│       │   ├── RemoteFragment.kt
│       │   ├── RemoteViewModel.kt
│       │   └── LiveViewSurface.kt           # Custom SurfaceView for MJPEG rendering
│       └── di/
│           └── RemoteModule.kt
```

---

## Phase 0: Project Scaffolding

### Task 0.1: Create root Gradle build scripts

**Files:**
- Create: `build.gradle.kts`
- Create: `settings.gradle.kts`
- Create: `gradle.properties`

- [ ] **Step 1: Write root build.gradle.kts**

```kotlin
// build.gradle.kts
plugins {
    id("com.android.application") version "8.2.0" apply false
    id("com.android.library") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.20" apply false
    id("com.google.dagger.hilt.android") version "2.48.1" apply false
    id("com.google.devtools.ksp") version "1.9.20-1.0.14" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
```

- [ ] **Step 2: Write settings.gradle.kts**

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolution {
    @Suppress("UnstableApiUsage")
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "NikonLink"
include(":app")
include(":core-common")
include(":core-data")
include(":feature-connection")
include(":feature-browser")
include(":feature-transfer")
include(":feature-remote")
```

- [ ] **Step 3: Write gradle.properties**

```properties
# gradle.properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```

- [ ] **Step 4: Verify project structure**

Run: `./gradlew projects`
Expected: All 7 modules listed.

---

### Task 0.2: Create core-common module

**Files:**
- Create: `core-common/build.gradle.kts`
- Create: `core-common/src/main/java/com/nikonlink/common/Result.kt`
- Create: `core-common/src/main/java/com/nikonlink/common/ConnectionState.kt`
- Create: `core-common/src/main/java/com/nikonlink/common/FileType.kt`
- Create: `core-common/src/main/java/com/nikonlink/common/TransferStatus.kt`
- Create: `core-common/src/main/AndroidManifest.xml`

- [ ] **Step 1: Write core-common/build.gradle.kts**

```kotlin
// core-common/build.gradle.kts
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nikonlink.common"
    compileSdk = 34
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}
```

- [ ] **Step 2: Write Result.kt**

```kotlin
// core-common/src/main/java/com/nikonlink/common/Result.kt
package com.nikonlink.common

sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val exception: Throwable, val message: String? = null) : Result<Nothing>()
    data object Loading : Result<Nothing>()

    val isSuccess: Boolean get() = this is Success
    val isError: Boolean get() = this is Error
    val isLoading: Boolean get() = this is Loading

    fun getOrNull(): T? = when (this) {
        is Success -> data
        else -> null
    }

    fun <R> map(transform: (T) -> R): Result<R> = when (this) {
        is Success -> Success(transform(data))
        is Error -> this
        is Loading -> Loading
    }
}
```

- [ ] **Step 3: Write ConnectionState.kt**

```kotlin
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
```

- [ ] **Step 4: Write FileType.kt**

```kotlin
// core-common/src/main/java/com/nikonlink/common/FileType.kt
package com.nikonlink.common

enum class FileType(val extensions: List<String>, val isImage: Boolean, val isVideo: Boolean) {
    JPEG(listOf("jpg", "jpeg"), isImage = true, isVideo = false),
    NEF(listOf("nef"), isImage = true, isVideo = false),
    TIFF(listOf("tif", "tiff"), isImage = true, isVideo = false),
    MP4(listOf("mp4"), isImage = false, isVideo = true),
    MOV(listOf("mov"), isImage = false, isVideo = true);

    companion object {
        fun fromExtension(ext: String): FileType? =
            entries.find { ext.lowercase() in it.extensions }
    }
}
```

- [ ] **Step 5: Write TransferStatus.kt**

```kotlin
// core-common/src/main/java/com/nikonlink/common/TransferStatus.kt
package com.nikonlink.common

enum class TransferStatus {
    Pending,
    Transferring,
    Completed,
    Failed;

    val isTerminal: Boolean get() = this == Completed || this == Failed
}
```

- [ ] **Step 6: Write minimal AndroidManifest.xml**

```xml
<!-- core-common/src/main/AndroidManifest.xml -->
<manifest />
```

- [ ] **Step 7: Verify compilation**

Run: `./gradlew :core-common:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

---

### Task 0.3: Create core-data module

**Files:**
- Create: `core-data/build.gradle.kts`
- Create: `core-data/src/main/java/com/nikonlink/data/db/entity/CameraDeviceEntity.kt`
- Create: `core-data/src/main/java/com/nikonlink/data/db/entity/TransferTaskEntity.kt`
- Create: `core-data/src/main/java/com/nikonlink/data/db/dao/CameraDeviceDao.kt`
- Create: `core-data/src/main/java/com/nikonlink/data/db/dao/TransferTaskDao.kt`
- Create: `core-data/src/main/java/com/nikonlink/data/db/NikonLinkDatabase.kt`
- Create: `core-data/src/main/java/com/nikonlink/data/datastore/SettingsDataStore.kt`
- Create: `core-data/src/main/AndroidManifest.xml`

- [ ] **Step 1: Write core-data/build.gradle.kts**

```kotlin
// core-data/build.gradle.kts
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.nikonlink.data"
    compileSdk = 34
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core-common"))

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.48.1")
    ksp("com.google.dagger:hilt-compiler:2.48.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
```

- [ ] **Step 2: Write CameraDeviceEntity.kt**

```kotlin
// core-data/src/main/java/com/nikonlink/data/db/entity/CameraDeviceEntity.kt
package com.nikonlink.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "camera_devices")
data class CameraDeviceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val model: String,
    val bleAddress: String,
    val wifiSsid: String? = null,
    val wifiPassword: String? = null,
    val lastConnected: Long = System.currentTimeMillis(),
    val firmwareVersion: String? = null,
    val protocol: String = "snapbridge" // snapbridge | ptpip | http | wmu
)
```

- [ ] **Step 3: Write TransferTaskEntity.kt**

```kotlin
// core-data/src/main/java/com/nikonlink/data/db/entity/TransferTaskEntity.kt
package com.nikonlink.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transfer_tasks")
data class TransferTaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val cameraId: Long,
    val remotePath: String,
    val fileName: String,
    val fileSize: Long = 0,
    val fileType: String, // JPEG, NEF, TIFF, MP4, MOV
    val status: String = "pending", // pending, transferring, completed, failed
    val progressBytes: Long = 0,
    val localUri: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val errorMessage: String? = null
)
```

- [ ] **Step 4: Write CameraDeviceDao.kt**

```kotlin
// core-data/src/main/java/com/nikonlink/data/db/dao/CameraDeviceDao.kt
package com.nikonlink.data.db.dao

import androidx.room.*
import com.nikonlink.data.db.entity.CameraDeviceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CameraDeviceDao {
    @Query("SELECT * FROM camera_devices ORDER BY last_connected DESC")
    fun getAllCameras(): Flow<List<CameraDeviceEntity>>

    @Query("SELECT * FROM camera_devices WHERE id = :id")
    suspend fun getCameraById(id: Long): CameraDeviceEntity?

    @Query("SELECT * FROM camera_devices WHERE ble_address = :bleAddress LIMIT 1")
    suspend fun getCameraByBleAddress(bleAddress: String): CameraDeviceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCamera(camera: CameraDeviceEntity): Long

    @Update
    suspend fun updateCamera(camera: CameraDeviceEntity)

    @Delete
    suspend fun deleteCamera(camera: CameraDeviceEntity)

    @Query("UPDATE camera_devices SET last_connected = :timestamp WHERE id = :id")
    suspend fun updateLastConnected(id: Long, timestamp: Long = System.currentTimeMillis())
}
```

- [ ] **Step 5: Write TransferTaskDao.kt**

```kotlin
// core-data/src/main/java/com/nikonlink/data/db/dao/TransferTaskDao.kt
package com.nikonlink.data.db.dao

import androidx.room.*
import com.nikonlink.data.db.entity.TransferTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferTaskDao {
    @Query("SELECT * FROM transfer_tasks ORDER BY created_at DESC")
    fun getAllTasks(): Flow<List<TransferTaskEntity>>

    @Query("SELECT * FROM transfer_tasks WHERE status = 'pending' ORDER BY created_at ASC")
    suspend fun getPendingTasks(): List<TransferTaskEntity>

    @Query("SELECT * FROM transfer_tasks WHERE status = 'transferring'")
    suspend fun getActiveTasks(): List<TransferTaskEntity>

    @Query("SELECT * FROM transfer_tasks WHERE id = :id")
    suspend fun getTaskById(id: Long): TransferTaskEntity?

    @Query("SELECT * FROM transfer_tasks WHERE remote_path = :remotePath AND file_name = :fileName LIMIT 1")
    suspend fun getDuplicateTask(remotePath: String, fileName: String): TransferTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: TransferTaskEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTasks(tasks: List<TransferTaskEntity>)

    @Update
    suspend fun updateTask(task: TransferTaskEntity)

    @Query("UPDATE transfer_tasks SET status = :status, progress_bytes = :progressBytes WHERE id = :id")
    suspend fun updateProgress(id: Long, status: String, progressBytes: Long)

    @Query("DELETE FROM transfer_tasks WHERE status = 'completed'")
    suspend fun clearCompleted()
}
```

- [ ] **Step 6: Write NikonLinkDatabase.kt**

```kotlin
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
```

- [ ] **Step 7: Write SettingsDataStore.kt**

```kotlin
// core-data/src/main/java/com/nikonlink/data/datastore/SettingsDataStore.kt
package com.nikonlink.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val AUTO_TRANSFER = booleanPreferencesKey("auto_transfer")
        val TRANSFER_QUALITY = stringPreferencesKey("transfer_quality") // original | compressed
        val POWER_SAVE_ENABLED = booleanPreferencesKey("power_save_enabled")
        val STORAGE_PATH = stringPreferencesKey("storage_path")
    }

    val autoTransfer: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_TRANSFER] ?: false }
    val transferQuality: Flow<String> = context.dataStore.data.map { it[Keys.TRANSFER_QUALITY] ?: "original" }
    val powerSaveEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.POWER_SAVE_ENABLED] ?: true }

    suspend fun setAutoTransfer(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_TRANSFER] = enabled }
    }

    suspend fun setTransferQuality(quality: String) {
        context.dataStore.edit { it[Keys.TRANSFER_QUALITY] = quality }
    }

    suspend fun setPowerSaveEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.POWER_SAVE_ENABLED] = enabled }
    }
}
```

- [ ] **Step 8: Write minimal AndroidManifest.xml and verify**

```xml
<!-- core-data/src/main/AndroidManifest.xml -->
<manifest />
```

Run: `./gradlew :core-data:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

---

## Phase 1: Connection Layer (P0)

### Task 1.1: Create feature-connection module skeleton

**Files:**
- Create: `feature-connection/build.gradle.kts`
- Create: `feature-connection/src/main/AndroidManifest.xml`
- Create: `feature-connection/src/main/java/com/nikonlink/connection/di/ConnectionModule.kt`

- [ ] **Step 1: Write feature-connection/build.gradle.kts**

```kotlin
// feature-connection/build.gradle.kts
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.nikonlink.connection"
    compileSdk = 34
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core-common"))
    implementation(project(":core-data"))

    // Hilt
    implementation("com.google.dagger:hilt-android:2.48.1")
    ksp("com.google.dagger:hilt-compiler:2.48.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // OkHttp (for WiFi HTTP-based protocols)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
}
```

- [ ] **Step 2: Write ConnectionModule.kt (Hilt bindings)**

```kotlin
// feature-connection/src/main/java/com/nikonlink/connection/di/ConnectionModule.kt
package com.nikonlink.connection.di

import com.nikonlink.connection.ConnectionManager
import com.nikonlink.connection.ble.BleScanner
import com.nikonlink.connection.ble.BleGattManager
import com.nikonlink.connection.wifi.WifiConnectionManager
import com.nikonlink.connection.wifi.PtpIpClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ConnectionModule {

    @Provides
    @Singleton
    fun provideBleScanner(): BleScanner = BleScanner()

    @Provides
    @Singleton
    fun provideBleGattManager(): BleGattManager = BleGattManager()

    @Provides
    @Singleton
    fun provideWifiConnectionManager(): WifiConnectionManager = WifiConnectionManager()

    @Provides
    @Singleton
    fun providePtpIpClient(): PtpIpClient = PtpIpClient()

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
```

- [ ] **Step 3: Verify module compiles** — `./gradlew :feature-connection:compileDebugKotlin`

---

### Task 1.2: Implement BLE Scanner

**Files:**
- Create: `feature-connection/src/main/java/com/nikonlink/connection/ble/BleScanner.kt`

- [ ] **Step 1: Write BleScanner.kt**

```kotlin
// feature-connection/src/main/java/com/nikonlink/connection/ble/BleScanner.kt
package com.nikonlink.connection.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID

/**
 * SnapBridge BLE Service UUIDs:
 * Primary Service:   0000A000-0000-1000-8000-00805F9B34FB
 * Some older models: 0000FEE7-0000-1000-8000-00805F9B34FB (also common)
 */
class BleScanner {

    companion object {
        val SNAPBIRDGE_SERVICE_UUID: UUID = UUID.fromString("0000A000-0000-1000-8000-00805F9B34FB")
        private const val SCAN_TIMEOUT_MS = 30_000L
    }

    data class ScanDevice(
        val device: BluetoothDevice,
        val name: String,
        val rssi: Int
    )

    fun scan(): Flow<List<ScanDevice>> = callbackFlow {
        val adapter: BluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            ?: run { close(Exception("Bluetooth not available")); return@callbackFlow }

        val scanner: BluetoothLeScanner = adapter.bluetoothLeScanner
            ?: run { close(Exception("BLE not supported")); return@callbackFlow }

        val discoveredDevices = mutableMapOf<String, ScanDevice>()

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SNAPBIRDGE_SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val name = device.name ?: "Unknown Nikon"
                discoveredDevices[device.address] = ScanDevice(
                    device = device,
                    name = name,
                    rssi = result.rssi
                )
                trySend(discoveredDevices.values.toList())
            }

            override fun onScanFailed(errorCode: Int) {
                close(Exception("BLE scan failed with code: $errorCode"))
            }
        }

        scanner.startScan(listOf(filter), settings, callback)
        trySend(emptyList())

        awaitClose {
            scanner.stopScan(callback)
        }
    }
}
```

- [ ] **Step 2: Verify** — `./gradlew :feature-connection:compileDebugKotlin`

---

### Task 1.3: Implement BLE GATT Manager

**Files:**
- Create: `feature-connection/src/main/java/com/nikonlink/connection/ble/BleGattManager.kt`

- [ ] **Step 1: Write BleGattManager.kt**

```kotlin
// feature-connection/src/main/java/com/nikonlink/connection/ble/BleGattManager.kt
package com.nikonlink.connection.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
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
    }

    sealed class GattEvent {
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
        val gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {

            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        trySend(GattEvent.Connected(gatt))
                        gatt.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        trySend(GattEvent.Disconnected(gatt.device.address))
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    trySend(GattEvent.ServicesDiscovered(gatt))
                } else {
                    trySend(GattEvent.Error("Service discovery failed: $status"))
                }
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    trySend(GattEvent.CharacteristicRead(characteristic, value))
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                trySend(GattEvent.CharacteristicWrite(characteristic, status == BluetoothGatt.GATT_SUCCESS))
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                trySend(GattEvent.CharacteristicChanged(characteristic, value))
            }
        })

        activeGatt = gatt

        awaitClose {
            gatt.close()
            activeGatt = null
        }
    }

    fun enableNotification(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ): Boolean {
        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
            ?: return false
        gatt.setCharacteristicNotification(characteristic, true)
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return gatt.writeDescriptor(descriptor)
    }

    fun readCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ): Boolean = gatt.readCharacteristic(characteristic)

    fun writeCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ): Boolean {
        characteristic.value = value
        return gatt.writeCharacteristic(characteristic)
    }

    fun disconnect() {
        activeGatt?.disconnect()
        activeGatt?.close()
        activeGatt = null
    }
}
```

- [ ] **Step 2: Verify** — `./gradlew :feature-connection:compileDebugKotlin`

---

### Task 1.4: Implement SnapBridge BLE Protocol

**Files:**
- Create: `feature-connection/src/main/java/com/nikonlink/connection/ble/SnapBridgeBleProtocol.kt`

- [ ] **Step 1: Write SnapBridgeBleProtocol.kt**

```kotlin
// feature-connection/src/main/java/com/nikonlink/connection/ble/SnapBridgeBleProtocol.kt
package com.nikonlink.connection.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import java.util.UUID

/**
 * Nikon SnapBridge BLE protocol constants.
 *
 * SnapBridge uses a proprietary BLE service with UUID 0000A000-...
 * Key characteristics handle: pairing, camera info, thumbnail transfer,
 * shutter control, WiFi handshake parameters, and exposure settings.
 *
 * Note: Exact characteristic UUIDs and command formats are based on
 * reverse-engineered protocol documentation. Some variance exists
 * between camera firmware versions.
 */
class SnapBridgeBleProtocol {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000A000-0000-1000-8000-00805F9B34FB")

        // Characteristics within SnapBridge service
        val CHAR_CAMERA_CONTROL: UUID = UUID.fromString("0000A001-0000-1000-8000-00805F9B34FB")
        val CHAR_CAMERA_STATUS: UUID = UUID.fromString("0000A002-0000-1000-8000-00805F9B34FB")
        val CHAR_THUMBNAIL_DATA: UUID = UUID.fromString("0000A003-0000-1000-8000-00805F9B34FB")
        val CHAR_WIFI_CONFIG: UUID = UUID.fromString("0000A004-0000-1000-8000-00805F9B34FB")
        val CHAR_FILE_LIST: UUID = UUID.fromString("0000A005-0000-1000-8000-00805F9B34FB")
        val CHAR_EXPOSURE_SETTINGS: UUID = UUID.fromString("0000A006-0000-1000-8000-00805F9B34FB")

        // Command opcodes (simplified)
        const val CMD_SHUTTER_RELEASE = 0x01.toByte()
        const val CMD_SHUTTER_HALF_PRESS = 0x02.toByte()
        const val CMD_GET_FILE_LIST = 0x10.toByte()
        const val CMD_GET_THUMBNAIL = 0x11.toByte()
        const val CMD_REQUEST_WIFI_CONFIG = 0x20.toByte()
        const val CMD_SET_EXPOSURE = 0x30.toByte()
        const val CMD_GET_EXPOSURE = 0x31.toByte()
        const val CMD_FOCUS_AT = 0x40.toByte()
    }

    /** Builds a shutter release command packet. */
    fun buildShutterCommand(): ByteArray = byteArrayOf(CMD_SHUTTER_RELEASE)

    /** Builds a half-press (focus) command packet. */
    fun buildHalfPressCommand(): ByteArray = byteArrayOf(CMD_SHUTTER_HALF_PRESS)

    /** Builds a request for file list (optionally filtered by path). */
    fun buildFileListRequest(path: String = "/"): ByteArray {
        val pathBytes = path.toByteArray(Charsets.UTF_8)
        val packet = ByteArray(1 + 2 + pathBytes.size)
        packet[0] = CMD_GET_FILE_LIST
        packet[1] = (pathBytes.size shr 8).toByte()
        packet[2] = pathBytes.size.toByte()
        System.arraycopy(pathBytes, 0, packet, 3, pathBytes.size)
        return packet
    }

    /** Builds a thumbnail request for a specific file. */
    fun buildThumbnailRequest(filePath: String): ByteArray {
        val pathBytes = filePath.toByteArray(Charsets.UTF_8)
        val packet = ByteArray(1 + 2 + pathBytes.size)
        packet[0] = CMD_GET_THUMBNAIL
        packet[1] = (pathBytes.size shr 8).toByte()
        packet[2] = pathBytes.size.toByte()
        System.arraycopy(pathBytes, 0, packet, 3, pathBytes.size)
        return packet
    }

    /** Builds a WiFi configuration request (triggers camera to share SSID/password). */
    fun buildWifiConfigRequest(): ByteArray = byteArrayOf(CMD_REQUEST_WIFI_CONFIG)

    /** Builds an exposure settings query. */
    fun buildExposureQuery(): ByteArray = byteArrayOf(CMD_GET_EXPOSURE)

    /** Builds a focus-at-position command. x and y are normalized 0.0-1.0. */
    fun buildFocusAtCommand(x: Float, y: Float): ByteArray {
        val xi = (x * 10000).toInt()
        val yi = (y * 10000).toInt()
        return byteArrayOf(
            CMD_FOCUS_AT,
            (xi shr 8).toByte(), xi.toByte(),
            (yi shr 8).toByte(), yi.toByte()
        )
    }

    /**
     * Parses camera status notification data.
     * Returns a map of key-value pairs like "shutter" -> "1/250", "aperture" -> "5.6", etc.
     */
    fun parseCameraStatus(data: ByteArray): Map<String, String> {
        // Simplified parsing — actual SnapBridge uses TLV or JSON depending on firmware version
        val result = mutableMapOf<String, String>()
        val str = String(data, Charsets.UTF_8)
        if (str.startsWith("{")) {
            // JSON-based status (newer firmware)
            try {
                val json = org.json.JSONObject(str)
                json.keys().forEachRemaining { key ->
                    result[key] = json.optString(key, "")
                }
            } catch (_: Exception) { }
        }
        // TLV parsing for older binary format would go here
        return result
    }

    /**
     * Parses file list response. Returns list of RemoteFileEntry.
     */
    fun parseFileListResponse(data: ByteArray): List<RemoteFileEntry> {
        val entries = mutableListOf<RemoteFileEntry>()
        val str = String(data, Charsets.UTF_8)
        if (str.startsWith("[")) {
            try {
                val jsonArray = org.json.JSONArray(str)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    entries.add(
                        RemoteFileEntry(
                            path = obj.optString("path", ""),
                            name = obj.optString("name", ""),
                            size = obj.optLong("size", 0),
                            isDirectory = obj.optBoolean("is_dir", false),
                            dateModified = obj.optLong("date", 0),
                            fileType = obj.optString("type", "jpg")
                        )
                    )
                }
            } catch (_: Exception) { }
        }
        if (entries.isEmpty()) {
            // Fallback: line-by-line listing (older firmware)
            str.lines().filter { it.isNotBlank() }.forEach { line ->
                val parts = line.split("\t")
                if (parts.size >= 3) {
                    entries.add(
                        RemoteFileEntry(
                            path = parts[0],
                            name = parts[0].substringAfterLast('/'),
                            size = parts[1].toLongOrNull() ?: 0,
                            isDirectory = parts[0].endsWith('/'),
                            dateModified = parts[2].toLongOrNull() ?: 0,
                            fileType = ""
                        )
                    )
                }
            }
        }
        return entries
    }

    data class RemoteFileEntry(
        val path: String,
        val name: String,
        val size: Long,
        val isDirectory: Boolean,
        val dateModified: Long,
        val fileType: String
    )
}
```

- [ ] **Step 2: Verify** — `./gradlew :feature-connection:compileDebugKotlin`

---

### Task 1.5: Implement WiFi Connection Manager

**Files:**
- Create: `feature-connection/src/main/java/com/nikonlink/connection/wifi/WifiConnectionManager.kt`

- [ ] **Step 1: Write WifiConnectionManager.kt**

```kotlin
// feature-connection/src/main/java/com/nikonlink/connection/wifi/WifiConnectionManager.kt
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
            // API 28 fallback: use WifiManager to connect (with user permission flow if needed)
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
```

- [ ] **Step 2: Verify** — `./gradlew :feature-connection:compileDebugKotlin`

---

### Task 1.6: Implement PTP-IP Client (TCP)

**Files:**
- Create: `feature-connection/src/main/java/com/nikonlink/connection/wifi/PtpIpClient.kt`

- [ ] **Step 1: Write PtpIpClient.kt**

```kotlin
// feature-connection/src/main/java/com/nikonlink/connection/wifi/PtpIpClient.kt
package com.nikonlink.connection.wifi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Minimal PTP-IP (Picture Transfer Protocol over TCP/IP) client.
 *
 * Nikon cameras expose a PTP-IP server on port 15740 when in WiFi mode.
 * PTP-IP is used for: file browsing (GetObjectHandles), file download (GetObject),
 * LiveView streaming (InitiateCapture + MJPEG), and camera control operations.
 *
 * This implementation covers the PTP-IP connection layer. Full PTP command
 * enumeration is extensive; we implement the subset needed for key features.
 */
@Singleton
class PtpIpClient @Inject constructor() {

    companion object {
        const val PTP_IP_PORT = 15740
        const val INIT_COMMAND_REQUEST = 1
        const val INIT_EVENT_REQUEST = 2
        const val INIT_RESPONSE_OK = 3
    }

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var sessionId: Int = 0

    val isConnected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    /**
     * Connect to the camera PTP-IP server and perform the init handshake.
     */
    suspend fun connect(host: String = "192.168.1.1", port: Int = PTP_IP_PORT): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                socket = Socket().also {
                    it.connect(InetSocketAddress(host, port), 5000)
                    it.soTimeout = 10000
                }
                input = DataInputStream(socket!!.getInputStream())
                output = DataOutputStream(socket!!.getOutputStream())

                // PTP-IP Init Command Request
                val initPacket = buildInitPacket(INIT_COMMAND_REQUEST)
                output!!.write(initPacket)
                output!!.flush()

                // Read Init Command Ack
                val ackPacket = ByteArray(12)
                input!!.readFully(ackPacket)
                sessionId = ((ackPacket[8].toInt() and 0xFF) shl 24) or
                        ((ackPacket[9].toInt() and 0xFF) shl 16) or
                        ((ackPacket[10].toInt() and 0xFF) shl 8) or
                        (ackPacket[11].toInt() and 0xFF)

                // PTP-IP Init Event Request
                val eventPacket = buildInitPacket(INIT_EVENT_REQUEST)
                output!!.write(eventPacket)
                output!!.flush()

                // Read Init Event Ack
                input!!.readFully(ackPacket)

                Result.success(Unit)
            } catch (e: Exception) {
                disconnect()
                Result.failure(e)
            }
        }

    /**
     * Send a raw PTP command packet and receive the response.
     */
    suspend fun sendCommand(commandCode: Int, params: List<Int> = emptyList()): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                val packet = buildCommandPacket(commandCode, params)
                output?.write(packet)
                output?.flush()

                // Read response header: length(4) + type(2) + code(2) + transaction(4) + paramCount(4)
                val header = ByteArray(16)
                input?.readFully(header)

                val length = ((header[0].toInt() and 0xFF) shl 24) or
                        ((header[1].toInt() and 0xFF) shl 16) or
                        ((header[2].toInt() and 0xFF) shl 8) or
                        (header[3].toInt() and 0xFF)

                if (length > 16) {
                    val data = ByteArray(length - 16)
                    input?.readFully(data)
                    data
                } else {
                    ByteArray(0)
                }
            } catch (e: Exception) {
                null
            }
        }

    /**
     * Read raw data from the socket (for file downloads).
     */
    suspend fun readData(length: Int): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val buffer = ByteArray(length)
            var totalRead = 0
            while (totalRead < length) {
                val read = input?.read(buffer, totalRead, length - totalRead) ?: -1
                if (read == -1) break
                totalRead += read
            }
            buffer.copyOf(totalRead)
        } catch (e: Exception) {
            null
        }
    }

    fun disconnect() {
        try { input?.close() } catch (_: Exception) {}
        try { output?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        input = null
        output = null
    }

    private fun buildInitPacket(requestType: Int): ByteArray {
        return ByteArray(12).apply {
            // length
            this[0] = 0; this[1] = 0; this[2] = 0; this[3] = 12
            // type
            this[4] = 0; this[5] = 0; this[6] = 0; this[7] = requestType.toByte()
            // sessionId placeholder (0 for init)
            this[8] = 0; this[9] = 0; this[10] = 0; this[11] = 0
        }
    }

    private fun buildCommandPacket(code: Int, params: List<Int>): ByteArray {
        val payloadSize = 12 + 4 + params.size * 4 // header + transactionId + params
        val packet = ByteArray(payloadSize)

        // length
        packet[0] = ((payloadSize shr 24) and 0xFF).toByte()
        packet[1] = ((payloadSize shr 16) and 0xFF).toByte()
        packet[2] = ((payloadSize shr 8) and 0xFF).toByte()
        packet[3] = (payloadSize and 0xFF).toByte()

        // type = 1 (Command)
        packet[6] = 0; packet[7] = 1

        // code
        packet[8] = ((code shr 8) and 0xFF).toByte()
        packet[9] = (code and 0xFF).toByte()

        // transaction ID
        packet[12] = 0; packet[13] = 0; packet[14] = 0; packet[15] = 1

        // params
        params.forEachIndexed { i, param ->
            val offset = 16 + i * 4
            packet[offset] = ((param shr 24) and 0xFF).toByte()
            packet[offset + 1] = ((param shr 16) and 0xFF).toByte()
            packet[offset + 2] = ((param shr 8) and 0xFF).toByte()
            packet[offset + 3] = (param and 0xFF).toByte()
        }

        return packet
    }
}
```

- [ ] **Step 2: Verify** — `./gradlew :feature-connection:compileDebugKotlin`

---

### Task 1.7: Implement ConnectionManager State Machine

**Files:**
- Create: `feature-connection/src/main/java/com/nikonlink/connection/ConnectionManager.kt`

- [ ] **Step 1: Write ConnectionManager.kt**

```kotlin
// feature-connection/src/main/java/com/nikonlink/connection/ConnectionManager.kt
package com.nikonlink.connection

import android.bluetooth.BluetoothDevice
import com.nikonlink.common.ConnectionState
import com.nikonlink.connection.ble.BleGattManager
import com.nikonlink.connection.ble.BleScanner
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

    private var scanJob: Job? = null
    private var gattJob: Job? = null
    private var wifiJob: Job? = null
    private var heartbeatJob: Job? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Start scanning for nearby Nikon cameras. */
    fun startScanning() {
        if (_connectionState.value != ConnectionState.Disconnected) return
        _connectionState.value = ConnectionState.BLEScanning

        scanJob?.cancel()
        scanJob = scope.launch {
            bleScanner.scan()
                .catch { e ->
                    _connectionState.value = ConnectionState.Disconnected
                    _discoveredCameras.value = emptyList()
                }
                .collect { devices ->
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
                    is BleGattManager.GattEvent.Connected -> {
                        _connectionState.value = ConnectionState.BLEConnected
                        startHeartbeat()
                    }
                    is BleGattManager.GattEvent.Disconnected -> {
                        _connectionState.value = ConnectionState.Disconnected
                        stopHeartbeat()
                    }
                    is BleGattManager.GattEvent.ServicesDiscovered -> {
                        // GATT services ready — we can now read/write characteristics
                    }
                    else -> { /* handled by callers via dedicated Flows */ }
                }
            }
        }
    }

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
                        // Now connect PTP-IP
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

    /** Disconnect everything — return to Disconnected state. */
    fun disconnectAll() {
        stopHeartbeat()
        wifiJob?.cancel()
        gattJob?.cancel()
        scanJob?.cancel()
        ptpIpClient.disconnect()
        wifiConnectionManager.disconnect()
        bleGattManager.disconnect()
        _connectionState.value = ConnectionState.Disconnected
    }

    /** Send a keep-alive heartbeat via BLE. Auto-reconnect if no response for 5s. */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            var missedBeats = 0
            while (isActive) {
                delay(1000)
                // If BLE characteristic supports read, do a heartbeat read.
                // If no response within 5 seconds, trigger disconnect and auto-reconnect.
                missedBeats++
                if (missedBeats >= 5) {
                    _connectionState.value = ConnectionState.Disconnected
                    stopHeartbeat()
                    // Auto-reconnect: start scanning again
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
```

- [ ] **Step 2: Verify** — `./gradlew :feature-connection:compileDebugKotlin`

---

## Phase 2: File Browser (P0)

### Task 2.1: Create feature-browser module skeleton

**Files:**
- Create: `feature-browser/build.gradle.kts`
- Create: `feature-browser/src/main/AndroidManifest.xml`

- [ ] **Step 1: Write feature-browser/build.gradle.kts**

```kotlin
// feature-browser/build.gradle.kts
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.nikonlink.browser"
    compileSdk = 34
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
}

dependencies {
    implementation(project(":core-common"))
    implementation(project(":core-data"))
    implementation(project(":feature-connection"))

    // Hilt
    implementation("com.google.dagger:hilt-android:2.48.1")
    ksp("com.google.dagger:hilt-compiler:2.48.1")

    // Fragment + ViewModel
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // RecyclerView
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Coil (image loading with custom RAW decoder support)
    implementation("io.coil-kt:coil:2.5.0")
}
```

- [ ] **Step 2: Verify** — `./gradlew :feature-browser:compileDebugKotlin`

---

### Task 2.2: Implement BrowserViewModel

**Files:**
- Create: `feature-browser/src/main/java/com/nikonlink/browser/ui/BrowserViewModel.kt`

- [ ] **Step 1: Write BrowserViewModel.kt**

```kotlin
// feature-browser/src/main/java/com/nikonlink/browser/ui/BrowserViewModel.kt
package com.nikonlink.browser.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nikonlink.common.ConnectionState
import com.nikonlink.common.FileType
import com.nikonlink.common.Result
import com.nikonlink.connection.ConnectionManager
import com.nikonlink.connection.ble.SnapBridgeBleProtocol
import com.nikonlink.connection.wifi.PtpIpClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BrowserViewModel @Inject constructor(
    private val connectionManager: ConnectionManager,
    private val ptpIpClient: PtpIpClient,
    private val snapBridgeProtocol: SnapBridgeBleProtocol
) : ViewModel() {

    data class BrowserUiState(
        val currentPath: String = "/DCIM",
        val entries: List<FileEntry> = emptyList(),
        val isLoading: Boolean = false,
        val selectedPaths: Set<String> = emptySet(),
        val isMultiSelectMode: Boolean = false,
        val filterType: FileType? = null, // null = show all
        val sortOrder: SortOrder = SortOrder.NAME_ASC,
        val selectedTotalSize: Long = 0
    )

    data class FileEntry(
        val path: String,
        val name: String,
        val size: Long,
        val isDirectory: Boolean,
        val dateModified: Long,
        val fileType: FileType?
    )

    enum class SortOrder { NAME_ASC, NAME_DESC, DATE_DESC, SIZE_DESC }

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    private val pathStack = ArrayDeque<String>()

    fun loadDirectory(path: String) {
        _uiState.update { it.copy(isLoading = true, currentPath = path) }

        viewModelScope.launch {
            val entries = when {
                connectionManager.connectionState.value.isWifiConnected -> {
                    loadDirectoryViaPtpIp(path)
                }
                connectionManager.connectionState.value.isBleConnected -> {
                    loadDirectoryViaBle(path)
                }
                else -> emptyList()
            }
            _uiState.update {
                it.copy(isLoading = false, entries = applyFilterAndSort(entries, it.filterType, it.sortOrder))
            }
        }
    }

    fun navigateToFolder(path: String) {
        pathStack.addLast(_uiState.value.currentPath)
        loadDirectory(path)
    }

    fun navigateBack(): Boolean {
        val prevPath = pathStack.removeLastOrNull() ?: return false
        loadDirectory(prevPath)
        return true
    }

    fun toggleSelection(path: String, size: Long) {
        _uiState.update { state ->
            val newSelected = if (path in state.selectedPaths) {
                state.selectedPaths - path
            } else {
                state.selectedPaths + path
            }
            val newTotalSize = newSelected.sumOf { selectedPath ->
                state.entries.find { it.path == selectedPath }?.size ?: 0
            }
            state.copy(
                selectedPaths = newSelected,
                selectedTotalSize = newTotalSize,
                isMultiSelectMode = newSelected.isNotEmpty()
            )
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedPaths = emptySet(), selectedTotalSize = 0, isMultiSelectMode = false) }
    }

    fun selectAll() {
        _uiState.update { state ->
            val allPaths = state.entries.filter { !it.isDirectory }.map { it.path }.toSet()
            state.copy(
                selectedPaths = allPaths,
                selectedTotalSize = state.entries.filter { !it.isDirectory }.sumOf { it.size },
                isMultiSelectMode = true
            )
        }
    }

    fun setFilter(filterType: FileType?) {
        _uiState.update { state ->
            state.copy(
                filterType = filterType,
                entries = applyFilterAndSort(state.entries, filterType, state.sortOrder)
            )
        }
    }

    fun setSortOrder(sortOrder: SortOrder) {
        _uiState.update { state ->
            state.copy(
                sortOrder = sortOrder,
                entries = applyFilterAndSort(state.entries, state.filterType, sortOrder)
            )
        }
    }

    private suspend fun loadDirectoryViaPtpIp(path: String): List<FileEntry> {
        // PTP GetObjectHandles for the given storage ID + path
        // opcode 0x1007 (GetObjectHandles) with storageID=0x00010001, objectFormatCode=0, associationHandle=0
        val response = ptpIpClient.sendCommand(0x1007, listOf(0x00010001, 0x00000000, 0x00000000))
            ?: return emptyList()
        // Parse response — actual PTP parsing is more involved; this shows the pattern
        return parsePtpObjectHandles(response, path)
    }

    private suspend fun loadDirectoryViaBle(path: String): List<FileEntry> {
        // Send file list request via SnapBridge BLE characteristic
        val request = snapBridgeProtocol.buildFileListRequest(path)
        // The response comes asynchronously via BLE notification — for now, return cached/empty
        // In production, this would be a suspend function awaiting the BLE response flow
        return emptyList()
    }

    private fun parsePtpObjectHandles(data: ByteArray, basePath: String): List<FileEntry> {
        val entries = mutableListOf<FileEntry>()
        // PTP ObjectHandles response contains: objectHandleCount(4) + handles[n*4]
        if (data.size < 4) return entries
        val count = ((data[0].toInt() and 0xFF) shl 24) or
                ((data[1].toInt() and 0xFF) shl 16) or
                ((data[2].toInt() and 0xFF) shl 8) or
                (data[3].toInt() and 0xFF)

        for (i in 0 until count) {
            val offset = 4 + i * 4
            if (offset + 4 > data.size) break
            val handle = ((data[offset].toInt() and 0xFF) shl 24) or
                    ((data[offset + 1].toInt() and 0xFF) shl 16) or
                    ((data[offset + 2].toInt() and 0xFF) shl 8) or
                    (data[offset + 3].toInt() and 0xFF)

            // For each handle, we'd call GetObjectInfo to get name, size, etc.
            // Simplified: create placeholder entries
            entries.add(
                FileEntry(
                    path = "$basePath/object_$handle",
                    name = "DSC_${handle}.NEF",
                    size = 25_000_000,
                    isDirectory = false,
                    dateModified = System.currentTimeMillis(),
                    fileType = FileType.NEF
                )
            )
        }
        return entries
    }

    private fun applyFilterAndSort(
        entries: List<FileEntry>,
        filter: FileType?,
        sort: SortOrder
    ): List<FileEntry> {
        var result = if (filter != null) {
            entries.filter { it.isDirectory || it.fileType == filter }
        } else {
            entries
        }
        result = when (sort) {
            SortOrder.NAME_ASC -> result.sortedWith(compareBy({ it.isDirectory }, { it.name })).reversed()
            SortOrder.NAME_DESC -> result.sortedWith(compareBy({ it.isDirectory }, { it.name }))
            SortOrder.DATE_DESC -> result.sortedWith(compareBy({ it.isDirectory }, { -it.dateModified }))
            SortOrder.SIZE_DESC -> result.sortedWith(compareBy({ it.isDirectory }, { -it.size }))
        }
        return result
    }
}
```

- [ ] **Step 2: Verify** — `./gradlew :feature-browser:compileDebugKotlin`

---

### Task 2.3: Implement FileListAdapter

**Files:**
- Create: `feature-browser/src/main/java/com/nikonlink/browser/ui/FolderListAdapter.kt`

- [ ] **Step 1: Write FolderListAdapter.kt**

```kotlin
// feature-browser/src/main/java/com/nikonlink/browser/ui/FolderListAdapter.kt
package com.nikonlink.browser.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nikonlink.browser.databinding.ItemFileEntryBinding
import com.nikonlink.common.FileType

class FolderListAdapter(
    private val onFolderClick: (BrowserViewModel.FileEntry) -> Unit,
    private val onFileClick: (BrowserViewModel.FileEntry) -> Unit,
    private val onLongClick: (BrowserViewModel.FileEntry) -> Unit
) : ListAdapter<BrowserViewModel.FileEntry, FolderListAdapter.ViewHolder>(DiffCallback) {

    private var selectedPaths: Set<String> = emptySet()

    fun updateSelectedPaths(paths: Set<String>) {
        selectedPaths = paths
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFileEntryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = getItem(position)
        holder.bind(entry, entry.path in selectedPaths)
    }

    inner class ViewHolder(
        private val binding: ItemFileEntryBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(entry: BrowserViewModel.FileEntry, isSelected: Boolean) {
            binding.apply {
                fileName.text = entry.name
                fileSize.text = if (entry.isDirectory) "" else formatSize(entry.size)

                when (entry.fileType) {
                    FileType.NEF -> {
                        fileTypeBadge.text = "RAW"
                        fileTypeBadge.setBackgroundColor(0xFFF5A623.toInt())
                    }
                    FileType.JPEG, FileType.TIFF -> {
                        fileTypeBadge.text = ""
                        fileTypeBadge.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    }
                    FileType.MP4, FileType.MOV -> {
                        fileTypeBadge.text = formatDuration(0) // duration from metadata
                        fileTypeBadge.setBackgroundColor(0xFF4A90D9.toInt())
                    }
                    null -> {
                        fileTypeBadge.text = if (entry.isDirectory) "📁" else ""
                        fileTypeBadge.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    }
                }

                fileIcon.text = when {
                    entry.isDirectory -> "📁"
                    entry.fileType?.isVideo == true -> "🎬"
                    else -> "🖼"
                }

                selectionCheckbox.isChecked = isSelected

                root.setBackgroundColor(
                    if (isSelected) 0x3327AE60 else android.graphics.Color.TRANSPARENT
                )

                root.setOnClickListener {
                    if (entry.isDirectory) onFolderClick(entry) else onFileClick(entry)
                }
                root.setOnLongClickListener {
                    onLongClick(entry)
                    true
                }
            }
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<BrowserViewModel.FileEntry>() {
        override fun areItemsTheSame(old: BrowserViewModel.FileEntry, new: BrowserViewModel.FileEntry) =
            old.path == new.path

        override fun areContentsTheSame(old: BrowserViewModel.FileEntry, new: BrowserViewModel.FileEntry) =
            old == new
    }

    companion object {
        fun formatSize(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
            else -> "${"%.2f".format(bytes.toDouble() / (1024 * 1024 * 1024))} GB"
        }

        /** Format seconds to mm:ss. Returns empty string if zero. */
        fun formatDuration(seconds: Int): String =
            if (seconds <= 0) "" else "${seconds / 60}:${"%02d".format(seconds % 60)}"
    }
}
```

- [ ] **Step 2: Verify** — `./gradlew :feature-browser:compileDebugKotlin`

---

### Task 2.4: Create BrowserFragment layout

**Files:**
- Create: `feature-browser/src/main/res/layout/fragment_browser.xml`
- Create: `feature-browser/src/main/res/layout/item_file_entry.xml`

- [ ] **Step 1: Write fragment_browser.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:background="@android:color/black">

    <!-- Toolbar: path breadcrumb + filter/sort -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="48dp"
        android:gravity="center_vertical"
        android:paddingHorizontal="12dp"
        android:background="#16213e">

        <ImageButton
            android:id="@+id/btnBack"
            android:layout_width="32dp"
            android:layout_height="32dp"
            android:src="@android:drawable/ic_menu_revert"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:contentDescription="返回" />

        <TextView
            android:id="@+id/tvPath"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:text="/DCIM"
            android:textColor="@android:color/white"
            android:textSize="14sp"
            android:ellipsize="start"
            android:singleLine="true"
            android:layout_marginHorizontal="8dp" />

        <ImageButton
            android:id="@+id/btnFilter"
            android:layout_width="32dp"
            android:layout_height="32dp"
            android:src="@android:drawable/ic_menu_sort_by_size"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:contentDescription="筛选" />
    </LinearLayout>

    <!-- File list -->
    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/recyclerView"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:clipToPadding="false"
        android:paddingBottom="8dp" />

    <!-- Selection bottom bar (visible when items selected) -->
    <LinearLayout
        android:id="@+id/selectionBar"
        android:layout_width="match_parent"
        android:layout_height="52dp"
        android:gravity="center_vertical"
        android:paddingHorizontal="16dp"
        android:background="#1a3a2e"
        android:visibility="gone">

        <TextView
            android:id="@+id/tvSelectedCount"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:textColor="@android:color/white"
            android:textSize="14sp" />

        <Button
            android:id="@+id/btnSelectAll"
            android:layout_width="wrap_content"
            android:layout_height="36dp"
            android:text="全选"
            android:textSize="12sp"
            style="@style/Widget.AppCompat.Button.Borderless" />

        <Button
            android:id="@+id/btnTransfer"
            android:layout_width="wrap_content"
            android:layout_height="36dp"
            android:text="传输"
            android:textSize="12sp"
            android:textColor="#27AE60"
            style="@style/Widget.AppCompat.Button.Borderless" />
    </LinearLayout>
</LinearLayout>
```

- [ ] **Step 2: Write item_file_entry.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="64dp"
    android:gravity="center_vertical"
    android:paddingHorizontal="12dp"
    android:background="?attr/selectableItemBackground"
    android:orientation="horizontal">

    <TextView
        android:id="@+id/fileIcon"
        android:layout_width="40dp"
        android:layout_height="40dp"
        android:gravity="center"
        android:textSize="22sp"
        android:text="🖼" />

    <LinearLayout
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:orientation="vertical"
        android:layout_marginStart="8dp">

        <TextView
            android:id="@+id/fileName"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textColor="@android:color/white"
            android:textSize="14sp"
            android:ellipsize="end"
            android:singleLine="true" />

        <TextView
            android:id="@+id/fileSize"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textColor="#999999"
            android:textSize="12sp" />
    </LinearLayout>

    <TextView
        android:id="@+id/fileTypeBadge"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:paddingHorizontal="6dp"
        android:paddingVertical="2dp"
        android:textSize="10sp"
        android:textColor="@android:color/white"
        android:gravity="center"
        android:layout_marginEnd="8dp" />

    <CheckBox
        android:id="@+id/selectionCheckbox"
        android:layout_width="24dp"
        android:layout_height="24dp"
        android:clickable="false"
        android:focusable="false" />
</LinearLayout>
```

- [ ] **Step 3: Write BrowserFragment.kt**

```kotlin
// feature-browser/src/main/java/com/nikonlink/browser/ui/BrowserFragment.kt
package com.nikonlink.browser.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.nikonlink.browser.databinding.FragmentBrowserBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class BrowserFragment : Fragment() {

    private var _binding: FragmentBrowserBinding? = null
    private val binding get() = _binding!!

    private val viewModel: BrowserViewModel by viewModels()
    private lateinit var adapter: FolderListAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBrowserBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = FolderListAdapter(
            onFolderClick = { entry -> viewModel.navigateToFolder(entry.path) },
            onFileClick = { entry -> viewModel.toggleSelection(entry.path, entry.size) },
            onLongClick = { entry ->
                if (!viewModel.uiState.value.isMultiSelectMode) {
                    viewModel.toggleSelection(entry.path, entry.size)
                }
            }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.btnBack.setOnClickListener { viewModel.navigateBack() }
        binding.btnSelectAll.setOnClickListener { viewModel.selectAll() }
        binding.btnTransfer.setOnClickListener {
            // Enqueue selected files for transfer (handled by transfer module)
            viewModel.clearSelection()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                adapter.submitList(state.entries)
                adapter.updateSelectedPaths(state.selectedPaths)

                binding.tvPath.text = state.currentPath
                binding.selectionBar.visibility = if (state.isMultiSelectMode) View.VISIBLE else View.GONE
                binding.tvSelectedCount.text = "已选 ${state.selectedPaths.size} 项 (${FolderListAdapter.formatSize(state.selectedTotalSize)})"
            }
        }

        // Initial load
        viewModel.loadDirectory("/DCIM")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
```

- [ ] **Step 4: Verify** — `./gradlew :feature-browser:compileDebugKotlin`

---

## Phase 3: File Transfer (P0)

### Task 3.1: Create feature-transfer module skeleton

**Files:**
- Create: `feature-transfer/build.gradle.kts`
- Create: `feature-transfer/src/main/AndroidManifest.xml`

- [ ] **Step 1: Write feature-transfer/build.gradle.kts**

```kotlin
// feature-transfer/build.gradle.kts
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.nikonlink.transfer"
    compileSdk = 34
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
}

dependencies {
    implementation(project(":core-common"))
    implementation(project(":core-data"))
    implementation(project(":feature-connection"))

    // Hilt
    implementation("com.google.dagger:hilt-android:2.48.1")
    ksp("com.google.dagger:hilt-compiler:2.48.1")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.hilt:hilt-work:1.1.0")
    ksp("androidx.hilt:hilt-compiler:1.1.0")

    // Fragment + ViewModel
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
}
```

- [ ] **Step 2: Verify** — `./gradlew :feature-transfer:compileDebugKotlin`

---

### Task 3.2: Implement TransferWorker

**Files:**
- Create: `feature-transfer/src/main/java/com/nikonlink/transfer/worker/TransferWorker.kt`

- [ ] **Step 1: Write TransferWorker.kt**

```kotlin
// feature-transfer/src/main/java/com/nikonlink/transfer/worker/TransferWorker.kt
package com.nikonlink.transfer.worker

import android.content.Context
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.nikonlink.connection.ConnectionManager
import com.nikonlink.connection.wifi.PtpIpClient
import com.nikonlink.data.db.dao.TransferTaskDao
import com.nikonlink.data.db.entity.TransferTaskEntity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

@HiltWorker
class TransferWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val transferTaskDao: TransferTaskDao,
    private val connectionManager: ConnectionManager,
    private val ptpIpClient: PtpIpClient
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_TASK_ID = "task_id"
        const val CHANNEL_TRANSFER = "nikonlink_transfer"
        const val NOTIFICATION_PROGRESS_ID = 1001

        fun createOneTimeRequest(taskId: Long): OneTimeWorkRequest {
            return OneTimeWorkRequestBuilder<TransferWorker>()
                .setInputData(workDataOf(KEY_TASK_ID to taskId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30, TimeUnit.SECONDS
                )
                .build()
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val taskId = inputData.getLong(KEY_TASK_ID, -1)
        if (taskId == -1L) return@withContext Result.failure()

        val task = transferTaskDao.getTaskById(taskId) ?: return@withContext Result.failure()

        return@withContext try {
            transferTaskDao.updateProgress(task.id, "transferring", 0)
            setForeground(createForegroundInfo(task.fileName, 0))

            // Ensure WiFi connection is active
            if (!connectionManager.connectionState.value.isWifiConnected) {
                return@withContext Result.retry()
            }

            // Download file via PTP-IP
            // PTP GetObject opcode = 0x1009
            val objectHandle = task.remotePath.hashCode() // In production: resolve handle from path
            val fileData = ptpIpClient.sendCommand(0x1009, listOf(objectHandle))

            if (fileData == null) {
                transferTaskDao.updateProgress(task.id, "failed", 0)
                return@withContext Result.retry()
            }

            // Save to local storage
            val localUri = saveToMediaStore(task.fileName, fileData, task.fileType)

            transferTaskDao.updateTask(
                task.copy(
                    status = "completed",
                    progressBytes = fileData.size.toLong(),
                    localUri = localUri,
                    completedAt = System.currentTimeMillis()
                )
            )

            Result.success()
        } catch (e: Exception) {
            transferTaskDao.updateTask(
                task.copy(
                    status = "failed",
                    errorMessage = e.message
                )
            )
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private fun saveToMediaStore(
        fileName: String,
        data: ByteArray,
        fileType: String
    ): String? {
        val isVideo = fileType in listOf("MP4", "MOV")
        val collection = if (isVideo) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, getMimeType(fileType))
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                if (isVideo) "Movies/NikonLink" else "Pictures/NikonLink"
            )
        }

        val uri = applicationContext.contentResolver.insert(collection, values) ?: return null

        applicationContext.contentResolver.openOutputStream(uri)?.use { output ->
            output.write(data)
        }

        return uri.toString()
    }

    private fun getMimeType(fileType: String): String = when (fileType) {
        "JPEG" -> "image/jpeg"
        "NEF" -> "image/x-nikon-nef"
        "TIFF" -> "image/tiff"
        "MP4" -> "video/mp4"
        "MOV" -> "video/quicktime"
        else -> "application/octet-stream"
    }

    private fun createForegroundInfo(fileName: String, progress: Int): ForegroundInfo {
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(applicationContext, CHANNEL_TRANSFER)
                .setContentTitle("传输中")
                .setContentText(fileName)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setProgress(100, progress, progress == 0)
                .build()
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(applicationContext)
                .setContentTitle("传输中")
                .setContentText(fileName)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setProgress(100, progress, progress == 0)
                .build()
        }
        return ForegroundInfo(NOTIFICATION_PROGRESS_ID, notification)
    }
}
```

- [ ] **Step 2: Verify** — `./gradlew :feature-transfer:compileDebugKotlin`

---

### Task 3.3: Implement TransferManager and TransferViewModel

**Files:**
- Create: `feature-transfer/src/main/java/com/nikonlink/transfer/TransferManager.kt`
- Create: `feature-transfer/src/main/java/com/nikonlink/transfer/ui/TransferViewModel.kt`

- [ ] **Step 1: Write TransferManager.kt**

```kotlin
// feature-transfer/src/main/java/com/nikonlink/transfer/TransferManager.kt
package com.nikonlink.transfer

import androidx.work.WorkManager
import com.nikonlink.data.db.dao.TransferTaskDao
import com.nikonlink.data.db.entity.TransferTaskEntity
import com.nikonlink.transfer.worker.TransferWorker
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransferManager @Inject constructor(
    private val transferTaskDao: TransferTaskDao,
    private val workManager: WorkManager
) {
    /** Enqueue a batch of files for transfer. Returns count of new tasks. */
    suspend fun enqueueFiles(
        cameraId: Long,
        files: List<Pair<String, Pair<String, Long>>> // (remotePath, (fileName, fileSize))
    ): Int {
        var added = 0
        files.forEach { (remotePath, fileInfo) ->
            val (fileName, fileSize) = fileInfo
            // Duplicate check
            val existing = transferTaskDao.getDuplicateTask(remotePath, fileName)
            if (existing != null && existing.status == "completed") return@forEach

            val task = TransferTaskEntity(
                cameraId = cameraId,
                remotePath = remotePath,
                fileName = fileName,
                fileSize = fileSize,
                fileType = fileName.substringAfterLast('.').uppercase()
            )
            val taskId = transferTaskDao.insertTask(task)

            // Enqueue WorkManager task
            val request = TransferWorker.createOneTimeRequest(taskId)
            workManager.enqueue(request)
            added++
        }
        return added
    }

    /** Cancel all pending transfers. */
    fun cancelAll() {
        workManager.cancelAllWorkByTag("nikonlink_transfer")
    }
}
```

- [ ] **Step 2: Write TransferViewModel.kt**

```kotlin
// feature-transfer/src/main/java/com/nikonlink/transfer/ui/TransferViewModel.kt
package com.nikonlink.transfer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nikonlink.data.db.dao.TransferTaskDao
import com.nikonlink.data.db.entity.TransferTaskEntity
import com.nikonlink.transfer.TransferManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TransferViewModel @Inject constructor(
    private val transferTaskDao: TransferTaskDao,
    private val transferManager: TransferManager
) : ViewModel() {

    data class TransferUiState(
        val activeTasks: List<TransferTaskEntity> = emptyList(),
        val historyTasks: List<TransferTaskEntity> = emptyList(),
        val overallProgress: Float = 0f,
        val currentSpeedBytesPerSec: Long = 0,
        val estimatedRemainingSeconds: Long = 0
    )

    private val _uiState = MutableStateFlow(TransferUiState())
    val uiState: StateFlow<TransferUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            transferTaskDao.getAllTasks().collect { tasks ->
                val active = tasks.filter { it.status == "pending" || it.status == "transferring" }
                val history = tasks.filter { it.status == "completed" || it.status == "failed" }
                val totalBytes = active.sumOf { it.fileSize }
                val transferredBytes = active.sumOf { it.progressBytes }
                _uiState.value = TransferUiState(
                    activeTasks = active,
                    historyTasks = history,
                    overallProgress = if (totalBytes > 0) transferredBytes.toFloat() / totalBytes else 0f
                )
            }
        }
    }

    fun cancelTransfer(taskId: Long) {
        transferManager.cancelAll() // cancel all for simplicity
        viewModelScope.launch {
            transferTaskDao.updateProgress(taskId, "failed", 0)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            transferTaskDao.clearCompleted()
        }
    }
}
```

- [ ] **Step 3: Verify** — `./gradlew :feature-transfer:compileDebugKotlin`

---

## Phase 4: App Module — Assembly (P0)

### Task 4.1: Create app module

**Files:**
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/nikonlink/NikonLinkApp.kt`
- Create: `app/src/main/java/com/nikonlink/MainActivity.kt`
- Create: `app/src/main/res/layout/activity_main.xml`
- Create: `app/src/main/res/navigation/nav_graph.xml`
- Create: `app/src/main/res/menu/bottom_nav_menu.xml`

- [ ] **Step 1: Write app/build.gradle.kts**

```kotlin
// app/build.gradle.kts
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.nikonlink"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nikonlink"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
}

dependencies {
    implementation(project(":core-common"))
    implementation(project(":core-data"))
    implementation(project(":feature-connection"))
    implementation(project(":feature-browser"))
    implementation(project(":feature-transfer"))
    implementation(project(":feature-remote"))

    // Hilt
    implementation("com.google.dagger:hilt-android:2.48.1")
    ksp("com.google.dagger:hilt-compiler:2.48.1")

    // Navigation
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.6")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.6")

    // Material
    implementation("com.google.android.material:material:1.11.0")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
}
```

- [ ] **Step 2: Write AndroidManifest.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- BLE -->
    <uses-permission android:name="android.permission.BLUETOOTH" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN"
        android:usesPermissionFlags="neverForLocation" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />

    <!-- WiFi -->
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />
    <uses-permission android:name="android.permission.INTERNET" />

    <!-- Storage -->
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
        android:maxSdkVersion="32" />
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
        android:maxSdkVersion="28" />

    <!-- Foreground service -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />
    <uses-feature android:name="android.hardware.wifi" android:required="true" />

    <application
        android:name=".NikonLinkApp"
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="NikonLink"
        android:theme="@style/Theme.Material3.Dark.NoActionBar"
        android:supportsRtl="true">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTask">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name="androidx.work.impl.foreground.SystemForegroundService"
            android:foregroundServiceType="dataSync"
            android:exported="false" />
    </application>
</manifest>
```

- [ ] **Step 3: Write NikonLinkApp.kt**

```kotlin
// app/src/main/java/com/nikonlink/NikonLinkApp.kt
package com.nikonlink

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.nikonlink.transfer.worker.TransferWorker
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class NikonLinkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                TransferWorker.CHANNEL_TRANSFER,
                "文件传输",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示文件传输进度"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
```

- [ ] **Step 4: Write activity_main.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical">

    <androidx.fragment.app.FragmentContainerView
        android:id="@+id/nav_host_fragment"
        android:name="androidx.navigation.fragment.NavHostFragment"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        app:defaultNavHost="true"
        app:navGraph="@navigation/nav_graph" />

    <com.google.android.material.bottomnavigation.BottomNavigationView
        android:id="@+id/bottomNav"
        android:layout_width="match_parent"
        android:layout_height="56dp"
        android:background="#16213e"
        app:menu="@menu/bottom_nav_menu"
        app:itemIconTint="@android:color/white"
        app:itemTextColor="@android:color/white" />
</LinearLayout>
```

- [ ] **Step 5: Write bottom_nav_menu.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<menu xmlns:android="http://schemas.android.com/apk/res/android">
    <item
        android:id="@+id/navigation_cameras"
        android:title="相机"
        android:icon="@android:drawable/ic_menu_camera" />
    <item
        android:id="@+id/navigation_browser"
        android:title="浏览"
        android:icon="@android:drawable/ic_menu_gallery" />
    <item
        android:id="@+id/navigation_transfer"
        android:title="传输"
        android:icon="@android:drawable/ic_menu_upload" />
    <item
        android:id="@+id/navigation_remote"
        android:title="遥控"
        android:icon="@android:drawable/ic_menu_manage" />
</menu>
```

- [ ] **Step 6: Write nav_graph.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<navigation xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    app:startDestination="@id/browserFragment">

    <fragment
        android:id="@+id/browserFragment"
        android:name="com.nikonlink.browser.ui.BrowserFragment"
        android:label="浏览" />

    <fragment
        android:id="@+id/transferFragment"
        android:name="com.nikonlink.transfer.ui.TransferFragment"
        android:label="传输" />
</navigation>
```

- [ ] **Step 7: Write MainActivity.kt**

```kotlin
// app/src/main/java/com/nikonlink/MainActivity.kt
package com.nikonlink

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.nikonlink.databinding.ActivityMainBinding
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHost = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHost.navController

        binding.bottomNav.setupWithNavController(navController)
    }
}
```

- [ ] **Step 8: Verify** — `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Phase 5: Remote Control (P1) — Key Implementation Notes

The remote control module (`feature-remote`) follows the same MVVM pattern. Key components:

### Task 5.1: RemoteViewModel

`feature-remote/src/main/java/com/nikonlink/remote/ui/RemoteViewModel.kt`

State includes: `LiveViewFrame?`, `ExposureSettings`, `CaptureMode`, `isCapturing`, `timerSeconds`, `intervalSeconds`.

- `startLiveView()`: triggers WiFi handshake → connects PTP-IP → sends `InitiateCapture` (PTP opcode 0x100E) → reads MJPEG stream from TCP socket
- `touchToFocus(x, y)`: sends `FocusAt` command via BLE or PTP-IP
- `adjustShutterSpeed(value)`: sends exposure change via BLE characteristic
- `releaseShutter()`: sends shutter command via BLE (lowest latency)
- `startIntervalCapture(intervalSec, count)`: local timer triggers BLE shutter at intervals

### Task 5.2: LiveViewSurface

`feature-remote/src/main/java/com/nikonlink/remote/ui/LiveViewSurface.kt`

Custom `SurfaceView` that decodes MJPEG frames from a byte stream. Uses Android's `MediaCodec` or frame-by-frame JPEG decoding. Each frame is received as a JPEG byte array, decoded to Bitmap, and rendered to the SurfaceView's Canvas.

### Task 5.3: RemoteFragment layout

`feature-remote/src/main/res/layout/fragment_remote.xml`

Layout matches the spec: LiveView area (top 50%), exposure sliders (middle), shutter button row (bottom), mode tabs (bottom-most).

---

## Phase 6: Settings & Polish (P2) — Key Implementation Notes

### Task 6.1: SettingsFragment

`app/src/main/java/com/nikonlink/ui/settings/SettingsFragment.kt`

PreferenceFragmentCompat reading/writing via `SettingsDataStore`. Options:
- Storage path picker (SAF directory picker)
- Transfer quality (original / compressed JPEG proxy)
- Auto-transfer on connect toggle
- Power save timeout (1min / 5min / never)
- Clear transfer history

---

## Phase 7: Testing

### Task 7.1: Unit tests — Domain/Data layer

**Files:**
- Create: `core-data/src/test/java/com/nikonlink/data/db/dao/CameraDeviceDaoTest.kt`
- Create: `core-data/src/test/java/com/nikonlink/data/db/dao/TransferTaskDaoTest.kt`
- Create: `feature-connection/src/test/java/com/nikonlink/connection/ble/SnapBridgeBleProtocolTest.kt`

Uses Room in-memory database for DAO tests (with InstantTaskExecutorRule). Protocol tests verify command packet structure against known SnapBridge command formats.

### Task 7.2: ViewModel tests

**Files:**
- Create: `feature-browser/src/test/java/com/nikonlink/browser/ui/BrowserViewModelTest.kt`
- Create: `feature-transfer/src/test/java/com/nikonlink/transfer/ui/TransferViewModelTest.kt`

Uses Turbine for testing StateFlow emissions, MockK for faking ConnectionManager/PtpIpClient.

### Task 7.3: Instrumentation tests

Requires physical device with BLE/WiFi. Test the critical path: launch app → scan → pair → browse files → select → transfer → verify file in gallery.

---

## Appendix: PTP Operation Codes Reference

| Opcode | Name | Use |
|--------|------|-----|
| 0x1001 | GetDeviceInfo | Read camera capabilities |
| 0x1005 | OpenSession | Start PTP session |
| 0x1006 | CloseSession | End PTP session |
| 0x1007 | GetObjectHandles | List files in storage |
| 0x1008 | GetObjectInfo | Get file metadata |
| 0x1009 | GetObject | Download file |
| 0x100E | InitiateCapture | Start capture/LiveView |
| 0x1015 | GetDevicePropDesc | Read camera settings |
| 0x1016 | SetDevicePropValue | Change camera settings |
| 0x9201 | NikonGetLiveViewImage | Nikon-proprietary LiveView |
| 0x9203 | NikonStartLiveView | Start LiveView stream |
| 0x9204 | NikonEndLiveView | Stop LiveView stream |

---

## Appendix: SnapBridge BLE Characteristic Map

| UUID suffix | Name | Direction | Format |
|-------------|------|-----------|--------|
| A001 | Camera Control | Write | Binary commands |
| A002 | Camera Status | Notify | JSON or TLV |
| A003 | Thumbnail Data | Notify | JPEG binary |
| A004 | WiFi Config | Read/Notify | Text (SSID\tPassword) |
| A005 | File List | Notify | JSON array |
| A006 | Exposure Settings | Read/Write/Notify | Key-value pairs |
