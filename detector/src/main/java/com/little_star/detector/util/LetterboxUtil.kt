package com.little_star.detector.util

import com.little_star.detector.model.BoundingBox

/**
 * Letterbox 变换参数
 * 用于将模型输入/输出坐标在原图和模型输入之间映射
 */
data class LetterboxTransform(
    /** 原图宽度 */
    val imgW: Int,
    /** 原图高度 */
    val imgH: Int,
    /** Letterbox 缩放比例 */
    val scale: Float,
    /** Letterbox 缩放后的宽度 */
    val newW: Int,
    /** Letterbox 缩放后的高度 */
    val newH: Int,
    /** Letterbox X 方向偏移量（padding） */
    val offsetX: Float,
    /** Letterbox Y 方向偏移量（padding） */
    val offsetY: Float
) {
    companion object {
        /**
         * 根据原图尺寸和输入尺寸计算 Letterbox 变换参数
         */
        fun compute(inputSize: Int, imgW: Int, imgH: Int): LetterboxTransform {
            val scale = inputSize.toFloat() / maxOf(imgW, imgH)
            val scaledW = imgW * scale
            val scaledH = imgH * scale
            val newW = scaledW.toInt()
            val newH = scaledH.toInt()
            val offsetX = (inputSize - scaledW) / 2f
            val offsetY = (inputSize - scaledH) / 2f
            return LetterboxTransform(imgW, imgH, scale, newW, newH, offsetX, offsetY)
        }
    }

    /**
     * 将模型输入坐标（Letterbox 坐标系）逆变换回原图坐标
     * @param x 模型输入中的 X 坐标
     * @param y 模型输入中的 Y 坐标
     * @return 原图坐标 (x, y)
     */
    fun inverseTransform(x: Float, y: Float): Pair<Float, Float> {
        return Pair(
            (x - offsetX) / scale,
            (y - offsetY) / scale
        )
    }

    /**
     * 逆变换边界框坐标
     */
    fun inverseTransformBox(
        x1: Float, y1: Float, x2: Float, y2: Float,
        clampToImage: Boolean = true
    ): BoundingBox {
        val (tx1, ty1) = inverseTransform(x1, y1)
        val (tx2, ty2) = inverseTransform(x2, y2)
        return BoundingBox(
            x1 = if (clampToImage) tx1.coerceIn(0f, imgW.toFloat()) else tx1,
            y1 = if (clampToImage) ty1.coerceIn(0f, imgH.toFloat()) else ty1,
            x2 = if (clampToImage) tx2.coerceIn(0f, imgW.toFloat()) else tx2,
            y2 = if (clampToImage) ty2.coerceIn(0f, imgH.toFloat()) else ty2
        )
    }
}
