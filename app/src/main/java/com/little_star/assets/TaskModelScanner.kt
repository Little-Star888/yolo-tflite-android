package com.little_star.assets

import android.content.Context
import com.little_star.R
import com.little_star.detector.model.InferenceType
import com.little_star.detector.model.ModelDescriptor
import com.little_star.detector.model.ModelFormat
import com.little_star.detector.model.ModelSize
import com.little_star.detector.model.TaskType

/**
 * 任务模型扫描器，解析级联结构：任务类型/格式/模型包/尺寸/文件
 *
 * 目录结构约定：
 * {taskType}/
 * └── tflite/
 *     └── {packageName}/              # 模型包目录（如 yolo26n）
 *         ├── label.txt               # 该模型包专属的标签文件
 *         └── models/
 *             └── {size}/             # 模型尺寸目录（如 x, n, s, m, l）
 *                 ├── [model].tflite  # 原始模型文件
 *                 └── aot/            # AOT预编译模型
 *
 * {packageName} 格式：{base}[-{task}] 或 {base}[_{task}]
 * 例如：yolo26n, yolo26, yolo26-custom
 *
 * 复合 Key 约定（确保内置/本地/远程三方隔离）：
 * - 内置 assets 包：key = packageName + "::builtin"（如 "yolo26n::builtin"）
 * - 本地导入包：key = packageName（如 "yolo26n"）
 * - 远程下载包：key = packageName + "::remote"（如 "yolo26n::remote"）
 *
 * @param context Android 上下文
 * @param taskType 任务类型（目标检测、关键点检测等）
 */
