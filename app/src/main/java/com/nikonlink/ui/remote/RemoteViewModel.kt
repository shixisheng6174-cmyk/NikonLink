package com.nikonlink.ui.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nikonlink.connection.wifi.PtpIpClient
import com.nikonlink.connection.wifi.CameraProperty
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class RemoteViewModel @Inject constructor(private val ptp: PtpIpClient) : ViewModel() {
    data class State(val preview: Boolean = false, val busy: Boolean = false,
        val message: String = "开启实时取景，在手机上构图。", val frame: ByteArray? = null,
        val properties: List<CameraProperty> = emptyList(), val parametersBusy: Boolean = false)
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    val connected = ptp.connected
    val recording = ptp.recording
    val operations get() = ptp.supportedOperations
    private var previewJob: Job? = null
    private var propertyJob: Job? = null
    private var selector: CameraProperty? = null

    @OptIn(FlowPreview::class)
    suspend fun observeParameters(): Unit = coroutineScope {
        launch {
            connected.collectLatest { isConnected ->
                if (!isConnected) {
                    propertyJob?.cancelAndJoin()
                    selector = null
                    _state.update { it.copy(properties = emptyList()) }
                } else {
                    refreshParameters()
                    while (isActive) { delay(5000); syncValues() }
                }
            }
        }
        launch {
            ptp.propertyChanges.filter { code -> code == 0x500E || code == 0xD1A6 || _state.value.properties.any { it.code == code } }
                .debounce(350).collect { code -> syncValues(code) }
        }
    }

    private fun syncValues(changedCode: Int? = null) {
        if (!connected.value || _state.value.busy || propertyJob?.isActive == true) return
        propertyJob = viewModelScope.launch {
            try {
                val mode = selector?.let { ptp.propertyValue(it) }
                val modeChanged = mode != null && mode.current != selector?.current
                val old = _state.value.properties
                val updated = if (old.isEmpty() || modeChanged || changedCode == 0x500E || changedCode == 0xD1A6) readParameters()
                else {
                    val values = old.map { property ->
                        if (changedCode == null || property.code == changedCode) ptp.propertyValue(property) ?: property else property
                    }
                    if (values.firstOrNull { it.code == 0x500E }?.current != old.firstOrNull { it.code == 0x500E }?.current) readParameters() else values
                }
                _state.update { it.copy(properties = updated) }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { /* Keep confirmed values; reconnect/poll retries automatically. */ }
        }
    }

    fun refreshParameters() {
        if (!connected.value || propertyJob?.isActive == true || _state.value.busy) return
        propertyJob = viewModelScope.launch {
            _state.update { it.copy(parametersBusy = true) }
            try {
                val properties = readParameters()
                _state.update { it.copy(properties = properties) }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { _state.update { it.copy(message = "读取参数失败：${e.message}") }
            } finally { _state.update { it.copy(parametersBusy = false) } }
        }
    }

    private suspend fun readParameters(): List<CameraProperty> {
        selector = ptp.property(0xD1A6)
        val properties = mutableListOf<CameraProperty>()
        for (code in listOf(0x500E, 0x500D, 0x5007, 0x500F, 0x5010, 0x5005)) {
            if (!connected.value) break
            ptp.property(code)?.let { properties.add(it) }
        }
        return properties
    }

    fun setParameter(property: CameraProperty, value: Long) {
        if (_state.value.busy || _state.value.parametersBusy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = "正在调整${property.title}…") }
            propertyJob?.cancelAndJoin()
            try {
                val confirmed = ptp.setProperty(property, value).getOrThrow()
                _state.update { it.copy(message = "${confirmed.title}：${confirmed.label()}",
                    properties = it.properties.map { old -> if (old.code == confirmed.code) confirmed else old }) }
                val refreshed = readParameters()
                _state.update { it.copy(properties = refreshed) }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { _state.update { it.copy(message = "调整失败：${e.message}") }
            } finally { _state.update { it.copy(busy = false) } }
        }
    }

    fun startPreview() {
        if (previewJob?.isActive == true || _state.value.busy || _state.value.parametersBusy) return
        previewJob = viewModelScope.launch {
            var started = false
            try {
                _state.update { it.copy(busy = true, message = "正在开启实时取景…") }
                ptp.startLiveView().getOrThrow()
                started = true
                _state.update { it.copy(busy = false, preview = true, message = "实时取景已开启") }
                refreshParameters()
                var emptyFrames = 0
                while (isActive) {
                    if (_state.value.busy || _state.value.parametersBusy) { delay(30); continue }
                    val frame = ptp.liveViewFrame().getOrThrow()
                    if (frame != null) {
                        emptyFrames = 0
                        _state.update { it.copy(frame = frame) }
                    } else if (++emptyFrames > 30) throw java.io.IOException("相机持续忙碌，暂时无法取得画面")
                    delay(if (frame == null) 100 else 10)
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = "取景失败：${e.message}") }
            } finally {
                if (started && !recording.value) withContext(NonCancellable) { ptp.endLiveView() }
                _state.update { it.copy(preview = false, busy = false, frame = null) }
            }
        }
    }

    fun stopPreview() { previewJob?.cancel() }

    fun capture() {
        if (_state.value.busy || _state.value.parametersBusy || recording.value) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = "正在发送快门指令…") }
            propertyJob?.cancelAndJoin()
            val result = ptp.capture()
            _state.update { it.copy(busy = false, message = result.fold(
                { "相机已接受拍照指令，可到相册刷新查看。" }, { "拍照失败：${it.message}" })) }
            refreshParameters()
        }
    }

    fun toggleRecording() {
        if (_state.value.busy || _state.value.parametersBusy) return
        viewModelScope.launch {
            val start = !recording.value
            _state.update { it.copy(busy = true, message = if (start) "正在开始录制…" else "正在停止录制…") }
            propertyJob?.cancelAndJoin()
            val result = ptp.movie(start)
            _state.update { it.copy(busy = false, message = result.fold(
                { if (start) "正在录制，视频保存在相机存储卡。" else "相机已停止录制。" },
                { "录制指令失败：${it.message}" })) }
            refreshParameters()
        }
    }
}
