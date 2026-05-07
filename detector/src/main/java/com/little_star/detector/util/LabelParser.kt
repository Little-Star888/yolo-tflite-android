package com.little_star.detector.util

import android.content.Context
import com.little_star.detector.model.LabelConfig

/**
 * 标签文件解析工具
 * 从 label.txt 加载类别名称、关键点配置等信息
 *
 * 支持两种格式：
 * 1. 简单格式：每行一个类别名称
 * 2. 多 section 格式：用 [section] 标记分组（classes、keypoints、flip_idx、keypoints_link）
 */

/**
 * 从 assets 下的标签文件加载类别名称（兼容简单格式）
 * @param context Android 上下文
 * @param labelFile 标签文件路径（如 "tflite/yolo26n/label.txt"）
 */
fun loadLabels(context: Context, labelFile: String = "tflite/yolo26n/label.txt"): Array<String> {
    return try {
        val content = context.assets.open(labelFile).bufferedReader().readText()
        val config = parseLabelConfig(content)
        config.classes
    } catch (e: Exception) {
        emptyArray()
    }
}

/**
 * 从文件系统绝对路径加载类别名称（兼容简单格式）
 * @param absolutePath 标签文件的绝对路径
 */
fun loadLabelsFromFile(absolutePath: String): Array<String> {
    return try {
        val content = java.io.File(absolutePath).readText()
        val config = parseLabelConfig(content)
        config.classes
    } catch (e: Exception) {
        emptyArray()
    }
}

/**
 * 从 assets 下的标签文件加载完整配置（支持多 section 格式）
 * @param context Android 上下文
 * @param labelFile 标签文件路径（如 "tflite/yolo26n/label.txt"）
 */
fun loadLabelConfig(context: Context, labelFile: String): LabelConfig {
    return try {
        val content = context.assets.open(labelFile).bufferedReader().readText()
        parseLabelConfig(content)
    } catch (e: Exception) {
        throw IllegalArgumentException("标签配置文件不存在或解析失败: $labelFile", e)
    }
}

/**
 * 从文件系统绝对路径加载完整配置（支持多 section 格式）
 * @param absolutePath 标签文件的绝对路径
 */
fun loadLabelConfigFromFile(absolutePath: String): LabelConfig {
    return try {
        val content = java.io.File(absolutePath).readText()
        parseLabelConfig(content)
    } catch (e: Exception) {
        throw IllegalArgumentException("标签配置文件不存在或解析失败: $absolutePath", e)
    }
}

/**
 * 解析 label.txt 内容
 * 支持多 section 格式和简单格式
 */
fun parseLabelConfig(content: String): LabelConfig {
    val trimmed = content.trim()
    return if (trimmed.contains("[")) {
        parseMultiSectionLabel(trimmed)
    } else {
        // 简单格式：每行一个类别名称
        LabelConfig(
            classes = trimmed.split("\n")
                .filter { it.isNotBlank() }
                .map { it.trim() }
                .toTypedArray()
        )
    }
}

/**
 * 解析多 section 格式的 label.txt
 * section 名称忽略大小写，支持空白字符
 */
private fun parseMultiSectionLabel(content: String): LabelConfig {
    var classes: Array<String>? = null
    var keypoints: Array<String>? = null
    var flipIdx: IntArray? = null
    var keypointsLink: Array<IntArray>? = null

    val lines = content.split("\n")
    var currentSection: String? = null
    val sectionContent = StringBuilder()

    for (line in lines) {
        val trimmedLine = line.trim()
        if (trimmedLine.isEmpty() || trimmedLine.startsWith("#")) continue

        val sectionMatch = Regex("^\\s*\\[\\s*(\\w+)\\s*\\]\\s*$", RegexOption.IGNORE_CASE).find(trimmedLine)
        if (sectionMatch != null) {
            // 保存上一个 section 的内容
            if (currentSection != null) {
                parseSection(currentSection, sectionContent.toString()).let { (c, k, f, l) ->
                    if (classes == null && c != null) classes = c
                    if (keypoints == null && k != null) keypoints = k
                    if (flipIdx == null && f != null) flipIdx = f
                    if (keypointsLink == null && l != null) keypointsLink = l
                }
            }
            currentSection = sectionMatch.groupValues[1].lowercase()
            sectionContent.clear()
        } else if (currentSection != null) {
            sectionContent.appendLine(trimmedLine)
        }
    }

    // 处理最后一个 section
    if (currentSection != null) {
        parseSection(currentSection, sectionContent.toString()).let { (c, k, f, l) ->
            if (classes == null && c != null) classes = c
            if (keypoints == null && k != null) keypoints = k
            if (flipIdx == null && f != null) flipIdx = f
            if (keypointsLink == null && l != null) keypointsLink = l
        }
    }

    return LabelConfig(
        classes = classes ?: emptyArray(),
        keypoints = keypoints,
        flipIdx = flipIdx,
        keypointsLink = keypointsLink
    )
}

