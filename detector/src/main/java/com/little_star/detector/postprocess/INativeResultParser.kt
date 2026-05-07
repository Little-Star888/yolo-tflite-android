package com.little_star.detector.postprocess

import com.little_star.detector.model.ModelConfig
import com.little_star.detector.model.DetectionResult

/**
 * Native JNI 结果解析器接口
 * 将 C++ 层返回的序列化 FloatArray 反序列化为 Kotlin DetectionResult 对象
 */
interface INativeResultParser {

    /**
     * 解析 native 层返回的检测结果
     * @param floatArray C++ 序列化格式：[taskType, numItems, extra, data...]
     * @param config 模型配置（提供 classNames 等信息）
     * @return 检测结果列表
     */
    fun parse(floatArray: FloatArray, config: ModelConfig): List<DetectionResult>
}
