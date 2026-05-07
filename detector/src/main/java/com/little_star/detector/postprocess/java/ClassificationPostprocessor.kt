package com.little_star.detector.postprocess.java

import com.little_star.detector.postprocess.IPostprocessor
import com.little_star.detector.model.ModelConfig
import com.little_star.detector.util.LetterboxTransform
import com.little_star.detector.model.ClassificationResult
import com.little_star.detector.model.DetectionResult

/**
 * 图像分类后处理策略
 *
 * 输出格式：[1, numClasses] 或 [numClasses]
 * 返回置信度最高的类别
 */
class ClassificationPostprocessor : IPostprocessor {

    override fun postprocess(
        outputs: List<FloatArray>,
        imgW: Int,
        imgH: Int,
        confThreshold: Float,
        config: ModelConfig,
        letterboxTransform: LetterboxTransform?
    ): List<DetectionResult> {
        val output = outputs[0]
        var maxScore = Float.NEGATIVE_INFINITY
        var maxClassId = 0

        for (i in output.indices) {
            if (output[i] > maxScore) {
                maxScore = output[i]
                maxClassId = i
            }
        }

        return listOf(
            ClassificationResult(
                classId = maxClassId,
                className = config.classNames.getOrElse(maxClassId) { "class_$maxClassId" },
                confidence = maxScore
            )
        )
    }

    override fun getOutputBufferCount(): Int = 1
}
