package com.little_star.detector.factory

import android.content.Context
import com.little_star.detector.IDetector
import com.little_star.detector.impl.tflite.LiteRtNativeDetector
import com.little_star.detector.impl.tflite.LiteRtJavaDetector
import com.little_star.detector.model.InferenceBackend

/**
 * 检测器工厂
 * 根据推理后端创建对应的检测器实例
 * 风格与 PreprocessorFactory / PostprocessorFactory 一致
 */
object DetectorFactory {

    /**
     * 创建检测器实例
     * @param context Android 上下文
     * @param backend 推理后端
     * @return 对应的检测器实例
     */
    fun create(context: Context, backend: InferenceBackend): IDetector {
        return when (backend) {
            InferenceBackend.LITERT_JAVA -> LiteRtJavaDetector(context)
            InferenceBackend.LITERT_NATIVE -> LiteRtNativeDetector(context)
        }
    }
}
