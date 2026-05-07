package com.little_star.model

import android.graphics.Bitmap
import com.little_star.detector.model.DetectionResult

/**
 * 单张图片检测结果（带可视化）
 */
data class ImageDetectionResult(
    val fileName: String,
    val originalBitmap: Bitmap?,
    val resultBitmap: Bitmap?,
    val detections: List<DetectionResult>,
    val inferenceTimeMs: Double,
    val error: String? = null
)
