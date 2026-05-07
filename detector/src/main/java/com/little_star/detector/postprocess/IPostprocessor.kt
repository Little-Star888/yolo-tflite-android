package com.little_star.detector.postprocess

import com.little_star.detector.model.ModelConfig
import com.little_star.detector.util.LetterboxTransform
import com.little_star.detector.model.DetectionResult

/**
 * 后处理策略接口
 * 定义模型输出后处理的统一抽象
 */
interface IPostprocessor {

    /**
     * 后处理模型输出
     * @param outputs 模型原始输出列表（按输出索引顺序）
     * @param imgW 原图宽度
     * @param imgH 原图高度
     * @param confThreshold 置信度阈值
     * @param config 模型配置
     * @param letterboxTransform Letterbox 逆变换信息（可为 null）
     * @return 检测结果列表
     */
    fun postprocess(
        outputs: List<FloatArray>,
        imgW: Int,
        imgH: Int,
        confThreshold: Float,
        config: ModelConfig,
        letterboxTransform: LetterboxTransform?
    ): List<DetectionResult>

    /**
     * 获取所需输出缓冲区数量
     * @return 输出缓冲区数量
     */
    fun getOutputBufferCount(): Int
}
