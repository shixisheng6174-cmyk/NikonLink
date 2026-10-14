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
        files: List<Triple<String, String, Long>> // (remotePath, fileName, fileSize)
    ): Int {
        var added = 0
        files.forEach { (remotePath, fileName, fileSize) ->
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
