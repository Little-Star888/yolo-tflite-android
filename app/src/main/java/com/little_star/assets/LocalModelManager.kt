package com.little_star.assets

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.little_star.R
import com.little_star.detector.model.ModelDescriptor
import com.little_star.detector.model.ModelFormat
import com.little_star.detector.model.ModelSize
import com.little_star.detector.model.TaskType
import com.little_star.detector.util.SoCUtils
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream

/**
 * 本地模型导入管理器
 *
 * 负责将用户从手机目录选择的模型文件导入到应用内部存储，
 * 并提供扫描、删除等管理功能。
 *
 * 导入后的目录结构（与 assets 保持一致）：
 * filesDir/imported_models/{taskType}/tflite/{packageName}/label.txt
 * filesDir/imported_models/{taskType}/tflite/{packageName}/models/{size}/[model].tflite
 * filesDir/imported_models/{taskType}/tflite/{packageName}/models/{size}/aot/[aot_model].tflite
 *
 * 关键点任务额外配置文件：
 * filesDir/imported_models/{taskType}/tflite/{packageName}/label.txt
 *
 * 目录结构校验规则（解压/导入时校验）：
 * 1. 第一级目录必须是有效的 taskType 名字（如 detection, keypoint, segmentation, classification, oriented_bbox）
 * 2. 第二级目录必须是 "tflite"（固定）
 * 3. 第三级目录是 packageName（不校验）
 * 4. 必须包含 models/ 目录
 * 5. models/ 下必须是有效的尺寸目录（如 n, s, m, l, x）
 * 6. 尺寸目录下模型文件必须以 .tflite 结尾
 * 7. aot/ 目录（可选）存在时，aot 模型文件命名格式必须为：
 *    - Qualcomm: {baseName}_Qualcomm_{socModel}.tflite
 *    - MediaTek: {baseName}_MediaTek.tflite
 * 8. 【关键点任务】label.txt 必须包含 [keypoints]、[flip_idx]、[keypoints_link] section
 */
object LocalModelManager {

    private const val IMPORT_ROOT = "imported_models"
    private const val REMOTE_IMPORT_ROOT = "downloaded_models"
    private const val FORMAT_DIR = "tflite"

    private val VALID_TASK_TYPES = TaskType.entries.map { it.assetDir }

    /**
     * 递归查找目录下匹配 taskType 的子目录（File 版本）
     */
    private fun findTaskTypeDir(dir: File, taskType: TaskType): File? {
        for (subDir in dir.listFiles()?.filter { it.isDirectory } ?: emptyList()) {
            if (subDir.name == taskType.assetDir) {
                return subDir
            }
            val found = findTaskTypeDir(subDir, taskType)
            if (found != null) return found
        }
        return null
    }

    /**
     * 递归查找目录下匹配 taskType 的子目录（DocumentFile 版本）
     */
    private fun findTaskTypeDirAsDocumentFile(dir: DocumentFile, taskType: TaskType): DocumentFile? {
        for (subDir in dir.listFiles().filter { it.isDirectory }) {
            if (subDir.name == taskType.assetDir) {
                return subDir
            }
            val found = findTaskTypeDirAsDocumentFile(subDir, taskType)
            if (found != null) return found
        }
        return null
    }

    /**
     * 递归查找目录下任意有效的 taskType 子目录
     * @param dir 要查找的目录
     * @return 找到返回 Pair(目录, taskType名称)，否则返回 null
     */
    private fun findAnyTaskTypeDir(dir: File): Pair<File, String>? {
        for (subDir in dir.listFiles()?.filter { it.isDirectory } ?: emptyList()) {
            if (subDir.name in VALID_TASK_TYPES) {
                return Pair(subDir, subDir.name)
            }
            val found = findAnyTaskTypeDir(subDir)
            if (found != null) return found
        }
        return null
    }

    /** 导入被取消时抛出的异常 */
    class ImportCancelledException : Exception("导入已取消")

    /** 目录结构校验异常 */
    class InvalidStructureException(message: String) : Exception(message)

    /** 导入进度回调 */
    interface ImportProgressCallback {
        /** 开始导入前调用，报告检测到的包数量
         * @param totalPackages 包总数
         */
        fun onStart(totalPackages: Int)

        /** 开始导入一个包时调用
         * @param current 当前第几个（从1开始）
         * @param total 总数
         * @param packageName 当前包的名称
         */
        fun onPackageStart(current: Int, total: Int, packageName: String)

        /** 导入一个包完成时调用
         * @param packageName 包名称
         * @param modelCount 该包中的模型数量
         * @param success 是否成功
         */
        fun onPackageComplete(packageName: String, modelCount: Int, success: Boolean)
    }

    /** 导入进度状态 */
    data class ImportProgress(
        val currentPackage: Int = 0,      // 当前第几个包（从1开始）
        val totalPackages: Int = 0,       // 总包数
        val currentPackageName: String = "", // 当前包名称
        val completedPackages: List<PackageResult> = emptyList() // 已完成的包结果
    ) {
        val progressPercent: Int get() = if (totalPackages > 0) (currentPackage * 100 / totalPackages) else 0
    }

    /** 已完成的包结果 */
    data class PackageResult(
        val name: String,
        val modelCount: Int,
        val success: Boolean
    )

    /** 导入结果 */
    data class ImportResult(
        val success: Boolean,
        val packageName: String?,      // 第一个成功的包名（兼容用）
        val modelCount: Int,
        val error: String? = null,
        val successCount: Int = 0,    // 成功数量
        val failCount: Int = 0,       // 失败数量
        val errors: List<String> = emptyList(),  // 所有错误信息
        val packageNames: List<String> = emptyList()  // 所有成功导入的包名
    )

