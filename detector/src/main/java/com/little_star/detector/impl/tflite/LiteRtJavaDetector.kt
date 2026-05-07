package com.little_star.detector.impl.tflite

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.BuiltinNpuAcceleratorProvider
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import com.little_star.detector.model.ModelConfig
import com.little_star.detector.factory.PostprocessorFactory
import com.little_star.detector.factory.PreprocessorFactory
import com.little_star.detector.postprocess.IPostprocessor
import com.little_star.detector.preprocess.IPreprocessor
import com.little_star.detector.util.LetterboxTransform
import com.little_star.detector.model.AcceleratorMode
import com.little_star.detector.model.DetectionResult
import com.little_star.detector.util.LogCapture

/**
 * TFLite 目标检测器实现（Java API）
 *
 * 支持多种加速模式：CPU / GPU / NPU（Qualcomm Hexagon）
 * 支持多种推理类型：END2END / TRADITIONAL
 * 支持多种任务类型：DETECTION / SEGMENTATION / KEYPOINT / CLASSIFICATION / ORIENTED_BBOX
 */
class LiteRtJavaDetector(context: Context) : BaseLiteRtDetector(context) {

    companion object {
        /** 共享的 Environment 实例，保持 GPU/NPU 上下文活跃 */
        @Volatile
        private var sharedEnv: Environment? = null
        @Volatile
        private var sharedEnvMode: AcceleratorMode? = null

        /** 获取或创建共享的 Environment，模式变化时会重建 */
        @Synchronized
        private fun getOrCreateEnv(ctx: Context, mode: AcceleratorMode): Environment? {
            if (mode == AcceleratorMode.CPU) {
                if (sharedEnv != null) {
                    try { sharedEnv?.close() } catch (_: Exception) {}
                    sharedEnv = null
                    sharedEnvMode = null
                }
                return null
            }
            if (sharedEnv != null && sharedEnvMode != mode) {
                try { sharedEnv?.close() } catch (_: Exception) {}
                sharedEnv = null
            }
            if (sharedEnv == null) {
                sharedEnv = if (mode == AcceleratorMode.NPU) {
                    Environment.create(BuiltinNpuAcceleratorProvider(ctx))
                } else {
                    Environment.create()
                }
                sharedEnvMode = mode
            }
            return sharedEnv
        }

        /** 释放共享的 Environment */
        @Synchronized
        fun releaseSharedEnv() {
            try { sharedEnv?.close() } catch (_: Exception) {}
            sharedEnv = null
            sharedEnvMode = null
        }
    }

    private var compiledModel: CompiledModel? = null
    private var env: Environment? = null
    private var inputBuffers: List<TensorBuffer>? = null
    private var outputBuffers: List<TensorBuffer>? = null
    private lateinit var preprocessor: IPreprocessor
    private lateinit var postprocessor: IPostprocessor

    override fun initialize(assetPath: String, modelConfig: ModelConfig, acceleratorMode: AcceleratorMode) {
        initModel(assetPath, modelConfig, acceleratorMode, isFromAssets = true) { effectivePath, options, env ->
            CompiledModel.create(context.assets, effectivePath, options, env)
        }
    }

    override fun initializeFromPath(absolutePath: String, modelConfig: ModelConfig, acceleratorMode: AcceleratorMode) {
        initModel(absolutePath, modelConfig, acceleratorMode, isFromAssets = false) { effectivePath, options, env ->
            CompiledModel.create(effectivePath, options, env)
        }
    }

