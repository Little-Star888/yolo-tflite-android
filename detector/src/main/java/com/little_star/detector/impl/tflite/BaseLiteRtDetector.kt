package com.little_star.detector.impl.tflite

import android.content.Context
import android.system.Os
import com.little_star.detector.IDetector
import com.little_star.detector.model.AcceleratorMode
import com.little_star.detector.model.ModelConfig
import com.little_star.detector.util.LogCapture
import com.little_star.detector.util.SoCUtils

/**
 * LiteRt 检测器基类
 * 封装 NPU 环境设置、AOT 模型选择、加速器检测等公共逻辑
 */
abstract class BaseLiteRtDetector(protected val context: Context) : IDetector {

    protected var modelConfig: ModelConfig? = null

    private var _isInitialized = false
    override val isInitialized: Boolean get() = _isInitialized

    override var lastInferenceTimeMs: Double = 0.0
        protected set

    /** 初始化时是否实际使用了 AOT 预编译模型 */
    protected var _usedAotModel = false
    override val usedAotModel: Boolean get() = _usedAotModel

    /** 实际使用的加速器类型（通过 logcat 日志解析） */
    override var actualAccelerator: AcceleratorMode = AcceleratorMode.CPU
        protected set

    /** 是否命中了 JIT 编译缓存（true = 命中缓存，false = 新编译或 AOT） */
    override var jitCacheHit: Boolean = false
        protected set

    /** 静默回退原因 */
    protected var fallbackReason: LogCapture.FallbackReason = LogCapture.FallbackReason.UNKNOWN
        private set

    /** 用户请求的加速器模式（用于检测 NPU→CPU 回退） */
    private var _requestedAccelerator: AcceleratorMode = AcceleratorMode.CPU
        private set

    /** 标记已初始化 */
    protected fun markInitialized() {
        _isInitialized = true
    }

    /** 标记未初始化 */
    protected fun markReleased() {
        _isInitialized = false
    }

    /**
     * 设置 ADSP_LIBRARY_PATH 环境变量
     * 让高通 FastRPC 框架能从 App 的 native lib 目录加载 DSP 侧库
     */
    protected fun setupAdspLibraryPath() {
        try {
            val nativeLibPath = context.applicationInfo.nativeLibraryDir
            Os.setenv(
                "ADSP_LIBRARY_PATH",
                "$nativeLibPath;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/vendor/dsp",
                true
            )
            android.util.Log.i(TAG, "ADSP_LIBRARY_PATH set to: $nativeLibPath")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to set ADSP_LIBRARY_PATH", e)
        }
    }

    /**
     * 根据加速器模式和来源选择有效的模型路径
     * NPU 模式下优先查找 AOT 预编译模型，其他模式直接返回原始路径
     */
    protected fun selectEffectivePath(
        path: String,
        acceleratorMode: AcceleratorMode,
        isFromAssets: Boolean
    ): String {
        _usedAotModel = false
        _requestedAccelerator = acceleratorMode
        if (acceleratorMode != AcceleratorMode.NPU) return path
        val effective = if (isFromAssets) selectAotModelPath(path) else selectAotModelPathFromFile(path)
        // 路径不同说明命中了 AOT 预编译模型
        if (effective != path) _usedAotModel = true
        return effective
    }

