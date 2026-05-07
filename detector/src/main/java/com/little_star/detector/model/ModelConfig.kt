package com.little_star.detector.model

/**
 * 模型运行时配置
 * 根据任务类型分为不同的配置子类
 */
sealed interface ModelConfig {
    /** 模型输入尺寸（如 640） */
    val inputSize: Int
    /** 类别名称数组（数量从数组长度获取） */
    val classNames: Array<String>
    /** 推理类型（影响后处理逻辑） */
    val inferenceType: InferenceType
    /** 任务类型（影响预处理和后处理逻辑） */
    val taskType: TaskType
    /** 类别数量 */
    val numClasses: Int get() = classNames.size
}

/** 目标检测模型配置 */
data class DetectionModelConfig(
    override val inputSize: Int = 640,
    override val classNames: Array<String> = emptyArray(),
    override val inferenceType: InferenceType = InferenceType.END2END
) : ModelConfig {
    override val taskType: TaskType = TaskType.DETECTION
}

/** 实例分割模型配置 */
data class SegmentationModelConfig(
    override val inputSize: Int = 640,
    override val classNames: Array<String> = emptyArray(),
    override val inferenceType: InferenceType = InferenceType.END2END
) : ModelConfig {
    override val taskType: TaskType = TaskType.SEGMENTATION
}

/** 关键点检测模型配置 */
data class KeypointModelConfig(
    override val inputSize: Int = 640,
    override val classNames: Array<String> = emptyArray(),
    override val inferenceType: InferenceType = InferenceType.END2END,
    /** 关键点名称数组（非空） */
    val kptNames: Array<String>,
    /** 关键点翻转索引（非空，用于水平翻转时重映射关键点） */
    val flipIdx: IntArray,
    /** 关键点连线索引，每个元素为 [from, to] 对，如：[[0,1],[1,2],[2,3]...] */
    val keypointsLink: Array<IntArray>
) : ModelConfig {
    override val taskType: TaskType = TaskType.KEYPOINT
    /** 关键点数量 */
    val numKeypoints: Int get() = kptNames.size
}

/** 图像分类模型配置 */
data class ClassificationModelConfig(
    override val inputSize: Int = 224,
    override val classNames: Array<String> = emptyArray(),
    override val inferenceType: InferenceType = InferenceType.END2END
) : ModelConfig {
    override val taskType: TaskType = TaskType.CLASSIFICATION
}

/** 旋转框检测模型配置 */
data class OrientedBBoxModelConfig(
    override val inputSize: Int = 1024,
    override val classNames: Array<String> = emptyArray(),
    override val inferenceType: InferenceType = InferenceType.END2END
) : ModelConfig {
    override val taskType: TaskType = TaskType.ORIENTED_BBOX
}

/**
 * 标签配置文件内容
 * 整合了 label.txt 的所有配置内容
 *
 * label.txt 支持多 section 格式（section 之间无顺序要求）：
 * ```
 * [classes]
 * person
 * car
 *
 * [keypoints]
 * nose
 * left_eye
 * right_eye
 * ...
 *
 * [flip_idx]
 * 0,2,1,4,3,6,5,8,7,10,9,12,11,14,13,16,15
 *
 * [keypoints_link]
 * 0,1
 * 1,2
 * 2,3
 * ...
 * ```
 *
 * 也支持简单格式（仅有 classes，下标从 0 开始递增）：
 * ```
 * person
 * car
 * dog
 * ```
 *
 * section 名称忽略大小写，支持空白字符，如 [classes]、[ CLASSES ]、[classes ] 均有效
 */
data class LabelConfig(
    /** 类别名称列表 */
    val classes: Array<String>,
    /** 关键点名称列表（关键点任务必须有） */
    val keypoints: Array<String>? = null,
    /** 翻转索引数组（关键点任务必须有） */
    val flipIdx: IntArray? = null,
    /** 关键点连线数组（关键点任务必须有） */
    val keypointsLink: Array<IntArray>? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as LabelConfig
        return classes.contentEquals(other.classes) &&
                keypoints?.contentEquals(other.keypoints) == true &&
                flipIdx?.contentEquals(other.flipIdx) == true &&
                keypointsLink?.contentEquals(other.keypointsLink) == true
    }

    override fun hashCode(): Int {
        var result = classes.contentHashCode()
        result = 31 * result + (keypoints?.contentHashCode() ?: 0)
        result = 31 * result + (flipIdx?.contentHashCode() ?: 0)
        result = 31 * result + (keypointsLink?.contentHashCode() ?: 0)
        return result
    }
}
