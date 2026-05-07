package com.little_star.util

import androidx.annotation.StringRes
import com.little_star.R
import com.little_star.detector.model.InferenceType
import com.little_star.detector.model.TaskType

/**
 * detector 模块枚举的字符串资源映射
 * detector 模块无法直接引用 app 模块的 R.string，通过扩展函数桥接
 */

/** 推理类型显示名称 */
@StringRes
fun InferenceType.displayNameRes(): Int = when (this) {
    InferenceType.END2END -> R.string.inference_type_end2end
    InferenceType.TRADITIONAL -> R.string.inference_type_traditional
}

/** 任务类型 - 页面标题 */
@StringRes
fun TaskType.titleTextRes(): Int = when (this) {
    TaskType.DETECTION -> R.string.tasktype_detection_title
    TaskType.KEYPOINT -> R.string.tasktype_keypoint_title
    TaskType.SEGMENTATION -> R.string.tasktype_segmentation_title
    TaskType.CLASSIFICATION -> R.string.tasktype_classification_title
    TaskType.ORIENTED_BBOX -> R.string.tasktype_obb_title
}

/** 任务类型 - 格式提示对话框标题 */
@StringRes
fun TaskType.formatHintTitleRes(): Int = when (this) {
    TaskType.DETECTION -> R.string.tasktype_detection_hint_title
    TaskType.KEYPOINT -> R.string.tasktype_keypoint_hint_title
    TaskType.SEGMENTATION -> R.string.tasktype_segmentation_hint_title
    TaskType.CLASSIFICATION -> R.string.tasktype_classification_hint_title
    TaskType.ORIENTED_BBOX -> R.string.tasktype_obb_hint_title
}

/** 任务类型 - 格式提示简要说明 */
@StringRes
fun TaskType.formatHintSummaryRes(): Int = when (this) {
    TaskType.DETECTION -> R.string.tasktype_detection_hint_summary
    TaskType.KEYPOINT -> R.string.tasktype_keypoint_hint_summary
    TaskType.SEGMENTATION -> R.string.tasktype_segmentation_hint_summary
    TaskType.CLASSIFICATION -> R.string.tasktype_classification_hint_summary
    TaskType.ORIENTED_BBOX -> R.string.tasktype_obb_hint_summary
}

/** 任务类型 - 格式提示注意事项 */
@StringRes
fun TaskType.formatHintNoteRes(): Int = when (this) {
    TaskType.DETECTION -> R.string.tasktype_detection_hint_note
    TaskType.KEYPOINT -> R.string.tasktype_keypoint_hint_note
    TaskType.SEGMENTATION -> R.string.tasktype_segmentation_hint_note
    TaskType.CLASSIFICATION -> R.string.tasktype_classification_hint_note
    TaskType.ORIENTED_BBOX -> R.string.tasktype_obb_hint_note
}
