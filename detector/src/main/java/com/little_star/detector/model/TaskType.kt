package com.little_star.detector.model

/**
 * 任务类型枚举
 * 每种任务类型对应独立的模型目录和功能模块
 *
 * @param displayName 显示名称（用于 UI 展示）
 * @param description 功能描述（用于主首页卡片）
 * @param assetDir assets 目录下的顶级目录名
 * @param titleText 页面标题（用于首页顶部）
 * @param formatHintTitle 格式提示对话框标题
 * @param formatHintSummary 格式提示中针对该任务类型的简要说明
 * @param formatHintNote 格式提示中针对该任务类型的额外注意事项
 */
enum class TaskType(
    val displayName: String,
    val description: String,
    val assetDir: String,
    val titleText: String,
    val formatHintTitle: String,
    val formatHintSummary: String,
    val formatHintNote: String,
    val defaultInputSize: Int = 640
) {
    /** 目标检测：检测图像中的目标物体并标注边界框 */
    DETECTION(
        displayName = "目标检测",
        description = "检测图像中的目标物体并标注边界框",
        assetDir = "detection",
        titleText = "YOLO 目标检测",
        formatHintTitle = "导入目标检测模型包的格式要求",
        formatHintSummary = "适用于 YOLO 目标检测模型（如yolo26n-detect）",
        formatHintNote = "label.txt 中每行一个类别名称，如：\nperson\ncar\ndog"
    ),

    /** 关键点检测：检测目标关键点位置 */
    KEYPOINT(
        displayName = "关键点检测",
        description = "检测目标关键点位置（人体姿态、手部关键点、游戏区域等）",
        assetDir = "keypoint",
        titleText = "YOLO 关键点检测",
        formatHintTitle = "导入关键点检测模型包的格式要求",
        formatHintSummary = "适用于 YOLO 关键点检测模型（如yolo26n-pose、自定义关键点模型等）\n模型输出包含目标边界框和关键点坐标",
        formatHintNote = """
            label.txt — 统一的配置文件，支持多 section 格式：

            [classes]
            person

            [keypoints]
            nose
            left_eye
            right_eye
            ...

            [flip_idx]
            0,2,1,4,3,6,5,8,7,10,9,12,11,14,13,16,15

            [keypoints_link]
            0,1
            1,2
            2,3
            ...

            ⚠️ 关键点任务的 label.txt 必须包含 [classes]、[keypoints]、[flip_idx]、[keypoints_link] 四个 section（从训练配置中获取）
        """.trimIndent()
    ),

    /** 实例分割：对图像中的目标进行像素级分割 */
    SEGMENTATION(
        displayName = "实例分割",
        description = "对图像中的目标进行像素级分割",
        assetDir = "segmentation",
        titleText = "YOLO 实例分割",
        formatHintTitle = "导入实例分割模型包的格式要求",
        formatHintSummary = "适用于 YOLO 实例分割模型（如yolo26n-seg）\n模型输出包含边界框和像素级分割掩码",
        formatHintNote = "label.txt 中每行一个类别名称，与目标检测格式相同"
    ),

    /** 图像分类：对图像进行分类识别 */
    CLASSIFICATION(
        displayName = "图像分类",
        description = "对图像进行分类识别",
        assetDir = "classification",
        titleText = "YOLO 图像分类",
        formatHintTitle = "导入图像分类模型包的格式要求",
        formatHintSummary = "适用于 YOLO 图像分类模型（如yolo26n-cls）\n模型输出为图像类别及置信度",
        formatHintNote = "label.txt 中每行一个类别名称，按类别索引顺序排列",
        defaultInputSize = 224
    ),

    /** 旋转框检测：使用旋转边界框检测倾斜目标 */
    ORIENTED_BBOX(
        displayName = "旋转框检测",
        description = "使用旋转边界框检测倾斜目标",
        assetDir = "oriented_bbox",
        titleText = "YOLO 旋转框检测",
        formatHintTitle = "导入旋转框检测模型包的格式要求",
        formatHintSummary = "适用于 YOLO OBB 模型（如yolo26n-obb）\n模型输出包含旋转角度的边界框",
        formatHintNote = "label.txt 中每行一个类别名称，与目标检测格式相同",
        defaultInputSize = 1024
    );

    companion object {
        /**
         * 从 assets 目录名解析任务类型
         * @param dir assets 目录名
         * @return 对应的任务类型，未找到则返回 null
         */
        fun fromAssetDir(dir: String): TaskType? = entries.find { it.assetDir == dir }
    }
}
