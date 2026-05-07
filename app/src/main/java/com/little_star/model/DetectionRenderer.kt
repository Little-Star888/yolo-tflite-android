package com.little_star.model

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.little_star.detector.model.ClassificationResult
import com.little_star.detector.model.DetectionResult
import com.little_star.detector.model.KeypointDetection
import com.little_star.detector.model.ObjectDetection
import com.little_star.detector.model.OrientedBoxDetection
import com.little_star.detector.model.SegmentationResult

/**
 * 检测结果渲染器
 * 将检测结果（边界框、关键点、分割掩码等）绘制到 Bitmap 上
 * 从 SharedDetectorViewModel 中提取，消除 ViewModel 对 Canvas 绘制的直接依赖
 */
object DetectionRenderer {

    // 关键点骨架颜色（左右对称配色）
    val skeletonColors = intArrayOf(
        Color.parseColor("#FF6B6B"),  // 红色
        Color.parseColor("#4ECDC4"),  // 青色
        Color.parseColor("#45B7D1"),  // 蓝色
        Color.parseColor("#96CEB4"),  // 绿色
        Color.parseColor("#FFEAA7"),  // 黄色
        Color.parseColor("#DDA0DD"),  // 紫色
        Color.parseColor("#98D8C8"),  // 薄荷绿
        Color.parseColor("#F7DC6F"),  // 金色
        Color.parseColor("#BB8FCE"),  // 淡紫
        Color.parseColor("#85C1E9"),  // 天蓝
        Color.parseColor("#F8B500"),  // 橙色
        Color.parseColor("#82E0AA"),  // 浅绿
        Color.parseColor("#F1948A"),  // 粉红
        Color.parseColor("#85C1E9"),  // 天蓝
        Color.parseColor("#F8B500"),  // 橙色
        Color.parseColor("#82E0AA")   // 浅绿
    )

    /**
     * 在 Bitmap 上绘制检测结果
     *
     * @param bitmap 原始图片
     * @param results 检测结果列表
     * @param confThreshold 置信度阈值，低于此值的结果不绘制
     * @return 绘制了检测框的 Bitmap
     */
    fun drawDetectionBoxes(
        bitmap: Bitmap,
        results: List<DetectionResult>,
        confThreshold: Float = 0.25f
    ): Bitmap {
        // 超大分辨率图片需要缩小，避免硬件加速 Canvas 超出 GPU 纹理限制（~100MB）导致崩溃
        val maxDim = 4096
        val srcBitmap = if (bitmap.width > maxDim || bitmap.height > maxDim) {
            val scale = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt(),
                (bitmap.height * scale).toInt(),
                true
            )
        } else {
            bitmap
        }

        // 创建可变的副本用于绘制
        val outputBitmap = srcBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(outputBitmap)

        // 根据图片短边动态缩放绘制参数（基准 640px）
        val drawScale = srcBitmap.width.coerceAtMost(srcBitmap.height).toFloat() / 640f

        // 准备绘制工具
        val boxPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = 4f * drawScale
        }

        val textPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            textSize = 32f * drawScale
            color = Color.WHITE
        }

        val bgPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            color = Color.BLACK
        }

        val keypointPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            strokeWidth = 6f * drawScale
        }

        val skeletonPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = 3f * drawScale
        }

        val maskPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            alpha = 128  // 半透明
        }

        results.forEach { result ->
            if (result.confidence < confThreshold) return@forEach

            val boxColor = when {
                result.confidence >= 0.8f -> Color.GREEN
                result.confidence >= 0.5f -> Color.YELLOW
                else -> Color.RED
            }

            when (result) {
                is ClassificationResult -> {
                    result.drawClassificationLabelTo(
                        canvas = canvas, textPaint = textPaint, bgPaint = bgPaint,
                        color = boxColor, textSize = 36f * drawScale, y = 20f * drawScale
                    )
                }

                is SegmentationResult -> {
                    result.drawMaskTo(canvas, maskPaint, boxColor)
                    boxPaint.color = boxColor
                    result.drawBoundingBoxTo(canvas, boxPaint, textPaint)
                }

                is OrientedBoxDetection -> {
                    boxPaint.color = boxColor
                    result.drawOrientedBoxTo(canvas, boxPaint, textPaint)
                }

                is KeypointDetection -> {
                    boxPaint.color = boxColor
                    result.drawBoundingBoxTo(canvas, boxPaint, textPaint)
                    result.drawKeypointsTo(
                        canvas = canvas,
                        keypointPaint = keypointPaint,
                        skeletonPaint = skeletonPaint,
                        skeletonColors = skeletonColors,
                        defaultKeypointsLink = defaultCocoKeypointsLink,
                        keypointRadius = 6f * drawScale
                    )
                }

                is ObjectDetection -> {
                    boxPaint.color = boxColor
                    result.drawBoundingBoxTo(canvas, boxPaint, textPaint)
                }
            }
        }

        return outputBitmap
    }
}
