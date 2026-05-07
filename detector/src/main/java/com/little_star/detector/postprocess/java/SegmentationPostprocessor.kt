package com.little_star.detector.postprocess.java

import android.util.Log
import com.little_star.detector.postprocess.IPostprocessor
import com.little_star.detector.model.ModelConfig
import com.little_star.detector.util.LetterboxTransform
import com.little_star.detector.util.NmsProcessor
import com.little_star.detector.model.DetectionResult
import com.little_star.detector.model.InferenceType
import com.little_star.detector.model.SegmentationMask
import com.little_star.detector.model.SegmentationResult

/**
 * 实例分割后处理策略
 *
 * 端到端输出：[1, 300, 6+nm] + proto [1, nm, H, W] → 无需 NMS
 * 传统输出：[1, 4+numClasses+nm, numCandidates] + proto [1, H, W, nm] → 需要手动 NMS
 *
 * nm = 掩码原型数量（默认 32）
 */
class SegmentationPostprocessor(
    private val inferenceType: InferenceType
) : IPostprocessor {

    companion object {
        private const val NMS_IOU_THRESHOLD = 0.45f
        private const val NUM_MASK_PROTOS = 32
        private const val DEFAULT_MASK_H = 160
        private const val DEFAULT_MASK_W = 160
        private const val TAG = "SegPostprocessor"
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
        return when (inferenceType) {
            InferenceType.END2END -> postprocessEnd2End(outputs, imgW, imgH, confThreshold, config, letterboxTransform)
            InferenceType.TRADITIONAL -> postprocessTraditional(outputs, imgW, imgH, confThreshold, config, letterboxTransform)
        }
    }

    /**
     * 端到端后处理：输出格式 (N, 300, 6+nm) + proto
     * 无需 NMS，只需置信度过滤
     */
    private fun postprocessEnd2End(
        outputs: List<FloatArray>,
        imgW: Int,
        imgH: Int,
        confThreshold: Float,
        config: ModelConfig,
        letterboxTransform: LetterboxTransform?
    ): List<DetectionResult> {
        val output = outputs[0]
        val maskProtos = if (outputs.size > 1) outputs[1] else floatArrayOf()
        val inputSize = config.inputSize
        val transform = letterboxTransform ?: LetterboxTransform.compute(inputSize, imgW, imgH)

        val stride = 6 + NUM_MASK_PROTOS
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

            if (conf >= confThreshold && conf <= 1.0f && cls in 0 until config.numClasses) {
                val box = transform.inverseTransformBox(
                    x1 * inputSize, y1 * inputSize,
                    x2 * inputSize, y2 * inputSize
                )
                val maskCoeffs = FloatArray(NUM_MASK_PROTOS) { m ->
                    output[base + 6 + m]
                }
                results.add(
                    SegmentationResult(
                        classId = cls,
                        className = config.classNames.getOrElse(cls) { "class_$cls" },
                        confidence = conf,
                        boundingBox = box,
                        mask = computeMask(
                            maskCoeffs = maskCoeffs,
                            maskProtos = maskProtos,
                            boxInModelX1 = x1 * inputSize,
                            boxInModelY1 = y1 * inputSize,
                            boxInModelX2 = x2 * inputSize,
                            boxInModelY2 = y2 * inputSize,
                            inputSize = inputSize
                        )
                    )
                )
            }
        }
        return results
    }

    /**
     * 传统后处理：输出格式 [1, 4+numClasses+nm, numCandidates] + proto
     * 需要手动 NMS
     */
    private fun postprocessTraditional(
        outputs: List<FloatArray>,
        imgW: Int,
        imgH: Int,
        confThreshold: Float,
        config: ModelConfig,
        letterboxTransform: LetterboxTransform?
    ): List<DetectionResult> {
        val output = outputs[0]
        val maskProtos = if (outputs.size > 1) outputs[1] else floatArrayOf()

        Log.d(TAG, "outputs: ${outputs.size} buf, det=${output.size}, proto=${maskProtos.size}")

        val numClasses = config.numClasses
        val numCandidates = output.size / (4 + numClasses + NUM_MASK_PROTOS)
        val numMasks = NUM_MASK_PROTOS
        val inputSize = config.inputSize
        val transform = letterboxTransform ?: LetterboxTransform.compute(inputSize, imgW, imgH)

        val candidates = mutableListOf<SegmentationCandidate>()
        for (i in 0 until numCandidates) {
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
                val maskCoeffs = FloatArray(numMasks) { m ->
                    output[(4 + numClasses + m) * numCandidates + i]
                }
                candidates.add(SegmentationCandidate(
                    x1 = cx - w / 2,
                    y1 = cy - h / 2,
                    x2 = cx + w / 2,
                    y2 = cy + h / 2,
                    confidence = maxScore,
                    classId = maxClassId,
                    maskCoeffs = maskCoeffs
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
                finalResults.add(
                    SegmentationResult(
                        classId = classId,
                        className = config.classNames.getOrElse(classId) { "class_$classId" },
                        confidence = c.confidence,
                        boundingBox = box,
                        mask = computeMask(
                            maskCoeffs = c.maskCoeffs,
                            maskProtos = maskProtos,
                            boxInModelX1 = c.x1,
                            boxInModelY1 = c.y1,
                            boxInModelX2 = c.x2,
                            boxInModelY2 = c.y2,
                            inputSize = inputSize
                        )
                    )
                )
            }
        }

        return finalResults.sortedByDescending { it.confidence }
    }

    private fun computeMask(
        maskCoeffs: FloatArray,
        maskProtos: FloatArray,
        boxInModelX1: Float, boxInModelY1: Float,
        boxInModelX2: Float, boxInModelY2: Float,
        inputSize: Int,
        maskH: Int = DEFAULT_MASK_H,
        maskW: Int = DEFAULT_MASK_W
    ): SegmentationMask {
        if (maskProtos.isEmpty()) {
            return SegmentationMask(maskW, maskH, BooleanArray(maskH * maskW) { false })
        }

        val numProtos = maskCoeffs.size
        val maskData = BooleanArray(maskH * maskW)

        val scale = maskW.toFloat() / inputSize.toFloat()
        val bx1 = (boxInModelX1 * scale).toInt().coerceIn(0, maskW - 1)
        val by1 = (boxInModelY1 * scale).toInt().coerceIn(0, maskH - 1)
        val bx2 = (boxInModelX2 * scale).toInt().coerceIn(0, maskW)
        val by2 = (boxInModelY2 * scale).toInt().coerceIn(0, maskH)

        var trueCount = 0
        for (y in by1 until by2) {
            for (x in bx1 until bx2) {
                var sum = 0f
                for (k in 0 until numProtos) {
                    sum += maskCoeffs[k] * maskProtos[y * maskW * numProtos + x * numProtos + k]
                }
                if (sum > 0.0f) {
                    maskData[y * maskW + x] = true
                    trueCount++
                }
            }
        }

        Log.d(TAG, "Mask: crop=[$bx1,$by1,$bx2,$by2], true=$trueCount in ${maskW}x${maskH}")
        return SegmentationMask(
            width = maskW, height = maskH, data = maskData,
            cropX1 = bx1, cropY1 = by1, cropX2 = bx2, cropY2 = by2
        )
    }

    override fun getOutputBufferCount(): Int = 2

    private data class SegmentationCandidate(
        val x1: Float, val y1: Float,
        val x2: Float, val y2: Float,
        val confidence: Float,
        val classId: Int,
        val maskCoeffs: FloatArray
    )
}
