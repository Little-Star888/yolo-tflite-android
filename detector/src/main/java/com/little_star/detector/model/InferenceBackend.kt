package com.little_star.detector.model

/**
 * 推理后端枚举
 * 同一模型格式下可选的不同推理引擎
 * 例如 TFLite 格式可使用 LiteRT Java API 或 JNI Native 后端
 */
enum class InferenceBackend(val displayName: String) {
    /** LiteRT Java API 后端（纯 Kotlin，预处理/后处理在 JVM 侧） */
    LITERT_JAVA("LiteRT Java"),

    /** LiteRT Native JNI 后端（C++ 原生预处理/后处理） */
    LITERT_NATIVE("LiteRT Native"),
}
