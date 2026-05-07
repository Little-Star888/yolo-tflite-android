package com.little_star.detector.postprocess.java

import com.little_star.detector.postprocess.IPostprocessor
import com.little_star.detector.model.ModelConfig
import com.little_star.detector.util.LetterboxTransform
import com.little_star.detector.util.NmsProcessor
import com.little_star.detector.model.DetectionResult
import com.little_star.detector.model.InferenceType
import com.little_star.detector.model.ObjectDetection

/**
 * 目标检测后处理策略
 * 支持 END2END 和 TRADITIONAL 两种推理类型
 */
class DetectionPostprocessor(
    private val inferenceType: InferenceType
) : IPostprocessor {

    companion object {
        private const val NMS_IOU_THRESHOLD = 0.45f
    }

    private val nmsProcessor = NmsProcessor(NMS_IOU_THRESHOLD)

    override fun postprocess(
        outputs: List<FloatArray>,
        imgW: Int,
        imgH: Int,
        confThreshold: Float,
        config: ModelConfig,
        letterboxTransform: LetterboxTransform?
    ): List<DetectionResult> {
        val output = outputs[0]
        return when (inferenceType) {
            InferenceType.END2END -> postprocessEnd2End(output, imgW, imgH, confThreshold, config, letterboxTransform)
            InferenceType.TRADITIONAL -> postprocessTraditional(output, imgW, imgH, confThreshold, config, letterboxTransform)
        }
    }

    private fun postprocessEnd2End(
        output: FloatArray,
        imgW: Int,
        imgH: Int,
        confThreshold: Float,
        config: ModelConfig,
        letterboxTransform: LetterboxTransform?
    ): List<DetectionResult> {
        val results = mutableListOf<DetectionResult>()
        val numDetections = output.size / 6
        val transform = letterboxTransform ?: LetterboxTransform.compute(config.inputSize, imgW, imgH)

        for (i in 0 until numDetections) {
            val baseIndex = i * 6
            val x1 = output[baseIndex + 0]
            val y1 = output[baseIndex + 1]
            val x2 = output[baseIndex + 2]
            val y2 = output[baseIndex + 3]
            val conf = output[baseIndex + 4]
            val cls = output[baseIndex + 5].toInt()

            if (conf >= confThreshold && conf <= 1.0f && cls in 0 until config.numClasses) {
                val box = transform.inverseTransformBox(
                    x1 * config.inputSize, y1 * config.inputSize,
                    x2 * config.inputSize, y2 * config.inputSize
                )
                results.add(
                    ObjectDetection(
                        classId = cls,
                        className = config.classNames.getOrElse(cls) { "class_$cls" },
                        confidence = conf,
                        boundingBox = box
                    )
                )
            }
        }
        return results
    }

    private fun postprocessTraditional(
        output: FloatArray,
        imgW: Int,
        imgH: Int,
        confThreshold: Float,
        config: ModelConfig,
        letterboxTransform: LetterboxTransform?
    ): List<DetectionResult> {
        val numClasses = config.numClasses
        // 动态计算候选数量：输出格式为 [1, 4+numClasses, numCandidates]
        val numCandidates = output.size / (4 + numClasses)
        val transform = letterboxTransform ?: LetterboxTransform.compute(config.inputSize, imgW, imgH)

        // 收集候选
        val candidates = mutableListOf<NmsProcessor.CandidateBox>()
        for (i in 0 until numCandidates) {
            val inputSize = config.inputSize
            val cx = output[0 * numCandidates + i] * inputSize
            val cy = output[1 * numCandidates + i] * inputSize
            val w = output[2 * numCandidates + i] * inputSize
            val h = output[3 * numCandidates + i] * inputSize

            var maxScore = 0f
            var maxClassId = 0
            for (c in 0 until numClasses) {
                val score = output[(4 + c) * numCandidates + i]
                if (score > maxScore) {
                    maxScore = score
                    maxClassId = c
                }
            }

            if (maxScore >= confThreshold) {
                candidates.add(NmsProcessor.CandidateBox(
                    x1 = cx - w / 2,
                    y1 = cy - h / 2,
                    x2 = cx + w / 2,
                    y2 = cy + h / 2,
                    confidence = maxScore,
                    classId = maxClassId
                ))
            }
        }

        // NMS
        val finalResults = mutableListOf<DetectionResult>()
        val groupedByClass = candidates.groupBy { it.classId }

        for ((classId, classCandidates) in groupedByClass) {
            val kept = nmsProcessor.applyNms(classCandidates)
            for (c in kept) {
                val box = transform.inverseTransformBox(c.x1, c.y1, c.x2, c.y2)
                finalResults.add(
                    ObjectDetection(
                        classId = classId,
                        className = config.classNames.getOrElse(classId) { "class_$classId" },
                        confidence = c.confidence,
                        boundingBox = box
                    )
                )
            }
        }

        return finalResults.sortedByDescending { it.confidence }
    }

    override fun getOutputBufferCount(): Int = 1
}
