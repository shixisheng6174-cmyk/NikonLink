package com.nikonlink.browser.ui

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nikonlink.browser.databinding.ItemFileEntryBinding
import kotlinx.coroutines.*

class FolderListAdapter(
    private val onFolderClick: (BrowserViewModel.FileEntry) -> Unit,
    private val onFileClick: (BrowserViewModel.FileEntry) -> Unit,
    private val onSelect: (BrowserViewModel.FileEntry) -> Unit,
    private val thumbnail: suspend (Long) -> ByteArray?,
    private val lifecycleOwner: LifecycleOwner,
    private val onLongClick: (BrowserViewModel.FileEntry) -> Unit
) : ListAdapter<BrowserViewModel.FileEntry, FolderListAdapter.ViewHolder>(DiffCallback) {
    private var selectedPaths: Set<String> = emptySet()
    private val holders = mutableSetOf<ViewHolder>()
    fun pauseThumbnails() { holders.forEach { it.cancel() } }
    fun resumeThumbnails() { notifyDataSetChanged() }
    fun updateSelectedPaths(paths: Set<String>) {
        if (paths == selectedPaths) return
        val changed = paths xor selectedPaths
        selectedPaths = paths
        currentList.forEachIndexed { index, entry -> if (entry.path in changed) notifyItemChanged(index, "selection") }
    }
    private infix fun <T> Set<T>.xor(other: Set<T>) = (this - other) + (other - this)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemFileEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))
    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) holder.selection(getItem(position)) else holder.bind(getItem(position))
    }
    override fun onViewRecycled(holder: ViewHolder) { holder.cancel(); holders.remove(holder); super.onViewRecycled(holder) }
    inner class ViewHolder(private val binding: ItemFileEntryBinding) : RecyclerView.ViewHolder(binding.root) {
        private var job: Job? = null
        fun cancel() { job?.cancel(); job = null }
        fun selection(entry: BrowserViewModel.FileEntry) {
            binding.selectionCheckbox.isChecked = entry.path in selectedPaths
            binding.root.alpha = if (entry.path in selectedPaths) 0.75f else 1f
        }
        fun bind(entry: BrowserViewModel.FileEntry) {
            holders.add(this)
            cancel()
            binding.fileName.text = entry.name
            binding.fileSize.text = formatSize(entry.size) + if (entry.dateModified > 0) {
                " · " + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(entry.dateModified))
            } else ""
            val ext = entry.name.substringAfterLast('.', "FILE").uppercase()
            binding.fileTypeBadge.text = if (entry.fileType?.isVideo == true) "视频 · $ext" else ext
            binding.fileIcon.text = ext
            binding.fileIcon.visibility = View.VISIBLE
            binding.thumbnail.setImageDrawable(null)
            selection(entry)
            binding.root.setOnClickListener { if (entry.isDirectory) onFolderClick(entry) else onFileClick(entry) }
            binding.selectionCheckbox.setOnClickListener { onSelect(entry) }
            binding.root.setOnLongClickListener { onLongClick(entry); true }
            job = lifecycleOwner.lifecycleScope.launch {
                val data = thumbnail(entry.handle) ?: return@launch
                val bitmap = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(data, 0, data.size) }
                ensureActive()
                if (bitmap != null) { binding.thumbnail.setImageBitmap(bitmap); binding.fileIcon.visibility = View.GONE }
            }
        }
    }
    object DiffCallback : DiffUtil.ItemCallback<BrowserViewModel.FileEntry>() {
        override fun areItemsTheSame(old: BrowserViewModel.FileEntry, new: BrowserViewModel.FileEntry) = old.handle == new.handle
        override fun areContentsTheSame(old: BrowserViewModel.FileEntry, new: BrowserViewModel.FileEntry) = old == new
    }
    companion object {
        fun formatSize(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024L * 1024 * 1024 -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
            else -> "${"%.2f".format(bytes.toDouble() / (1024L * 1024 * 1024))} GB"
        }
    }
}
