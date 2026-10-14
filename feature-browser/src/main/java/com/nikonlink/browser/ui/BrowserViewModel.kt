package com.nikonlink.browser.ui

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nikonlink.common.ConnectionState
import com.nikonlink.common.FileType
import com.nikonlink.connection.ConnectionManager
import com.nikonlink.connection.wifi.PtpIpClient
import com.nikonlink.connection.wifi.AlbumQuery
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class BrowserViewModel @Inject constructor(
    private val connectionManager: ConnectionManager,
    private val ptpIpClient: PtpIpClient,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    data class BrowserUiState(
        val currentPath: String = "/DCIM",
        val entries: List<FileEntry> = emptyList(),
        val isLoading: Boolean = false,
        val selectedPaths: Set<String> = emptySet(),
        val isMultiSelectMode: Boolean = false,
        val filterType: FileType? = null,
        val sortOrder: SortOrder = SortOrder.DATE_DESC,
        val query: AlbumQuery = AlbumQuery(),
        val scannedObjects: Int = 0,
        val selectedTotalSize: Long = 0,
        val isTransferring: Boolean = false,
        val listError: String? = null,
        val transferProgress: String? = null,
        val completedTransfers: Int = 0,
        val listProgress: String? = null,
        val hasMore: Boolean = true,
        val totalObjects: Int = 0
    )

    data class FileEntry(
        val handle: Long,
        val path: String,
        val name: String,
        val size: Long,
        val isDirectory: Boolean,
        val dateModified: Long,
        val fileType: FileType?
    )

    enum class SortOrder { NAME_ASC, NAME_DESC, DATE_DESC, DATE_ASC, SIZE_DESC }

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<String>()
    val events: SharedFlow<String> = _events.asSharedFlow()

    /** Exposes the connection state so the fragment can decide when to load. */
    val connectionState: StateFlow<ConnectionState> = connectionManager.connectionState
    private var loadJob: Job? = null
    private var allEntries: List<FileEntry> = emptyList()
    private var nextOffset = 0
    private var cameraSession: Long = -1
    private val thumbCache = object : android.util.LruCache<Long, ByteArray>(8 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: ByteArray) = value.size
    }

    suspend fun thumbnail(handle: Long): ByteArray? {
        thumbCache.get(handle)?.let { return it }
        return ptpIpClient.thumbnail(handle)?.also { thumbCache.put(handle, it) }
    }

    suspend fun preview(handle: Long) = ptpIpClient.preview(handle)

    fun downloadOne(entry: FileEntry) {
        if (_uiState.value.isTransferring) return
        _uiState.update { it.copy(selectedPaths = setOf(entry.path), selectedTotalSize = entry.size, isMultiSelectMode = true) }
        downloadSelected()
    }

    fun loadDirectory(path: String) {
        if (_uiState.value.isTransferring || loadJob?.isActive == true) return
        allEntries = emptyList()
        nextOffset = 0
        thumbCache.evictAll()
        _uiState.update { it.copy(isLoading = true, currentPath = path, selectedPaths = emptySet(),
            selectedTotalSize = 0, isMultiSelectMode = false, listError = null,
            entries = emptyList(), hasMore = true, scannedObjects = 0, listProgress = "正在查找影像…") }
        loadNextPage(refresh = true)
    }

    fun ensureLoaded() {
        if (allEntries.isEmpty() || cameraSession != ptpIpClient.sessionGeneration) loadDirectory("/DCIM")
    }

    fun loadNextPage(refresh: Boolean = false) {
        if (loadJob?.isActive == true || _uiState.value.isTransferring || !_uiState.value.hasMore) return
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, listError = null) }
            try {
                var reload = refresh
                do {
                    val page = ptpIpClient.browsePage(nextOffset, reload, _uiState.value.query)
                    reload = false
                    nextOffset = page.nextOffset
                    cameraSession = ptpIpClient.sessionGeneration
                    allEntries = (allEntries + mapObjects(page.objects)).distinctBy { it.handle }
                    _uiState.update { it.copy(entries = applyFilterAndSort(allEntries, null, it.sortOrder),
                        hasMore = page.nextOffset < page.total, totalObjects = page.total,
                        scannedObjects = page.nextOffset, listProgress = "正在查找 · ${page.nextOffset}/${page.total}") }
                } while (allEntries.isEmpty() && _uiState.value.hasMore)
                _uiState.update { it.copy(isLoading = false, listProgress = null) }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, listProgress = null, listError = e.message ?: "读取失败") }
            }
        }
    }

    /** PTP has no real folder browsing here; listing is flat across all storages. */
    fun navigateToFolder(path: String) {
        loadDirectory(path)
    }

    fun navigateBack(): Boolean {
        loadDirectory(_uiState.value.currentPath)
        return true
    }

    fun toggleSelection(path: String, size: Long) {
        if (_uiState.value.isTransferring) return
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
        _uiState.update {
            it.copy(selectedPaths = emptySet(), selectedTotalSize = 0, isMultiSelectMode = false)
        }
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

    fun setMediaFilter(mode: Int) {
        changeQuery(_uiState.value.query.copy(media = mode))
    }
    fun changeQuery(query: AlbumQuery) {
        if (query == _uiState.value.query || _uiState.value.isTransferring) return
        pauseLoading()
        _uiState.update { it.copy(query = query) }
        loadDirectory("/DCIM")
    }
    fun setFilename(text: String) = changeQuery(_uiState.value.query.copy(filename = text.trim()))
    fun clearFilters() {
        _uiState.update { it.copy(sortOrder = SortOrder.DATE_DESC, entries = applyFilterAndSort(allEntries, null, SortOrder.DATE_DESC)) }
        changeQuery(AlbumQuery())
    }

    fun pauseLoading() {
        loadJob?.cancel()
        loadJob = null
        _uiState.update { it.copy(isLoading = false) }
    }

    fun setSortOrder(sortOrder: SortOrder) {
        _uiState.update { state ->
            state.copy(
                sortOrder = sortOrder,
                entries = applyFilterAndSort(allEntries, state.filterType, sortOrder)
            )
        }
        changeQuery(_uiState.value.query.copy(oldestFirst = sortOrder == SortOrder.DATE_ASC))
    }

    /** Download every selected file into the phone's gallery folders. */
    fun downloadSelected() {
        val state = _uiState.value
        if (state.selectedPaths.isEmpty()) {
            viewModelScope.launch { _events.emit("请勾选要保存的文件") }
            return
        }
        if (state.isTransferring) return
        pauseLoading()

        val toDownload = state.entries.filter { it.path in state.selectedPaths }
        _uiState.update { it.copy(isTransferring = true, transferProgress = "准备传输…") }
        viewModelScope.launch {
            var done = 0
            for ((index, entry) in toDownload.withIndex()) {
                if (!ptpIpClient.isConnected) break
                _events.emit("传输中 ${index + 1}/${toDownload.size}: ${entry.name}")
                if (downloadToGallery(entry, index + 1, toDownload.size)) done++
            }
            _events.emit("完成：$done/${toDownload.size} 个文件已存入相册 (NikonLink 目录)")
            _uiState.update { it.copy(isTransferring = false, completedTransfers = it.completedTransfers + done,
                transferProgress = "已保存 $done/${toDownload.size} 个文件" + if (done < toDownload.size) " · ${ptpIpClient.lastError ?: "部分文件未完成"}" else " · 系统相册 NikonLink") }
            clearSelection()
        }
    }

    private suspend fun downloadToGallery(entry: FileEntry, index: Int, count: Int): Boolean = withContext(Dispatchers.IO) {
        var pendingUri: Uri? = null
        try {
            val isVideo = entry.fileType?.isVideo == true
            val collection: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (isVideo) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                }
            } else {
                if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, entry.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeFor(entry.name))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        if (isVideo) "Movies/NikonLink" else "Pictures/NikonLink"
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val uri = appContext.contentResolver.insert(collection, values)
                ?: return@withContext false
            pendingUri = uri
            val stream = appContext.contentResolver.openOutputStream(uri)
                ?: throw java.io.IOException("无法写入手机相册")
            val ok = stream.use { output -> ptpIpClient.downloadObject(entry.handle, output) { bytes, total ->
                val percent = if (total > 0) "${bytes * 100 / total}%" else "${bytes / 1024} KB"
                _uiState.update { it.copy(transferProgress = "$index/$count · ${entry.name} · $percent") }
            } }
            if (ok && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val update = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                appContext.contentResolver.update(uri, update, null, null)
            }
            if (!ok) {
                appContext.contentResolver.delete(uri, null, null)
            }
            ok
        } catch (e: Exception) {
            pendingUri?.let { runCatching { appContext.contentResolver.delete(it, null, null) } }
            if (e is CancellationException) throw e
            Log.e("NikonLink-Browse", "download ${entry.name} failed", e)
            false
        }
    }

    private fun mimeTypeFor(fileName: String): String =
        when (fileName.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "heif", "heic", "hif" -> "image/heif"
            "nef", "nrw" -> "image/x-nikon-nef"
            "tif", "tiff" -> "image/tiff"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "r3d" -> "video/x-redcode"
            "nev" -> "video/x-nikon-raw"
            else -> "application/octet-stream"
        }

    private fun mapObjects(objects: List<PtpIpClient.ObjectInfo>): List<FileEntry> {
        return objects
            .filter { !it.isDirectory }
            .map { obj ->
                val ext = obj.filename.substringAfterLast('.', "").lowercase()
                FileEntry(
                    handle = obj.handle,
                    path = "/${obj.handle}/${obj.filename}",
                    name = obj.filename,
                    size = obj.size,
                    isDirectory = false,
                    dateModified = obj.captureTime,
                    fileType = FileType.fromExtension(ext)
                )
            }
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
            SortOrder.NAME_ASC -> result.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name })
            SortOrder.NAME_DESC -> result.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenByDescending { it.name })
            SortOrder.DATE_DESC -> result.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenByDescending { it.dateModified })
            SortOrder.DATE_ASC -> result.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.dateModified })
            SortOrder.SIZE_DESC -> result.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenByDescending { it.size })
        }
        return result
    }
}
