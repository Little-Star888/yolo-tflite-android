package com.little_star.detector.model

/**
 * 模型格式枚举
 * 对应不同的推理引擎实现
 * 当前仅支持 TFLite，预留扩展空间（如 ONNX、NCNN 等）
 */
enum class ModelFormat(
    val displayName: String
) {
    TFLITE("TFLite");
    // ONNX("ONNX"),   // 未来扩展
    // NCNN("NCNN"),    // 未来扩展

    /** 该格式支持的推理后端列表 */
    val availableBackends: List<InferenceBackend>
        get() = when (this) {
            TFLITE -> listOf(InferenceBackend.LITERT_JAVA, InferenceBackend.LITERT_NATIVE)
        }

    /** 该格式的默认后端 */
    val defaultBackend: InferenceBackend
        get() = availableBackends.first()
}
