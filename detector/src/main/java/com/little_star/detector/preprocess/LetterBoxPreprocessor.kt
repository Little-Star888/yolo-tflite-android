package com.little_star.detector.preprocess

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import androidx.core.graphics.createBitmap
import com.little_star.detector.model.ModelConfig
import com.little_star.detector.util.LetterboxTransform

/**
 * LetterBox 预处理策略
 * 适用于：检测、分割、关键点、旋转框任务
 *
 * 流程：
 * 1. 保持宽高比缩放图像，使最长边等于 inputSize
 * 2. 用黑色填充到正方形
 * 3. 归一化像素值到 [-1, 1]（RGB - 128）/ 128
 */
class LetterBoxPreprocessor : IPreprocessor {

    companion object {
        private const val INV_128 = 1f / 128f
    }

    // 预分配的缓冲区
    private var preallocFloats: FloatArray? = null
    private var preallocInts: IntArray? = null

    // Letterbox 变换参数（每次 preprocess 时更新）
    private var lastTransform: LetterboxTransform? = null

    override fun preprocess(bitmap: Bitmap, config: ModelConfig): FloatArray {
        val inputSize = config.inputSize

        // 初始化或验证预分配缓冲区
        val pixelCount = inputSize * inputSize
        val floats = preallocFloats?.takeIf { it.size == pixelCount * 3 }
            ?: FloatArray(pixelCount * 3).also { preallocFloats = it }
        val intValues = preallocInts?.takeIf { it.size == pixelCount }
            ?: IntArray(pixelCount).also { preallocInts = it }

        // 计算 Letterbox 变换参数
        lastTransform = LetterboxTransform.compute(inputSize, bitmap.width, bitmap.height)

        val t0 = System.nanoTime()

        // Letterbox 缩放
        val paddedBitmap = letterboxResize(bitmap, inputSize, lastTransform!!)
        val t1 = System.nanoTime()

        paddedBitmap.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)
        val t2 = System.nanoTime()

        // 归一化到 [-1, 1]
        for (i in intValues.indices) {
            val pixel = intValues[i]
            floats[i * 3]     = (((pixel shr 16) and 0xFF) - 128) * INV_128
            floats[i * 3 + 1] = (((pixel shr 8)  and 0xFF) - 128) * INV_128
            floats[i * 3 + 2] = ((pixel and 0xFF) - 128) * INV_128
        }
        val t3 = System.nanoTime()

        android.util.Log.i("LetterBoxPreprocess", String.format(
            "preprocess | resize=%.2f getPixels=%.2f normalize=%.2f | total=%.2f",
            (t1 - t0) / 1_000_000.0,
            (t2 - t1) / 1_000_000.0,
            (t3 - t2) / 1_000_000.0,
            (t3 - t0) / 1_000_000.0))

        paddedBitmap.recycle()
        return floats
    }

    private fun letterboxResize(bitmap: Bitmap, inputSize: Int, transform: LetterboxTransform): Bitmap {
        val padded = createBitmap(inputSize, inputSize)
        val canvas = Canvas(padded)
        canvas.drawColor(Color.BLACK)

        val matrix = Matrix()
        matrix.setScale(transform.scale, transform.scale)
        matrix.postTranslate(transform.offsetX, transform.offsetY)
        canvas.drawBitmap(bitmap, matrix, null)

        return padded
    }

    override fun getLetterboxTransform(): LetterboxTransform? = lastTransform
}
