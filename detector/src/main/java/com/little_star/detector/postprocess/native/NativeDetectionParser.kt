package com.little_star.detector.postprocess.native

import com.little_star.detector.model.ModelConfig
import com.little_star.detector.postprocess.INativeResultParser
import com.little_star.detector.model.BoundingBox
import com.little_star.detector.model.DetectionResult
import com.little_star.detector.model.ObjectDetection

/**
 * 目标检测结果解析器（Native JNI）
 * 解析 C++ 层返回的 DETECTION 类型序列化数据
 *
 * 数据格式：[taskType, numItems, extra, (clsId, conf, x1, y1, x2, y2) × numItems]
 */
class NativeDetectionParser : INativeResultParser {

    override fun parse(floatArray: FloatArray, config: ModelConfig): List<DetectionResult> {
        val numItems = floatArray[1].toInt()
        val results = mutableListOf<DetectionResult>()
        var offset = HEADER_SIZE

        for (i in 0 until numItems) {
            if (offset + ITEM_SIZE - 1 >= floatArray.size) break
            val clsId = floatArray[offset].toInt()
            val conf = floatArray[offset + 1]
            val x1 = floatArray[offset + 2]
            val y1 = floatArray[offset + 3]
            val x2 = floatArray[offset + 4]
            val y2 = floatArray[offset + 5]
            if (clsId in 0 until config.numClasses) {
                results.add(
                    ObjectDetection(
                        classId = clsId,
                        className = config.classNames.getOrElse(clsId) { "class_$clsId" },
                        confidence = conf,
                        boundingBox = BoundingBox(x1, y1, x2, y2)
                    )
                )
            }
            offset += ITEM_SIZE
        }
        return results
    }

    companion object {
        // header: taskType + numItems + extra
        private const val HEADER_SIZE = 3
        // 每个检测项：clsId + conf + x1 + y1 + x2 + y2
        private const val ITEM_SIZE = 6
    }
}
