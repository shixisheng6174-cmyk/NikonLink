// core-common/src/main/java/com/nikonlink/common/FileType.kt
package com.nikonlink.common

enum class FileType(val extensions: List<String>, val isImage: Boolean, val isVideo: Boolean) {
    JPEG(listOf("jpg", "jpeg"), isImage = true, isVideo = false),
    NEF(listOf("nef", "nrw"), isImage = true, isVideo = false),
    TIFF(listOf("tif", "tiff"), isImage = true, isVideo = false),
    HEIF(listOf("heif", "heic", "hif"), isImage = true, isVideo = false),
    MP4(listOf("mp4"), isImage = false, isVideo = true),
    MOV(listOf("mov"), isImage = false, isVideo = true),
    RAW_VIDEO(listOf("r3d", "nev"), isImage = false, isVideo = true);

    companion object {
        fun fromExtension(ext: String): FileType? =
            entries.find { ext.lowercase() in it.extensions }
    }
}