class TaskModelScanner(
    private val context: Context,
    private val taskType: TaskType
) {

    companion object {
        private const val BUILTIN_KEY_SUFFIX = "::builtin"
        private const val REMOTE_KEY_SUFFIX = "::remote"

        /** 推理类型缓存（跨 Scanner 实例持久化，key = absolutePath 或 assetPath） */
        private val inferenceTypeCache = mutableMapOf<String, InferenceType>()

        /** 判断是否是内置包的复合 key */
        fun isBuiltinKey(key: String): Boolean = key.endsWith(BUILTIN_KEY_SUFFIX)

        /** 判断是否是远程包的复合 key */
        fun isRemoteKey(key: String): Boolean = key.endsWith(REMOTE_KEY_SUFFIX)

        /** 从复合 key 提取真实包名 */
        fun keyToPackageName(key: String): String =
            key.removeSuffix(BUILTIN_KEY_SUFFIX).removeSuffix(REMOTE_KEY_SUFFIX)

        /** 将复合 key 转为显示名（带来源后缀） */
        fun keyToDisplayName(key: String, importedPackages: Set<String>, context: Context): String {
            val pkg = keyToPackageName(key)
            return when {
                isBuiltinKey(key) -> context.getString(R.string.source_builtin, pkg)
                isRemoteKey(key) -> context.getString(R.string.source_remote, pkg)
                pkg in importedPackages -> context.getString(R.string.source_local, pkg)
                else -> pkg
            }
        }
    }

    // modelTree: format -> packageName(或复合key) -> size -> models
    private val modelTree =
        mutableMapOf<ModelFormat, MutableMap<String, MutableMap<ModelSize, MutableList<ModelDescriptor>>>>()

    init {
        scan(context)
        mergeImportedModels(context)
        mergeDownloadedModels(context)
    }

    /**
     * 获取指定模型包的标签文件路径
     * @param key 复合 key（可能是 "packageName::builtin" 或 "packageName::remote"）
     */
    fun getLabelPath(key: String): String {
        val packageName = keyToPackageName(key)
        return "${taskType.assetDir}/tflite/$packageName/label.txt"
    }

    /**
     * 刷新模型列表（重新扫描 assets 和导入模型）
     */
    fun refresh(context: Context) {
        modelTree.clear()
        scan(context)
        mergeImportedModels(context)
        mergeDownloadedModels(context)
    }

    private fun scan(context: Context) {
        for (format in ModelFormat.entries) {
            val formatDir = format.displayName.lowercase()
            // 关键改动：包含任务类型顶级目录
            val taskTfliteDir = "${taskType.assetDir}/$formatDir"

            // 扫描目录下的所有模型包（模型包是包含 models/ 子目录的目录）
            val modelPackages = try {
                context.assets.list(taskTfliteDir) ?: return
            } catch (e: Exception) {
                // 目录不存在，跳过
                return
            }

            for (packageName in modelPackages) {
                val packagePath = "$taskTfliteDir/$packageName"
                val modelsDir = "$packagePath/models"

                // 检查是否是模型包（有 models/ 子目录）
                val sizeDirs = try {
                    context.assets.list(modelsDir) ?: continue
                } catch (e: Exception) {
                    continue
                }

                for (sizeDirName in sizeDirs) {
                    val size = ModelSize.entries.find { it.folder == sizeDirName } ?: continue
                    val sizePath = "$modelsDir/$sizeDirName"
                    val files = try {
                        context.assets.list(sizePath) ?: continue
                    } catch (e: Exception) {
                        continue
                    }

                    for (file in files) {
                        if (file.endsWith(".tflite")) {
                            val assetPath = "$sizePath/$file"
                            // 获取 assets 文件大小，与本地/远程模型保持一致
                            val assetFileSize = try {
                                context.assets.openFd(assetPath).length
                            } catch (e: Exception) {
                                0L
                            }
                            val descriptor = ModelDescriptor(
                                format = format,
                                packageName = packageName,
                                size = size,
                                assetPath = assetPath,
                                fileName = file,
                                isBuiltin = true,
                                fileSize = assetFileSize,
                                taskType = taskType
                            )
                            // 使用 ::builtin 后缀确保与本地导入/远程下载模型隔离
                            val key = packageName + BUILTIN_KEY_SUFFIX
                            modelTree.getOrPut(format) { mutableMapOf() }
                                .getOrPut(key) { mutableMapOf() }
                                .getOrPut(size) { mutableListOf() }
                                .add(descriptor)
                        }
                    }
                }
            }
        }
    }

    fun getAvailableFormats(): List<ModelFormat> = modelTree.keys.toList()

    /** 获取可用的模型包列表（复合 key） */
    fun getAvailablePackages(format: ModelFormat): List<String> =
        modelTree[format]?.keys?.toList() ?: emptyList()

    fun getAvailableSizes(format: ModelFormat, packageName: String): List<ModelSize> =
        modelTree[format]?.get(packageName)?.keys?.toList() ?: emptyList()

    fun getAvailableModels(
        format: ModelFormat,
        packageName: String,
        size: ModelSize
    ): List<ModelDescriptor> =
        modelTree[format]?.get(packageName)?.get(size)?.toList() ?: emptyList()

    /**
     * 根据模型输出 shape 自动检测推理类型
     *
     * 端到端: (1, max_det, features) 如 (1, 300, 6) — features < max_det
     * 传统:   (1, channels, candidates) 如 (1, 84, 8400) — candidates > channels
     *
     * 优化：直接从 TFLite FlatBuffer 二进制格式读取输出张量 shape，
     * 跳过 Interpreter 创建（微秒级 vs 原方案数百毫秒级）。
     * 结果按模型路径缓存，后续调用零开销。
     */
    fun detectInferenceType(model: ModelDescriptor): InferenceType {
        // 缓存命中
        val cacheKey = model.absolutePath ?: model.assetPath
        inferenceTypeCache[cacheKey]?.let { return it }

        // 快速路径：直接从 FlatBuffer 读取输出 shape
        val detected = try {
            detectInferenceTypeFast(model)
        } catch (_: Exception) {
            // 回退：使用 Interpreter（仅 FlatBuffer 解析失败时）
            detectInferenceTypeFallback(model)
        }

        // 缓存结果
        inferenceTypeCache[cacheKey] = detected
        return detected
    }

    /**
     * 快速检测：直接从 TFLite FlatBuffer 读取输出张量 shape
     * TFLite 模型使用 FlatBuffer 序列化，格式为小端字节序：
     *   Model → subgraphs[0] → outputs → tensors[idx] → shape
     * 无需创建 Interpreter，性能从数百毫秒降至微秒级。
     */
    private fun detectInferenceTypeFast(model: ModelDescriptor): InferenceType {
        val buffer = mapModelFile(model)
        val shape = readTfLiteOutputShape(buffer)
            ?: return InferenceType.TRADITIONAL

        return if (shape.size == 3 && shape[1] > 0 && shape[2] > 0) {
            if (shape[2] < shape[1]) InferenceType.END2END else InferenceType.TRADITIONAL
        } else {
            InferenceType.TRADITIONAL
        }
    }

    /** 将模型文件映射为 ByteBuffer（内存映射，零拷贝） */
    private fun mapModelFile(model: ModelDescriptor): java.nio.ByteBuffer {
        return if (model.isLocal) {
            val file = java.io.File(model.absolutePath!!)
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, file.length())
            }
        } else {
            context.assets.openFd(model.assetPath).use { afd ->
                afd.createInputStream().channel.map(
                    java.nio.channels.FileChannel.MapMode.READ_ONLY,
                    afd.startOffset, afd.declaredLength
                )
            }
        }
    }

    /**
     * 从 TFLite FlatBuffer 中读取第一个输出张量的 shape
     *
     * FlatBuffer vtable 布局：[vtable_size:u16][table_size:u16][field_offsets:u16[]]
     * 字段 i 的偏移存储在 vtable 的 (4 + i*2) 位置
     *
     * TFLite schema 字段索引：
     *   Model.subgraphs = field 2
     *   SubGraph.tensors = field 0, SubGraph.outputs = field 2
     *   Tensor.shape = field 0
     */
    private fun readTfLiteOutputShape(buf: java.nio.ByteBuffer): IntArray? {
        buf.order(java.nio.ByteOrder.LITTLE_ENDIAN)

        // 根表偏移
        val rootOff = buf.getInt(0)

        // ── Model.subgraphs (field 2) ──
        val sgVecFieldOff = vtableField(buf, rootOff, 2) ?: return null
        val sgVecPos = rootOff + sgVecFieldOff
        val sgCount = buf.getInt(sgVecPos)
        if (sgCount == 0) return null

        // 第一个 subgraph（偏移量相对于自身位置）
        val sg0RelOff = buf.getInt(sgVecPos + 4)
        val sg0Pos = sgVecPos + 4 + sg0RelOff

        // ── SubGraph.outputs (field 2) → 第一个输出张量索引 ──
        val outVecFieldOff = vtableField(buf, sg0Pos, 2) ?: return null
        val outVecPos = sg0Pos + outVecFieldOff
        val outCount = buf.getInt(outVecPos)
        if (outCount == 0) return null
        val firstOutIdx = buf.getInt(outVecPos + 4)

        // ── SubGraph.tensors (field 0) → tensors[firstOutIdx] ──
        val tensorVecFieldOff = vtableField(buf, sg0Pos, 0) ?: return null
        val tensorVecPos = sg0Pos + tensorVecFieldOff
        val tensorCount = buf.getInt(tensorVecPos)
        if (firstOutIdx < 0 || firstOutIdx >= tensorCount) return null

        val t0EntryPos = tensorVecPos + 4 + firstOutIdx * 4
        val t0RelOff = buf.getInt(t0EntryPos)
        val t0Pos = t0EntryPos + t0RelOff

        // ── Tensor.shape (field 0) → shape 向量 ──
        val shapeFieldOff = vtableField(buf, t0Pos, 0) ?: return null
        val shapeVecPos = t0Pos + shapeFieldOff
        val shapeLen = buf.getInt(shapeVecPos)
        if (shapeLen <= 0 || shapeLen > 10) return null  // 合理范围保护

        return IntArray(shapeLen) { i -> buf.getInt(shapeVecPos + 4 + i * 4) }
    }

    /** 读取 FlatBuffer vtable 中第 fieldIdx 个字段的偏移（相对表起点），不存在返回 null */
    private fun vtableField(buf: java.nio.ByteBuffer, tablePos: Int, fieldIdx: Int): Int? {
        val vtableSOff = buf.getInt(tablePos)
        val vtablePos = tablePos - vtableSOff
        val vtableLen = buf.getShort(vtablePos).toInt() and 0xFFFF
        val fieldSlotPos = 4 + fieldIdx * 2
        if (fieldSlotPos >= vtableLen) return null
        val off = buf.getShort(vtablePos + fieldSlotPos).toInt() and 0xFFFF
        return if (off == 0) null else off
    }

    /** 回退方案：使用 TFLite Interpreter 检测（仅 FlatBuffer 解析失败时使用） */
    private fun detectInferenceTypeFallback(model: ModelDescriptor): InferenceType {
        return try {
            val tempFile = if (model.isLocal) {
                java.io.File(model.absolutePath!!)
            } else {
                val tf = java.io.File.createTempFile("model", ".tflite")
                tf.deleteOnExit()
                context.assets.openFd(model.assetPath).use { afd ->
                    java.io.FileOutputStream(tf).use { fos ->
                        afd.createInputStream().use { fis ->
                            fos.channel.transferFrom(fis.channel, 0, afd.length)
                        }
                    }
                }
                tf
            }

            val interpreter = org.tensorflow.lite.Interpreter(tempFile)
            try {
                val shape = interpreter.getOutputTensor(0).shape()
                if (shape != null && shape.size == 3 && shape[1] > 0 && shape[2] > 0) {
                    if (shape[2] < shape[1]) InferenceType.END2END else InferenceType.TRADITIONAL
                } else {
                    InferenceType.TRADITIONAL
                }
            } finally {
                interpreter.close()
            }
        } catch (_: Exception) {
            InferenceType.TRADITIONAL
        }
    }

    /**
     * 将导入的本地模型合并到 modelTree（key 不带后缀）
     */
    private fun mergeImportedModels(context: Context) {
        val importedModels = LocalModelManager.scanImportedModels(context, taskType)
        for (model in importedModels) {
            modelTree.getOrPut(model.format) { mutableMapOf() }
                .getOrPut(model.packageName) { mutableMapOf() }
                .getOrPut(model.size) { mutableListOf() }
                .add(model)
        }
    }

    /**
     * 将远程下载的模型合并到 modelTree（key 带 ::remote 后缀）
     */
    private fun mergeDownloadedModels(context: Context) {
        val downloadedModels = LocalModelManager.scanDownloadedModels(context, taskType)
        for (model in downloadedModels) {
            val key = model.packageName + REMOTE_KEY_SUFFIX
            modelTree.getOrPut(model.format) { mutableMapOf() }
                .getOrPut(key) { mutableMapOf() }
                .getOrPut(model.size) { mutableListOf() }
                .add(model)
        }
    }
}
