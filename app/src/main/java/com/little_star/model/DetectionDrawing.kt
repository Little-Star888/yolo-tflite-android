package com.little_star.model

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.little_star.detector.model.BoundingBoxDetection
import com.little_star.detector.model.ClassificationResult
import com.little_star.detector.model.DetectionResult
import com.little_star.detector.model.KeypointDetection
import com.little_star.detector.model.OrientedBoxDetection
import com.little_star.detector.model.SegmentationResult

// ─────────────────────────────────────────────────────────────
// 内部使用数据结构（app 专属）
// ─────────────────────────────────────────────────────────────

/**
 * 单张图片推理结果（内部使用）
 */
data class ImageInferenceResult(
    val imagePath: String,
    val detections: List<DetectionResult>,
    val inferenceTimeMs: Double
)

/**
 * 目录检测项（不含 Bitmap，用于高效列表渲染）
 */
data class DirectoryItem(
    val fileName: String,
    val detections: List<DetectionResult>,
    val inferenceTimeMs: Double,
    val error: String? = null
)

// ─────────────────────────────────────────────────────────────
// 绘制扩展函数（供 CameraPreview / SharedDetectorViewModel 复用）
// ─────────────────────────────────────────────────────────────

/**
 * 默认 COCO 17 点骨架连接（当配置文件中未指定 keypointsLink 时使用）
 */
val defaultCocoKeypointsLink = listOf(
    0 to 1, 0 to 2, 1 to 3, 2 to 4, 5 to 6, 5 to 7, 7 to 9,
    6 to 8, 8 to 10, 5 to 11, 6 to 12, 11 to 12, 11 to 13,
    13 to 15, 12 to 14, 14 to 16
)

/**
 * 绘制关键点检测结果（骨架连线 + 关键点圆点）
 *
 * @param canvas 画布
 * @param keypointPaint 关键点圆点画笔
 * @param skeletonPaint 骨架连线画笔
 * @param skeletonColors 骨架颜色数组（按连接索引配色）
 * @param defaultKeypointsLink 当 keypointsLink 为空时使用的默认骨架连接
 * @param scale 坐标缩放比例（默认 1.0，不缩放）
 * @param offsetX X 轴偏移量（默认 0）
 * @param offsetY Y 轴偏移量（默认 0）
 * @param keypointRadius 关键点圆点半径（默认 8f）
 */
fun KeypointDetection.drawKeypointsTo(
    canvas: Canvas,
    keypointPaint: Paint,
    skeletonPaint: Paint,
    skeletonColors: IntArray,
    defaultKeypointsLink: List<Pair<Int, Int>> = defaultCocoKeypointsLink,
    scale: Float = 1f,
    offsetX: Float = 0f,
    offsetY: Float = 0f,
    keypointRadius: Float = 8f
) {
    // 绘制骨架连线
    val connections = keypointsLink.ifEmpty { defaultKeypointsLink }
    connections.forEachIndexed { idx, connection ->
        val kp1 = keypoints.getOrNull(connection.first)
        val kp2 = keypoints.getOrNull(connection.second)
        if (kp1 != null && kp2 != null && kp1.visibility > 0.5f && kp2.visibility > 0.5f) {
            skeletonPaint.color = skeletonColors.getOrElse(idx) { Color.WHITE }
            canvas.drawLine(
                kp1.x * scale + offsetX,
                kp1.y * scale + offsetY,
                kp2.x * scale + offsetX,
                kp2.y * scale + offsetY,
                skeletonPaint
            )
        }
    }
    // 绘制关键点圆点
    keypoints.forEach { kp ->
        if (kp.visibility > 0.5f) {
            keypointPaint.color = when {
                kp.visibility > 0.8f -> Color.GREEN
                kp.visibility > 0.5f -> Color.YELLOW
                else -> Color.RED
            }
            canvas.drawCircle(
                kp.x * scale + offsetX,
                kp.y * scale + offsetY,
                keypointRadius,
                keypointPaint
            )
        }
    }
}

