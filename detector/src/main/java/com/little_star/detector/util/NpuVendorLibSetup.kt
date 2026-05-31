package com.little_star.detector.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * NPU 厂商库运行时安装工具。
 *
 * 所有 NPU 相关 .so（QNN 运行时、LiteRT 厂商插件等）按厂商归档在
 * assets/npu/vendor/{qualcomm,mediatek}/ 中，避免被 LiteRT 自动发现导致跨厂商崩溃。
 *
 * 运行时根据 SoC 厂商，将匹配的 .so 复制到私有目录：
 * - 通用库（libQnnHtp.so 等）+ LiteRT 插件：全部复制
 * - Skel/Stub 库：只复制匹配当前 SoC 版本的（v69/v73/v75/v79/v81）
 *
 * 然后用 System.load() 预加载所有 .so 到进程，并通过 JNI
 * nativeSetNpuPluginDir() 设置 C++ 全局插件目录。
 */
object NpuVendorLibSetup {
    private const val TAG = "NpuVendorLibSetup"

    init {
        // 确保 litert_jni 已加载（LiteRtJavaDetector 路径可能未先加载 LiteRtNativeDetector）
        System.loadLibrary("litert_jni")
    }

    // 复制目标目录名
    private const val VENDOR_LIB_DIR = "npu_vendor_libs"

    // assets 中的厂商子目录
    private const val ASSET_DIR_QUALCOMM = "npu/vendor/qualcomm"
    private const val ASSET_DIR_MEDIATEK = "npu/vendor/mediatek"

    // Skel/Stub 版本子目录前缀
    private const val SKEL_STUB_DIR = "skel_stub"

    // 版本标记文件名（用于检测 assets 内容变化，需要重新复制）
    private const val VERSION_FILE = ".version"

    // 缓存已安装的厂商库目录路径
    @Volatile
    private var vendorLibDir: String? = null

    /**
     * 安装匹配当前设备的厂商 NPU 库，并设置 C++ 全局插件目录。
     *
     * 首次调用时会从 assets 复制文件到私有目录并预加载，后续调用直接跳过。
     * 应在首次 NPU 推理之前调用。
     *
     * @return 厂商库目录路径，设备不支持 NPU 时返回 null
     */
    fun setup(context: Context): String? {
        vendorLibDir?.let { return it }
        synchronized(this) {
            vendorLibDir?.let { return it }

            val vendor = SoCUtils.getVendor()
            if (vendor == "unknown") {
                Log.w(TAG, "未知 SoC 厂商，跳过 NPU 库安装")
                return null
            }

            val assetDir = when (vendor) {
                "qualcomm" -> ASSET_DIR_QUALCOMM
                "mediatek" -> ASSET_DIR_MEDIATEK
                else -> {
                    Log.w(TAG, "不支持的 SoC 厂商: $vendor")
                    return null
                }
            }

            val targetDir = File(context.filesDir, "$VENDOR_LIB_DIR/$vendor")

            // 检查是否需要重新安装
            if (needsReinstall(context, targetDir, assetDir)) {
                installLibs(context, targetDir, assetDir, vendor)
            }

            // 预加载所有 .so 到进程
            // Android 的 dlopen 不搜索 LD_LIBRARY_PATH，QNN dispatch 库内部
            // 通过 dlopen("libQnnSystem.so") 短名加载依赖，必须先预加载
            preloadLibs(targetDir)

            // 设置 C++ 全局 NPU 库目录（插件发现 + ADSP 路径统一使用此目录）
            val path = targetDir.absolutePath
            nativeSetNpuPluginDir(path)
            Log.i(TAG, "NPU 厂商库就绪: vendor=$vendor, dir=$path")

            vendorLibDir = path
            return path
        }
    }

    /**
     * 获取已安装的厂商库目录路径，未安装时返回 null。
     */
    fun getVendorLibDir(): String? = vendorLibDir

