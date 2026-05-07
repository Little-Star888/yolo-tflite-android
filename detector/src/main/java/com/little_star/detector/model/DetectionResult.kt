package com.little_star.detector.model

/**
 * YOLO 检测结果基接口（sealed）
 */
sealed interface DetectionResult {
    /** 类别ID */
    val classId: Int
    /** 类别名称 */
    val className: String
    /** 置信度 0.0-1.0 */
    val confidence: Float
}

/**
 * 带边界框的检测结果（目标检测/分割/姿态/OBB）
 */
sealed interface BoundingBoxDetection : DetectionResult {
    val boundingBox: BoundingBox
}

/**
 * 目标检测结果
 */
data class ObjectDetection(
    override val classId: Int,
    override val className: String,
    override val confidence: Float,
    override val boundingBox: BoundingBox
) : BoundingBoxDetection

/**
 * 实例分割结果
 */
data class SegmentationResult(
    override val classId: Int,
    override val className: String,
    override val confidence: Float,
    override val boundingBox: BoundingBox,
    val mask: SegmentationMask
) : BoundingBoxDetection

/**
 * 关键点检测结果（姿态估计）
 */
data class KeypointDetection(
    override val classId: Int,
    override val className: String,
    override val confidence: Float,
    override val boundingBox: BoundingBox,
    val keypoints: List<Keypoint>,
    /** 关键点连线索引对列表，每个元素为 [from, to] 对 */
    val keypointsLink: List<Pair<Int, Int>> = emptyList()
) : BoundingBoxDetection

/**
 * 旋转框检测结果（OBB）
 */
data class OrientedBoxDetection(
    override val classId: Int,
    override val className: String,
    override val confidence: Float,
    override val boundingBox: BoundingBox,
    /** 旋转角度（单位：度，范围 0-90） */
    val rotationAngle: Float
) : BoundingBoxDetection

/**
 * 图像分类结果（不需要边界框）
 */
data class ClassificationResult(
    override val classId: Int,
    override val className: String,
    override val confidence: Float
) : DetectionResult

// ─────────────────────────────────────────────────────────────
// 辅助数据结构
// ─────────────────────────────────────────────────────────────

/**
 * 边界框（左上角 + 右下角）
 */
data class BoundingBox(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float
) {
    val width: Float get() = x2 - x1
    val height: Float get() = y2 - y1
    val centerX: Float get() = (x1 + x2) / 2
    val centerY: Float get() = (y1 + y2) / 2
}

/**
 * 关键点（姿态估计）
 * @param x X 坐标（已映射到原图尺寸）
 * @param y Y 坐标（已映射到原图尺寸）
 * @param visibility 可见度（0.0-1.0，低于阈值表示被遮挡或不可见）
 * @param name 关键点名称（如 "nose", "left_eye" 等）
 */
data class Keypoint(
    val x: Float,
    val y: Float,
    val visibility: Float = 1.0f,
    val name: String? = null
)

/**
 * 分割掩码（实例分割）
 * @param width 掩码宽度
 * @param height 掩码高度
 * @param data 二值掩码数据（true 表示该像素属于该实例）
 * @param cropX1 裁剪区域左边界（mask 坐标系）
 * @param cropY1 裁剪区域上边界
 * @param cropX2 裁剪区域右边界
 * @param cropY2 裁剪区域下边界
 */
data class SegmentationMask(
    val width: Int,
    val height: Int,
    val data: BooleanArray,
    val cropX1: Int = 0,
    val cropY1: Int = 0,
    val cropX2: Int = width,
    val cropY2: Int = height
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SegmentationMask
        return width == other.width && height == other.height && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + data.contentHashCode()
        return result
    }
}
