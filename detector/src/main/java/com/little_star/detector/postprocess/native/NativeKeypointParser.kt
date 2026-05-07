package com.little_star.detector.postprocess.native

import com.little_star.detector.model.KeypointModelConfig
import com.little_star.detector.model.ModelConfig
import com.little_star.detector.postprocess.INativeResultParser
import com.little_star.detector.model.BoundingBox
import com.little_star.detector.model.DetectionResult
import com.little_star.detector.model.Keypoint
import com.little_star.detector.model.KeypointDetection

/**
 * 关键点检测结果解析器（Native JNI）
 * 解析 C++ 层返回的 KEYPOINT 类型序列化数据
 *
 * 数据格式：[taskType, numItems, numKpts, (clsId, conf, x1, y1, x2, y2, kx0, ky0, kv0, ...) × numItems]
 */
class NativeKeypointParser : INativeResultParser {

    override fun parse(floatArray: FloatArray, config: ModelConfig): List<DetectionResult> {
        val numItems = floatArray[1].toInt()
        val numKpts = floatArray[2].toInt()
        val stride = BOX_FIELDS + numKpts * KPT_FIELDS
        val results = mutableListOf<DetectionResult>()
        var offset = HEADER_SIZE

        val kptConfig = config as? KeypointModelConfig

        for (i in 0 until numItems) {
            if (offset + stride - 1 >= floatArray.size) break
            val clsId = floatArray[offset].toInt()
            val conf = floatArray[offset + 1]
            val x1 = floatArray[offset + 2]
            val y1 = floatArray[offset + 3]
            val x2 = floatArray[offset + 4]
            val y2 = floatArray[offset + 5]

            if (clsId in 0 until config.numClasses) {
                val keypoints = mutableListOf<Keypoint>()
                for (k in 0 until numKpts) {
                    val kx = floatArray[offset + BOX_FIELDS + k * KPT_FIELDS]
                    val ky = floatArray[offset + BOX_FIELDS + k * KPT_FIELDS + 1]
                    val kv = floatArray[offset + BOX_FIELDS + k * KPT_FIELDS + 2]
                    keypoints.add(
                        Keypoint(
                            x = kx, y = ky, visibility = kv,
                            name = kptConfig?.kptNames?.getOrElse(k) { "kp_$k" } ?: "kp_$k"
                        )
                    )
                }
                results.add(
                    KeypointDetection(
                        classId = clsId,
                        className = config.classNames.getOrElse(clsId) { "class_$clsId" },
                        confidence = conf,
                        boundingBox = BoundingBox(x1, y1, x2, y2),
                        keypoints = keypoints,
                        keypointsLink = kptConfig?.keypointsLink?.map { it[0] to it[1] } ?: emptyList()
                    )
                )
            }
            offset += stride
        }
        return results
    }

    companion object {
        private const val HEADER_SIZE = 3
        // 边界框字段：clsId + conf + x1 + y1 + x2 + y2
        private const val BOX_FIELDS = 6
        // 每个关键点字段：kx + ky + kv
        private const val KPT_FIELDS = 3
    }
}
