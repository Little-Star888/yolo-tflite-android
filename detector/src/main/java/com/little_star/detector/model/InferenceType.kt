package com.little_star.detector.model

/**
 * 推理类型枚举
 * 影响检测器的后处理方式，与模型文件目录无关
 *
 * 端到端：模型内置 NMS，直接输出最终结果
 * 传统模式：模型输出原始结果，需要手动执行 NMS
 */
enum class InferenceType(
    val displayName: String
) {
    END2END("端到端"),
    TRADITIONAL("传统模式")
}
