package com.little_star.detector.impl.tflite

import android.content.Context
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import com.little_star.detector.model.ModelConfig
import com.little_star.detector.factory.PostprocessorFactory
import com.little_star.detector.postprocess.INativeResultParser
import com.little_star.detector.model.AcceleratorMode
import com.little_star.detector.model.DetectionResult
import com.little_star.detector.model.InferenceType
import com.little_star.detector.model.KeypointModelConfig
import com.little_star.detector.util.LogCapture

/**
 * TFLite 目标检测器实现（Native JNI）
 * 通过 C++ JNI 调用 LiteRT C API，后处理在 native 层完成
 */
class LiteRtNativeDetector(context: Context) : BaseLiteRtDetector(context) {

    companion object {
        private const val NATIVE_TAG = "LiteRtNativeDetector"

        init {
            System.loadLibrary("litert_jni")
        }

        /** AHWB 互操作检测（用于 PipelinePrerequisites，MTK/Tensor 平台零拷贝） */
        @JvmStatic
        private external fun nativeSupportsAhwbGlInterop(): Boolean

        /** 查询 AHWB 互操作能力（可在无实例时调用） */
        @JvmStatic
        fun isAhwbGlInteropSupported(): Boolean {
            return try {
                nativeSupportsAhwbGlInterop()
            } catch (e: Exception) {
                false
            }
        }

        /**
         * 创建 BLOB AHardwareBuffer 并映射为 GL SSBO（MTK/Tensor 零拷贝）
         * 在 GL 线程上调用（onSurfaceCreated）
         * @return Object[]{HardwareBuffer, Integer(ssboId)}，失败返回 null
         */
        @JvmStatic
        external fun nativeCreateBlobAhwbSsbo(inputSize: Int): Array<Any?>?

        /**
         * 执行 compute shader：FBO 纹理 → BLOB SSBO（BHWC float32）
         * 在 GL 线程上调用（onDrawFrame）
         */
        @JvmStatic
        external fun nativeRunComputeToSsbo(ssboId: Int, fboTexId: Int, inputSize: Int)

        /**
         * 销毁 BLOB AHWB + SSBO 资源
         * 需在 GL context 有效的线程上调用
         */
        @JvmStatic
        external fun nativeDestroyBlobSsbo()

        /**
         * 注入当前 GL 线程的 EGL 上下文到 native 层
         * 在 GL 线程的 onSurfaceCreated 中调用
         * 创建 EGL-aware Environment，用于 MTK/Tensor 零拷贝路径的
         * CreateFromAhwb 和 CreateFromEglSyncFence
         * @return 是否成功注入
         */
        @JvmStatic
        external fun nativeInjectEglContext(): Boolean
    }

    init {
        // System.loadLibrary 在 companion object init 中已加载，此处不再重复
    }

    /** Native JNI 结果解析器 */
    private lateinit var resultParser: INativeResultParser

    private external fun nativeInit(
        modelPath: String,
        inputSize: Int,
        numClasses: Int,
        classNames: Array<String>,
        taskType: Int,
        inferenceType: Int,
        acceleratorMode: Int,
        isFromAssets: Boolean,
        assetManager: Any,
        npuLibDir: String,
        npuCacheDir: String,
        numKeypoints: Int
    ): Boolean

    private external fun nativeRelease()

    private external fun nativeDetect(bitmap: Bitmap, confThreshold: Float): FloatArray

    private external fun nativeDetectFromBuffer(
        floatArray: FloatArray,
        inputSize: Int,
        imgWidth: Int,
        imgHeight: Int,
        letterboxScale: Float,
        letterboxOffsetX: Float,
        letterboxOffsetY: Float,
        confThreshold: Float
    ): FloatArray

    private external fun nativeGetLastInferenceMs(): Double

    /**
     * BLOB AHWB 零拷贝检测 — compute shader 写入 BLOB AHWB 后，NPU 直接读取
     * 适用于 MTK / Google Tensor NPU
     */
    private external fun nativeDetectFromAhwb(
        hardwareBuffer: HardwareBuffer,
        inputSize: Int,
        imgWidth: Int,
        imgHeight: Int,
        letterboxScale: Float,
        letterboxOffsetX: Float,
        letterboxOffsetY: Float,
        confThreshold: Float
    ): FloatArray?

    override fun initialize(assetPath: String, modelConfig: ModelConfig, acceleratorMode: AcceleratorMode) {
        doInit(assetPath, modelConfig, acceleratorMode, isFromAssets = true)
    }

    override fun initializeFromPath(absolutePath: String, modelConfig: ModelConfig, acceleratorMode: AcceleratorMode) {
        doInit(absolutePath, modelConfig, acceleratorMode, isFromAssets = false)
    }

