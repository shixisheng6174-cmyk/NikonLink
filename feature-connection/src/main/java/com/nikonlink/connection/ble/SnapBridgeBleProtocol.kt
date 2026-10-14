package com.nikonlink.connection.ble

import java.util.UUID

/**
 * Nikon SnapBridge BLE protocol constants and command builders.
 */
class SnapBridgeBleProtocol {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000A000-0000-1000-8000-00805F9B34FB")

        val CHAR_CAMERA_CONTROL: UUID = UUID.fromString("0000A001-0000-1000-8000-00805F9B34FB")
        val CHAR_CAMERA_STATUS: UUID = UUID.fromString("0000A002-0000-1000-8000-00805F9B34FB")
        val CHAR_THUMBNAIL_DATA: UUID = UUID.fromString("0000A003-0000-1000-8000-00805F9B34FB")
        val CHAR_WIFI_CONFIG: UUID = UUID.fromString("0000A004-0000-1000-8000-00805F9B34FB")
        val CHAR_FILE_LIST: UUID = UUID.fromString("0000A005-0000-1000-8000-00805F9B34FB")
        val CHAR_EXPOSURE_SETTINGS: UUID = UUID.fromString("0000A006-0000-1000-8000-00805F9B34FB")

        const val CMD_SHUTTER_RELEASE = 0x01.toByte()
        const val CMD_SHUTTER_HALF_PRESS = 0x02.toByte()
        const val CMD_GET_FILE_LIST = 0x10.toByte()
        const val CMD_GET_THUMBNAIL = 0x11.toByte()
        const val CMD_REQUEST_WIFI_CONFIG = 0x20.toByte()
        const val CMD_SET_EXPOSURE = 0x30.toByte()
        const val CMD_GET_EXPOSURE = 0x31.toByte()
        const val CMD_FOCUS_AT = 0x40.toByte()
    }

    fun buildShutterCommand(): ByteArray = byteArrayOf(CMD_SHUTTER_RELEASE)

    fun buildHalfPressCommand(): ByteArray = byteArrayOf(CMD_SHUTTER_HALF_PRESS)

    fun buildFileListRequest(path: String = "/"): ByteArray {
        val pathBytes = path.toByteArray(Charsets.UTF_8)
        val packet = ByteArray(1 + 2 + pathBytes.size)
        packet[0] = CMD_GET_FILE_LIST
        packet[1] = (pathBytes.size shr 8).toByte()
        packet[2] = pathBytes.size.toByte()
        System.arraycopy(pathBytes, 0, packet, 3, pathBytes.size)
        return packet
    }

    fun buildThumbnailRequest(filePath: String): ByteArray {
        val pathBytes = filePath.toByteArray(Charsets.UTF_8)
        val packet = ByteArray(1 + 2 + pathBytes.size)
        packet[0] = CMD_GET_THUMBNAIL
        packet[1] = (pathBytes.size shr 8).toByte()
        packet[2] = pathBytes.size.toByte()
        System.arraycopy(pathBytes, 0, packet, 3, pathBytes.size)
        return packet
    }

    fun buildWifiConfigRequest(): ByteArray = byteArrayOf(CMD_REQUEST_WIFI_CONFIG)

    fun buildExposureQuery(): ByteArray = byteArrayOf(CMD_GET_EXPOSURE)

    fun buildFocusAtCommand(x: Float, y: Float): ByteArray {
        val xi = (x * 10000).toInt()
        val yi = (y * 10000).toInt()
        return byteArrayOf(
            CMD_FOCUS_AT,
            (xi shr 8).toByte(), xi.toByte(),
            (yi shr 8).toByte(), yi.toByte()
        )
    }

    fun parseCameraStatus(data: ByteArray): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val str = String(data, Charsets.UTF_8)
        if (str.startsWith("{")) {
            try {
                val json = org.json.JSONObject(str)
                json.keys().forEachRemaining { key ->
                    result[key] = json.optString(key, "")
                }
            } catch (_: Exception) { }
        }
        return result
    }

    fun parseFileListResponse(data: ByteArray): List<RemoteFileEntry> {
        val entries = mutableListOf<RemoteFileEntry>()
        val str = String(data, Charsets.UTF_8)
        if (str.startsWith("[")) {
            try {
                val jsonArray = org.json.JSONArray(str)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    entries.add(
                        RemoteFileEntry(
                            path = obj.optString("path", ""),
                            name = obj.optString("name", ""),
                            size = obj.optLong("size", 0),
                            isDirectory = obj.optBoolean("is_dir", false),
                            dateModified = obj.optLong("date", 0),
                            fileType = obj.optString("type", "jpg")
                        )
                    )
                }
            } catch (_: Exception) { }
        }
        if (entries.isEmpty()) {
            str.lines().filter { it.isNotBlank() }.forEach { line ->
                val parts = line.split("\t")
                if (parts.size >= 3) {
                    entries.add(
                        RemoteFileEntry(
                            path = parts[0],
                            name = parts[0].substringAfterLast('/'),
                            size = parts[1].toLongOrNull() ?: 0,
                            isDirectory = parts[0].endsWith('/'),
                            dateModified = parts[2].toLongOrNull() ?: 0,
                            fileType = ""
                        )
                    )
                }
            }
        }
        return entries
    }

    data class RemoteFileEntry(
        val path: String,
        val name: String,
        val size: Long,
        val isDirectory: Boolean,
        val dateModified: Long,
        val fileType: String
    )
}
