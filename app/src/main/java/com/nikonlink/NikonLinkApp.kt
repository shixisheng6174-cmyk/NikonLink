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
                "File Transfer",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows file transfer progress"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
