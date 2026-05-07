package com.little_star.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.camera.core.ImageCapture
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import com.little_star.detector.IDetector
import com.little_star.detector.model.BoundingBox
import com.little_star.detector.model.ClassificationResult
import com.little_star.detector.model.DetectionResult
import com.little_star.detector.model.Keypoint
import com.little_star.detector.model.KeypointDetection
import com.little_star.detector.model.ObjectDetection
import com.little_star.detector.model.OrientedBoxDetection
import com.little_star.detector.model.SegmentationMask
import com.little_star.detector.model.SegmentationResult
import com.little_star.model.drawClassificationLabelTo
import com.little_star.model.drawKeypointsTo
import com.little_star.model.drawMaskTo
import com.little_star.model.drawOrientedBoxTo
import com.little_star.pipeline.CameraPipeline
import com.little_star.pipeline.CpuCameraPipeline
import com.little_star.pipeline.GlCameraPipeline
import com.little_star.pipeline.PipelineCallback
import com.little_star.pipeline.PipelineStrategy
import java.util.concurrent.Executors

private const val TAG = "CameraPreview"

private val BOX_COLORS = intArrayOf(
    Color.argb(255, 255, 89, 89),
    Color.argb(255, 255, 202, 58),
    Color.argb(255, 138, 201, 38),
    Color.argb(255, 25, 130, 196),
    Color.argb(255, 106, 76, 147),
    Color.argb(255, 255, 146, 76),
    Color.argb(255, 0, 187, 212),
    Color.argb(255, 233, 30, 99),
)

data class DetectionStats(
    val objectCount: Int,
    val inferenceTimeMs: Long,
    val fps: Long,
    val detections: List<DetectionResult>,
    val showStats: Boolean = true
)

private class DetectionOverlayView(context: Context) : android.view.View(context) {

