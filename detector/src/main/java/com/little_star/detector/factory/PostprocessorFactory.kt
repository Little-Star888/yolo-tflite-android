package com.little_star.detector.factory

import com.little_star.detector.model.ModelConfig
import com.little_star.detector.postprocess.INativeResultParser
import com.little_star.detector.postprocess.java.ClassificationPostprocessor
import com.little_star.detector.postprocess.java.DetectionPostprocessor
import com.little_star.detector.postprocess.IPostprocessor
import com.little_star.detector.postprocess.java.KeypointPostprocessor
import com.little_star.detector.postprocess.java.OrientedBBoxPostprocessor
import com.little_star.detector.postprocess.java.SegmentationPostprocessor
import com.little_star.detector.postprocess.native.NativeClassificationParser
import com.little_star.detector.postprocess.native.NativeDetectionParser
import com.little_star.detector.postprocess.native.NativeKeypointParser
import com.little_star.detector.postprocess.native.NativeOrientedBoxParser
import com.little_star.detector.postprocess.native.NativeSegmentationParser
import com.little_star.detector.model.TaskType

/**
 * 后处理策略工厂
 * 根据推理后端类型（Java / Native）和任务类型创建对应的后处理器
 */
object PostprocessorFactory {

    /**
     * Java 后端：创建 Kotlin 后处理器
     * 接收原始模型输出，执行 NMS、坐标逆变换、mask 计算等完整后处理
     */
    fun createJava(config: ModelConfig): IPostprocessor {
        return when (config.taskType) {
            TaskType.CLASSIFICATION -> ClassificationPostprocessor()
            TaskType.SEGMENTATION -> SegmentationPostprocessor(config.inferenceType)
            TaskType.KEYPOINT -> KeypointPostprocessor(config.inferenceType)
            TaskType.ORIENTED_BBOX -> OrientedBBoxPostprocessor(config.inferenceType)
            TaskType.DETECTION -> DetectionPostprocessor(config.inferenceType)
        }
    }

    /**
     * Native 后端：创建 JNI 结果解析器
     * 接收 C++ 序列化的 FloatArray，执行反序列化为 DetectionResult 对象
     */
    fun createNative(taskType: TaskType): INativeResultParser {
        return when (taskType) {
            TaskType.CLASSIFICATION -> NativeClassificationParser()
            TaskType.SEGMENTATION -> NativeSegmentationParser()
            TaskType.KEYPOINT -> NativeKeypointParser()
            TaskType.ORIENTED_BBOX -> NativeOrientedBoxParser()
            TaskType.DETECTION -> NativeDetectionParser()
        }
    }
}