    /**
     * 公共初始化逻辑
     * @param modelCreator 模型创建回调，接收 effectivePath + options + env
     */
    private fun initModel(
        path: String,
        modelConfig: ModelConfig,
        acceleratorMode: AcceleratorMode,
        isFromAssets: Boolean,
        modelCreator: (String, CompiledModel.Options, Environment?) -> CompiledModel
    ) {
        this.modelConfig = modelConfig
        try {
            if (acceleratorMode == AcceleratorMode.NPU) {
                setupAdspLibraryPath()
            }

            env = getOrCreateEnv(context, acceleratorMode)

            val options = buildOptions(acceleratorMode)

            val needLogCapture = acceleratorMode != AcceleratorMode.CPU
            if (needLogCapture) {
                LogCapture.clearBuffer()
            }

            // NPU 模式下优先查找 AOT 预编译模型
            val effectivePath = selectEffectivePath(path, acceleratorMode, isFromAssets)
            compiledModel = modelCreator(effectivePath, options, env)

            inputBuffers = compiledModel!!.createInputBuffers()
            outputBuffers = compiledModel!!.createOutputBuffers()

            preprocessor = PreprocessorFactory.create(modelConfig.taskType)
            postprocessor = PostprocessorFactory.createJava(modelConfig)

            if (needLogCapture) {
                detectActualAccelerator()
            } else {
                actualAccelerator = AcceleratorMode.CPU
            }
            android.util.Log.d(TAG, "actualAccelerator set to: $actualAccelerator")
            markInitialized()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "初始化检测器失败", e)
            closeBuffers()
            close()
            env = null
            markReleased()
            throw e
        }
    }

    override fun release() {
        closeBuffers()
        close()
        env = null
        markReleased()
    }

    override fun detect(bitmap: Bitmap, confThreshold: Float): List<DetectionResult> {
        val model = compiledModel
            ?: throw IllegalStateException("检测器未初始化，请先调用 initialize()")
        val inputs = inputBuffers
            ?: throw IllegalStateException("输入缓冲区未初始化")
        val outputs = outputBuffers
            ?: throw IllegalStateException("输出缓冲区未初始化")
        val config = modelConfig ?: throw IllegalStateException("模型配置未设置")

        val t0 = System.nanoTime()

        // 1. 预处理
        val inputData = preprocessor.preprocess(bitmap, config)
        val t1 = System.nanoTime()

        inputs[0].writeFloat(inputData)
        val t2 = System.nanoTime()

        // 2. 模型推理
        model.run(inputs, outputs)
        val t3 = System.nanoTime()

        // 3. 读取输出
        val outputList = outputs.map { it.readFloat() }
        val t4 = System.nanoTime()

        lastInferenceTimeMs = (t4 - t0) / 1_000_000.0

        // 4. 后处理
        val letterboxTransform = preprocessor.getLetterboxTransform()
        val results = postprocessor.postprocess(
            outputs = outputList,
            imgW = bitmap.width,
            imgH = bitmap.height,
            confThreshold = confThreshold,
            config = config,
            letterboxTransform = letterboxTransform
        )
        val t5 = System.nanoTime()

        val preprocessMs = (t1 - t0) / 1_000_000.0
        val writeMs = (t2 - t1) / 1_000_000.0
        val inferenceMs = (t3 - t2) / 1_000_000.0
        val readMs = (t4 - t3) / 1_000_000.0
        val postprocessMs = (t5 - t4) / 1_000_000.0
        val totalMs = (t5 - t0) / 1_000_000.0
        Log.i(TAG, String.format("perf | preprocess=%.1f write=%.1f inference=%.1f read=%.1f postprocess=%.1f | total=%.1f",
            preprocessMs, writeMs, inferenceMs, readMs, postprocessMs, totalMs))

        return results
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
        val model = compiledModel
            ?: throw IllegalStateException("检测器未初始化，请先调用 initialize()")
        val inputs = inputBuffers
            ?: throw IllegalStateException("输入缓冲区未初始化")
        val outputs = outputBuffers
            ?: throw IllegalStateException("输出缓冲区未初始化")
        val config = modelConfig ?: throw IllegalStateException("模型配置未设置")

        val t0 = System.nanoTime()

        inputs[0].writeFloat(preprocessedData)
        val t1 = System.nanoTime()

        model.run(inputs, outputs)
        val t2 = System.nanoTime()

        val outputList = outputs.map { it.readFloat() }
        val t3 = System.nanoTime()

        lastInferenceTimeMs = (t3 - t0) / 1_000_000.0

        // 从 GL 管线提供的参数重建 LetterboxTransform
        val letterboxTransform = LetterboxTransform(
            imgW = imgWidth,
            imgH = imgHeight,
            scale = letterboxScale,
            newW = (imgWidth * letterboxScale).toInt(),
            newH = (imgHeight * letterboxScale).toInt(),
            offsetX = letterboxOffsetX,
            offsetY = letterboxOffsetY
        )
        val results = postprocessor.postprocess(
            outputs = outputList,
            imgW = imgWidth,
            imgH = imgHeight,
            confThreshold = confThreshold,
            config = config,
            letterboxTransform = letterboxTransform
        )
        val t4 = System.nanoTime()

        val writeMs = (t1 - t0) / 1_000_000.0
        val inferenceMs = (t2 - t1) / 1_000_000.0
        val readMs = (t3 - t2) / 1_000_000.0
        val postprocessMs = (t4 - t3) / 1_000_000.0
        val totalMs = (t4 - t0) / 1_000_000.0
        Log.i(TAG, String.format("perf(buf) | write=%.1f inference=%.1f read=%.1f postprocess=%.1f | total=%.1f",
            writeMs, inferenceMs, readMs, postprocessMs, totalMs))

        return results
    }

    /** 根据加速器模式创建 CompiledModel.Options */
    private fun buildOptions(acceleratorMode: AcceleratorMode): CompiledModel.Options = when (acceleratorMode) {
        AcceleratorMode.NPU -> CompiledModel.Options(Accelerator.NPU).apply {
            qualcommOptions = CompiledModel.QualcommOptions(
                htpPerformanceMode = CompiledModel.QualcommOptions.HtpPerformanceMode.BURST
            )
        }
        AcceleratorMode.GPU -> CompiledModel.Options(Accelerator.GPU)
        else -> CompiledModel.Options(Accelerator.CPU)
    }

    /** 关闭并释放模型资源 */
    private fun close() {
        compiledModel?.close()
        compiledModel = null
    }

    /** 关闭输入输出缓冲区 */
    private fun closeBuffers() {
        try {
            inputBuffers?.forEach { it.close() }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "关闭输入缓冲区时出错", e)
        }
        inputBuffers = null

        try {
            outputBuffers?.forEach { it.close() }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "关闭输出缓冲区时出错", e)
        }
        outputBuffers = null
    }
}
