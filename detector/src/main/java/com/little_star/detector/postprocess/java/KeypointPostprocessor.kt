package com.little_star.detector.postprocess.java

import com.little_star.detector.postprocess.IPostprocessor
import com.little_star.detector.model.KeypointModelConfig
import com.little_star.detector.model.ModelConfig
import com.little_star.detector.util.LetterboxTransform
import com.little_star.detector.util.NmsProcessor
import com.little_star.detector.model.DetectionResult
import com.little_star.detector.model.InferenceType
import com.little_star.detector.model.Keypoint
import com.little_star.detector.model.KeypointDetection

/**
 * 关键点检测后处理策略
 *
 * 端到端输出：[1, 300, 6+numKpts*3] → [x1,y1,x2,y2,conf,cls, k0x,k0y,k0v, ...]，无需 NMS
 * 传统输出：[1, 4+numClasses+numKpts*3, 8400] → 需要手动 NMS
 */
class KeypointPostprocessor(
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

    /**
     * 端到端后处理：输出格式 (N, 300, 6+numKpts*3)
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
        val kptConfig = config as KeypointModelConfig
        val numKeypoints = kptConfig.numKeypoints
        val inputSize = kptConfig.inputSize
        val transform = letterboxTransform ?: LetterboxTransform.compute(inputSize, imgW, imgH)

        val stride = 6 + numKeypoints * 3
        val numDetections = output.size / stride
        val results = mutableListOf<DetectionResult>()

        for (i in 0 until numDetections) {
            val base = i * stride
            val x1 = output[base + 0]
            val y1 = output[base + 1]
            val x2 = output[base + 2]
            val y2 = output[base + 3]
            val conf = output[base + 4]
            val cls = output[base + 5].toInt()

            if (conf >= confThreshold && conf <= 1.0f && cls in 0 until kptConfig.numClasses) {
                val box = transform.inverseTransformBox(
                    x1 * inputSize, y1 * inputSize,
                    x2 * inputSize, y2 * inputSize
                )
                val keypoints = mutableListOf<Keypoint>()
                for (k in 0 until numKeypoints) {
                    val kx = output[base + 6 + k * 3] * inputSize
                    val ky = output[base + 6 + k * 3 + 1] * inputSize
                    val kv = output[base + 6 + k * 3 + 2]
                    val (tx, ty) = transform.inverseTransform(kx, ky)
                    keypoints.add(Keypoint(tx, ty, kv, kptConfig.kptNames.getOrElse(k) { "kp_$k" }))
                }
                results.add(
                    KeypointDetection(
                        classId = cls,
                        className = kptConfig.classNames.getOrElse(cls) { "class_$cls" },
                        confidence = conf,
                        boundingBox = box,
                        keypoints = keypoints,
                        keypointsLink = kptConfig.keypointsLink.map { it[0] to it[1] }
                    )
                )
            }
        }
        return results
    }

    /**
     * 传统后处理：输出格式 [1, 4+numClasses+numKpts*3, 8400]
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
        val kptConfig = config as KeypointModelConfig

        val numClasses = kptConfig.numClasses
        val numCandidates = output.size / (4 + numClasses + kptConfig.numKeypoints * 3)
        val numKeypoints = kptConfig.numKeypoints
        val kptNames = kptConfig.kptNames
        val keypointOffset = 4 + numClasses
        val transform = letterboxTransform ?: LetterboxTransform.compute(kptConfig.inputSize, imgW, imgH)

        val candidates = mutableListOf<KeypointCandidate>()

        for (i in 0 until numCandidates) {
            val inputSize = kptConfig.inputSize
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
                val keypoints = mutableListOf<Keypoint>()
                for (k in 0 until numKeypoints) {
                    val kx = output[(keypointOffset + k * 3) * numCandidates + i] * inputSize
                    val ky = output[(keypointOffset + k * 3 + 1) * numCandidates + i] * inputSize
                    val kv = output[(keypointOffset + k * 3 + 2) * numCandidates + i]
                    keypoints.add(Keypoint(kx, ky, kv, kptNames.getOrElse(k) { "kp_$k" }))
                }
                candidates.add(KeypointCandidate(
                    x1 = cx - w / 2, y1 = cy - h / 2,
                    x2 = cx + w / 2, y2 = cy + h / 2,
                    confidence = maxScore, classId = maxClassId,
                    keypoints = keypoints
                ))
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

            for (c in classCandidates.filter { keptSet.contains(NmsProcessor.CandidateBox(it.x1, it.y1, it.x2, it.y2, it.confidence, it.classId)) }) {
                val box = transform.inverseTransformBox(c.x1, c.y1, c.x2, c.y2)
                val transformedKeypoints = c.keypoints.map { kp ->
                    val (kx, ky) = transform.inverseTransform(kp.x, kp.y)
                    Keypoint(kx, ky, kp.visibility, kp.name)
                }
                finalResults.add(
                    KeypointDetection(
                        classId = classId,
                        className = kptConfig.classNames.getOrElse(classId) { "class_$classId" },
                        confidence = c.confidence,
                        boundingBox = box,
                        keypoints = transformedKeypoints,
                        keypointsLink = kptConfig.keypointsLink.map { it[0] to it[1] }
                    )
                )
            }
        }

        return finalResults.sortedByDescending { it.confidence }
    }

    override fun getOutputBufferCount(): Int = 1

    private data class KeypointCandidate(
        val x1: Float, val y1: Float,
        val x2: Float, val y2: Float,
        val confidence: Float,
        val classId: Int,
        val keypoints: List<Keypoint>
    )
}