    private var detections: List<DetectionResult> = emptyList()
    private var bitmapWidth: Float = 0f
    private var bitmapHeight: Float = 0f
    private var cameraFacing: Int = 0
    private var inferenceTimeMs: Long = 0L
    private var fps: Long = 0L
    private var isActive: Boolean = true
    private var drawScale: Float = 1f
    private var diagLogCount: Int = 0

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
    }
    private val statsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        textSize = 42f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val statsBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val keypointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        strokeWidth = 6f
    }
    private val skeletonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    // 分割掩膜绘制画笔（半透明）
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        alpha = 128
    }
    private val textBounds = android.graphics.Rect()

    // 引用 DetectionDrawing 中的公共骨架连线定义（消除重复）
    private val defaultCocoKeypointsLink = com.little_star.model.defaultCocoKeypointsLink

    // 引用 DetectionRenderer 中的公共骨架颜色定义（消除重复）
    private val defaultSkeletonColors = com.little_star.model.DetectionRenderer.skeletonColors

    init {
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        drawScale = w.coerceAtMost(h).toFloat() / 1080f
        boxPaint.strokeWidth = 6f * drawScale
        textPaint.textSize = 36f * drawScale
        statsPaint.textSize = 42f * drawScale
        keypointPaint.strokeWidth = 6f * drawScale
        skeletonPaint.strokeWidth = 3f * drawScale
    }

    fun setActive(active: Boolean) {
        isActive = active
        if (!active) {
            clear()
        }
    }

    fun setResults(
        detections: List<DetectionResult>,
        bitmapWidth: Float,
        bitmapHeight: Float,
        cameraFacing: Int,
        inferenceTimeMs: Long = 0L,
        fps: Long = 0L
    ) {
        if (!isActive) return
        this.detections = detections
        this.bitmapWidth = bitmapWidth
        this.bitmapHeight = bitmapHeight
        this.cameraFacing = cameraFacing
        this.inferenceTimeMs = inferenceTimeMs
        this.fps = fps
        invalidate()
    }

    fun clear() {
        detections = emptyList()
        inferenceTimeMs = 0L
        fps = 0L
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0 || viewHeight <= 0) return

        // 绘制 FPS/耗时信息（右上角）
        if (fps > 0 || inferenceTimeMs > 0) {
            val statsText = if (fps > 0) {
                "${inferenceTimeMs}ms | ${fps}fps"
            } else {
                "${inferenceTimeMs}ms"
            }
            statsPaint.getTextBounds(statsText, 0, statsText.length, textBounds)
            val statsTextWidth = textBounds.width().toFloat()
            val statsTextHeight = textBounds.height().toFloat()
            val padding = 16f * drawScale
            val statsX = viewWidth - statsTextWidth - padding
            val statsY = padding + statsTextHeight

            statsBgPaint.color = Color.argb(180, 0, 0, 0)
            canvas.drawRect(
                statsX - padding,
                padding,
                viewWidth,
                statsY + padding,
                statsBgPaint
            )
            canvas.drawText(statsText, statsX, statsY, statsPaint)
        }

        if (detections.isEmpty()) return
        if (bitmapWidth <= 0 || bitmapHeight <= 0) return

        val imageAspect = bitmapWidth / bitmapHeight
        val viewAspect = viewWidth / viewHeight

        val scale: Float
        val offsetX: Float
        val offsetY: Float

        if (imageAspect > viewAspect) {
            scale = viewHeight / bitmapHeight
            offsetX = (viewWidth - bitmapWidth * scale) / 2f
            offsetY = 0f
        } else {
            scale = viewWidth / bitmapWidth
            offsetX = 0f
            offsetY = (viewHeight - bitmapHeight * scale) / 2f
        }

        // 诊断日志：每 100 帧打印 overlay 映射参数
        if (diagLogCount < 5 || diagLogCount % 100 == 0) {
            Log.i(TAG, "Overlay: bitmap=${bitmapWidth}x${bitmapHeight} view=${viewWidth.toInt()}x${viewHeight.toInt()} " +
                "imgAspect=${"%.4f".format(imageAspect)} viewAspect=${"%.4f".format(viewAspect)} " +
                "scale=${"%.4f".format(scale)} offset=(${offsetX.toInt()},${offsetY.toInt()}) facing=$cameraFacing")
        }
        diagLogCount++

        detections.forEachIndexed { index, detection ->
            val color = BOX_COLORS[index % BOX_COLORS.size]

            when (detection) {
                is ClassificationResult -> {
                    detection.drawClassificationLabelTo(
                        canvas = canvas, textPaint = textPaint, bgPaint = fillPaint,
                        color = color, textSize = 36f * drawScale,
                        x = viewWidth / 2f, y = 20f * drawScale
                    )
                }

                is OrientedBoxDetection -> {
                    // 前置摄像头水平镜像
                    val drawBox = if (cameraFacing == 1) {
                        val box = detection.boundingBox
                        BoundingBox(
                            viewWidth - (box.x2 * scale + offsetX), box.y1 * scale + offsetY,
                            viewWidth - (box.x1 * scale + offsetX), box.y2 * scale + offsetY
                        )
                    } else {
                        val box = detection.boundingBox
                        BoundingBox(
                            box.x1 * scale + offsetX, box.y1 * scale + offsetY,
                            box.x2 * scale + offsetX, box.y2 * scale + offsetY
                        )
                    }
                    val mappedDetection = OrientedBoxDetection(
                        classId = detection.classId, className = detection.className,
                        confidence = detection.confidence, boundingBox = drawBox,
                        rotationAngle = detection.rotationAngle
                    )
                    boxPaint.color = color
                    mappedDetection.drawOrientedBoxTo(
                        canvas = canvas, paint = boxPaint, labelPaint = textPaint
                    )
                }

                is SegmentationResult -> {
                    val drawBox = if (cameraFacing == 1) {
                        val box = detection.boundingBox
                        BoundingBox(
                            viewWidth - (box.x2 * scale + offsetX), box.y1 * scale + offsetY,
                            viewWidth - (box.x1 * scale + offsetX), box.y2 * scale + offsetY
                        )
                    } else {
                        val box = detection.boundingBox
                        BoundingBox(
                            box.x1 * scale + offsetX, box.y1 * scale + offsetY,
                            box.x2 * scale + offsetX, box.y2 * scale + offsetY
                        )
                    }
                    // 前置摄像头：mask 需要水平翻转，与 mirrored 的 boundingBox 对齐
                    val finalMask = if (cameraFacing == 1) {
                        val mask = detection.mask
                        val flippedData = BooleanArray(mask.data.size)
                        for (y in 0 until mask.height) {
                            for (x in 0 until mask.width) {
                                flippedData[y * mask.width + x] =
                                    mask.data[y * mask.width + (mask.width - 1 - x)]
                            }
                        }
                        SegmentationMask(
                            width = mask.width,
                            height = mask.height,
                            data = flippedData,
                            cropX1 = mask.cropX1,
                            cropY1 = mask.cropY1,
                            cropX2 = mask.cropX2,
                            cropY2 = mask.cropY2
                        )
                    } else {
                        detection.mask
                    }
                    val mappedResult = SegmentationResult(
                        classId = detection.classId, className = detection.className,
                        confidence = detection.confidence, boundingBox = drawBox,
                        mask = finalMask
                    )
                    mappedResult.drawMaskTo(
                        canvas,
                        maskPaint,
                        color,
                        scale = 1f,
                        offsetX = 0f,
                        offsetY = 0f
                    )
                    boxPaint.color = color
                    canvas.drawRect(drawBox.x1, drawBox.y1, drawBox.x2, drawBox.y2, boxPaint)
                    drawBoxLabel(canvas, drawBox, detection.className, detection.confidence, color)
                }

                is KeypointDetection -> {
                    val drawBox = mapBoundingBox(
                        detection.boundingBox,
                        scale,
                        offsetX,
                        offsetY,
                        cameraFacing,
                        viewWidth
                    )
                    // For front camera, keypoints need mirroring just like the bounding box
                    val mappedKeypoints = if (cameraFacing == 1) {
                        detection.keypoints.map { kp ->
                            Keypoint(
                                x = viewWidth - kp.x * scale - offsetX,
                                y = kp.y * scale + offsetY,
                                visibility = kp.visibility,
                                name = kp.name
                            )
                        }
                    } else {
                        detection.keypoints.map { kp ->
                            Keypoint(
                                x = kp.x * scale + offsetX,
                                y = kp.y * scale + offsetY,
                                visibility = kp.visibility,
                                name = kp.name
                            )
                        }
                    }
                    val mappedKeypointDetection = KeypointDetection(
                        classId = detection.classId,
                        className = detection.className,
                        confidence = detection.confidence,
                        boundingBox = drawBox,
                        keypoints = mappedKeypoints,
                        keypointsLink = detection.keypointsLink
                    )
                    boxPaint.color = color
                    canvas.drawRect(drawBox.x1, drawBox.y1, drawBox.x2, drawBox.y2, boxPaint)
                    // Keypoints are already in canvas coordinates, use scale=1f and no offsets
                    mappedKeypointDetection.drawKeypointsTo(
                        canvas = canvas,
                        keypointPaint = keypointPaint,
                        skeletonPaint = skeletonPaint,
                        skeletonColors = defaultSkeletonColors,
                        defaultKeypointsLink = defaultCocoKeypointsLink,
                        scale = 1f,
                        offsetX = 0f,
                        offsetY = 0f,
                        keypointRadius = 8f * drawScale
                    )
                    drawBoxLabel(canvas, drawBox, detection.className, detection.confidence, color)
                }

                is ObjectDetection -> {
                    val drawBox = mapBoundingBox(
                        detection.boundingBox,
                        scale,
                        offsetX,
                        offsetY,
                        cameraFacing,
                        viewWidth
                    )
                    boxPaint.color = color
                    canvas.drawRect(drawBox.x1, drawBox.y1, drawBox.x2, drawBox.y2, boxPaint)
                    drawBoxLabel(canvas, drawBox, detection.className, detection.confidence, color)
                }
            }
        }
    }

    /** 将 bitmap 坐标映射到 view 坐标（含前置摄像头镜像） */
    private fun mapBoundingBox(
        box: BoundingBox, scale: Float, offsetX: Float, offsetY: Float,
        facing: Int, viewWidth: Float
    ): BoundingBox {
        return if (facing == 1) {
            BoundingBox(
                viewWidth - (box.x2 * scale + offsetX), box.y1 * scale + offsetY,
                viewWidth - (box.x1 * scale + offsetX), box.y2 * scale + offsetY
            )
        } else {
            BoundingBox(
                box.x1 * scale + offsetX, box.y1 * scale + offsetY,
                box.x2 * scale + offsetX, box.y2 * scale + offsetY
            )
        }
    }

    /** 绘制带背景的标签 */
    private fun drawBoxLabel(
        canvas: Canvas, box: BoundingBox,
        className: String, confidence: Float, color: Int
    ) {
        val label = String.format("%s %.0f%%", className, confidence * 100)
        textPaint.getTextBounds(label, 0, label.length, textBounds)
        val textWidth = textBounds.width().toFloat()
        val textHeight = textBounds.height().toFloat()
        val padding = textPaint.textSize * 0.333f

        val labelBottom =
            if (box.y1 - textHeight - padding * 2 > 0) box.y1 else box.y1 + textHeight + padding * 2
        val labelTop = labelBottom - textHeight - padding * 2

        fillPaint.color = color
        fillPaint.alpha = (0.78f * 255).toInt()
        canvas.drawRect(box.x1, labelTop, box.x1 + textWidth + padding * 2, labelBottom, fillPaint)
        fillPaint.alpha = 255
        canvas.drawText(label, box.x1 + padding, labelBottom - padding, textPaint)
    }
}

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    detector: IDetector?,
    cameraFacing: Int = 0,
    confThreshold: Float = 0.25f,
    isDetecting: Boolean = false,
    inputSize: Int = 640,
    pipelineStrategy: PipelineStrategy = PipelineStrategy.GL_TRANSIT,
    /** 分类任务使用 center-crop 预处理，其他任务使用 letterbox */
    centerCrop: Boolean = false,
    onDetectionStats: (DetectionStats) -> Unit = {},
    /** 拍照功能回调：当 ImageCapture 就绪时通知上层，View 分离时传 null */
    onImageCaptureReady: ((ImageCapture?) -> Unit)? = null,
    /** 闪光灯模式：ImageCapture.FLASH_MODE_OFF / FLASH_MODE_ON / FLASH_MODE_AUTO */
    flashMode: Int = ImageCapture.FLASH_MODE_OFF,
    /** 强制手电筒状态（用于 AUTO 模式拍照前预照），null 表示使用 flashMode 逻辑 */
    torchOverride: Boolean? = null
) {
    @Suppress("DEPRECATION")
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    var overlayViewRef by remember { mutableStateOf<DetectionOverlayView?>(null) }

    // 缩放状态：记录当前缩放比例，通过双指捏合手势调整
    var pipelineRef by remember { mutableStateOf<CameraPipeline?>(null) }
    var currentZoomRatio by remember { mutableStateOf(1f) }

    val isDetectingState = rememberUpdatedState(isDetecting)
    val detectorState = rememberUpdatedState(detector)
    val confThresholdState = rememberUpdatedState(confThreshold)
    val onDetectionStatsState = rememberUpdatedState(onDetectionStats)
    val cameraFacingState = rememberUpdatedState(cameraFacing)
    val onImageCaptureReadyState = rememberUpdatedState(onImageCaptureReady)
    val flashModeState = rememberUpdatedState(flashMode)
    val torchOverrideState = rememberUpdatedState(torchOverride)

    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    // 当需要拍照功能时，创建 ImageCapture 实例并通过 OrientationEventListener 动态更新旋转
    var imageCaptureRef by remember { mutableStateOf<ImageCapture?>(null) }

    // 闪光灯模式变化时同步：torchOverride 优先，否则按 flashMode 逻辑
    // ON 模式开启手电筒持续补光，AUTO/OFF 关闭（AUTO 模式拍照时通过 torchOverride 临时开启）
    androidx.compose.runtime.LaunchedEffect(flashMode, torchOverride, pipelineRef) {
        val pipeline = pipelineRef ?: return@LaunchedEffect
        val override = torchOverrideState.value
        val mode = flashModeState.value
        // torchOverride 优先：true 强制开，false 强制关，null 按 flashMode 逻辑
        val shouldEnableTorch = override ?: (mode == ImageCapture.FLASH_MODE_ON)
        pipeline.enableTorch(shouldEnableTorch)
    }
    androidx.compose.runtime.LaunchedEffect(flashMode, imageCaptureRef) {
        imageCaptureRef?.flashMode = flashModeState.value
    }

    // 动态更新 ImageCapture 的 targetRotation，确保 JPEG EXIF 旋转信息正确
    DisposableEffect(onImageCaptureReady != null) {
        if (onImageCaptureReady == null) return@DisposableEffect onDispose {}

        val listener = object : android.view.OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rotation = when {
                    orientation >= 315 || orientation < 45  -> Surface.ROTATION_0
                    orientation >= 45  && orientation < 135 -> Surface.ROTATION_270
                    orientation >= 135 && orientation < 225 -> Surface.ROTATION_180
                    else                                     -> Surface.ROTATION_90
                }
                imageCaptureRef?.setTargetRotation(rotation)
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }

    key(cameraFacing, pipelineStrategy) {
        AndroidView(
            modifier = modifier.pointerInput(Unit) {
                // 双指捏合缩放手势
                detectTransformGestures { _, _, zoomChange, _ ->
                    val pipeline = pipelineRef ?: return@detectTransformGestures
                    val minZoom = pipeline.getMinZoomRatio()
                    val maxZoom = pipeline.getMaxZoomRatio()
                    val newRatio = (currentZoomRatio * zoomChange).coerceIn(minZoom, maxZoom)
                    currentZoomRatio = newRatio
                    pipeline.setZoomRatio(newRatio)
                }
            },
            factory = { ctx ->
                val cameraExecutor = Executors.newSingleThreadExecutor { r ->
                    Thread(r).apply { isDaemon = true }
                }

                val overlayView = DetectionOverlayView(ctx).apply {
                    translationZ = 1f
                }
                overlayViewRef = overlayView

                val frameLayout = FrameLayout(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }

                // 根据管线策略创建对应的实现
                val pipeline: CameraPipeline = if (pipelineStrategy.useGlPipeline) {
                    GlCameraPipeline(
                        inputSize = inputSize,
                        getDetector = { detectorState.value },
                        getConfThreshold = { confThresholdState.value },
                        getIsDetecting = { isDetectingState.value },
                        getCameraFacing = { cameraFacingState.value },
                        centerCrop = centerCrop,
                        strategy = pipelineStrategy
                    )
                } else {
                    CpuCameraPipeline(
                        inputSize = inputSize,
                        getDetector = { detectorState.value },
                        getConfThreshold = { confThresholdState.value },
                        getIsDetecting = { isDetectingState.value },
                        getCameraFacing = { cameraFacingState.value }
                    )
                }

                // 如果需要拍照功能，创建 ImageCapture 并绑定
                val imageCapture = if (onImageCaptureReady != null) {
                    val initialRotation = try {
                        @Suppress("DEPRECATION")
                        (ctx as? android.app.Activity)
                            ?.windowManager?.defaultDisplay?.rotation
                            ?: Surface.ROTATION_0
                    } catch (_: Exception) {
                        Surface.ROTATION_0
                    }
                    ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setTargetRotation(initialRotation)
                        .build().also {
                            imageCaptureRef = it
                        }
                } else null

                // 创建回调
                val pipelineCallback = object : PipelineCallback {
                    override fun onDetectionResult(
                        detections: List<DetectionResult>,
                        overlayWidth: Float,
                        overlayHeight: Float,
                        cameraFacing: Int,
                        inferenceTimeMs: Long,
                        fps: Long
                    ) {
                        mainHandler.post {
                            overlayView.setResults(
                                detections, overlayWidth, overlayHeight, cameraFacing,
                                inferenceTimeMs, fps
                            )
                        }
                    }

                    override fun onSensorOrientationChanged(degrees: Int) {
                        // 产线自主管理传感器方向
                    }
                }

                // 初始化产线
                pipeline.setupViews(ctx, frameLayout, overlayView)
                pipelineRef = pipeline
                currentZoomRatio = pipeline.getMinZoomRatio()

                // 绑定相机
                pipeline.bindCamera(ctx, lifecycleOwner, cameraFacing, cameraExecutor, pipelineCallback, imageCapture)

                // 通知上层 ImageCapture 已就绪
                if (imageCapture != null) {
                    onImageCaptureReadyState.value?.invoke(imageCapture)
                }

                // 视图分离时释放资源
                frameLayout.addOnAttachStateChangeListener(object :
                    android.view.View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: android.view.View) {}
                    override fun onViewDetachedFromWindow(v: android.view.View) {
                        Log.i(TAG, "View detached, releasing pipeline (${pipelineStrategy.name})")
                        pipeline.release()
                        pipelineRef = null
                        imageCaptureRef = null
                        // 注意：不通知上层 imageCapture 失效（传 null）
                        // 原因：key 变化时旧 View 异步分离，detach 回调可能在
                        // 新 View 已就绪后才触发，传 null 会覆盖新 ImageCapture 引用
                    }
                })

                frameLayout
            },
            update = { view ->
                val frame = view
                // overlay 始终是最后一个子视图
                overlayViewRef = frame.getChildAt(frame.childCount - 1) as? DetectionOverlayView
            }
        )
    } // end key(cameraFacing)

    LaunchedEffect(isDetecting) {
        snapshotFlow { isDetecting }
            .collect { detecting ->
                overlayViewRef?.setActive(detecting)
            }
    }
}
