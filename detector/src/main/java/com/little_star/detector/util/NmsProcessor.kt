package com.little_star.detector.util

/**
 * NMS（Non-Maximum Suppression）处理器
 * 消除原有代码中 4 处 NMS 流程的重复
 */
class NmsProcessor(
    private val iouThreshold: Float = 0.45f
) {

    /**
     * NMS 候选框数据类
     */
    data class CandidateBox(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val confidence: Float,
        val classId: Int
    )

    /**
     * 应用 NMS
     * @param candidates 候选框列表
     * @return 保留的框列表
     */
    fun applyNms(candidates: List<CandidateBox>): List<CandidateBox> {
        if (candidates.isEmpty()) return emptyList()

        // 按置信度降序排列
        val sorted = candidates.sortedByDescending { it.confidence }
        val kept = mutableListOf<CandidateBox>()

        for (candidate in sorted) {
            val overlaps = kept.any { existing ->
                computeIou(candidate, existing) >= iouThreshold
            }
            if (!overlaps) {
                kept.add(candidate)
            }
        }

        return kept
    }

    /**
     * 计算两个框的 IOU
     */
    fun computeIou(a: CandidateBox, b: CandidateBox): Float {
        val interX1 = maxOf(a.x1, b.x1)
        val interY1 = maxOf(a.y1, b.y1)
        val interX2 = minOf(a.x2, b.x2)
        val interY2 = minOf(a.y2, b.y2)

        val interArea = maxOf(0f, interX2 - interX1) * maxOf(0f, interY2 - interY1)
        val aArea = (a.x2 - a.x1) * (a.y2 - a.y1)
        val bArea = (b.x2 - b.x1) * (b.y2 - b.y1)
        val unionArea = aArea + bArea - interArea

        return if (unionArea > 0) interArea / unionArea else 0f
    }
}
