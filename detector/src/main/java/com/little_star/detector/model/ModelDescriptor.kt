package com.little_star.detector.model

/**
 * 模型文件描述
 * 描述 assets 目录或本地导入目录中一个具体的模型文件
 *
 * 目录约定：
 * assets/{taskType}/格式/{packageName}/models/尺寸/文件名
 * 例如：detection/tflite/yolo26n/models/x/model.tflite
 * 或：keypoint/tflite/yolo26-pose/models/x/model.tflite
 *
 * packageName 格式：{base}[-{task}] 或 {base}[_{task}]
 * 例如：yolo26n, yolo26, yolo26-custom
 *
 * @param format 模型格式（TFLite等）
 * @param packageName 模型包完整名称（如 "yolo26n"），用于显示
 * @param size 模型尺寸（n, s, m, l, x）
 * @param assetPath 在assets中的完整路径（内置模型使用）
 * @param fileName 文件名
 * @param isLocal 是否为用户导入的本地模型
 * @param absolutePath 本地模型的文件系统绝对路径（仅 isLocal=true 时有效）
 * @param taskType 任务类型（目标检测、关键点检测等）
 */
data class ModelDescriptor(
    val format: ModelFormat,
    val packageName: String,
    val size: ModelSize,
    val assetPath: String,
    val fileName: String,
    val isLocal: Boolean = false,
    val isRemote: Boolean = false,
    val isBuiltin: Boolean = false,
    val absolutePath: String? = null,
    val fileSize: Long = 0L,
    val taskType: TaskType = TaskType.DETECTION
) {
    /** 显示名称（去掉 .tflite 后缀） */
    val displayName: String
        get() = fileName.removeSuffix(".tflite")

    /** 扫描器中的复合 key（内置包带 ::builtin，远程包带 ::remote，其他不带） */
    val scannerKey: String
        get() = when {
            isBuiltin -> "$packageName::builtin"
            isRemote -> "$packageName::remote"
            else -> packageName
        }

    /** 基础模型名称，从 packageName 提取（去掉最后的 -或_ 后缀） */
    val baseName: String
        get() {
            val lastHyphen = packageName.lastIndexOf('-')
            val lastUnderscore = packageName.lastIndexOf('_')
            val lastSep = maxOf(lastHyphen, lastUnderscore)
            return if (lastSep > 0) packageName.substring(0, lastSep) else packageName
        }
}