/**
 * 绘制边界框
 *
 * @param canvas 画布
 * @param paint 边框画笔
 * @param labelPaint 标签文字画笔（可为空，仅用于显示类别名+置信度）
 * @param showLabel 是否显示类别名和置信度标签
 * @param scale 坐标缩放比例（默认 1.0，不缩放）
 * @param offsetX X 轴偏移量（默认 0）
 * @param offsetY Y 轴偏移量（默认 0）
 */
fun BoundingBoxDetection.drawBoundingBoxTo(
    canvas: Canvas,
    paint: Paint,
    labelPaint: Paint? = null,
    showLabel: Boolean = true,
    scale: Float = 1f,
    offsetX: Float = 0f,
    offsetY: Float = 0f
) {
    val box = boundingBox
    val left = box.x1 * scale + offsetX
    val top = box.y1 * scale + offsetY
    val right = box.x2 * scale + offsetX
    val bottom = box.y2 * scale + offsetY
    canvas.drawRect(left, top, right, bottom, paint)
    if (showLabel && labelPaint != null) {
        val label = "$className ${"%.1f".format(confidence * 100)}%"
        val labelBgPaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
            alpha = 180
        }
        val textBounds = android.graphics.Rect()
        labelPaint.getTextBounds(label, 0, label.length, textBounds)
        val padding = labelPaint.textSize * 0.125f
        val bgRect = RectF(
            left,
            top - textBounds.height() - padding * 2,
            left + textBounds.width() + padding * 2,
            top
        )
        canvas.drawRect(bgRect, labelBgPaint)
        canvas.drawText(label, left + padding, top - padding, labelPaint)
    }
}

/**
 * 绘制实例分割掩膜（静态/实时模式共用）
 * 将 mask 的 crop 区域着色后，缩放到边界框大小绘制
 *
 * @param canvas 画布
 * @param maskPaint 掩膜画笔（建议设置半透明 alpha）
 * @param color 掩膜颜色
 * @param scale 坐标缩放比例（默认 1.0，不缩放）
 * @param offsetX X 轴偏移量（默认 0）
 * @param offsetY Y 轴偏移量（默认 0）
 */
fun SegmentationResult.drawMaskTo(
    canvas: Canvas,
    maskPaint: Paint,
    color: Int,
    scale: Float = 1f,
    offsetX: Float = 0f,
    offsetY: Float = 0f
) {
    val mask = this.mask
    val box = this.boundingBox
    val trueCount = mask.data.count { it }
    if (trueCount == 0) return

    // 创建完整 mask Bitmap 并着色
    val fullBitmap = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ARGB_8888)
    for (y in 0 until mask.height) {
        for (x in 0 until mask.width) {
            if (mask.data[y * mask.width + x]) {
                fullBitmap.setPixel(x, y, color)
            }
        }
    }

    // 裁剪到 crop 区域
    val cropW = mask.cropX2 - mask.cropX1
    val cropH = mask.cropY2 - mask.cropY1
    if (cropW <= 0 || cropH <= 0) {
        fullBitmap.recycle()
        return
    }
    val croppedBitmap = Bitmap.createBitmap(fullBitmap, mask.cropX1, mask.cropY1, cropW, cropH)

    // 映射边界框到目标坐标
    val targetLeft = box.x1 * scale + offsetX
    val targetTop = box.y1 * scale + offsetY
    val targetRight = box.x2 * scale + offsetX
    val targetBottom = box.y2 * scale + offsetY
    val targetWidth = (targetRight - targetLeft).toInt().coerceAtLeast(1)
    val targetHeight = (targetBottom - targetTop).toInt().coerceAtLeast(1)

    // 缩放 mask 到目标大小并绘制
    val scaledMask = Bitmap.createScaledBitmap(croppedBitmap, targetWidth, targetHeight, true)
    canvas.drawBitmap(scaledMask, targetLeft, targetTop, maskPaint)

    // 回收中间 Bitmap
    fullBitmap.recycle()
    if (croppedBitmap != fullBitmap) croppedBitmap.recycle()
    if (scaledMask != croppedBitmap && scaledMask != fullBitmap) scaledMask.recycle()
}

