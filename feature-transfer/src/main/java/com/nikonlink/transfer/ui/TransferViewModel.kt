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
        transferManager.cancelAll()
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