    /**
     * 通过解析 logcat 日志检测 LiteRT 实际使用的加速器
     * 在 CompiledModel.create() 或 nativeInit() 成功后调用
     *
     * 同时检测 NPU 编译成功但存在 QNN 算子验证警告的情况，
     * 此时运行时推理会失败，直接抛异常让上层处理
     */
    protected fun detectActualAccelerator() {
        val captureResult = LogCapture.capture(timeoutMs = 2000)
        android.util.Log.d(TAG, "LogCapture returned: $captureResult")
        fallbackReason = captureResult.fallbackReason
        jitCacheHit = captureResult.jitCacheHit
        actualAccelerator = when (captureResult.delegateType) {
            LogCapture.DelegateType.CPU -> AcceleratorMode.CPU
            LogCapture.DelegateType.GPU -> AcceleratorMode.GPU
            LogCapture.DelegateType.NPU -> AcceleratorMode.NPU
            LogCapture.DelegateType.UNKNOWN -> AcceleratorMode.CPU
        }
        android.util.Log.d(TAG, "detectActualAccelerator() = $actualAccelerator, reason: $fallbackReason, jitCacheHit: $jitCacheHit, requested: $_requestedAccelerator")

        // NPU 编译成功但存在 QNN 算子验证警告，预示运行时推理会失败
        // QNN 编译器可能将不支持的算子纳入 partition，导致编译"成功"但执行失败
        if (actualAccelerator == AcceleratorMode.NPU &&
            fallbackReason == LogCapture.FallbackReason.QNN_VALIDATION_WARNING) {
            throw RuntimeException(
                "QNN JIT 编译存在不支持的算子，NPU 运行时推理将失败: QNN op validation error"
            )
        }

        // 用户选择 NPU 但实际回退到了 CPU（LiteRT 静默回退）
        // 此时应提示用户手动切换加速器，而不是静默失败
        if (_requestedAccelerator == AcceleratorMode.NPU &&
            actualAccelerator == AcceleratorMode.CPU) {
            throw RuntimeException(
                "QNN JIT 编译存在不支持的算子，NPU 运行时推理将失败: QNN op validation error"
            )
        }
    }

    /**
     * 根据 SoC 厂商和型号构建 AOT 文件名
     * @return AOT 文件名，如果 SoC 未知或厂商不支持则返回 null
     */
    private fun buildAotFileName(baseName: String): String? {
        val socModel = SoCUtils.getSoCModel()
        if (socModel == "unknown") {
            android.util.Log.w(TAG, "无法检测 SoC 型号，使用原始模型（将触发 JIT 编译）")
            return null
        }
        return when (SoCUtils.getVendor()) {
            "qualcomm" -> "${baseName}_Qualcomm_$socModel.tflite"
            "mediatek" -> "${baseName}_MediaTek.tflite"
            else -> {
                android.util.Log.i(TAG, "未知 SoC 厂商，使用原始模型（SoC: $socModel）")
                null
            }
        }
    }

    /**
     * 根据 SoC 型号选择 AOT 预编译模型（从 assets）
     */
    private fun selectAotModelPath(originalPath: String): String {
        try {
            val lastSlash = originalPath.lastIndexOf('/')
            val dir = if (lastSlash >= 0) originalPath.substring(0, lastSlash) else ""
            val fileName = if (lastSlash >= 0) originalPath.substring(lastSlash + 1) else originalPath
            val aotFileName = buildAotFileName(fileName.removeSuffix(".tflite")) ?: return originalPath
            val aotDir = if (dir.isNotEmpty()) "$dir/aot" else "aot"
            val aotPath = "$aotDir/$aotFileName"

            val aotDirFiles = context.assets.list(aotDir)
            if (aotDirFiles != null && aotFileName in aotDirFiles) {
                android.util.Log.i(TAG, "找到 AOT 预编译模型: $aotPath")
                return aotPath
            }
            android.util.Log.i(TAG, "未找到 AOT 模型 $aotPath，使用原始模型")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "AOT 模型选择失败，回退到原始模型", e)
        }
        return originalPath
    }

    /**
     * 根据 SoC 型号选择 AOT 预编译模型（从本地文件系统）
     */
    private fun selectAotModelPathFromFile(originalPath: String): String {
        try {
            val originalFile = java.io.File(originalPath)
            val aotFileName = buildAotFileName(originalFile.name.removeSuffix(".tflite")) ?: return originalPath
            val aotDir = java.io.File(originalFile.parentFile, "aot")
            if (!aotDir.exists() || !aotDir.isDirectory) return originalPath

            val aotFile = java.io.File(aotDir, aotFileName)
            if (aotFile.exists()) {
                android.util.Log.i(TAG, "找到本地 AOT 预编译模型: ${aotFile.absolutePath}")
                return aotFile.absolutePath
            }
            android.util.Log.i(TAG, "未找到本地 AOT 模型 ${aotFile.absolutePath}，使用原始模型")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "本地 AOT 模型选择失败，回退到原始模型", e)
        }
        return originalPath
    }

    companion object {
        protected const val TAG = "LiteRtDetector"
    }
}
