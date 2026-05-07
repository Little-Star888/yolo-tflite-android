package com.little_star.detector.postprocess.java

import com.little_star.detector.postprocess.IPostprocessor
import com.little_star.detector.model.ModelConfig
import com.little_star.detector.util.LetterboxTransform
import com.little_star.detector.util.NmsProcessor
import com.little_star.detector.model.DetectionResult
import com.little_star.detector.model.InferenceType
import com.little_star.detector.model.OrientedBoxDetection

/**
 * 旋转框检测后处理策略
 *
 * 端到端输出：[1, 300, 7] → [cx,cy,w,h,conf,cls,angle]，无需 NMS
 * 传统输出：[1, 4+numClasses+1, numCandidates] → [cx,cy,w,h, scores×numClasses, angle]，需要手动 NMS
 */
class OrientedBBoxPostprocessor(
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
            InferenceType.END2END -> postprocessEnd2End(
                output,
                imgW,
                imgH,
                confThreshold,
                config,
                letterboxTransform
            )

            InferenceType.TRADITIONAL -> postprocessTraditional(
                output,
                imgW,
                imgH,
                confThreshold,
                config,
                letterboxTransform
            )
        }
    }

    /**
     * 端到端后处理：输出格式 (N, 300, 7) → [cx,cy,w,h,conf,cls,angle]
     * 坐标是归一化的 cx,cy,w,h（0~1），需乘 inputSize 后通过 inverseTransformBox 逆变换
     * 无需 NMS，只需置信度过滤
     */
    private fun postprocessEnd2End(
        output: FloatArray,
        imgW: Int,
        imgH: Int,
        confThreshold: Float,
        config: ModelConfig,
        letterboxTransform: LetterboxTransform?
    ): List<DetectionResult> {
        val inputSize = config.inputSize
        val transform = letterboxTransform ?: LetterboxTransform.compute(inputSize, imgW, imgH)
        val numDetections = output.size / 7
        val results = mutableListOf<DetectionResult>()

        for (i in 0 until numDetections) {
            // 行优先 [300, 7]，格式是归一化的 cx,cy,w,h（0~1）
            val base = i * 7
            val cx = output[base + 0] * inputSize  // 转换为 letterbox 像素坐标
            val cy = output[base + 1] * inputSize
            val w = output[base + 2] * inputSize
            val h = output[base + 3] * inputSize
            val conf = output[base + 4]
            val cls = output[base + 5].toInt()
            val angle = output[base + 6]

            if (conf >= confThreshold && conf <= 1.0f && cls in 0 until config.numClasses) {
                // 只调用一次 inverseTransformBox，不要再手动做 offset/scale 变换
                val box = transform.inverseTransformBox(
                    cx - w / 2, cy - h / 2,
                    cx + w / 2, cy + h / 2
                )
                val angleDeg = Math.toDegrees(angle.toDouble()).toFloat()
                results.add(
                    OrientedBoxDetection(
                        classId = cls,
                        className = config.classNames.getOrElse(cls) { "class_$cls" },
                        confidence = conf,
                        boundingBox = box,
                        rotationAngle = angleDeg
                    )
                )
            }
        }
        return results
    }

    /**
     * 传统后处理：输出格式 (N, 4+numClasses+1, numCandidates) → [cx,cy,w,h, scores×numClasses, angle]
     * 坐标是归一化的 cx,cy,w,h（0~1），需乘 inputSize 后通过 inverseTransformBox 逆变换
     * 需要手动 NMS
     */
    private fun postprocessTraditional(
        output: FloatArray,
        imgW: Int,
        imgH: Int,
        confThreshold: Float,
        config: ModelConfig,
        letterboxTransform: LetterboxTransform?
    ): List<DetectionResult> {
        val numClasses = config.numClasses
        val numCandidates = output.size / (4 + numClasses + 1)
        val transform =
            letterboxTransform ?: LetterboxTransform.compute(config.inputSize, imgW, imgH)

        val candidates = mutableListOf<OBBCandidate>()

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

            val angle = output[(4 + numClasses) * numCandidates + i]

            if (maxScore >= confThreshold) {
                candidates.add(
                    OBBCandidate(
                        x1 = cx - w / 2, y1 = cy - h / 2,
                        x2 = cx + w / 2, y2 = cy + h / 2,
                        confidence = maxScore, classId = maxClassId,
                        angle = angle
                    )
                )
            }
        }

        val finalResults = mutableListOf<DetectionResult>()
        val groupedByClass = candidates.groupBy { it.classId }

        for ((classId, classCandidates) in groupedByClass) {
            val candidateBoxes = classCandidates.map {
                NmsProcessor.CandidateBox(it.x1, it.y1, it.x2, it.y2, it.confidence, it.classId)
            }
            val kept = nmsProcessor.applyNms(candidateBoxes)
            val keptSet = kept.toSet()

            for (c in classCandidates.filter {
                keptSet.contains(
                    NmsProcessor.CandidateBox(
                        it.x1,
                        it.y1,
                        it.x2,
                        it.y2,
                        it.confidence,
                        it.classId
                    )
                )
            }) {
                val box = transform.inverseTransformBox(c.x1, c.y1, c.x2, c.y2)
                val angleDeg = Math.toDegrees(c.angle.toDouble()).toFloat()
                finalResults.add(
                    OrientedBoxDetection(
                        classId = classId,
                        className = config.classNames.getOrElse(classId) { "class_$classId" },
                        confidence = c.confidence,
                        boundingBox = box,
                        rotationAngle = angleDeg
                    )
                )
            }
        }

        return finalResults.sortedByDescending { it.confidence }
    }

    override fun getOutputBufferCount(): Int = 1

    private data class OBBCandidate(
        val x1: Float, val y1: Float,
        val x2: Float, val y2: Float,
        val confidence: Float,
        val classId: Int,
        val angle: Float
    )
}