    /**
     * 预加载目录下所有 .so 文件到当前进程。
     * 使用 System.load(绝对路径)，因为这些 .so 不在 nativeLibraryDir 中。
     *
     * Skel .so 不需要预加载——它们被 FastRPC 通过 ADSP_LIBRARY_PATH 加载到 DSP 侧，
     * 不走 CPU 侧的 dlopen。
     *
     * 加载顺序按依赖链排列，一轮即可。
     */
    private fun preloadLibs(dir: File) {
        val soFiles = dir.listFiles { f ->
            f.name.endsWith(".so") && !f.name.contains("Skel")
        }?.sortedBy { loadOrder(it.name) }
            ?: return

        for (so in soFiles) {
            try {
                System.load(so.absolutePath)
                Log.d(TAG, "预加载: ${so.name}")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "预加载失败: ${so.name}", e)
            }
        }
    }

    /**
     * .so 加载优先级：按依赖链从底层到上层排列。
     * 数字越小越先加载。不在列表中的返回 50（默认中间位置）。
     */
    private fun loadOrder(name: String): Int = when {
        // QNN 基础库（最底层依赖）
        name.contains("QnnSystem") -> 0
        name.contains("QnnHtpPrepare") -> 1
        name.contains("QnnHtpV") && name.contains("Stub") -> 2
        name.contains("QnnHtp.") -> 3
        // LiteRT dispatch（依赖 QNN）
        name.contains("LiteRtDispatch") -> 4
        // LiteRT compiler plugin（依赖 dispatch）
        name.contains("LiteRtCompilerPlugin") -> 5
        // MediaTek 依赖链
        name.contains("neuron_buffer") -> 6
        name.contains("neuronusdk") -> 7
        else -> 50
    }

    /**
     * 检查是否需要重新安装。
     * 版本标记包含 SoC 型号+QNN 版本+assets 文件列表，任一变化都会触发重装。
     */
    private fun needsReinstall(context: Context, targetDir: File, assetDir: String): Boolean {
        if (!targetDir.exists()) return true

        val currentVersion = computeInstallVersion(context, assetDir)
        val versionFile = File(targetDir, VERSION_FILE)
        if (!versionFile.exists()) return true

        val installedVersion = versionFile.readText().trim()
        return installedVersion != currentVersion
    }

    /**
     * 计算安装版本标识。
     * 包含 SoC 型号、QNN 版本和 assets 根目录文件列表，
     * 确保 SoC 变化或 assets 内容变化时触发重装。
     */
    private fun computeInstallVersion(context: Context, assetDir: String): String {
        val rootFiles = context.assets.list(assetDir)?.sorted()?.joinToString(",") ?: ""
        val soc = SoCUtils.getSoCModel()
        val qnnVer = SoCUtils.qnnVersion ?: "none"
        return "$soc|$qnnVer|$rootFiles"
    }

    /**
     * 从 assets 复制文件到目标目录。
     * 高通平台只复制匹配 SoC 版本的 Skel/Stub，其余全部复制。
     */
    private fun installLibs(context: Context, targetDir: File, assetDir: String, vendor: String) {
        targetDir.mkdirs()

        // 删除旧文件（保留 .version）
        targetDir.listFiles()?.forEach { file ->
            if (file.name != VERSION_FILE) file.deleteRecursively()
        }

        // 复制根目录下的通用 .so
        val rootFiles = context.assets.list(assetDir) ?: return
        for (fileName in rootFiles) {
            // skel_stub 是目录，单独处理
            if (fileName == SKEL_STUB_DIR) continue
            copyAssetFile(context, "$assetDir/$fileName", File(targetDir, fileName))
        }

        // 复制匹配版本的 Skel/Stub（仅高通）
        if (vendor == "qualcomm") {
            val qnnVer = SoCUtils.qnnVersion
            if (qnnVer != null) {
                val skelDir = "$assetDir/$SKEL_STUB_DIR/$qnnVer"
                val skelFiles = context.assets.list(skelDir)
                if (skelFiles != null) {
                    for (fileName in skelFiles) {
                        copyAssetFile(context, "$skelDir/$fileName", File(targetDir, fileName))
                    }
                    Log.i(TAG, "已复制 QNN $qnnVer Skel/Stub (${skelFiles.size} 文件)")
                }
            } else {
                Log.w(TAG, "未知 QNN 版本，跳过 Skel/Stub 复制")
            }
        }

        // 写入版本标记
        val version = computeInstallVersion(context, assetDir)
        File(targetDir, VERSION_FILE).writeText(version)

        val totalFiles = targetDir.listFiles()?.count { it.name.endsWith(".so") } ?: 0
        Log.i(TAG, "NPU 厂商库安装完成: vendor=$vendor, files=$totalFiles")
    }

    private fun copyAssetFile(context: Context, assetPath: String, targetFile: File) {
        context.assets.open(assetPath).use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        }
        Log.d(TAG, "已安装: ${targetFile.name}")
    }

    /**
     * 重置安装状态，强制下次 setup 重新执行。
     * 主要用于测试。
     */
    fun reset() {
        synchronized(this) {
            vendorLibDir = null
        }
    }

    // JNI：设置 C++ 全局 NPU 库目录（插件发现 + ADSP 路径）
    private external fun nativeSetNpuPluginDir(dir: String)
}
