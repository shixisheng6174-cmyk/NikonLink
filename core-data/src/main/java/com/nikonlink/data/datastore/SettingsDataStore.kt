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
    val storagePath: Flow<String> = context.dataStore.data.map { it[Keys.STORAGE_PATH] ?: "" }

    suspend fun setAutoTransfer(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_TRANSFER] = enabled }
    }

    suspend fun setTransferQuality(quality: String) {
        context.dataStore.edit { it[Keys.TRANSFER_QUALITY] = quality }
    }

    suspend fun setPowerSaveEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.POWER_SAVE_ENABLED] = enabled }
    }

    suspend fun setStoragePath(path: String) {
        context.dataStore.edit { it[Keys.STORAGE_PATH] = path }
    }
}