    /**
     * 校验目录结构是否符合规范
     *
     * @param dir 要校验的目录
     * @param validatePackageName 是否校验 packageName 目录结构
     * @return 校验通过的 packageName 列表（可能是空列表表示目录结构正确但没有有效包）
     * @throws InvalidStructureException 目录结构不符合规范时抛出
     */
    @Throws(InvalidStructureException::class)
    fun validateAndListPackages(dir: File, context: Context, taskType: TaskType? = null, validatePackageName: Boolean = true): List<String> {
        if (!dir.exists() || !dir.isDirectory) {
            throw InvalidStructureException(context.getString(R.string.import_dir_not_exist))
        }

        // 获取第一级目录名（应该是 taskType）
        val firstLevelName = dir.name
        val validTaskTypes = VALID_TASK_TYPES
        if (firstLevelName !in validTaskTypes) {
            throw InvalidStructureException(
                context.getString(R.string.import_invalid_task_type, firstLevelName, validTaskTypes.joinToString(", "))
            )
        }

        // 如果指定了 taskType，校验是否匹配
        if (taskType != null && firstLevelName != taskType.assetDir) {
            throw InvalidStructureException(
                context.getString(R.string.import_wrong_module, firstLevelName, taskType.assetDir)
            )
        }

        // 检查第二级目录是否是 "tflite"
        val tfliteDir = File(dir, FORMAT_DIR)
        if (!tfliteDir.exists() || !tfliteDir.isDirectory) {
            throw InvalidStructureException(context.getString(R.string.import_missing_tflite_dir))
        }

        // 列出所有 packageName 目录
        val packageNames = mutableListOf<String>()
        for (pkgDir in tfliteDir.listFiles()?.filter { it.isDirectory } ?: emptyList()) {
            val packageName = pkgDir.name

            if (validatePackageName) {
                // 校验 models 目录（必需）
                val modelsDir = File(pkgDir, "models")
                if (!modelsDir.exists() || !modelsDir.isDirectory) {
                    throw InvalidStructureException(context.getString(R.string.import_missing_models_dir, packageName))
                }

                // 校验 label.txt（必需）
                val labelFile = File(pkgDir, "label.txt")
                if (!labelFile.exists() || !labelFile.isFile) {
                    throw InvalidStructureException(context.getString(R.string.import_missing_label, packageName))
                }

                // 【关键点任务】校验 label.txt 存在（KEYPOINT 任务的内容校验在运行时解析时进行）
                val detectedTaskType = TaskType.fromAssetDir(firstLevelName)
                if (detectedTaskType == TaskType.KEYPOINT) {
                    val labelFile = File(pkgDir, "label.txt")
                    if (!labelFile.exists() || !labelFile.isFile) {
                        throw InvalidStructureException(context.getString(R.string.import_missing_label, packageName))
                    }
                }

                // 校验尺寸目录和模型文件
                var hasValidModel = false
                for (sizeDir in modelsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()) {
                    val sizeName = sizeDir.name
                    val size = ModelSize.entries.find { it.folder == sizeName }
                    if (size == null) {
                        android.util.Log.w("LocalModelManager", context.getString(R.string.import_size_dir_invalid, sizeName, packageName))
                        continue
                    }

                    // 检查尺寸目录下的 tflite 文件
                    val tfliteFiles = sizeDir.listFiles()?.filter {
                        it.isFile && it.name.endsWith(".tflite")
                    } ?: emptyList()

                    if (tfliteFiles.isEmpty()) {
                        android.util.Log.w("LocalModelManager", context.getString(R.string.import_no_tflite_files, packageName, sizeName))
                        continue
                    }

                    hasValidModel = true

                    // 校验 aot 目录（如果存在）
                    val aotDir = File(sizeDir, "aot")
                    if (aotDir.exists() && aotDir.isDirectory) {
                        validateAotDir(context, aotDir, tfliteFiles.map { it.name.removeSuffix(".tflite") }, packageName, sizeName)
                    }
                }

                if (!hasValidModel) {
                    android.util.Log.w("LocalModelManager", context.getString(R.string.import_no_valid_models, packageName))
                    continue
                }
            }

            packageNames.add(packageName)
        }

        return packageNames
    }

    /**
     * 校验 aot 目录中的文件命名格式
     *
     * @param aotDir aot 目录
     * @param baseNames 对应的原始模型文件名（不含 .tflite 后缀）
     * @param packageName 包名（用于日志）
     * @param sizeName 尺寸名（用于日志）
     */
    private fun validateAotDir(context: Context, aotDir: File, baseNames: List<String>, packageName: String, sizeName: String) {
        val socModel = try { SoCUtils.getSoCModel() } catch (_: Exception) { "unknown" }
        val vendor = try { SoCUtils.getVendor() } catch (_: Exception) { "" }

        val validBaseNames = baseNames.toSet()

        for (aotFile in aotDir.listFiles()?.filter { it.isFile && it.name.endsWith(".tflite") } ?: emptyList()) {
            val fileName = aotFile.name
            val baseName = fileName.removeSuffix(".tflite")

            // 提取可能的 baseName 前缀
            val isValidName = when (vendor) {
                "qualcomm" -> {
                    // Qualcomm 格式: baseName_Qualcomm_socModel.tflite
                    // 原始模型可能是 yolo26n，则 aot 可能是 yolo26n_Qualcomm_SM8550.tflite
                    validBaseNames.any { bn ->
                        baseName.startsWith("${bn}_Qualcomm_") ||
                        baseName == "${bn}_Qualcomm_$socModel"
                    }
                }
                "mediatek" -> {
                    // MediaTek 格式: baseName_MediaTek.tflite
                    validBaseNames.any { bn ->
                        baseName.startsWith("${bn}_MediaTek")
                    }
                }
                else -> {
                    // 未知厂商时，只检查是否符合通用格式
                    baseName.contains("_Qualcomm_") || baseName.contains("_MediaTek")
                }
            }

            if (!isValidName) {
                android.util.Log.w(
                    "LocalModelManager",
                    context.getString(R.string.import_aot_mismatch, fileName, packageName, sizeName, vendor, socModel)
                )
            }
        }
    }

