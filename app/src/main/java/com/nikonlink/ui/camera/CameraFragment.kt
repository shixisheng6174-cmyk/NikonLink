package com.nikonlink.ui.camera

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.nikonlink.R
import com.nikonlink.common.ConnectionState
import com.nikonlink.databinding.FragmentCameraBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import androidx.navigation.fragment.findNavController
import javax.inject.Inject
import com.nikonlink.connection.wifi.PtpIpClient
import com.google.android.material.dialog.MaterialAlertDialogBuilder

@AndroidEntryPoint
class CameraFragment : Fragment(R.layout.fragment_camera) {
    @Inject lateinit var ptp: PtpIpClient

    private val viewModel: CameraViewModel by viewModels()
    private var ipPrefilled = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentCameraBinding.bind(view)

        binding.btnConnect.setOnClickListener {
            val ip = binding.etCameraIp.text.toString().trim()
                .ifEmpty { viewModel.uiState.value.cameraIpHint }
            viewModel.connectPtpIpDirectly(ip)
        }

        binding.btnRetry.setOnClickListener {
            val ip = binding.etCameraIp.text.toString().trim()
                .ifEmpty { viewModel.uiState.value.cameraIpHint }
            viewModel.connectPtpIpDirectly(ip)
        }

        binding.btnDisconnect.setOnClickListener {
            if (ptp.recording.value) {
                Toast.makeText(requireContext(), "相机仍在录制，请先在遥控页停止录制", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            viewModel.disconnect()
        }
        binding.btnOpenAlbum.setOnClickListener { findNavController().navigate(R.id.navigation_browser) }
        binding.btnDiagnostics.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext()).setTitle("连接诊断")
                .setMessage(ptp.diagnostics.value.ifBlank { "尚无连接记录，请先连接相机。" })
                .setPositiveButton("关闭", null).setNeutralButton("复制") { _, _ ->
                    val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("NikonLink 诊断", ptp.diagnostics.value))
                }.show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.events.collect { message ->
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                if (!ipPrefilled) {
                    binding.etCameraIp.setText(state.cameraIpHint)
                    ipPrefilled = true
                }
                binding.progressBar.visibility = View.GONE
                binding.btnConnect.visibility = View.GONE
                binding.btnRetry.visibility = View.GONE
                binding.btnDisconnect.visibility = View.GONE
                binding.btnOpenAlbum.visibility = View.GONE
                binding.etCameraIp.isEnabled = state.connectionState == ConnectionState.Disconnected

                when (state.connectionState) {
                    ConnectionState.Disconnected -> {
                        binding.tvConnectionStatus.text = "未连接"
                        binding.tvConnectionStatus.setTextColor(0xFF888888.toInt())
                        if (state.lastError != null) {
                            binding.tvStatusTitle.text = "连接失败"
                            binding.tvStatusDetail.text = state.lastError
                            binding.tvStatusDetail.setTextColor(0xFFE74C3C.toInt())
                            binding.btnRetry.visibility = View.VISIBLE
                        } else if (state.isOnCameraWifi) {
                            binding.tvStatusTitle.text = "Wi-Fi 已就绪"
                            binding.tvStatusDetail.text =
                                "确认手机已加入相机热点，点击下方建立连接。"
                            binding.tvStatusDetail.setTextColor(0xFF27AE60.toInt())
                            binding.btnConnect.visibility = View.VISIBLE
                        } else {
                            binding.tvStatusTitle.text = "请先连接相机 WiFi"
                            binding.tvStatusDetail.text =
                                "在手机 WLAN 中加入相机热点后，回到这里连接。"
                            binding.tvStatusDetail.setTextColor(0xFF999999.toInt())
                        }
                    }
                    ConnectionState.WiFiConnecting -> {
                        binding.tvConnectionStatus.text = "连接中"
                        binding.tvConnectionStatus.setTextColor(0xFFF5A623.toInt())
                        binding.tvStatusTitle.text = "正在连接相机"
                        binding.tvStatusDetail.text =
                            "正在建立连接，请保持相机开机并等待片刻。"
                        binding.tvStatusDetail.setTextColor(0xFF999999.toInt())
                        binding.progressBar.visibility = View.VISIBLE
                    }
                    ConnectionState.WiFiTransfer -> {
                        binding.tvConnectionStatus.text = "已连接"
                        binding.tvConnectionStatus.setTextColor(0xFF27AE60.toInt())
                        binding.tvStatusTitle.text = ptp.cameraName ?: "相机已就绪"
                        binding.tvStatusDetail.text =
                            "Wi-Fi 连接已建立\n打开相册浏览原片，或前往监看 / 遥控。"
                        binding.tvStatusDetail.setTextColor(0xFF27AE60.toInt())
                        binding.btnDisconnect.visibility = View.VISIBLE
                        binding.btnOpenAlbum.visibility = View.VISIBLE
                    }
                    else -> {
                        binding.tvConnectionStatus.text = state.connectionState.name
                    }
                }
            }
        }

        // Auto-check WiFi on start
        viewModel.refreshWifiState()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshWifiState()
    }
}
