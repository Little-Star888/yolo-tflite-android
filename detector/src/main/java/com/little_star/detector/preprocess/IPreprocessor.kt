package com.little_star.detector.preprocess

import android.graphics.Bitmap
import com.little_star.detector.model.ModelConfig
import com.little_star.detector.util.LetterboxTransform

/**
 * 预处理策略接口
 * 定义图像预处理的统一抽象
 */
interface IPreprocessor {

    /**
     * 预处理图像
     * @param bitmap 输入图像
     * @param config 模型配置
     * @return 归一化后的浮点数组 [inputSize * inputSize * 3]
     */
    fun preprocess(bitmap: Bitmap, config: ModelConfig): FloatArray

    /**
     * 获取 Letterbox 变换信息（如果使用了 Letterbox）
     * @return LetterboxTransform 或 null（如果未使用 letterbox）
     */
    fun getLetterboxTransform(): LetterboxTransform?
}
