package com.nikonlink.transfer.worker

import android.content.Context
import android.content.ContentValues
import android.os.Build
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

            if (!connectionManager.connectionState.value.isWifiConnected) {
                return@withContext Result.retry()
            }

            // Downloads now run through BrowserViewModel.downloadSelected() with real
            // PTP object handles; this WorkManager path has no handle available.
            transferTaskDao.updateProgress(task.id, "failed", 0)
            Result.failure()
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
                .setContentTitle("Transferring")
                .setContentText(fileName)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setProgress(100, progress, progress == 0)
                .build()
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(applicationContext)
                .setContentTitle("Transferring")
                .setContentText(fileName)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setProgress(100, progress, progress == 0)
                .build()
        }
        return ForegroundInfo(NOTIFICATION_PROGRESS_ID, notification)
    }
}