/**
 * 绘制旋转框（OBB）
 *
 * @param canvas 画布
 * @param paint 边框画笔
 * @param labelPaint 标签文字画笔
 * @param showLabel 是否显示标签
 * @param scale 坐标缩放比例（默认 1.0，不缩放）
 * @param offsetX X 轴偏移量（默认 0）
 * @param offsetY Y 轴偏移量（默认 0）
 */
fun OrientedBoxDetection.drawOrientedBoxTo(
    canvas: Canvas,
    paint: Paint,
    labelPaint: Paint? = null,
    showLabel: Boolean = true,
    scale: Float = 1f,
    offsetX: Float = 0f,
    offsetY: Float = 0f
) {
    val box = boundingBox
    val cx = box.centerX * scale + offsetX
    val cy = box.centerY * scale + offsetY
    val w = box.width * scale
    val h = box.height * scale
    val angle = Math.toRadians(rotationAngle.toDouble())
    val cos = kotlin.math.cos(angle).toFloat()
    val sin = kotlin.math.sin(angle).toFloat()
    // 计算四个角的旋转后坐标
    val corners = arrayOf(
        floatArrayOf(-w / 2, -h / 2),
        floatArrayOf(w / 2, -h / 2),
        floatArrayOf(w / 2, h / 2),
        floatArrayOf(-w / 2, h / 2)
    )
    val rotated = corners.map { (dx, dy) ->
        floatArrayOf(cx + dx * cos - dy * sin, cy + dx * sin + dy * cos)
    }
    val path = android.graphics.Path()
    path.moveTo(rotated[0][0], rotated[0][1])
    rotated.forEachIndexed { idx, point ->
        if (idx > 0) path.lineTo(point[0], point[1])
    }
    path.close()
    canvas.drawPath(path, paint)
    if (showLabel && labelPaint != null) {
        val label = "$className ${"%.1f".format(confidence * 100)}% ${"%.1f".format(rotationAngle)}°"
        canvas.drawText(label, cx, cy, labelPaint)
    }
}

/**
 * 绘制分类结果标签
 *
 * @param canvas 画布
 * @param textPaint 文字画笔
 * @param bgPaint 背景画笔
 * @param color 背景颜色
 * @param textSize 文字大小
 * @param x 标签中心 X 坐标（默认画布中心）
 * @param y 标签顶部 Y 坐标（默认 20f）
 */
fun ClassificationResult.drawClassificationLabelTo(
    canvas: Canvas,
    textPaint: Paint,
    bgPaint: Paint,
    color: Int,
    textSize: Float = 36f,
    x: Float = -1f,
    y: Float = 20f
) {
    val label = "$className ${(confidence * 100).toInt()}%"
    textPaint.textSize = textSize
    val textBounds = android.graphics.Rect()
    textPaint.getTextBounds(label, 0, label.length, textBounds)
    val textWidth = textBounds.width().toFloat()
    val textHeight = textBounds.height().toFloat()
    val hPadding = textSize * 0.667f
    val vPadding = textSize * 0.333f
    val radius = (textHeight + vPadding * 2) / 2f

    val centerX = if (x >= 0) x else canvas.width / 2f
    val rectLeft = centerX - (textWidth + hPadding * 2) / 2f
    val rectTop = y
    val rectRight = rectLeft + textWidth + hPadding * 2
    val rectBottom = rectTop + textHeight + vPadding * 2

    bgPaint.color = color
    bgPaint.alpha = (0.85f * 255).toInt()
    canvas.drawRoundRect(rectLeft, rectTop, rectRight, rectBottom, radius, radius, bgPaint)
    bgPaint.alpha = 255

    val textX = centerX - textWidth / 2f
    val textY = rectTop + vPadding + textHeight
    canvas.drawText(label, textX, textY, textPaint)
}
