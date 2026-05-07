package com.little_star.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.little_star.detector.IDetector
import com.little_star.detector.model.BoundingBox
import com.little_star.detector.model.DetectionResult
import com.little_star.detector.model.Keypoint
import com.little_star.detector.model.KeypointDetection
import com.little_star.detector.model.ObjectDetection
import com.little_star.detector.model.OrientedBoxDetection
import com.little_star.detector.model.SegmentationResult
import java.util.concurrent.Executor

/**
 * CPU 产线实现
 * 使用 PreviewView + ImageAnalysis 进行 CPU 侧预处理
 * Preview 和 ImageAnalysis 使用不同分辨率，需要 scaleDetections 修正 overlay 偏移
 *
 * @param inputSize 模型输入尺寸（如 640）
 * @param getDetector 获取当前检测器实例的 lambda
 * @param getConfThreshold 获取当前置信度阈值的 lambda
 * @param getIsDetecting 获取是否正在检测的 lambda
 * @param getCameraFacing 获取摄像头朝向的 lambda
 */
class CpuCameraPipeline(
    private val inputSize: Int,
    private val getDetector: () -> IDetector?,
    private val getConfThreshold: () -> Float,
    private val getIsDetecting: () -> Boolean,
    private val getCameraFacing: () -> Int
) : CameraPipeline {

    companion object {
        private const val TAG = "CpuCameraPipeline"
    }

    private var callback: PipelineCallback? = null
    private var camera: androidx.camera.core.Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null

    // setupViews 中创建的 PreviewView 引用，bindCamera 时使用
    private var previewView: PreviewView? = null

    // Preview 有效分辨率，用于修正 overlay 坐标映射
    private val previewEffDims = IntArray(2)
    // Preview 原始分辨率（SurfaceRequest 捕获，旋转前）
    private val previewRawDims = IntArray(2)

    override fun setupViews(ctx: Context, frameLayout: ViewGroup, overlayView: android.view.View) {
        Log.i(TAG, "═══════ CPU管线启动 ═══════")
        Log.i(TAG, "  inputSize: ${inputSize}x${inputSize}")
        Log.i(TAG, "  注: CPU产线是最终降级路径，不使用GL预处理")

        val pv = PreviewView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        previewView = pv

        frameLayout.addView(pv)
        frameLayout.addView(overlayView)

        Log.i(TAG, "═══════ CPU管线视图就绪 ═══════")
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
        val pv = previewView ?: run {
            Log.e(TAG, "bindCamera called but PreviewView is null")
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                this@CpuCameraPipeline.cameraProvider = cameraProvider

                val preview = Preview.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                            .build()
                    )
                    .build()

                // 拦截 SurfaceProvider 以捕获 Preview 分辨率，再转发给 PreviewView
                val pvSurfaceProvider = pv.surfaceProvider
                preview.setSurfaceProvider(executor) { request ->
                    previewRawDims[0] = request.resolution.width
                    previewRawDims[1] = request.resolution.height
                    Log.i(TAG, "Preview raw=${request.resolution.width}x${request.resolution.height}")
                    pvSurfaceProvider.onSurfaceRequested(request)
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                            .build()
                    )
                    .build()

                imageAnalysis.setAnalyzer(executor) { imageProxy: ImageProxy ->
                    processFrame(imageProxy)
                }

                val cameraSelector = if (cameraFacing == 0) {
                    CameraSelector.DEFAULT_BACK_CAMERA
                } else {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                }

                cameraProvider.unbindAll()
                // 将 ImageCapture 与 Preview + ImageAnalysis 一起绑定
                val useCases: MutableList<UseCase> = mutableListOf(preview, imageAnalysis)
                imageCapture?.let { useCases.add(it) }
                val camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner, cameraSelector, *useCases.toTypedArray()
                )
                this@CpuCameraPipeline.camera = camera
                callback.onSensorOrientationChanged(camera.cameraInfo.sensorRotationDegrees)

                // 监听 CameraX 内部错误（偶发超时），自动重绑恢复
                camera.cameraInfo.cameraState.observe(lifecycleOwner) { state ->
                    val err = state.error ?: return@observe
                    Log.w(TAG, "CameraX 错误: code=${err.code}, 延迟重绑...")
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        try {
                            cameraProvider.unbindAll()
                            val retryCamera = cameraProvider.bindToLifecycle(
                                lifecycleOwner, cameraSelector, *useCases.toTypedArray()
                            )
                            this@CpuCameraPipeline.camera = retryCamera
                            Log.i(TAG, "CameraX 重绑成功")
                        } catch (e: Exception) {
                            Log.e(TAG, "CameraX 重绑失败: ${e.message}", e)
                        }
                    }, 500)
                }

                pv.post {
                    Log.i(TAG, "PreviewView ${pv.width}x${pv.height}, sensorOrientation=${camera.cameraInfo.sensorRotationDegrees}")
                }

                Log.i(TAG, "CPU相机绑定完成: facing=$cameraFacing")
            } catch (e: Exception) {
                Log.e(TAG, "CPU camera init failed: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    private var cpuFrameCount = 0

    /** 处理单帧图像 */
    private fun processFrame(imageProxy: ImageProxy) {
        if (!getIsDetecting()) {
            imageProxy.close()
            return
        }
        cpuFrameCount++
        try {
            val t0 = System.nanoTime()
            val rawBitmap = imageProxy.toBitmap()
            val t1 = System.nanoTime()

            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val bitmap: Bitmap = if (rotationDegrees != 0) {
                val matrix = Matrix()
                matrix.postRotate(rotationDegrees.toFloat())
                val rotated = Bitmap.createBitmap(
                    rawBitmap, 0, 0,
                    rawBitmap.width, rawBitmap.height,
                    matrix, true
                )
                rawBitmap.recycle()
                rotated
            } else {
                rawBitmap
            }
            val t2 = System.nanoTime()

            val bw = bitmap.width.toFloat()
            val bh = bitmap.height.toFloat()

            // 用 Preview 原始分辨率计算旋转后的有效尺寸
            if (previewRawDims[0] > 0 && previewRawDims[1] > 0 && previewEffDims[0] == 0) {
                if (rotationDegrees == 90 || rotationDegrees == 270) {
                    previewEffDims[0] = previewRawDims[1]
                    previewEffDims[1] = previewRawDims[0]
                } else {
                    previewEffDims[0] = previewRawDims[0]
                    previewEffDims[1] = previewRawDims[1]
                }
                Log.i(TAG, "[#$cpuFrameCount] bitmap=${bitmap.width}x${bitmap.height} " +
                    "preview=${previewRawDims[0]}x${previewRawDims[1]} " +
                    "previewEff=${previewEffDims[0]}x${previewEffDims[1]} rot=$rotationDegrees")
            }

            val detections = try {
                getDetector()?.detect(bitmap, getConfThreshold()) ?: emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "[#$cpuFrameCount] detect异常: ${e.message}", e)
                emptyList()
            }
            val t3 = System.nanoTime()

            val toBitmapMs = (t1 - t0) / 1_000_000.0
            val rotateMs = (t2 - t1) / 1_000_000.0
            val detectMs = (t3 - t2) / 1_000_000.0
            val fps = if (detectMs > 0) (1000.0 / detectMs).toLong() else 0L

            if (cpuFrameCount <= 3 || cpuFrameCount % 100 == 0) {
                Log.i(TAG, String.format("[#$cpuFrameCount] CPU产线完成: " +
                    "toBitmap=%.1fms rotate=%.1fms detect=%.1fms fps=%d 检出数=%d",
                    toBitmapMs, rotateMs, detectMs, fps, detections.size))
            }

            bitmap.recycle()

            // 使用 Preview 有效分辨率映射 overlay，修正宽高比不一致的偏移
            val pw = previewEffDims[0].let { if (it > 0) it.toFloat() else bw }
            val ph = previewEffDims[1].let { if (it > 0) it.toFloat() else bh }
            val scaleX = pw / bw
            val scaleY = ph / bh
            val finalDetections = if (scaleX != 1f || scaleY != 1f) {
                if (cpuFrameCount <= 3) {
                    Log.i(TAG, String.format("[#$cpuFrameCount] overlay映射: " +
                        "bitmap=%.0fx%.0f preview=%.0fx%.0f scale=%.3f,%.3f", bw, bh, pw, ph, scaleX, scaleY))
                }
                scaleDetections(detections, scaleX, scaleY)
            } else {
                detections
            }

            callback?.onDetectionResult(
                finalDetections, pw, ph,
                getCameraFacing(),
                detectMs.toLong(), fps
            )
        } catch (e: Exception) {
            Log.e(TAG, "[#$cpuFrameCount] 帧处理异常: ${e.message}", e)
        } finally {
            imageProxy.close()
        }
    }

    override fun unbindCamera() {
        cameraProvider?.unbindAll()
    }

    override fun release() {
        Log.i(TAG, "═══════ CPU管线释放 (共处理${cpuFrameCount}帧) ═══════")
        // 不调 unbindAll()：新管线 bindCamera() 会统一调用，避免两次 unbindAll 冲突超时
        callback = null
        previewView = null
        previewRawDims.fill(0)
        previewEffDims.fill(0)
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

/**
 * 将检测结果从 ImageAnalysis bitmap 坐标空间缩放到 Preview 坐标空间
 * 解决两者分辨率/宽高比不一致时的 overlay 偏移问题
 */
private fun scaleDetections(
    detections: List<DetectionResult>,
    scaleX: Float,
    scaleY: Float
): List<DetectionResult> {
    if (scaleX == 1f && scaleY == 1f) return detections
    return detections.map { det ->
        when (det) {
            is ObjectDetection -> det.copy(
                boundingBox = BoundingBox(
                    det.boundingBox.x1 * scaleX, det.boundingBox.y1 * scaleY,
                    det.boundingBox.x2 * scaleX, det.boundingBox.y2 * scaleY
                )
            )
            is SegmentationResult -> det.copy(
                boundingBox = BoundingBox(
                    det.boundingBox.x1 * scaleX, det.boundingBox.y1 * scaleY,
                    det.boundingBox.x2 * scaleX, det.boundingBox.y2 * scaleY
                )
            )
            is KeypointDetection -> det.copy(
                boundingBox = BoundingBox(
                    det.boundingBox.x1 * scaleX, det.boundingBox.y1 * scaleY,
                    det.boundingBox.x2 * scaleX, det.boundingBox.y2 * scaleY
                ),
                keypoints = det.keypoints.map { kp ->
                    Keypoint(
                        kp.x * scaleX, kp.y * scaleY, kp.visibility, kp.name
                    )
                }
            )
            is OrientedBoxDetection -> det.copy(
                boundingBox = BoundingBox(
                    det.boundingBox.x1 * scaleX, det.boundingBox.y1 * scaleY,
                    det.boundingBox.x2 * scaleX, det.boundingBox.y2 * scaleY
                )
            )
            else -> det
        }
    }
}
