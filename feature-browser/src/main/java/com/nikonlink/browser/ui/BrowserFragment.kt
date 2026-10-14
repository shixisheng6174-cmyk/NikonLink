package com.nikonlink.browser.ui

import android.app.Dialog
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.widget.doAfterTextChanged
import com.nikonlink.browser.R
import com.nikonlink.browser.databinding.FragmentBrowserBinding
import com.nikonlink.common.ConnectionState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Locale

@AndroidEntryPoint
class BrowserFragment : Fragment() {
    private var _binding: FragmentBrowserBinding? = null
    private val binding get() = _binding!!
    private val viewModel: BrowserViewModel by viewModels()
    private lateinit var adapter: FolderListAdapter
    private var previewDialog: Dialog? = null
    private var searchJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBrowserBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = FolderListAdapter(
            onFolderClick = { viewModel.navigateToFolder(it.path) },
            onFileClick = { showPreview(it) },
            onSelect = { viewModel.toggleSelection(it.path, it.size) },
            thumbnail = { viewModel.thumbnail(it) },
            lifecycleOwner = viewLifecycleOwner,
            onLongClick = { viewModel.toggleSelection(it.path, it.size) }
        )
        val grid = GridLayoutManager(requireContext(), 2)
        binding.recyclerView.layoutManager = grid
        binding.recyclerView.adapter = adapter
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && grid.findLastVisibleItemPosition() >= adapter.itemCount - 8) viewModel.loadNextPage()
            }
        })
        binding.btnBack.setOnClickListener { viewModel.navigateBack() }
        binding.btnFilters.setOnClickListener { showFilters() }
        binding.searchFilename.setText(viewModel.uiState.value.query.filename)
        binding.searchFilename.doAfterTextChanged { text ->
            val filename = text.toString()
            searchJob?.cancel()
            searchJob = viewLifecycleOwner.lifecycleScope.launch { delay(400); viewModel.setFilename(filename) }
        }
        binding.btnSelectAll.setOnClickListener { viewModel.selectAll() }
        binding.btnTransfer.setOnClickListener { viewModel.downloadSelected() }
        binding.filterGroup.setOnCheckedChangeListener { _, checked ->
            viewModel.setMediaFilter(when (checked) { R.id.filterPhoto -> 1; R.id.filterRaw -> 2; R.id.filterVideo -> 3; else -> 0 })
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.events.collect { Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show() } }
                launch { viewModel.connectionState.collect { if (it == ConnectionState.WiFiTransfer) viewModel.ensureLoaded() } }
                launch { viewModel.uiState.collect { state ->
                    adapter.submitList(state.entries)
                    adapter.updateSelectedPaths(state.selectedPaths)
                    binding.tvSummary.text = when {
                        state.listError != null -> state.listError
                        state.isLoading -> state.listProgress ?: "正在查找影像…"
                        else -> "已显示 ${state.entries.size} 项 · 点图放大，勾选保存" + if (state.isLoading) " · 加载中" else ""
                    }
                    binding.btnBack.isEnabled = !state.isLoading && !state.isTransferring
                    binding.tvLoadStatus.visibility = when {
                        state.isLoading && state.entries.isNotEmpty() -> View.VISIBLE
                        !state.hasMore && state.entries.isNotEmpty() -> View.VISIBLE
                        else -> View.GONE
                    }
                    binding.tvLoadStatus.text = when {
                        state.isLoading -> state.listProgress ?: "正在加载下一批…"
                        !state.hasMore -> "已显示全部 ${state.entries.size} 项"
                        else -> "下滑加载更多"
                    }
                    binding.btnFilters.isEnabled = !state.isTransferring
                    binding.searchFilename.isEnabled = !state.isTransferring
                    val query = state.query
                    val labels = buildList {
                        if (query.extension.isNotEmpty()) add(query.extension.uppercase())
                        query.from?.let { add("从 ${dateLabel(it)}") }
                        query.until?.let { add("至 ${dateLabel(it - 1)}") }
                        if (query.filename.isNotEmpty()) add("文件名：${query.filename}")
                    }
                    binding.tvFilters.text = labels.joinToString(" · ")
                    binding.tvFilters.visibility = if (labels.isEmpty()) View.GONE else View.VISIBLE
                    val radio = when(query.media) { 1 -> R.id.filterPhoto; 2 -> R.id.filterRaw; 3 -> R.id.filterVideo; else -> R.id.filterAll }
                    if (binding.filterGroup.checkedRadioButtonId != radio) binding.filterGroup.check(radio)
                    binding.btnTransfer.isEnabled = !state.isTransferring
                    binding.btnSelectAll.isEnabled = !state.isTransferring
                    binding.btnSelectAll.text = "全选已加载"
                    for (i in 0 until binding.filterGroup.childCount) binding.filterGroup.getChildAt(i).isEnabled = !state.isTransferring
                    binding.tvTransferProgress.visibility = if (state.transferProgress != null) View.VISIBLE else View.GONE
                    binding.tvTransferProgress.text = state.transferProgress
                    binding.selectionBar.visibility = if (state.isMultiSelectMode) View.VISIBLE else View.GONE
                    binding.tvSelectedCount.text = if (state.isTransferring) "正在保存…" else "已选 ${state.selectedPaths.size} 项 (${FolderListAdapter.formatSize(state.selectedTotalSize)})"
                    binding.tvEmpty.visibility = if (state.entries.isEmpty()) View.VISIBLE else View.GONE
                    binding.tvEmpty.text = when {
                        viewModel.connectionState.value != ConnectionState.WiFiTransfer -> "请先在「相机」页建立连接"
                        state.isLoading -> "正在查找符合条件的影像…\n可随时暂停或更改筛选"
                        state.listError != null -> "读取失败\n${state.listError}"
                        state.hasMore -> "尚未找到匹配文件\n下滑继续查找"
                        else -> "没有符合条件的影像\n可清除筛选或更改照片 / 视频类型"
                    }
                } }
            }
        }
    }

    private fun dateLabel(value: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(java.util.Date(value))

    private fun showFilters() {
        val state = viewModel.uiState.value
        var from = state.query.from
        var until = state.query.until
        val box = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(24.dp(), 8.dp(), 24.dp(), 8.dp()) }
        fun label(text: String) { box.addView(TextView(requireContext()).apply { this.text = text; setPadding(0, 12.dp(), 0, 4.dp()) }) }
        val extensions = listOf("", "jpg", "nef", "nrw", "heif", "heic", "hif", "tif", "tiff", "mov", "mp4", "nev", "r3d")
        label("文件格式")
        val format = Spinner(requireContext()).apply {
            adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, extensions.map { if (it.isEmpty()) "全部格式" else it.uppercase() })
            setSelection(extensions.indexOf(state.query.extension).coerceAtLeast(0))
        }
        box.addView(format)
        label("排序")
        val orders = listOf(BrowserViewModel.SortOrder.DATE_DESC, BrowserViewModel.SortOrder.DATE_ASC, BrowserViewModel.SortOrder.NAME_ASC, BrowserViewModel.SortOrder.NAME_DESC, BrowserViewModel.SortOrder.SIZE_DESC)
        val sort = Spinner(requireContext()).apply {
            adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, listOf("拍摄时间：从新到旧", "拍摄时间：从旧到新", "文件名 A → Z（已加载）", "文件名 Z → A（已加载）", "文件大小（已加载）"))
            setSelection(orders.indexOf(state.sortOrder).coerceAtLeast(0))
        }
        box.addView(sort)
        label("拍摄日期")
        val start = Button(requireContext())
        val end = Button(requireContext())
        fun labels() { start.text = "开始：${from?.let { dateLabel(it) } ?: "不限"}"; end.text = "结束：${until?.let { dateLabel(it - 1) } ?: "不限"}" }
        fun chooseDate(initial: Long?, endDate: Boolean) {
            val calendar = Calendar.getInstance().apply { if (initial != null) timeInMillis = initial }
            DatePickerDialog(requireContext(), { _, year, month, day ->
                val value = Calendar.getInstance().apply { set(year, month, day, 0, 0, 0); set(Calendar.MILLISECOND, 0); if (endDate) add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
                if (endDate) until = value else from = value
                labels()
            }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
        }
        start.setOnClickListener { chooseDate(from, false) }
        end.setOnClickListener { chooseDate(until?.minus(1), true) }
        start.setOnLongClickListener { from = null; labels(); true }
        end.setOnLongClickListener { until = null; labels(); true }
        labels(); box.addView(start); box.addView(end)
        label("长按日期可取消限制。搜索会逐批查找；已有结果可直接查看、保存。")
        val dialog = AlertDialog.Builder(requireContext()).setTitle("筛选相机影像").setView(box)
            .setPositiveButton("应用", null).setNegativeButton("取消", null)
            .setNeutralButton("清除筛选") { _, _ -> binding.searchFilename.setText(""); viewModel.clearFilters() }.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (from != null && until != null && from!! >= until!!) {
                    Toast.makeText(requireContext(), "结束日期不能早于开始日期", Toast.LENGTH_SHORT).show()
                } else {
                    val order = orders[sort.selectedItemPosition]
                    viewModel.changeQuery(viewModel.uiState.value.query.copy(extension = extensions[format.selectedItemPosition], from = from, until = until, oldestFirst = order == BrowserViewModel.SortOrder.DATE_ASC))
                    viewModel.setSortOrder(order)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun showPreview(entry: BrowserViewModel.FileEntry) {
        previewDialog?.dismiss()
        viewModel.pauseLoading()
        adapter.pauseThumbnails()
        val dialog = Dialog(requireContext(), android.R.style.Theme_Material_NoActionBar)
        previewDialog = dialog
        val box = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 20, 20, 20); setBackgroundColor(0xFF101416.toInt()) }
        val title = TextView(requireContext()).apply { text = entry.name; setTextColor(-1); textSize = 16f; setPadding(12, 16, 12, 16) }
        val image = ZoomImageView(requireContext())
        val hint = TextView(requireContext()).apply {
            text = "正在读取预览…"; setTextColor(0xFFB0B9BF.toInt()); gravity = Gravity.CENTER; setPadding(8, 16, 8, 16)
        }
        val controls = LinearLayout(requireContext())
        fun button(label: String, action: () -> Unit) = Button(requireContext()).apply { text = label; setOnClickListener { action() } }
        controls.addView(button("关闭") { dialog.dismiss() }, LinearLayout.LayoutParams(0, 56.dp(), 1f))
        controls.addView(button("勾选") { viewModel.toggleSelection(entry.path, entry.size); dialog.dismiss() }, LinearLayout.LayoutParams(0, 56.dp(), 1f))
        controls.addView(button("保存原片") { viewModel.downloadOne(entry); dialog.dismiss() }, LinearLayout.LayoutParams(0, 56.dp(), 1f))
        box.addView(title)
        box.addView(image, LinearLayout.LayoutParams(-1, 0, 1f))
        box.addView(hint)
        box.addView(controls)
        dialog.setContentView(box)
        dialog.show()
        val job = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val bytes = viewModel.preview(entry.handle)
                val bitmap = bytes?.let { withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(it, 0, it.size) } }
                image.setImageBitmap(bitmap)
                hint.text = when {
                    bitmap == null -> "相机未提供预览，可保存原文件查看"
                    entry.fileType?.isVideo == true -> "视频封面 · 保存到手机后播放"
                    else -> "双指缩放 · 放大后拖动画面 · 双击复位"
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { hint.text = "无法读取预览：${e.message}" }
        }
        dialog.setOnDismissListener { job.cancel(); previewDialog = null; if (isResumed) adapter.resumeThumbnails() }
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
    override fun onStart() { super.onStart(); if (::adapter.isInitialized) adapter.resumeThumbnails() }
    override fun onStop() {
        searchJob?.cancel()
        previewDialog?.dismiss()
        viewModel.pauseLoading()
        if (::adapter.isInitialized) adapter.pauseThumbnails()
        super.onStop()
    }
    override fun onDestroyView() { binding.recyclerView.adapter = null; super.onDestroyView(); _binding = null }
}
