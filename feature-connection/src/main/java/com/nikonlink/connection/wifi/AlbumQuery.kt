package com.nikonlink.connection.wifi

import com.nikonlink.common.FileType

data class AlbumQuery(val media: Int = 0, val extension: String = "", val filename: String = "",
    val from: Long? = null, val until: Long? = null, val oldestFirst: Boolean = false) {
    fun accepts(info: PtpIpClient.ObjectInfo): Boolean {
        if (info.isDirectory) return false
        val ext = info.filename.substringAfterLast('.', "").lowercase()
        val type = FileType.fromExtension(ext)
        return (when (media) { 1 -> type?.isImage == true; 2 -> type == FileType.NEF; 3 -> type?.isVideo == true; else -> true }) &&
            (extension.isEmpty() || ext == extension || (extension == "jpg" && ext == "jpeg")) &&
            info.filename.contains(filename.trim(), ignoreCase = true) &&
            (from == null || info.captureTime >= from) && (until == null || info.captureTime < until)
    }

    /** Known format codes allow the camera to skip other media before fetching metadata. */
    fun acceptsFormat(code: Int): Boolean {
        val extensions = when (code) {
            0x3001 -> return false
            0x3801 -> setOf("jpg", "jpeg")
            0x3802 -> setOf("nef", "nrw")
            0x380D -> setOf("tif", "tiff")
            0xB110 -> setOf("heif", "heic", "hif")
            0x300D, 0xB104, 0xB105 -> setOf("mov")
            0xB982 -> setOf("mp4")
            else -> return true // Preserve unknown Nikon formats; inspect their filenames.
        }
        return extensions.any { ext ->
            val type = FileType.fromExtension(ext)
            (extension.isEmpty() || ext == extension) && when (media) {
                1 -> type?.isImage == true; 2 -> type == FileType.NEF; 3 -> type?.isVideo == true; else -> true
            }
        }
    }
}

/** Merge independently ordered format/storage streams by actual capture date, not global handle value. */
internal class AlbumIndex(groups: List<List<Long>>, private val oldestFirst: Boolean) {
    private val streams = groups.map { if (oldestFirst) it.distinct().sorted() else it.distinct().sortedDescending() }
    private val positions = IntArray(streams.size)
    private val ordered = mutableListOf<PtpIpClient.ObjectInfo>()
    private val seen = mutableSetOf<Long>()
    val total = streams.flatten().distinct().size

    suspend fun page(offset: Int, count: Int, info: suspend (Long) -> PtpIpClient.ObjectInfo?): List<PtpIpClient.ObjectInfo> {
        while (ordered.size < offset + count) {
            val heads = streams.indices.mapNotNull { group ->
                val handle = streams[group].getOrNull(positions[group]) ?: return@mapNotNull null
                group to (info(handle) ?: throw java.io.IOException("无法读取相机文件信息"))
            }
            if (heads.isEmpty()) break
            val next = if (oldestFirst) heads.minBy { it.second.captureTime } else heads.maxBy { it.second.captureTime }
            positions[next.first]++
            if (seen.add(next.second.handle)) ordered.add(next.second)
        }
        return ordered.drop(offset).take(count)
    }
}
