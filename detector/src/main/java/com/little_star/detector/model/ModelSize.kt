package com.little_star.detector.model

/**
 * 模型尺寸枚举
 * 对应 assets 目录下的子文件夹名
 */
enum class ModelSize(
    val displayName: String,
    val folder: String
) {
    N("Nano", "n"),
    S("Small", "s"),
    M("Medium", "m"),
    L("Large", "l"),
    X("XLarge", "x")
}