    /**
     * 从 SAF 目录导入模型（自动判断）
     *
     * @param context 上下文
     * @param uri SAF 选择器返回的 Uri（可以是目录或 .zip 文件）
     * @param taskType 任务类型
     * @param isCancelled 取消检查回调，返回 true 时中止导入并清理
     * @param progressCallback 进度回调
     * @return 导入结果
     */
    fun importAuto(
        context: Context,
        uri: Uri,
        taskType: TaskType = TaskType.DETECTION,
        isCancelled: () -> Boolean = { false },
        progressCallback: ImportProgressCallback? = null
    ): ImportResult {
        // 尝试作为文件处理（可能是 .zip）
        try {
            val docFile = DocumentFile.fromSingleUri(context, uri)
            if (docFile != null && docFile.exists() && docFile.isFile) {
                val fileName = docFile.name ?: ""
                if (fileName.endsWith(".zip", ignoreCase = true)) {
                    return importFromArchive(context, uri, taskType, isCancelled, progressCallback)
                }
            }
        } catch (e: Exception) {
            // 不是单个文件，继续尝试作为目录处理
        }

        // 尝试作为目录处理
        return importFromDirectory(context, uri, taskType, isCancelled, progressCallback)
    }

    /**
     * 从 SAF 目录导入模型
     *
     * @param context 上下文
     * @param treeUri SAF 目录选择器返回的 Uri
     * @param taskType 任务类型
     * @param isCancelled 取消检查回调
     * @param progressCallback 进度回调
     * @return 导入结果
     */
    fun importFromDirectory(
        context: Context,
        treeUri: Uri,
        taskType: TaskType = TaskType.DETECTION,
        isCancelled: () -> Boolean = { false },
        progressCallback: ImportProgressCallback? = null
    ): ImportResult {
        val pickedDir = DocumentFile.fromTreeUri(context, treeUri)
            ?: return ImportResult(false, null, 0, context.getString(R.string.import_cannot_access_dir))

        // 尝试递归查找 taskType 子目录
        // pickedDir 可能是父目录，需要在其中查找 taskType 目录
        val taskTypeDir = findTaskTypeDirAsDocumentFile(pickedDir, taskType)
            ?: // 如果找不到，尝试将 pickedDir 本身当作 taskType 目录
            if (pickedDir.name == taskType.assetDir) pickedDir
            else return ImportResult(false, null, 0, context.getString(R.string.import_dir_structure_mismatch, taskType.assetDir))

        // 校验目录结构：taskTypeDir 必须是有效的 taskType，第二级必须是 tflite
        try {
            validateDirectoryStructure(context, taskTypeDir, taskType)
        } catch (e: InvalidStructureException) {
            return ImportResult(false, null, 0, context.getString(R.string.import_dir_structure_invalid, e.message))
        }

        // taskTypeDir 是 {taskType} 目录，tfliteDir 是 {taskType}/tflite
        val tfliteDir = taskTypeDir.findFile(FORMAT_DIR) ?: return ImportResult(false, null, 0, context.getString(R.string.import_missing_tflite_dir))

        // 校验所有包结构：models 目录和 label.txt 都必须存在
        val validationError = validatePackagesInTfliteDir(context, tfliteDir, taskType)
        if (validationError != null) {
            return ImportResult(false, null, 0, validationError)
        }

        val importRoot = File(context.filesDir, "$IMPORT_ROOT/${taskType.assetDir}/$FORMAT_DIR")
        var totalModels = 0
        val importedPackages = mutableListOf<String>()  // 追踪所有成功的包名
        // 记录已导入的包目录，取消时用于清理
        val importedDirs = mutableListOf<File>()

        val subDirs = tfliteDir.listFiles().filter { it.isDirectory }
        if (subDirs.isEmpty()) {
            // tflite 下没有子目录，可能选中的是 packageName 目录本身（但外层有 taskType/tflite 两级）
            // 这种情况下，需要把整个 tflite 目录当作 package source
            val pkgName = tfliteDir.name ?: context.getString(R.string.import_unknown_package)
            progressCallback?.onStart(1)
            progressCallback?.onPackageStart(1, 1, pkgName)
            val result = tryImportPackageFromTfliteDir(context, tfliteDir, importRoot, taskType, isCancelled)
            return if (result != null && result.success) {
                progressCallback?.onPackageComplete(pkgName, result.modelCount, true)
                ImportResult(true, pkgName, result.modelCount, packageNames = listOf(pkgName))
            } else {
                progressCallback?.onPackageComplete(pkgName, 0, false)
                ImportResult(false, null, 0, context.getString(R.string.import_need_subdirs))
            }
        }

        // 报告开始
        progressCallback?.onStart(subDirs.size)

        // 遍历子目录，尝试导入每个模型包
        try {
            subDirs.forEachIndexed { index, subDir ->
                if (isCancelled()) throw ImportCancelledException()
                val pkgName = subDir.name
                if (pkgName == null) return@forEachIndexed
                progressCallback?.onPackageStart(index + 1, subDirs.size, pkgName)
                val targetDir = File(importRoot, pkgName)
                val result = tryImportPackage(context, subDir, importRoot, taskType, isCancelled)
                if (result != null && result.success) {
                    totalModels += result.modelCount
                    importedPackages.add(pkgName)
                    importedDirs.add(targetDir)
                    progressCallback?.onPackageComplete(pkgName, result.modelCount, true)
                } else {
                    progressCallback?.onPackageComplete(pkgName, 0, false)
                }
            }
        } catch (e: ImportCancelledException) {
            // 取消时清理本次导入的所有包目录
            importedDirs.forEach { it.deleteRecursively() }
            return ImportResult(false, null, 0, context.getString(R.string.import_cancelled))
        }

        // 如果子目录都不是模型包，尝试将选中目录本身当作模型包
        if (totalModels == 0) {
            val result = tryImportPackageFromTfliteDir(context, tfliteDir, importRoot, taskType, isCancelled)
            if (result != null && result.success) {
                val pkgName = tfliteDir.name ?: context.getString(R.string.import_unknown_package)
                return ImportResult(true, pkgName, result.modelCount, packageNames = listOf(pkgName))
            }
        }

        return if (totalModels > 0) {
            ImportResult(true, importedPackages.firstOrNull(), totalModels, packageNames = importedPackages.toList())
        } else {
            ImportResult(false, null, 0, context.getString(R.string.import_no_valid_files))
        }
    }

