package com.little_star.pipeline

import android.content.Context
import android.hardware.HardwareBuffer
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.little_star.detector.IDetector
import com.little_star.detector.model.DetectionResult
import com.little_star.gl.CameraGlRenderer
import java.util.concurrent.Executor

/**
 * GL 产线实现
 * 使用 GLSurfaceView + CameraGlRenderer 进行 GPU 预处理
 * 同一帧画面用于显示和检测，不存在分辨率不一致的偏移问题
 *
 * @param inputSize 模型输入尺寸（如 640）
 * @param getDetector 获取当前检测器实例的 lambda
 * @param getConfThreshold 获取当前置信度阈值的 lambda
 * @param getIsDetecting 获取是否正在检测的 lambda
 * @param getCameraFacing 获取摄像头朝向的 lambda
 * @param centerCrop 分类任务使用 center-crop 预处理，其他任务使用 letterbox
 */
class GlCameraPipeline(
    private val inputSize: Int,
    private val getDetector: () -> IDetector?,
    private val getConfThreshold: () -> Float,
    private val getIsDetecting: () -> Boolean,
    private val getCameraFacing: () -> Int,
    private val centerCrop: Boolean = false,
    /** xselect 管线策略 */
    val strategy: PipelineStrategy = PipelineStrategy.GL_TRANSIT
) : CameraPipeline {

    companion object {
        private const val TAG = "GlCameraPipeline"
    }

    private var glRenderer: CameraGlRenderer? = null
    private var glSurfaceView: GLSurfaceView? = null
    private var callback: PipelineCallback? = null
    private var camera: androidx.camera.core.Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    // 传感器方向，用于 computeLetterbox 计算有效宽高
    private var sensorOrientation = 0

    private var frameSeq = 0  // 帧序号
    private var skipCount = 0  // isDetecting=false 跳过计数（节流日志用）
    private var ahwbFailCount = 0  // AHWB零拷贝失败计数（节流日志）

    override fun setupViews(ctx: Context, frameLayout: ViewGroup, overlayView: android.view.View) {
        val surfaceView = GLSurfaceView(ctx).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        Log.i(TAG, "═══════ GL管线启动 ═══════")
        Log.i(TAG, "  策略: ${strategy.name} (isZeroCopy=${strategy.isZeroCopy})")
        Log.i(TAG, "  inputSize: ${inputSize}x${inputSize}")
        Log.i(TAG, "  centerCrop: $centerCrop")

        // BLOB AHWB 零拷贝模式：MTK/Tensor NPU 专用
        val isBlobMode = (strategy == PipelineStrategy.GL_ZEROCOPY)
        val renderer = CameraGlRenderer(
            inputSize, centerCrop = centerCrop,
            useBlobAhwb = isBlobMode,
            callback = object : CameraGlRenderer.Callback {
                override fun onFramePreprocessed(
                    floatArray: FloatArray,
                    imgWidth: Int, imgHeight: Int,
                    letterboxScale: Float,
                    letterboxOffsetX: Float,
                    letterboxOffsetY: Float,
                    rotationDegrees: Int
                ) {
                    if (!getIsDetecting()) return

                    frameSeq++
                    val pathLabel = if (strategy.isZeroCopy) {
                        "CPU回退(零拷贝失败→glReadPixels+detectFromBuffer)"
                    } else {
                        "CPU路径(detectFromBuffer)"
                    }
                    if (frameSeq <= 3 || frameSeq % 100 == 0) {
                        Log.i(TAG, "[#$frameSeq] → $pathLabel")
                    }

                    val t0 = System.nanoTime()
                    val detections: List<DetectionResult> = try {
                        getDetector()?.detectFromBuffer(
                            floatArray, inputSize,
                            imgWidth, imgHeight,
                            letterboxScale, letterboxOffsetX, letterboxOffsetY,
                            getConfThreshold()
                        ) ?: emptyList()
                    } catch (e: Exception) {
                        Log.e(TAG, "[#$frameSeq] detectFromBuffer 异常: ${e.message}", e)
                        emptyList()
                    }
                    val t1 = System.nanoTime()
                    val detectMs = (t1 - t0) / 1_000_000.0
                    val fps = if (detectMs > 0) (1000.0 / detectMs).toLong() else 0L

                    if (frameSeq <= 3 || frameSeq % 100 == 0) {
                        Log.i(TAG, String.format("[#$frameSeq] %s 完成: detect=%.1fms fps=%d 检出数=%d",
                            pathLabel, detectMs, fps, detections.size))
                    }

                    callback?.onDetectionResult(
                        detections,
                        imgWidth.toFloat(), imgHeight.toFloat(),
                        0,
                        detectMs.toLong(), fps
                    )
                }

                override fun getRotationDegrees(): Int {
                    return sensorOrientation
                }

                override fun isFrontCamera(): Boolean {
                    return getCameraFacing() == 1
                }
            })

        // ─── BLOB AHWB 零拷贝回调（MTK/Tensor NPU） ───
        // compute shader 将 FBO RGBA32F 转为 float32 BHWC 写入 BLOB AHWB
        // NPU 通过 CreateFromAhwb 直接导入，无需 CPU 中转
        if (strategy.isZeroCopy && isBlobMode) {
            Log.i(TAG, "  启用BLOB AHWB零拷贝回调: strategy=$strategy")
            renderer.setAhwbZeroCopyCallback(object : CameraGlRenderer.AhwbZeroCopyCallback {
                override fun onFramePreprocessedAhwb(
                    hardwareBuffer: HardwareBuffer,
                    imgWidth: Int, imgHeight: Int,
                    letterboxScale: Float, letterboxOffsetX: Float, letterboxOffsetY: Float,
                    rotationDegrees: Int
                ): Boolean {
                    if (!getIsDetecting()) {
                        skipCount++
                        if (skipCount <= 2 || skipCount % 100 == 0) {
                            Log.d(TAG, "[#$frameSeq] AHWB零拷贝跳过: isDetecting=false " +
                                "(累计跳过=${skipCount}次)")
                        }
                        return false
                    }
                    skipCount = 0
                    val detector = getDetector()
                    if (detector == null) return false

                    val t0 = System.nanoTime()
                    var ahwbFailed = false
                    val detections: List<DetectionResult> = try {
                        detector.detectFromAhwb(
                            hardwareBuffer,
                            inputSize,
                            imgWidth, imgHeight,
                            letterboxScale, letterboxOffsetX, letterboxOffsetY,
                            rotationDegrees,
                            getConfThreshold()
                        )
                    } catch (e: UnsupportedOperationException) {
                        ahwbFailed = true
                        if (ahwbFailCount <= 2) {
                            Log.w(TAG, "[#$frameSeq] AHWB零拷贝不支持: ${e.message}")
                        }
                        emptyList()
                    } catch (e: Exception) {
                        ahwbFailed = true
                        Log.e(TAG, "[#$frameSeq] AHWB零拷贝异常: ${e.message}", e)
                        emptyList()
                    }
                    val t1 = System.nanoTime()
                    val totalMs = (t1 - t0) / 1_000_000.0

                    // AHWB 真正失败（异常）→ return false 触发 CPU 回退
                    if (ahwbFailed) {
                        ahwbFailCount++
                        return false
                    }

                    // AHWB 成功（包括空检测结果）→ return true 跳过 CPU 回退
                    ahwbFailCount = 0
                    val detectMs = detector.lastInferenceTimeMs
                    val fps = if (detectMs > 0) (1000.0 / detectMs).toLong() else 0L

                    callback?.onDetectionResult(
                        detections,
                        imgWidth.toFloat(), imgHeight.toFloat(),
                        0,
                        detectMs.toLong(), fps
                    )
                    return true
                }
            })
        } else {
            Log.i(TAG, "  零拷贝未启用 (strategy=${strategy.name})")
        }

        renderer.setGlSurfaceView(surfaceView)
        surfaceView.setRenderer(renderer)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

        glRenderer = renderer
        glSurfaceView = surfaceView

        frameLayout.addView(surfaceView)
        frameLayout.addView(overlayView)

        Log.i(TAG, "═══════ GL管线视图就绪 ═══════")
    }

    override fun bindCamera(
        ctx: Context,
        lifecycleOwner: LifecycleOwner,
        cameraFacing: Int,
        executor: Executor,
        callback: PipelineCallback,
        imageCapture: androidx.camera.core.ImageCapture?
    ) {
        this.callback = callback
        val renderer = glRenderer ?: run {
            Log.e(TAG, "bindCamera called but renderer is null")
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                this@GlCameraPipeline.cameraProvider = cameraProvider

                val preview = Preview.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                            .build()
                    )
                    .build()

                preview.setSurfaceProvider { request ->
                    if (!renderer.isReady()) {
                        // GL 线程尚未初始化，延迟重试
                        android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed({
                                try {
                                    if (renderer.isReady()) {
                                        val surface = renderer.createCameraSurface(
                                            request.resolution.width,
                                            request.resolution.height
                                        )
                                        request.provideSurface(surface, executor) {}
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Deferred surface creation failed: ${e.message}")
                                }
                            }, 100)
                        return@setSurfaceProvider
                    }
                    val surface = renderer.createCameraSurface(
                        request.resolution.width,
                        request.resolution.height
                    )
                    request.provideSurface(surface, executor) {}
                }

                val cameraSelector = if (cameraFacing == 0) {
                    CameraSelector.DEFAULT_BACK_CAMERA
                } else {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                }

                cameraProvider.unbindAll()
                val useCases: MutableList<UseCase> = mutableListOf(preview)
                imageCapture?.let { useCases.add(it) }
                val camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner, cameraSelector, *useCases.toTypedArray()
                )
                this@GlCameraPipeline.camera = camera
                sensorOrientation = camera.cameraInfo.sensorRotationDegrees
                callback.onSensorOrientationChanged(sensorOrientation)

                // 监听 CameraX 内部错误（偶发超时），自动重绑恢复（最多3次）
                var cameraRetryCount = 0
                camera.cameraInfo.cameraState.observe(lifecycleOwner) { state ->
                    val err = state.error ?: return@observe
                    if (cameraRetryCount >= 3) {
                        Log.w(TAG, "CameraX 错误重绑已达上限(3次), 放弃: code=${err.code}")
                        return@observe
                    }
                    cameraRetryCount++
                    Log.w(TAG, "CameraX 错误: code=${err.code} " +
                        "cause=${err.cause?.javaClass?.simpleName}, 重绑(${cameraRetryCount}/3)...")
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        try {
                            cameraProvider.unbindAll()
                            val retryCamera = cameraProvider.bindToLifecycle(
                                lifecycleOwner, cameraSelector, *useCases.toTypedArray()
                            )
                            this@GlCameraPipeline.camera = retryCamera
                            cameraRetryCount = 0
                            Log.i(TAG, "CameraX 重绑成功")
                        } catch (e: Exception) {
                            Log.e(TAG, "CameraX 重绑失败: ${e.message}", e)
                        }
                    }, 500)
                }

                Log.i(TAG, "GL相机绑定完成: facing=$cameraFacing sensorOrientation=$sensorOrientation" +
                    " strategy=${strategy.name}")
            } catch (e: Exception) {
                Log.e(TAG, "GL camera init failed: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    override fun unbindCamera() {
        cameraProvider?.unbindAll()
    }

    override fun release() {
        Log.i(TAG, "═══════ GL管线释放: strategy=${strategy.name} ═══════")
        // 不调 unbindAll()：新管线 bindCamera() 会统一调用，避免两次 unbindAll 冲突超时
        glRenderer?.release()
        glRenderer = null
        glSurfaceView = null
        callback = null
        camera = null
        cameraProvider = null
    }

    override fun setZoomRatio(ratio: Float) {
        camera?.cameraControl?.setZoomRatio(ratio)
    }

    override fun getMinZoomRatio(): Float {
        return camera?.cameraInfo?.zoomState?.value?.minZoomRatio ?: 1f
    }

    override fun getMaxZoomRatio(): Float {
        return camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f
    }

    override fun enableTorch(enabled: Boolean) {
        camera?.cameraControl?.enableTorch(enabled)
    }
}
