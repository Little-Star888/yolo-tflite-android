package com.little_star.detector.preprocess

import android.graphics.Bitmap
import com.little_star.detector.model.ModelConfig
import com.little_star.detector.util.LetterboxTransform
import kotlin.math.min
import androidx.core.graphics.scale

/**
 * CenterCrop 预处理策略
 * 适用于：分类任务
 *
 * 流程：
 * 1. 缩放最短边到 inputSize
 * 2. 中心裁剪到 inputSize x inputSize
 * 3. 归一化像素值到 [0, 1]
 */
class CenterCropPreprocessor : IPreprocessor {

    override fun preprocess(bitmap: Bitmap, config: ModelConfig): FloatArray {
        val inputSize = config.inputSize
        val floats = FloatArray(inputSize * inputSize * 3)

        val srcW = bitmap.width
        val srcH = bitmap.height

        // 1. 计算缩放比例，使最短边等于 inputSize
        val scale = inputSize.toFloat() / min(srcW, srcH)
        val scaledW = (srcW * scale).toInt()
        val scaledH = (srcH * scale).toInt()

        // 2. 缩放图像
        val scaledBitmap = bitmap.scale(scaledW, scaledH)

        // 3. 中心裁剪
        val startX = (scaledW - inputSize) / 2
        val startY = (scaledH - inputSize) / 2
        val croppedBitmap = Bitmap.createBitmap(scaledBitmap, startX, startY, inputSize, inputSize)

        // 4. 提取像素并归一化到 [0, 1]
        val intValues = IntArray(inputSize * inputSize)
        croppedBitmap.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)

        for (i in intValues.indices) {
            val pixel = intValues[i]
            floats[i * 3]     = ((pixel shr 16) and 0xFF) / 255.0f  // R
            floats[i * 3 + 1] = ((pixel shr 8)  and 0xFF) / 255.0f  // G
            floats[i * 3 + 2] = (pixel and 0xFF) / 255.0f           // B
        }

        if (scaledBitmap != bitmap) scaledBitmap.recycle()
        if (croppedBitmap != bitmap) croppedBitmap.recycle()

        return floats
    }

    override fun getLetterboxTransform(): LetterboxTransform? = null  // 分类不使用 Letterbox
}