    /**
     * 校验 DocumentFile 目录结构是否符合规范
     * 第一级：有效的 taskType（必须与当前模块匹配）
     * 第二级：tflite
     */
    private fun validateDirectoryStructure(context: Context, dir: DocumentFile, taskType: TaskType) {
        val firstLevelName = dir.name
        val validTaskTypes = VALID_TASK_TYPES
        if (firstLevelName !in validTaskTypes) {
            throw InvalidStructureException(
                context.getString(R.string.import_invalid_task_type, firstLevelName, validTaskTypes.joinToString(", "))
            )
        }

        // 校验第一级目录是否与当前模块匹配
        if (firstLevelName != taskType.assetDir) {
            throw InvalidStructureException(
                context.getString(R.string.import_wrong_module, firstLevelName, taskType.assetDir)
            )
        }

        val tfliteDir = dir.findFile(FORMAT_DIR)
        if (tfliteDir == null || !tfliteDir.isDirectory) {
            throw InvalidStructureException(context.getString(R.string.import_missing_tflite_dir))
        }
    }

    /**
     * 校验 tflite 目录下的所有包结构
     * @param tfliteDir tflite 目录（DocumentFile）
     * @param taskType 当前模块类型，用于决定是否校验关键点配置文件
     * @return 如果有错误返回错误信息，校验通过返回 null
     */
    private fun validatePackagesInTfliteDir(context: Context, tfliteDir: DocumentFile, taskType: TaskType): String? {
        for (pkgDir in tfliteDir.listFiles().filter { it.isDirectory }) {
            val packageName = pkgDir.name ?: continue

            // 检查 models 目录
            val modelsDir = pkgDir.findFile("models")
            if (modelsDir == null || !modelsDir.isDirectory) {
                return context.getString(R.string.import_missing_models_dir, packageName)
            }

            // 检查 label.txt
            val labelFile = pkgDir.findFile("label.txt")
            if (labelFile == null || !labelFile.isFile) {
                return context.getString(R.string.import_missing_label, packageName)
            }

        }
        return null
    }

    /**
     * 从 tflite 目录（DocumentFile）导入模型包
     * 当用户选择的目录结构为 {taskType}/tflite/ 而非 {taskType}/tflite/{packageName}/ 时调用
     */
    private fun tryImportPackageFromTfliteDir(
        context: Context,
        tfliteDir: DocumentFile,
        importRoot: File,
        taskType: TaskType = TaskType.DETECTION,
        isCancelled: () -> Boolean = { false }
    ): ImportResult? {
        val packageName = tfliteDir.name ?: return null

        // 检查是否有 label.txt
        val labelFile = tfliteDir.findFile("label.txt")
        // 检查是否有 models/ 子目录
        val modelsDir = tfliteDir.findFile("models")
        if (modelsDir == null || !modelsDir.isDirectory) return null

        var modelCount = 0

        // 创建目标目录
        val targetPackageDir = File(importRoot, packageName)
        val targetModelsDir = File(targetPackageDir, "models")

        try {
            // 拷贝 label.txt（如果存在）
            if (isCancelled()) throw ImportCancelledException()
            if (labelFile != null && labelFile.isFile) {
                copyDocumentFile(context, labelFile, File(targetPackageDir, "label.txt"))
            }

            // 遍历 models/ 下的尺寸目录
            for (sizeDir in modelsDir.listFiles().filter { it.isDirectory }) {
                if (isCancelled()) throw ImportCancelledException()
                val sizeDirName = sizeDir.name ?: continue
                val size = ModelSize.entries.find { it.folder == sizeDirName } ?: continue
                val targetSizeDir = File(targetModelsDir, sizeDirName)

                // 拷贝 .tflite 文件
                for (file in sizeDir.listFiles().filter { it.isFile }) {
                    if (isCancelled()) throw ImportCancelledException()
                    val name = file.name ?: continue
                    if (name.endsWith(".tflite")) {
                        copyDocumentFile(context, file, File(targetSizeDir, name))
                        modelCount++
                    }
                }

                // 拷贝 aot/ 子目录
                val aotDir = sizeDir.findFile("aot")
                if (aotDir != null && aotDir.isDirectory) {
                    val targetAotDir = File(targetSizeDir, "aot")
                    for (aotFile in aotDir.listFiles().filter { it.isFile }) {
                        if (isCancelled()) throw ImportCancelledException()
                        val name = aotFile.name ?: continue
                        if (name.endsWith(".tflite")) {
                            copyDocumentFile(context, aotFile, File(targetAotDir, name))
                        }
                    }
                }
            }
        } catch (e: ImportCancelledException) {
            // 取消时清理已创建的目标目录
            targetPackageDir.deleteRecursively()
            throw e
        }

        return if (modelCount > 0) {
            ImportResult(true, packageName, modelCount)
        } else {
            // 清理空目录
            targetPackageDir.deleteRecursively()
            null
        }
    }

