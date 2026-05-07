package com.little_star.model

import com.little_star.R

/**
 * 检测模式枚举
 * 4种检测场景（相机模式已合并实时检测和拍照检测）
 */
enum class DetectionMode(
    val displayNameRes: Int,
    val descriptionRes: Int,
    val requiresCamera: Boolean
) {
    REALTIME(R.string.mode_camera, R.string.mode_camera_desc, true),
    SINGLE_IMAGE(R.string.mode_image, R.string.mode_image_desc, false),
    IMAGE_DIRECTORY(R.string.mode_directory, R.string.mode_directory_desc, false),
    VIDEO(R.string.mode_video, R.string.mode_video_desc, false)
}