/**
 * 解析单个 section 的内容
 */
private fun parseSection(sectionName: String, content: String): ParsedSection {
    val lines = content.split("\n").filter { it.isNotBlank() }.map { it.trim() }
    return when (sectionName.lowercase()) {
        "classes", "class", "label", "labels" -> {
            ParsedSection(classes = lines.toTypedArray())
        }
        "keypoints", "kpts", "keypoint", "kpt" -> {
            ParsedSection(keypoints = lines.toTypedArray())
        }
        "flip_idx", "flipidx", "flip" -> {
            ParsedSection(flipIdx = parseFlipIdx(content))
        }
        "keypoints_link", "keypointslink", "link", "links", "skeleton" -> {
            ParsedSection(keypointsLink = parseKeypointsLink(content))
        }
        else -> ParsedSection()
    }
}

private data class ParsedSection(
    val classes: Array<String>? = null,
    val keypoints: Array<String>? = null,
    val flipIdx: IntArray? = null,
    val keypointsLink: Array<IntArray>? = null
)

/**
 * 解析 flip_idx 内容
 * 支持逗号分隔和每行一个数字两种格式
 */
private fun parseFlipIdx(content: String): IntArray {
    val trimmed = content.trim()
    val numbers = if (trimmed.contains(",")) {
        trimmed.split(",").map { it.trim().toInt() }
    } else {
        trimmed.split("\n").filter { it.isNotBlank() }.map { it.trim().toInt() }
    }
    return IntArray(numbers.size) { numbers[it] }
}

/**
 * 解析 keypoints_link 内容
 * 支持 JSON 数组格式和每行一个连接对两种格式
 */
private fun parseKeypointsLink(content: String): Array<IntArray> {
    val trimmed = content.trim()
    return if (trimmed.startsWith("[")) {
        parseKeypointsLinkJson(trimmed)
    } else {
        trimmed.split("\n")
            .filter { it.contains(",") }
            .map { line ->
                val parts = line.split(",").map { it.trim().toInt() }
                intArrayOf(parts[0], parts[1])
            }
            .toTypedArray()
    }
}

/**
 * 解析 JSON 格式的 keypoints_link
 */
private fun parseKeypointsLinkJson(content: String): Array<IntArray> {
    val trimmed = content.trim().removePrefix("[").removeSuffix("]")
    val pairs = trimmed.split("],[").map { pair ->
        pair.removePrefix("[").removeSuffix("]").split(",").map { it.trim().toInt() }
    }
    return Array(pairs.size) { i ->
        intArrayOf(pairs[i][0], pairs[i][1])
    }
}

/** 默认的 COCO 17 关键点连线（作为回退） */
val DEFAULT_COCO_KEYPOINTS_LINK: Array<IntArray> = arrayOf(
    intArrayOf(0, 1),   // nose - left_eye
    intArrayOf(0, 2),   // nose - right_eye
    intArrayOf(1, 3),   // left_eye - left_ear
    intArrayOf(2, 4),   // right_eye - right_ear
    intArrayOf(5, 6),   // left_shoulder - right_shoulder
    intArrayOf(5, 7),   // left_shoulder - left_elbow
    intArrayOf(7, 9),   // left_elbow - left_wrist
    intArrayOf(6, 8),   // right_shoulder - right_elbow
    intArrayOf(8, 10),  // right_elbow - right_wrist
    intArrayOf(5, 11),  // left_shoulder - left_hip
    intArrayOf(6, 12),  // right_shoulder - right_hip
    intArrayOf(11, 12), // left_hip - right_hip
    intArrayOf(11, 13), // left_hip - left_knee
    intArrayOf(13, 15), // left_knee - left_ankle
    intArrayOf(12, 14), // right_hip - right_knee
    intArrayOf(14, 16)  // right_knee - right_ankle
)