    /**
     * 从压缩包导入模型
     *
     * @param context 上下文
     * @param archiveUri SAF 文件选择器返回的 Uri
     * @param taskType 任务类型
     * @param isCancelled 取消检查回调
     * @param progressCallback 进度回调
     * @return 导入结果
     */
    fun importFromArchive(
        context: Context,
        archiveUri: Uri,
        taskType: TaskType = TaskType.DETECTION,
        isCancelled: () -> Boolean = { false },
        progressCallback: ImportProgressCallback? = null
    ): ImportResult {
        val tempDir = File(context.cacheDir, "import_temp_${System.currentTimeMillis()}")
        // 获取文件名用于错误提示
        val fileName = DocumentFile.fromSingleUri(context, archiveUri)?.name ?: context.getString(R.string.import_unknown_file)
        // 记录已导入的包目录，取消时用于清理
        val importedDirs = mutableListOf<File>()
        try {
            // 解压到临时目录
            tempDir.mkdirs()
            context.contentResolver.openInputStream(archiveUri)?.use { input ->
                val zipInput = ZipInputStream(BufferedInputStream(input))
                var entry = zipInput.nextEntry
                while (entry != null) {
                    if (isCancelled()) throw ImportCancelledException()
                    val outFile = File(tempDir, entry.name)
                    // 安全检查：防止 zip slip
                    if (!outFile.canonicalPath.startsWith(tempDir.canonicalPath)) {
                        zipInput.closeEntry()
                        entry = zipInput.nextEntry
                        continue
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { output -> zipInput.copyTo(output) }
                    }
                    zipInput.closeEntry()
                    entry = zipInput.nextEntry
                }
            } ?: return ImportResult(false, null, 0, context.getString(R.string.import_cannot_read_archive, fileName))

            // 扫描解压后的目录结构，找到 taskType 子目录
            val taskTypeDir = findTaskTypeDir(tempDir, taskType)
                ?: return ImportResult(false, null, 0, context.getString(R.string.import_archive_structure_mismatch, fileName, taskType.assetDir))

            // 校验目录结构
            val packageNames: List<String>
            try {
                packageNames = validateAndListPackages(taskTypeDir, context, taskType)
            } catch (e: InvalidStructureException) {
                return ImportResult(false, null, 0, context.getString(R.string.import_archive_structure_invalid, fileName, e.message))
            }

            // 报告开始
            progressCallback?.onStart(packageNames.size)

            // 扫描解压后的目录结构
            val importRoot = File(context.filesDir, "$IMPORT_ROOT/${taskType.assetDir}/$FORMAT_DIR")

            // taskTypeDir 是 {taskType} 目录，需要进入 tflite 子目录
            val tfliteDir = File(taskTypeDir, FORMAT_DIR)

            // 情况1：tflite 目录下直接包含 {packageName}/ 子目录
            val subDirs = tfliteDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
            if (subDirs.isNotEmpty()) {
                var totalModels = 0
                val importedPackages = mutableListOf<String>()  // 追踪所有成功的包名
                subDirs.forEachIndexed { index, subDir ->
                    if (isCancelled()) throw ImportCancelledException()
                    val pkgName = subDir.name
                    progressCallback?.onPackageStart(index + 1, subDirs.size, pkgName)
                    val targetDir = File(importRoot, pkgName)
                    val result = tryImportExtractedPackage(subDir, importRoot, taskType, isCancelled)
                    if (result != null && result.success) {
                        totalModels += result.modelCount
                        importedPackages.add(pkgName)
                        importedDirs.add(targetDir)
                        progressCallback?.onPackageComplete(pkgName, result.modelCount, true)
                    } else {
                        progressCallback?.onPackageComplete(pkgName, 0, false)
                    }
                }
                if (totalModels > 0) {
                    return ImportResult(true, importedPackages.firstOrNull(), totalModels, packageNames = importedPackages.toList())
                }
            }

            // 情况2：tflite 目录下没有子目录，但 tflite 目录本身就是模型包内容
            val singlePkgName = tfliteDir.name
            progressCallback?.onPackageStart(1, 1, singlePkgName)
            val result = tryImportExtractedPackage(tfliteDir, importRoot, taskType, isCancelled)
            if (result != null && result.success) {
                progressCallback?.onPackageComplete(singlePkgName, result.modelCount, true)
                return ImportResult(true, singlePkgName, result.modelCount, packageNames = listOf(singlePkgName))
            }
            progressCallback?.onPackageComplete(singlePkgName, 0, false)

            return ImportResult(false, null, 0, context.getString(R.string.import_archive_no_valid, fileName))
        } catch (e: ImportCancelledException) {
            // 取消时清理已导入的包目录
            importedDirs.forEach { it.deleteRecursively() }
            return ImportResult(false, null, 0, context.getString(R.string.import_cancelled))
        } catch (e: Exception) {
            return ImportResult(false, null, 0, context.getString(R.string.import_archive_extract_failed, fileName, e.message))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * 尝试将解压后的目录作为模型包导入
     * @param isCancelled 取消检查回调
     */
    private fun tryImportExtractedPackage(
        sourceDir: File,
        importRoot: File,
        taskType: TaskType = TaskType.DETECTION,
        isCancelled: () -> Boolean = { false }
    ): ImportResult? {
        val labelFile = File(sourceDir, "label.txt")
        val modelsDir = File(sourceDir, "models")

        // 校验目录结构
        if (!modelsDir.exists() || !modelsDir.isDirectory) return null
        if (!labelFile.exists()) return null

        val packageName = sourceDir.name
        val targetPackageDir = File(importRoot, packageName)
        val targetModelsDir = File(targetPackageDir, "models")

        var modelCount = 0

        try {
            // 拷贝 label.txt
            if (isCancelled()) throw ImportCancelledException()
            labelFile.copyTo(File(targetPackageDir, "label.txt"), overwrite = true)

            // 遍历 models/ 下的尺寸目录
            for (sizeDir in modelsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()) {
                if (isCancelled()) throw ImportCancelledException()
                val sizeDirName = sizeDir.name
                val size = ModelSize.entries.find { it.folder == sizeDirName } ?: continue
                val targetSizeDir = File(targetModelsDir, sizeDirName)

                // 拷贝 .tflite 文件
                for (file in sizeDir.listFiles()?.filter { it.isFile && it.name.endsWith(".tflite") } ?: emptyList()) {
                    if (isCancelled()) throw ImportCancelledException()
                    file.copyTo(File(targetSizeDir, file.name), overwrite = true)
                    modelCount++
                }

                // 拷贝 aot/ 子目录
                val aotDir = File(sizeDir, "aot")
                if (aotDir.exists() && aotDir.isDirectory) {
                    val targetAotDir = File(targetSizeDir, "aot")
                    for (aotFile in aotDir.listFiles()?.filter { it.isFile && it.name.endsWith(".tflite") } ?: emptyList()) {
                        if (isCancelled()) throw ImportCancelledException()
                        aotFile.copyTo(File(targetAotDir, aotFile.name), overwrite = true)
                    }
                }
            }
        } catch (e: ImportCancelledException) {
            // 取消时清理已创建的目标目录
            targetPackageDir.deleteRecursively()
            throw e
        }

        return if (modelCount > 0) {
            ImportResult(true, packageName, modelCount)
        } else {
            targetPackageDir.deleteRecursively()
            null
        }
    }

    /**
     * 尝试将一个目录作为模型包导入
     * @param isCancelled 取消检查回调
     */
    private fun tryImportPackage(
        context: Context,
        packageDir: DocumentFile,
        importRoot: File,
        taskType: TaskType = TaskType.DETECTION,
        isCancelled: () -> Boolean = { false }
    ): ImportResult? {
        val packageName = packageDir.name ?: return null

        // 检查是否有 label.txt
        val labelFile = packageDir.findFile("label.txt")
        // 检查是否有 models/ 子目录
        val modelsDir = packageDir.findFile("models")
        if (modelsDir == null || !modelsDir.isDirectory) return null

        var modelCount = 0

        // 创建目标目录
        val targetPackageDir = File(importRoot, packageName)
        val targetModelsDir = File(targetPackageDir, "models")

        try {
            // 拷贝 label.txt（如果存在）
            if (isCancelled()) throw ImportCancelledException()
            if (labelFile != null && labelFile.isFile) {
                copyDocumentFile(context, labelFile, File(targetPackageDir, "label.txt"))
            }

            // 遍历 models/ 下的尺寸目录
            for (sizeDir in modelsDir.listFiles().filter { it.isDirectory }) {
                if (isCancelled()) throw ImportCancelledException()
                val sizeDirName = sizeDir.name ?: continue
                val size = ModelSize.entries.find { it.folder == sizeDirName } ?: continue
                val targetSizeDir = File(targetModelsDir, sizeDirName)

                // 拷贝 .tflite 文件
                for (file in sizeDir.listFiles().filter { it.isFile }) {
                    if (isCancelled()) throw ImportCancelledException()
                    val name = file.name ?: continue
                    if (name.endsWith(".tflite")) {
                        copyDocumentFile(context, file, File(targetSizeDir, name))
                        modelCount++
                    }
                }

                // 拷贝 aot/ 子目录
                val aotDir = sizeDir.findFile("aot")
                if (aotDir != null && aotDir.isDirectory) {
                    val targetAotDir = File(targetSizeDir, "aot")
                    for (aotFile in aotDir.listFiles().filter { it.isFile }) {
                        if (isCancelled()) throw ImportCancelledException()
                        val name = aotFile.name ?: continue
                        if (name.endsWith(".tflite")) {
                            copyDocumentFile(context, aotFile, File(targetAotDir, name))
                        }
                    }
                }
            }
        } catch (e: ImportCancelledException) {
            // 取消时清理已创建的目标目录
            targetPackageDir.deleteRecursively()
            throw e
        }

        return if (modelCount > 0) {
            ImportResult(true, packageName, modelCount)
        } else {
            // 清理空目录
            targetPackageDir.deleteRecursively()
            null
        }
    }

    /**
     * 拷贝 DocumentFile 到本地 File
     */
    private fun copyDocumentFile(context: Context, source: DocumentFile, target: File) {
        target.parentFile?.mkdirs()
        context.contentResolver.openInputStream(source.uri)?.use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    /**
     * 扫描已导入的本地模型
     */
    fun scanImportedModels(context: Context, taskType: TaskType = TaskType.DETECTION): List<ModelDescriptor> {
        val results = mutableListOf<ModelDescriptor>()
        val importRoot = File(context.filesDir, "$IMPORT_ROOT/${taskType.assetDir}/$FORMAT_DIR")
        if (!importRoot.exists()) return results

        val format = ModelFormat.TFLITE

        // 遍历 packageName 目录
        for (packageDir in importRoot.listFiles()?.filter { it.isDirectory } ?: emptyList()) {
            val packageName = packageDir.name
            val modelsDir = File(packageDir, "models")
            if (!modelsDir.exists()) continue

            // 遍历 size 目录
            for (sizeDir in modelsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()) {
                val size = ModelSize.entries.find { it.folder == sizeDir.name } ?: continue

                // 遍历 .tflite 文件（不含 aot 子目录中的文件）
                for (file in sizeDir.listFiles()?.filter { it.isFile && it.name.endsWith(".tflite") } ?: emptyList()) {
                    results.add(
                        ModelDescriptor(
                            format = format,
                            packageName = packageName,
                            size = size,
                            assetPath = "",  // 本地模型不使用 assetPath
                            fileName = file.name,
                            isLocal = true,
                            absolutePath = file.absolutePath,
                            fileSize = file.length(),
                            taskType = taskType
                        )
                    )
                }
            }
        }

        return results
    }

    /**
     * 获取导入模型的 label.txt 绝对路径
     */
    fun getImportedLabelPath(context: Context, packageName: String, taskType: TaskType = TaskType.DETECTION): String? {
        val labelFile = File(context.filesDir, "$IMPORT_ROOT/${taskType.assetDir}/$FORMAT_DIR/$packageName/label.txt")
        return if (labelFile.exists()) labelFile.absolutePath else null
    }

    /**
     * 删除已导入的模型（批量）
     */
    fun deleteImportedModels(context: Context, packageNames: List<String>, taskType: TaskType = TaskType.DETECTION): Int {
        val importRoot = File(context.filesDir, "$IMPORT_ROOT/${taskType.assetDir}/$FORMAT_DIR")
        if (!importRoot.exists()) return 0

        var count = 0
        for (packageName in packageNames) {
            val pkgDir = File(importRoot, packageName)
            if (pkgDir.exists() && pkgDir.deleteRecursively()) count++
        }
        return count
    }

    /**
     * 删除已导入的模型
     */
    fun deleteImportedModels(context: Context, packageName: String? = null, taskType: TaskType = TaskType.DETECTION): Int {
        val importRoot = File(context.filesDir, "$IMPORT_ROOT/${taskType.assetDir}/$FORMAT_DIR")
        if (!importRoot.exists()) return 0

        return if (packageName != null) {
            val pkgDir = File(importRoot, packageName)
            if (pkgDir.exists() && pkgDir.deleteRecursively()) 1 else 0
        } else {
            var count = 0
            importRoot.listFiles()?.filter { it.isDirectory }?.forEach {
                if (it.deleteRecursively()) count++
            }
            count
        }
    }

    /**
     * 获取已导入的模型包列表
     */
    fun getImportedPackages(context: Context, taskType: TaskType = TaskType.DETECTION): List<String> {
        val importRoot = File(context.filesDir, "$IMPORT_ROOT/${taskType.assetDir}/$FORMAT_DIR")
        if (!importRoot.exists()) return emptyList()

        return importRoot.listFiles()
            ?.filter { it.isDirectory && File(it, "models").exists() }
            ?.map { it.name }
            ?: emptyList()
    }

    /** 检查本地导入目录中是否已存在同名包 */
    fun importedPackageExists(context: Context, packageName: String, taskType: TaskType = TaskType.DETECTION): Boolean {
        val pkgDir = File(context.filesDir, "$IMPORT_ROOT/${taskType.assetDir}/$FORMAT_DIR/$packageName")
        return pkgDir.exists() && File(pkgDir, "models").exists()
    }

    /**
     * 预扫描目录导入的包名（不实际导入）
     * @param taskType 当前模块类型，用于校验目录结构
     */
    fun peekPackageNamesFromDirectory(context: Context, treeUri: Uri, taskType: TaskType = TaskType.DETECTION): List<String> {
        val pickedDir = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val subDirs = pickedDir.listFiles().filter { it.isDirectory }

        // 校验目录结构：第一级目录必须是有效的 taskType，第二级必须是 tflite
        val firstLevelName = pickedDir.name
        if (firstLevelName != taskType.assetDir) {
            // 如果第一级不是当前 taskType，不匹配，跳过
            return emptyList()
        }

        val tfliteDir = pickedDir.findFile(FORMAT_DIR) ?: return emptyList()
        if (!tfliteDir.isDirectory) return emptyList()

        val packages = mutableListOf<String>()
        for (pkgDir in tfliteDir.listFiles().filter { it.isDirectory }) {
            val modelsDir = pkgDir.findFile("models")
            if (modelsDir != null && modelsDir.isDirectory) {
                pkgDir.name?.let { packages.add(it) }
            }
        }
        return packages
    }

    private fun hasPackageStructure(dir: DocumentFile): Boolean {
        val modelsDir = dir.findFile("models")
        return modelsDir != null && modelsDir.isDirectory
    }

    /**
     * 预扫描 zip 压缩包的包名（不实际解压）
     * @param taskType 当前模块类型，用于过滤只返回属于该模块的包
     */
    fun peekPackageNamesFromArchive(context: Context, archiveUri: Uri, taskType: TaskType = TaskType.DETECTION): List<String> {
        val packageNames = mutableSetOf<String>()
        val validTaskTypes = VALID_TASK_TYPES
        try {
            context.contentResolver.openInputStream(archiveUri)?.use { input ->
                val zipInput = ZipInputStream(BufferedInputStream(input))
                var entry = zipInput.nextEntry
                while (entry != null) {
                    val name = entry.name
                    val firstSlash = name.indexOf('/')
                    if (firstSlash > 0) {
                        val firstLevel = name.substring(0, firstSlash)
                        // 第一级必须是有效的 taskType
                        if (firstLevel in validTaskTypes) {
                            val afterFirst = name.substring(firstSlash + 1)
                            val secondSlash = afterFirst.indexOf('/')
                            if (secondSlash > 0) {
                                val potentialPkg = afterFirst.substring(0, secondSlash)
                                val rest = afterFirst.substring(secondSlash + 1)
                                // 第二级是包名，第三级必须是 label.txt 或 models/
                                if (potentialPkg.isNotBlank() && (rest == "label.txt" || rest.startsWith("models/"))) {
                                    packageNames.add(potentialPkg)
                                }
                            }
                        }
                    }
                    zipInput.closeEntry()
                    entry = zipInput.nextEntry
                }
            }
        } catch (_: Exception) { }
        return packageNames.toList()
    }

    /**
     * 统一预扫描（自动判断目录或 zip）
     */
    fun peekPackageNames(context: Context, uri: Uri): List<String> {
        val docFile = DocumentFile.fromSingleUri(context, uri)
        if (docFile != null && docFile.isFile) {
            val fileName = docFile.name ?: ""
            if (fileName.endsWith(".zip", ignoreCase = true)) {
                return peekPackageNamesFromArchive(context, uri)
            }
        }
        return peekPackageNamesFromDirectory(context, uri)
    }

    // ── 远程下载模型存储（独立目录） ──

    /**
     * 将远程下载的 zip 导入到远程专用目录
     */
    fun importRemoteFromArchive(
        context: Context,
        archiveFile: File,
        taskType: TaskType = TaskType.DETECTION,
        progressCallback: ImportProgressCallback? = null
    ): ImportResult {
        val tempDir = File(context.cacheDir, "import_remote_temp_${System.currentTimeMillis()}")
        // 记录已导入的包目录，用于清理
        val importedDirs = mutableListOf<File>()
        try {
            tempDir.mkdirs()
            ZipInputStream(BufferedInputStream(FileInputStream(archiveFile))).use { zipInput ->
                var entry = zipInput.nextEntry
                while (entry != null) {
                    val outFile = File(tempDir, entry.name)
                    if (!outFile.canonicalPath.startsWith(tempDir.canonicalPath)) {
                        zipInput.closeEntry()
                        entry = zipInput.nextEntry
                        continue
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { output -> zipInput.copyTo(output) }
                    }
                    zipInput.closeEntry()
                    entry = zipInput.nextEntry
                }
            }

            // 扫描解压后的目录结构，找到 taskType 子目录
            val taskTypeDir = findTaskTypeDir(tempDir, taskType)
                ?: return ImportResult(false, null, 0, context.getString(R.string.import_remote_structure_mismatch, taskType.assetDir))

            // 校验目录结构
            val packageNames: List<String>
            try {
                packageNames = validateAndListPackages(taskTypeDir, context, taskType)
            } catch (e: InvalidStructureException) {
                return ImportResult(false, null, 0, context.getString(R.string.import_remote_structure_invalid, e.message))
            }

            // 报告开始
            progressCallback?.onStart(packageNames.size)

            val remoteRoot = File(context.filesDir, "$REMOTE_IMPORT_ROOT/${taskType.assetDir}/$FORMAT_DIR")

            // taskTypeDir 是 {taskType} 目录，需要进入 tflite 子目录
            val tfliteDir = File(taskTypeDir, FORMAT_DIR)

            val subDirs = tfliteDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
            if (subDirs.isNotEmpty()) {
                var totalModels = 0
                val importedPackages = mutableListOf<String>()  // 追踪所有成功的包名
                subDirs.forEachIndexed { index, subDir ->
                    val pkgName = subDir.name
                    progressCallback?.onPackageStart(index + 1, subDirs.size, pkgName)
                    val targetDir = File(remoteRoot, pkgName)
                    val result = tryImportExtractedPackage(subDir, remoteRoot, taskType)
                    if (result != null && result.success) {
                        totalModels += result.modelCount
                        importedPackages.add(pkgName)
                        importedDirs.add(targetDir)
                        progressCallback?.onPackageComplete(pkgName, result.modelCount, true)
                    } else {
                        progressCallback?.onPackageComplete(pkgName, 0, false)
                    }
                }
                if (totalModels > 0) {
                    return ImportResult(true, importedPackages.firstOrNull(), totalModels, packageNames = importedPackages.toList())
                }
            }

            // 情况2：tflite 目录本身就是模型包内容
            val singlePkgName = tfliteDir.name
            progressCallback?.onPackageStart(1, 1, singlePkgName)
            val result = tryImportExtractedPackage(tfliteDir, remoteRoot, taskType)
            if (result != null && result.success) {
                progressCallback?.onPackageComplete(singlePkgName, result.modelCount, true)
                return ImportResult(true, singlePkgName, result.modelCount, packageNames = listOf(singlePkgName))
            }
            progressCallback?.onPackageComplete(singlePkgName, 0, false)

            return ImportResult(false, null, 0, context.getString(R.string.import_remote_no_valid))
        } catch (e: InvalidStructureException) {
            return ImportResult(false, null, 0, context.getString(R.string.import_remote_structure_invalid, e.message))
        } catch (e: Exception) {
            // 出错时清理已导入的包目录
            importedDirs.forEach { it.deleteRecursively() }
            return ImportResult(false, null, 0, context.getString(R.string.import_remote_failed, e.message))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /** 扫描远程下载的模型 */
    fun scanDownloadedModels(context: Context, taskType: TaskType = TaskType.DETECTION): List<ModelDescriptor> {
        val results = mutableListOf<ModelDescriptor>()
        val remoteRoot = File(context.filesDir, "$REMOTE_IMPORT_ROOT/${taskType.assetDir}/$FORMAT_DIR")
        if (!remoteRoot.exists()) return results

        val format = ModelFormat.TFLITE
        for (packageDir in remoteRoot.listFiles()?.filter { it.isDirectory } ?: emptyList()) {
            val packageName = packageDir.name
            val modelsDir = File(packageDir, "models")
            if (!modelsDir.exists()) continue

            for (sizeDir in modelsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()) {
                val size = ModelSize.entries.find { it.folder == sizeDir.name } ?: continue
                for (file in sizeDir.listFiles()?.filter { it.isFile && it.name.endsWith(".tflite") } ?: emptyList()) {
                    results.add(
                        ModelDescriptor(
                            format = format,
                            packageName = packageName,
                            size = size,
                            assetPath = "",
                            fileName = file.name,
                            isLocal = true,
                            isRemote = true,
                            absolutePath = file.absolutePath,
                            fileSize = file.length(),
                            taskType = taskType
                        )
                    )
                }
            }
        }
        return results
    }

    /** 获取远程下载的模型包列表 */
    fun getDownloadedPackages(context: Context, taskType: TaskType = TaskType.DETECTION): List<String> {
        val remoteRoot = File(context.filesDir, "$REMOTE_IMPORT_ROOT/${taskType.assetDir}/$FORMAT_DIR")
        if (!remoteRoot.exists()) return emptyList()
        return remoteRoot.listFiles()
            ?.filter { it.isDirectory && File(it, "models").exists() }
            ?.map { it.name }
            ?: emptyList()
    }

    /** 获取远程下载模型的 label.txt 路径 */
    fun getDownloadedLabelPath(context: Context, packageName: String, taskType: TaskType = TaskType.DETECTION): String? {
        val labelFile = File(context.filesDir, "$REMOTE_IMPORT_ROOT/${taskType.assetDir}/$FORMAT_DIR/$packageName/label.txt")
        return if (labelFile.exists()) labelFile.absolutePath else null
    }

    /** 检查远程目录中是否已存在同名包 */
    fun downloadedPackageExists(context: Context, packageName: String, taskType: TaskType = TaskType.DETECTION): Boolean {
        val pkgDir = File(context.filesDir, "$REMOTE_IMPORT_ROOT/${taskType.assetDir}/$FORMAT_DIR/$packageName")
        return pkgDir.exists() && File(pkgDir, "models").exists()
    }

    /** 删除远程下载的模型（批量） */
    fun deleteDownloadedModels(context: Context, packageNames: List<String>, taskType: TaskType = TaskType.DETECTION): Int {
        val remoteRoot = File(context.filesDir, "$REMOTE_IMPORT_ROOT/${taskType.assetDir}/$FORMAT_DIR")
        if (!remoteRoot.exists()) return 0
        var count = 0
        for (packageName in packageNames) {
            val pkgDir = File(remoteRoot, packageName)
            if (pkgDir.exists() && pkgDir.deleteRecursively()) count++
        }
        return count
    }

    /** 删除远程下载的模型（单个或全部） */
    fun deleteDownloadedModels(context: Context, packageName: String? = null, taskType: TaskType = TaskType.DETECTION): Int {
        val remoteRoot = File(context.filesDir, "$REMOTE_IMPORT_ROOT/${taskType.assetDir}/$FORMAT_DIR")
        if (!remoteRoot.exists()) return 0
        return if (packageName != null) {
            val pkgDir = File(remoteRoot, packageName)
            if (pkgDir.exists() && pkgDir.deleteRecursively()) 1 else 0
        } else {
            var count = 0
            remoteRoot.listFiles()?.filter { it.isDirectory }?.forEach {
                if (it.deleteRecursively()) count++
            }
            count
        }
    }
}
