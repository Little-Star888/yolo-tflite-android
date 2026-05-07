package com.little_star.detector.model

/**
 * 加速器模式枚举
 * CPU：纯CPU推理
 * GPU：使用GPU加速
 * NPU：使用NPU加速（Qualcomm Hexagon / MediaTek APU）
 */
enum class AcceleratorMode(
    val displayName: String
) {
    CPU("CPU"),
    GPU("GPU"),
    NPU("NPU")
}
