package com.little_star.ui.home

/**
 * 格式化文件大小为可读字符串
 */
internal fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024 * 1024 * 1024)} GB"
    }
}

/**
 * 格式化下载速度为可读字符串
 */
internal fun formatSpeed(bytesPerSec: Long): String {
    return when {
        bytesPerSec < 1024 -> "$bytesPerSec B/s"
        bytesPerSec < 1024 * 1024 -> String.format("%.1f KB/s", bytesPerSec / 1024.0)
        else -> String.format("%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
    }
}
