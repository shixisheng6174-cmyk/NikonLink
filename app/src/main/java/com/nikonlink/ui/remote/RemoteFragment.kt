package com.nikonlink.ui.remote

import android.graphics.BitmapFactory
import android.app.AlertDialog
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.nikonlink.R
import com.nikonlink.databinding.FragmentRemoteBinding
import com.nikonlink.connection.wifi.PtpIpClient
import com.nikonlink.connection.wifi.CameraProperty
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest

@AndroidEntryPoint
class RemoteFragment : Fragment(R.layout.fragment_remote) {
    private val vm: RemoteViewModel by viewModels()
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentRemoteBinding.bind(view)
        b.btnPreview.setOnClickListener { if (vm.state.value.preview) vm.stopPreview() else vm.startPreview() }
        b.btnShutter.setOnClickListener { vm.capture() }
        b.btnRecord.setOnClickListener { vm.toggleRecording() }
        var lastFrame: ByteArray? = null
        var lastProperties: List<CameraProperty>? = null
        viewLifecycleOwner.lifecycleScope.launch {
          viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { vm.observeParameters() }
            launch {
            combine(vm.state, vm.connected, vm.recording) { state, connected, recording -> Triple(state, connected, recording) }
                .collectLatest { (state, connected, recording) ->
                    val ops = vm.operations
                    val liveSupported = PtpIpClient.OP_START_LIVE_VIEW in ops && PtpIpClient.OP_LIVE_VIEW_IMAGE in ops
                    b.tvRemoteStatus.text = when {
                        !connected -> "请先在「相机」页建立 Wi-Fi 连接。"
                        !liveSupported -> "相机在当前 Wi-Fi 模式下未提供实时取景。可尝试支持的拍摄功能。"
                        else -> state.message
                    }
                    b.btnPreview.isEnabled = connected && liveSupported && !state.busy && !state.parametersBusy
                    b.btnPreview.text = if (state.preview) "关闭取景" else "开启实时取景"
                    b.btnShutter.isEnabled = connected && !state.busy && !state.parametersBusy && !recording &&
                        (PtpIpClient.OP_CAPTURE_MEDIA in ops || 0x100E in ops)
                    b.btnRecord.isEnabled = connected && !state.busy && !state.parametersBusy &&
                        (if (recording) PtpIpClient.OP_END_MOVIE in ops else PtpIpClient.OP_START_MOVIE in ops)
                    b.btnRecord.text = if (recording) "■  停止录制" else "●  开始录制"
                    b.tvLiveBadge.text = if (recording) "●  REC" else if (state.preview) "●  LIVE" else "STANDBY"
                    b.tvLiveBadge.setTextColor(if (recording) 0xFFFF6B6B.toInt() else 0xFFE4F078.toInt())
                    b.tvParameterHint.text = when {
                        state.parametersBusy -> "正在读取相机参数…"
                        !connected -> "连接相机后可读取参数"
                        state.properties.isEmpty() -> "正在等待相机参数"
                        else -> "参数自动同步 · 只读项目受当前曝光模式或镜头限制"
                    }
                    if (lastProperties != state.properties) {
                        lastProperties = state.properties
                        b.parameterButtons.removeAllViews()
                        for (pair in state.properties.chunked(2)) {
                            val row = LinearLayout(requireContext())
                            for (property in pair) {
                                val button = Button(requireContext()).apply {
                                    text = "${property.title}\n${property.label()}" + if (!property.writable) " · 只读" else ""
                                    isAllCaps = false; textSize = 12f
                                    setOnClickListener { showProperty(property) }
                                }
                                row.addView(button, LinearLayout.LayoutParams(0, (68 * resources.displayMetrics.density).toInt(), 1f))
                            }
                            b.parameterButtons.addView(row)
                        }
                    }
                    for (i in 0 until b.parameterButtons.childCount) {
                        val row = b.parameterButtons.getChildAt(i) as LinearLayout
                        for (j in 0 until row.childCount) row.getChildAt(j).isEnabled = connected && !state.busy && !state.parametersBusy
                    }
                    b.tvPreviewHint.visibility = if (state.frame == null) View.VISIBLE else View.GONE
                    b.tvPreviewHint.text = if (connected) "相机实时画面\n\n点击下方开启取景" else "等待连接相机"
                    if (state.frame !== lastFrame) {
                        val frame = state.frame
                        val bitmap = if (frame == null) null else withContext(Dispatchers.Default) {
                            BitmapFactory.decodeByteArray(frame, 0, frame.size)
                        }
                        b.liveImage.setImageBitmap(bitmap)
                        lastFrame = frame
                    }
                }
            }
          }
        }
    }
    private fun showProperty(property: CameraProperty) {
        if (!property.writable) {
            AlertDialog.Builder(requireContext()).setTitle(property.title)
                .setMessage("当前值：${property.label()}\n当前曝光模式或镜头不允许修改。切换到 M / A / S 模式后会自动更新。")
                .setPositiveButton("知道了", null).show()
            return
        }
        val choices = property.choices()
        if (choices.isNotEmpty()) {
            val dialog = AlertDialog.Builder(requireContext()).setTitle(property.title)
                .setSingleChoiceItems(choices.map { property.label(it) }.toTypedArray(), choices.indexOf(property.current)) { d, index ->
                    vm.setParameter(property, choices[index]); d.dismiss()
                }.setNegativeButton("取消", null).create()
            dialog.show()
            return
        }
        val input = EditText(requireContext()).apply {
            hint = when(property.code) { 0x500D -> "秒，例如 1/125 或 0.5"; 0x5007 -> "光圈，例如 2.8"; 0x5010 -> "EV，例如 -0.3"; else -> "输入数值" }
            setSingleLine()
        }
        val dialog = AlertDialog.Builder(requireContext()).setTitle(property.title).setView(input)
            .setPositiveButton("应用", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try { vm.setParameter(property, property.parseInput(input.text.toString())); dialog.dismiss()
                } catch (e: Exception) { input.error = e.message ?: "数值无效" }
            }
        }
        dialog.show()
    }
    override fun onStop() { vm.stopPreview(); super.onStop() }
}
