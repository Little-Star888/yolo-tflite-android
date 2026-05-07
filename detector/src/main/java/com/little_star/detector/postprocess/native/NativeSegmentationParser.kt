package com.little_star.detector.postprocess.native

import com.little_star.detector.model.ModelConfig
import com.little_star.detector.postprocess.INativeResultParser
import com.little_star.detector.model.BoundingBox
import com.little_star.detector.model.DetectionResult
import com.little_star.detector.model.SegmentationMask
import com.little_star.detector.model.SegmentationResult

/**
 * 实例分割结果解析器（Native JNI）
 * 解析 C++ 层返回的 SEGMENTATION 类型序列化数据
 *
 * 数据格式：[taskType, numItems, maskSize, (clsId, conf, x1, y1, x2, y2, cropX1, cropY1, cropX2, cropY2, maskData...) × numItems]
 */
class NativeSegmentationParser : INativeResultParser {

    override fun parse(floatArray: FloatArray, config: ModelConfig): List<DetectionResult> {
        val numItems = floatArray[1].toInt()
        val maskSize = floatArray[2].toInt()
        val stride = BOX_AND_CROP_FIELDS + maskSize
        val results = mutableListOf<DetectionResult>()
        var offset = HEADER_SIZE

        for (i in 0 until numItems) {
            if (offset + stride - 1 >= floatArray.size) break
            val clsId = floatArray[offset].toInt()
            val conf = floatArray[offset + 1]
            val x1 = floatArray[offset + 2]
            val y1 = floatArray[offset + 3]
            val x2 = floatArray[offset + 4]
            val y2 = floatArray[offset + 5]
            val cropX1 = floatArray[offset + 6].toInt()
            val cropY1 = floatArray[offset + 7].toInt()
            val cropX2 = floatArray[offset + 8].toInt()
            val cropY2 = floatArray[offset + 9].toInt()

            if (clsId in 0 until config.numClasses) {
                val maskData = BooleanArray(maskSize)
                for (m in 0 until maskSize) {
                    maskData[m] = floatArray[offset + BOX_AND_CROP_FIELDS + m] > 0.5f
                }
                results.add(
                    SegmentationResult(
                        classId = clsId,
                        className = config.classNames.getOrElse(clsId) { "class_$clsId" },
                        confidence = conf,
                        boundingBox = BoundingBox(x1, y1, x2, y2),
                        mask = SegmentationMask(
                            width = MASK_DIM, height = MASK_DIM, data = maskData,
                            cropX1 = cropX1, cropY1 = cropY1,
                            cropX2 = cropX2, cropY2 = cropY2
                        )
                    )
                )
            }
            offset += stride
        }
        return results
    }

    companion object {
        private const val HEADER_SIZE = 3
        // 边界框 + 裁剪坐标：clsId + conf + x1 + y1 + x2 + y2 + cropX1 + cropY1 + cropX2 + cropY2
        private const val BOX_AND_CROP_FIELDS = 10
        // 掩码尺寸（160 × 160）
        private const val MASK_DIM = 160
    }
}