    private fun doInit(
        path: String,
        modelConfig: ModelConfig,
        acceleratorMode: AcceleratorMode,
        isFromAssets: Boolean
    ) {
        this.modelConfig = modelConfig
        val taskType = modelConfig.taskType.ordinal
        val inferenceType = when (modelConfig.inferenceType) {
            InferenceType.END2END -> 0
            InferenceType.TRADITIONAL -> 1
        }
        val accelMode = when (acceleratorMode) {
            AcceleratorMode.CPU -> 0
            AcceleratorMode.GPU -> 1
            AcceleratorMode.NPU -> 2
        }

        val npuLibDir = if (acceleratorMode == AcceleratorMode.NPU) {
            setupAdspLibraryPath()
            context.applicationInfo.nativeLibraryDir
        } else ""

        // NPU 模式下优先查找 AOT 预编译模型
        val effectivePath = selectEffectivePath(path, acceleratorMode, isFromAssets)

        val numKeypoints = (modelConfig as? KeypointModelConfig)?.numKeypoints ?: 0

        // 非 CPU 模式下捕获日志以检测实际加速器
        val needLogCapture = acceleratorMode != AcceleratorMode.CPU
        if (needLogCapture) {
            LogCapture.clearBuffer()
        }

        val success = nativeInit(
            effectivePath,
            modelConfig.inputSize,
            modelConfig.numClasses,
            modelConfig.classNames,
            taskType,
            inferenceType,
            accelMode,
            isFromAssets,
            context.assets,
            npuLibDir,
            context.cacheDir.absolutePath,
            numKeypoints
        )

        if (!success) {
            // 初始化失败时，通过 logcat 日志分析获取具体错误原因
            // 将分析结果包含在异常消息中，使上层 buildLoadErrorHint() 能正确匹配关键词
            if (needLogCapture) {
                val captureResult = LogCapture.capture(timeoutMs = 1000)
                // 优先使用 fallbackReason（精确匹配），其次用 delegateType 兜底
                val detail = when {
                    captureResult.fallbackReason == LogCapture.FallbackReason.QNN_ERROR ->
                        "QNN context creation failed"
                    captureResult.fallbackReason == LogCapture.FallbackReason.COMPILE_ERROR ->
                        "Failed to compile model"
                    captureResult.delegateType == LogCapture.DelegateType.GPU ->
                        "not supported by GPU delegate"
                    captureResult.delegateType == LogCapture.DelegateType.NPU ->
                        "Failed to compile model with NPU dispatch"
                    else -> null
                }
                if (detail != null) {
                    throw RuntimeException("Failed to initialize LiteRtNativeDetector: $detail")
                }
            }
            throw RuntimeException("Failed to initialize LiteRtNativeDetector")
        }

        // 创建对应任务类型的 Native 结果解析器
        resultParser = PostprocessorFactory.createNative(modelConfig.taskType)

        // 检测实际加速器（检测 GPU/NPU 静默回退到 CPU 的情况）
        // detectActualAccelerator() 内部会检测 QNN 算子验证警告并抛异常
        if (needLogCapture) {
            detectActualAccelerator()
        } else {
            actualAccelerator = AcceleratorMode.CPU
        }

        markInitialized()
    }

    override fun release() {
        if (isInitialized) {
            nativeRelease()
            markReleased()
        }
    }

    override fun detect(bitmap: Bitmap, confThreshold: Float): List<DetectionResult> {
        if (!isInitialized) {
            throw IllegalStateException("Detector not initialized")
        }

        val floatArray = nativeDetect(bitmap, confThreshold)
        lastInferenceTimeMs = nativeGetLastInferenceMs()
        val config = modelConfig ?: return emptyList()
        if (floatArray.size < 3) return emptyList()
        return resultParser.parse(floatArray, config)
    }

    override fun detectFromBuffer(
        preprocessedData: FloatArray,
        inputSize: Int,
        imgWidth: Int,
        imgHeight: Int,
        letterboxScale: Float,
        letterboxOffsetX: Float,
        letterboxOffsetY: Float,
        confThreshold: Float
    ): List<DetectionResult> {
        if (!isInitialized) {
            throw IllegalStateException("Detector not initialized")
        }

        val floatArray = nativeDetectFromBuffer(
            preprocessedData, inputSize,
            imgWidth, imgHeight,
            letterboxScale, letterboxOffsetX, letterboxOffsetY,
            confThreshold
        )
        lastInferenceTimeMs = nativeGetLastInferenceMs()
        val config = modelConfig ?: return emptyList()
        if (floatArray.size < 3) return emptyList()
        return resultParser.parse(floatArray, config)
    }

    /**
     * BLOB AHWB 零拷贝检测 — compute shader 写入 BLOB AHWB 后，NPU 直接读取
     * 适用于 MTK / Google Tensor NPU
     */
    override fun detectFromAhwb(
        hardwareBuffer: HardwareBuffer,
        inputSize: Int,
        imgWidth: Int,
        imgHeight: Int,
        letterboxScale: Float,
        letterboxOffsetX: Float,
        letterboxOffsetY: Float,
        rotationDegrees: Int,
        confThreshold: Float
    ): List<DetectionResult> {
        if (!isInitialized) {
            throw IllegalStateException("Detector not initialized")
        }

        val floatArray = nativeDetectFromAhwb(
            hardwareBuffer,
            inputSize,
            imgWidth, imgHeight,
            letterboxScale, letterboxOffsetX, letterboxOffsetY,
            confThreshold
        )
        if (floatArray == null) {
            android.util.Log.w(NATIVE_TAG, "nativeDetectFromAhwb 返回null→AHWB零拷贝失败")
            return emptyList()
        }
        lastInferenceTimeMs = nativeGetLastInferenceMs()
        val config = modelConfig ?: return emptyList()
        if (floatArray.size < 3) return emptyList()
        return resultParser.parse(floatArray, config)
    }
}
