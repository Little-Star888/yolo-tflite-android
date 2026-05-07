package com.little_star.detector.postprocess.native

import com.little_star.detector.model.ModelConfig
import com.little_star.detector.postprocess.INativeResultParser
import com.little_star.detector.model.ClassificationResult
import com.little_star.detector.model.DetectionResult

/**
 * 图像分类结果解析器（Native JNI）
 * 解析 C++ 层返回的 CLASSIFICATION 类型序列化数据
 *
 * 数据格式：[taskType, numItems, extra, clsId, conf]
 */
class NativeClassificationParser : INativeResultParser {

    override fun parse(floatArray: FloatArray, config: ModelConfig): List<DetectionResult> {
        val offset = HEADER_SIZE
        if (offset + 1 >= floatArray.size) return emptyList()
        val clsId = floatArray[offset].toInt()
        val conf = floatArray[offset + 1]
        return listOf(
            ClassificationResult(
                classId = clsId,
                className = config.classNames.getOrElse(clsId) { "class_$clsId" },
                confidence = conf
            )
        )
    }

    companion object {
        private const val HEADER_SIZE = 3
    }
}
