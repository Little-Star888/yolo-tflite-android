package com.little_star.detector.factory

import com.little_star.detector.preprocess.CenterCropPreprocessor
import com.little_star.detector.preprocess.IPreprocessor
import com.little_star.detector.preprocess.LetterBoxPreprocessor
import com.little_star.detector.model.TaskType

/**
 * 预处理策略工厂
 */
object PreprocessorFactory {

    /**
     * 根据任务类型创建预处理策略
     */
    fun create(taskType: TaskType): IPreprocessor {
        return when (taskType) {
            TaskType.CLASSIFICATION -> CenterCropPreprocessor()
            else -> LetterBoxPreprocessor()  // 检测/分割/关键点/OBB
        }
    }
}
