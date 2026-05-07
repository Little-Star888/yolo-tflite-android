package com.little_star.model

import android.graphics.Bitmap
import com.little_star.detector.model.DetectionResult

/**
 * 视频帧检测结果
 */
data class VideoDetectionResult(
    /** 帧序号 */
    val frameIndex: Int,
    /** 帧时间戳（毫秒） */
    val timestampMs: Long,
    /** 检测到的目标列表 */
    val detections: List<DetectionResult>,
    /** 推理耗时（毫秒） */
    val inferenceTimeMs: Double,
    /** 带检测框的帧图片（可选） */
    val resultBitmap: Bitmap? = null
)
