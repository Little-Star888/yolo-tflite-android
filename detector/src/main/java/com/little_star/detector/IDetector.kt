package com.little_star.detector

import android.graphics.Bitmap
import android.view.Surface
import com.little_star.detector.model.AcceleratorMode
import com.little_star.detector.model.DetectionResult
import com.little_star.detector.model.ModelConfig

/**
 * 统一检测器接口
 * 定义所有检测器必须实现的基本功能
 */
interface IDetector {
    /** 是否已初始化 */
    val isInitialized: Boolean

    /** 上一次推理耗时（毫秒） */
    val lastInferenceTimeMs: Double

    /** 初始化时是否实际使用了 AOT 预编译模型 */
    val usedAotModel: Boolean

    /** 实际使用的加速器类型（通过 logcat 日志解析，可能与用户选择的不同） */
    val actualAccelerator: AcceleratorMode

    /** 是否命中了 JIT 编译缓存（true = 命中缓存，false = 新编译或 AOT） */
    val jitCacheHit: Boolean

    /**
     * 初始化检测器（从 assets 加载）
     * @param assetPath 模型在 assets 中的完整路径
     * @param modelConfig 模型运行时配置
     * @param acceleratorMode 加速器模式（CPU/GPU/NPU）
     */
    fun initialize(assetPath: String, modelConfig: ModelConfig, acceleratorMode: AcceleratorMode)

    /**
     * 初始化检测器（从本地文件路径加载）
     * @param absolutePath 模型文件的绝对路径
     * @param modelConfig 模型运行时配置
     * @param acceleratorMode 加速器模式（CPU/GPU/NPU）
     */
    fun initializeFromPath(absolutePath: String, modelConfig: ModelConfig, acceleratorMode: AcceleratorMode)

    /**
     * 释放检测器资源
     */
    fun release()

    /**
     * 图片检测
     * @param bitmap 待检测的图片
     * @param confThreshold 置信度阈值
     * @return 检测结果列表
     */
    fun detect(bitmap: Bitmap, confThreshold: Float): List<DetectionResult>

    /**
     * 使用已预处理的 float buffer 进行检测（GL 管线使用）
     * 跳过 Bitmap lock + NEON 预处理，直接接收预处理后的 float 数组
     *
     * @param preprocessedData 预处理后的 float 数组 (inputSize * inputSize * 3, NHWC, [-1,1])
     * @param inputSize 模型输入尺寸
     * @param imgWidth 原始图像宽度
     * @param imgHeight 原始图像高度
     * @param letterboxScale letterbox 缩放比例
     * @param letterboxOffsetX letterbox X 偏移
     * @param letterboxOffsetY letterbox Y 偏移
     * @param confThreshold 置信度阈值
     * @return 检测结果列表
     */
    fun detectFromBuffer(
        preprocessedData: FloatArray,
        inputSize: Int,
        imgWidth: Int,
        imgHeight: Int,
        letterboxScale: Float,
        letterboxOffsetX: Float,
        letterboxOffsetY: Float,
        confThreshold: Float
    ): List<DetectionResult> {
        throw UnsupportedOperationException("detectFromBuffer not implemented")
    }

    /**
     * GL零拷贝检测 — 直接从GL纹理推理，数据不离GPU
     *
     * BLOB AHWB 零拷贝检测 — compute shader 写入 BLOB AHWB，NPU 直接读取
     *
     * 适用于 MTK APU / Google Tensor TPU 平台。
     * GL compute shader 将 FBO RGBA32F 转为 float32 BHWC 写入 BLOB AHWB-backed SSBO，
     * NPU 通过 CreateFromAhwb 直接导入，无需 CPU 中转。
     *
     * @param hardwareBuffer BLOB 格式 AHardwareBuffer（compute shader 已写入 float32 数据）
     * @param inputSize 模型输入尺寸
     * @param imgWidth 原始图像宽度
     * @param imgHeight 原始图像高度
     * @param letterboxScale letterbox 缩放比例
     * @param letterboxOffsetX letterbox X 偏移
     * @param letterboxOffsetY letterbox Y 偏移
     * @param rotationDegrees 相机旋转角度
     * @param confThreshold 置信度阈值
     * @return 检测结果列表
     */
    fun detectFromAhwb(
        hardwareBuffer: android.hardware.HardwareBuffer,
        inputSize: Int,
        imgWidth: Int,
        imgHeight: Int,
        letterboxScale: Float,
        letterboxOffsetX: Float,
        letterboxOffsetY: Float,
        rotationDegrees: Int,
        confThreshold: Float
    ): List<DetectionResult> {
        throw UnsupportedOperationException("detectFromAhwb not implemented for this backend")
    }

    /**
     * 打开相机（可选实现）
     * @param facing 相机朝向（CameraMetadata.LENS_FACING_FRONT/BACK）
     * @return 是否成功打开
     */
    fun openCamera(facing: Int): Boolean = false

    /**
     * 关闭相机（可选实现）
     * @return 是否成功关闭
     */
    fun closeCamera(): Boolean = false

    /**
     * 设置输出窗口（可选实现）
     * @param surface 输出Surface
     * @return 是否成功设置
     */
    fun setOutputWindow(surface: Surface): Boolean = false
}
