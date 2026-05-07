package com.little_star.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.little_star.R
import com.little_star.detector.model.DetectionResult
import com.little_star.detector.model.TaskType
import com.little_star.model.ImageDetectionResult
import com.little_star.pipeline.PipelinePrerequisites
import com.little_star.ui.components.CameraPreview
import com.little_star.ui.components.DetectorStateBanner
import com.little_star.ui.components.FullScreenImageDialog
import com.little_star.ui.components.StrategyChipButton
import com.little_star.util.BitmapUtils
import com.little_star.viewmodel.CameraViewModel
import com.little_star.viewmodel.DetectorState
import com.little_star.viewmodel.SharedDetectorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * 前后置切换图标（FlipCameraAndroid 去掉中间圆点）
 */
private val CameraSwitchIcon: ImageVector
    get() = ImageVector.Builder(
        name = "CameraSwitch",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        // 上半弧线箭头（左下→右上）
        path(fill = SolidColor(Color.Black)) {
            moveTo(8f, 10f)
            lineTo(8f, 8f)
            lineTo(5.09f, 8f)
            curveTo(6.47f, 5.61f, 9.05f, 4f, 12f, 4f)
            curveTo(15.72f, 4f, 18.85f, 6.56f, 19.74f, 10f)
            lineTo(21.8f, 10f)
            curveTo(20.87f, 5.45f, 16.84f, 2f, 12f, 2f)
            curveTo(8.73f, 2f, 5.28f, 4.08f, 3.47f, 7.18f)
            lineTo(2f, 5.71f)
            lineTo(2f, 10f)
            close()
        }
        // 下半弧线箭头（右上→左下）
        path(fill = SolidColor(Color.Black)) {
            moveTo(16f, 14f)
            lineTo(16f, 16f)
            lineTo(18.91f, 16f)
            curveTo(17.53f, 18.39f, 14.95f, 20f, 12f, 20f)
            curveTo(8.28f, 20f, 5.15f, 17.44f, 4.26f, 14f)
            lineTo(2.2f, 14f)
            curveTo(3.13f, 18.55f, 7.16f, 22f, 12f, 22f)
            curveTo(15.44f, 22f, 18.49f, 20.28f, 20.35f, 17.65f)
            lineTo(22f, 18.29f)
            lineTo(22f, 14f)
            close()
        }
    }.build()

/**
 * 相机子模式（LIVE 状态下的两种使用场景）
 */
enum class CameraSubMode(val labelRes: Int) {
    /** 实时检测模式：实时检测覆盖层 + 硬件加速 */
    REALTIME(R.string.realtime_detect),

    /** 拍照模式：拍照后检测，无实时覆盖 */
    PHOTO(R.string.photo_detect)
}

/**
 * 统一相机模式 UI 状态
 */
enum class CameraUiState {
    /** 实时预览 + 检测覆盖层 */
    LIVE,

    /** 正在拍照（短暂过渡态） */
    CAPTURING,

    /** 拍照结果 + 检测详情 */
    CAPTURED
}

/** 闪光灯模式 */
enum class FlashMode {
    OFF, ON, AUTO
}

/**
 * 统一相机识别页面
 * 合并了实时检测和拍照检测两种模式
 *
 * 三种 UI 状态:
 * - LIVE: 实时预览 + 检测覆盖层（与原 CameraScreen 相同）
 * - CAPTURING: 拍照中（短暂过渡态）
 * - CAPTURED: 拍照结果 + 检测详情（与原 CaptureImageScreen 结果视图相同）
 *
 * @param sharedViewModel 共享检测器 ViewModel
 * @param onBack 返回回调
 * @param cameraViewModel CameraViewModel 实例
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnifiedCameraScreen(
    sharedViewModel: SharedDetectorViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    cameraViewModel: CameraViewModel = viewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // 预先缓存字符串资源，供非 Composable 回调使用
    val resCapturing = stringResource(R.string.capturing)
    val resCaptureCompleteTapDetect = stringResource(R.string.capture_complete_tap_detect)
    val resLoadImageFailed = stringResource(R.string.load_image_failed)
    val resCaptureFailed = stringResource(R.string.capture_failed)
    val resDetectorNotReady = stringResource(R.string.detector_not_ready)
    val resDetecting = stringResource(R.string.detecting)
    val resDetectionComplete = stringResource(R.string.detection_complete)
    val resDetectionFailed = stringResource(R.string.detection_failed)
    val strings = remember {
        object {
            val capturing = resCapturing
            val captureCompleteTapDetect = resCaptureCompleteTapDetect
            val loadImageFailed = resLoadImageFailed
            val captureFailed = resCaptureFailed
            val detectorNotReady = resDetectorNotReady
            val detecting = resDetecting
            val detectionComplete = resDetectionComplete
            val detectionFailed = resDetectionFailed
        }
    }

    // ViewModel 状态
    val cameraFacing by cameraViewModel.cameraFacing.collectAsState()
    val isDetecting by cameraViewModel.isDetecting.collectAsState()
    val effectiveStrategy by cameraViewModel.effectiveStrategy.collectAsState()
    val strategyChain by cameraViewModel.strategyChain.collectAsState()
    val availableStrategies by cameraViewModel.availableStrategies.collectAsState()
    val isAutoMode by cameraViewModel.isAutoMode.collectAsState()
    val degradationTriggered by cameraViewModel.degradationTriggered.collectAsState()
    val detectorState by sharedViewModel.detectorState.collectAsState()
    val confThreshold by sharedViewModel.confThreshold.collectAsState()
    val detector = sharedViewModel.getDetector()
    val inputSize = sharedViewModel.currentInputSize
    val centerCrop = sharedViewModel.currentTaskType == TaskType.CLASSIFICATION

    // 初始化 xselect 策略上下文（进入相机时根据当前 backend+accelerator+平台 初始化）
    LaunchedEffect(detectorState) {
        if (detectorState is DetectorState.Ready) {
            val config = sharedViewModel.getCurrentConfig()
            val backend = config.backend ?: return@LaunchedEffect
            val accelerator = config.accelerator ?: return@LaunchedEffect
            val glHealthy = PipelinePrerequisites.isGlContextAvailable()
            val ahwbOk = PipelinePrerequisites.isAhwbInteropAvailable()
            val npuBufType = if (accelerator == com.little_star.detector.model.AcceleratorMode.NPU) {
                PipelinePrerequisites.detectNpuBufferType()
            } else com.little_star.pipeline.NpuBufferType.UNKNOWN

            cameraViewModel.initializeContext(
                backend = backend,
                accelerator = accelerator,
                glHealthy = glHealthy,
                ahwbOk = ahwbOk,
                npuBufferType = npuBufType
            )
        }
    }

    // 降级提示 Toast
    val degradationMsg = stringResource(R.string.xselect_gl_not_available)
    if (degradationTriggered) {
        LaunchedEffect(degradationTriggered) {
            cameraViewModel.updateResultText(degradationMsg)
            cameraViewModel.consumeDegradation()
        }
    }

    // 相机权限
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted -> hasCameraPermission = isGranted }

    // 首次进入检查权限
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // === 统一相机状态 ===
    var uiState by remember { mutableStateOf(CameraUiState.LIVE) }
    var subMode by remember { mutableStateOf(CameraSubMode.PHOTO) }
    var flashMode by remember { mutableStateOf(FlashMode.OFF) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var isCapturing by remember { mutableStateOf(false) }
    // AUTO 模式拍照时临时强制开启 torch（解决闪光灯时机不同步问题）
    var torchOverride by remember { mutableStateOf<Boolean?>(null) }

    // 快门闪光动画（拍照时短暂闪白后快速消退）
    val flashAlpha = remember { Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(uiState) {
        if (uiState == CameraUiState.CAPTURING) {
            flashAlpha.snapTo(0.6f)
            flashAlpha.animateTo(0f, animationSpec = tween(durationMillis = 150))
        }
    }

    // 切换子模式时同步检测状态：拍照模式关闭检测
    // 同时处理闪光灯模式：实时检测不支持 AUTO，切换时重置为 OFF
    fun switchSubMode(mode: CameraSubMode) {
        subMode = mode
        if (mode == CameraSubMode.PHOTO && isDetecting) {
            cameraViewModel.toggleDetecting()
        }
        // 切换到实时检测时，AUTO 模式重置为 OFF
        if (mode == CameraSubMode.REALTIME && flashMode == FlashMode.AUTO) {
            flashMode = FlashMode.OFF
        }
    }

    // 拍照结果状态
    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var resultBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var detections by remember { mutableStateOf<List<DetectionResult>>(emptyList()) }
    var inferenceTimeMs by remember { mutableIntStateOf(0) }
    var isProcessing by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var fullScreenPreview by remember { mutableStateOf(false) }

    // === 拍照逻辑（来源于 CaptureImageScreen） ===

    /** 实际执行拍照 */
    fun doTakePicture(capture: ImageCapture) {
        isCapturing = true
        uiState = CameraUiState.CAPTURING
        statusText = strings.capturing
        errorText = null

        val photoFile = File.createTempFile("capture", ".jpg", context.cacheDir)

        // 前置摄像头通过 Metadata.isReversedHorizontal 处理镜像（CameraX 标准方式）
        val metadata = ImageCapture.Metadata().apply {
            isReversedHorizontal = (cameraFacing == 1)
        }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile)
            .setMetadata(metadata)
            .build()

        val executor = Executors.newSingleThreadExecutor()
        capture.takePicture(
            outputOptions,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    // AUTO 模式拍照完成后关闭 torch
                    torchOverride = null
                    coroutineScope.launch {
                        try {
                            // 使用 BitmapUtils 解码 JPEG 并校正 EXIF 旋转
                            val bitmap = withContext(Dispatchers.IO) {
                                BitmapUtils.decodeJpegWithExifRotation(photoFile.absolutePath)
                            }
                            capturedBitmap = bitmap
                            resultBitmap = null
                            detections = emptyList()
                            inferenceTimeMs = 0
                            statusText = strings.captureCompleteTapDetect
                            errorText = null
                            uiState = CameraUiState.CAPTURED
                        } catch (e: Exception) {
                            errorText = strings.loadImageFailed.format(e.message ?: "")
                            uiState = CameraUiState.LIVE
                        } finally {
                            isCapturing = false
                            photoFile.delete()
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    torchOverride = null
                    isCapturing = false
                    errorText = strings.captureFailed.format(exception.message ?: "")
                    uiState = CameraUiState.LIVE
                }
            }
        )
    }

    /** 拍照（AUTO 模式下先预照 torch 再拍） */
    fun takePicture() {
        val capture = imageCapture ?: return

        // AUTO 模式：先开启 torch 预照 300ms，确保光线到位
        if (flashMode == FlashMode.AUTO) {
            torchOverride = true
            // 延迟 300ms 后执行拍照
            coroutineScope.launch {
                kotlinx.coroutines.delay(300)
                doTakePicture(capture)
            }
        } else {
            doTakePicture(capture)
        }
    }

    /** 对拍照图片执行检测 */
    fun performDetection() {
        val bitmap = capturedBitmap ?: return
        if (detectorState !is DetectorState.Ready) {
            errorText = strings.detectorNotReady
            return
        }
        isProcessing = true
        statusText = strings.detecting
        errorText = null
        coroutineScope.launch {
            try {
                val results = sharedViewModel.detect(bitmap, confThreshold)
                detections = results
                inferenceTimeMs = sharedViewModel.lastInferenceTimeMs.toInt()
                resultBitmap = sharedViewModel.drawDetectionBoxes(bitmap, results, confThreshold)
                statusText = strings.detectionComplete
            } catch (e: Exception) {
                errorText = strings.detectionFailed.format(e.message ?: "")
            } finally {
                isProcessing = false
            }
        }
    }

    /** 返回相机模式 */
    fun returnToLive() {
        uiState = CameraUiState.LIVE
        capturedBitmap = null
        resultBitmap = null
        detections = emptyList()
        inferenceTimeMs = 0
        statusText = null
        errorText = null
    }

    // === UI 渲染 ===

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = if (uiState == CameraUiState.LIVE) Color.Black else MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    if (uiState == CameraUiState.LIVE || uiState == CameraUiState.CAPTURING) {
                        Text(
                            text = stringResource(R.string.camera_title),
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.capture_result),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (uiState == CameraUiState.CAPTURED) {
                            returnToLive()
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(
                            imageVector = if (uiState == CameraUiState.CAPTURED)
                                Icons.Default.Refresh
                            else
                                Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (uiState == CameraUiState.CAPTURED) stringResource(R.string.back_to_camera) else stringResource(R.string.back),
                            tint = if (uiState == CameraUiState.LIVE) Color.White else MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (uiState == CameraUiState.LIVE) Color(0xFF1A1A1A) else MaterialTheme.colorScheme.surface,
                    titleContentColor = if (uiState == CameraUiState.LIVE) Color.White else MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        // 全屏预览浮层
        if (fullScreenPreview && resultBitmap != null) {
            FullScreenImageDialog(
                results = listOf(
                    ImageDetectionResult(
                        fileName = stringResource(R.string.captured_image),
                        originalBitmap = capturedBitmap,
                        resultBitmap = resultBitmap,
                        detections = detections,
                        inferenceTimeMs = inferenceTimeMs.toDouble()
                    )
                ),
                initialIndex = 0,
                onDismiss = { fullScreenPreview = false }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (uiState) {
                CameraUiState.LIVE, CameraUiState.CAPTURING -> {
                    // === 实时预览模式 ===
                    // 摄像头预览区域（填充剩余空间，黑色背景）
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(Color.Black)
                    ) {
                        if (!hasCameraPermission) {
                            // 相机权限未授予
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(text = stringResource(R.string.camera_permission_denied), color = Color.White)
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }) {
                                    Text(stringResource(R.string.grant_camera_permission))
                                }
                            }
                        } else if (detector != null && detectorState is DetectorState.Ready) {
                            // 相机预览 + 实时检测 + ImageCapture
                            key(cameraFacing, effectiveStrategy) {
                                CameraPreview(
                                    modifier = Modifier.fillMaxSize(),
                                    detector = detector,
                                    cameraFacing = cameraFacing,
                                    confThreshold = confThreshold,
                                    isDetecting = isDetecting,
                                    inputSize = inputSize,
                                    pipelineStrategy = effectiveStrategy,
                                    centerCrop = centerCrop,
                                    onDetectionStats = { /* 右上角已有帧率显示 */ },
                                    onImageCaptureReady = { capture ->
                                        imageCapture = capture
                                    },
                                    flashMode = when (flashMode) {
                                        FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
                                        FlashMode.ON -> ImageCapture.FLASH_MODE_ON
                                        FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
                                    },
                                    torchOverride = torchOverride
                                )
                            }

                            // 快门闪光效果（短暂闪白后快速消退，模拟原生相机快门）
                            if (flashAlpha.value > 0.01f) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.White.copy(alpha = flashAlpha.value))
                                )
                            }

                            // 闪光灯按钮（右上角）
                            // 实时检测模式：Off ↔ On；拍照模式：Off ↔ Auto ↔ On
                            IconButton(
                                onClick = {
                                    flashMode = when (flashMode) {
                                        FlashMode.OFF -> if (subMode == CameraSubMode.REALTIME) FlashMode.ON else FlashMode.AUTO
                                        FlashMode.AUTO -> FlashMode.ON
                                        FlashMode.ON -> FlashMode.OFF
                                    }
                                },
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(12.dp)
                            ) {
                                Icon(
                                    imageVector = when (flashMode) {
                                        FlashMode.OFF -> Icons.Default.FlashOff
                                        FlashMode.ON -> Icons.Default.FlashOn
                                        FlashMode.AUTO -> Icons.Default.FlashOn
                                    },
                                    contentDescription = stringResource(R.string.flash),
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        } else {
                            // 检测器未就绪
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = when (detectorState) {
                                        is DetectorState.Idle -> stringResource(R.string.select_model_first)
                                        is DetectorState.Loading -> stringResource(R.string.loading_model)
                                        is DetectorState.Error ->
                                            stringResource(R.string.model_load_failed_camera, (detectorState as DetectorState.Error).message)

                                        is DetectorState.Ready -> ""
                                    },
                                    color = Color(0xFFCCCCCC)
                                )
                            }
                        }

                        // 底部控件浮层（透明背景，预览画面可见）
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                .padding(vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // === 实时检测模式控件 ===
                            if (subMode == CameraSubMode.REALTIME) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 24.dp),
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 切换前后置摄像头
                                    IconButton(
                                        onClick = { cameraViewModel.switchCamera() },
                                        enabled = detectorState is DetectorState.Ready,
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(
                                            imageVector = CameraSwitchIcon,
                                            contentDescription = stringResource(R.string.switch_camera),
                                            tint = if (detectorState is DetectorState.Ready) Color.White else Color.White.copy(
                                                alpha = 0.38f
                                            ),
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }

                                    // 检测按钮（原生相机录像风格：白色外圈 + 红色内圈/方块动画）
                                    val detectInnerSize by animateDpAsState(
                                        targetValue = if (isDetecting) 28.dp else 60.dp,
                                        animationSpec = tween(durationMillis = 250),
                                        label = "detectInnerSize"
                                    )
                                    val detectCornerRadius by animateDpAsState(
                                        targetValue = if (isDetecting) 5.dp else 30.dp,
                                        animationSpec = tween(durationMillis = 250),
                                        label = "detectCorner"
                                    )
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(72.dp)
                                            .border(
                                                width = 4.dp,
                                                color = Color.White,
                                                shape = CircleShape
                                            )
                                            .padding(3.dp)
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null
                                            ) { cameraViewModel.toggleDetecting() }
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(detectInnerSize)
                                                .background(
                                                    color = Color(0xFFE53935),
                                                    shape = RoundedCornerShape(detectCornerRadius)
                                                )
                                        )
                                    }

                                    // xselect 管线策略选择芯片按钮（替换旧的 GL 开关）
                                    StrategyChipButton(
                                        effectiveStrategy = effectiveStrategy,
                                        strategyChain = strategyChain,
                                        availableStrategies = availableStrategies,
                                        isAutoMode = isAutoMode,
                                        onSelectStrategy = { strategy ->
                                            cameraViewModel.selectStrategy(strategy)
                                        },
                                        onSelectAuto = {
                                            cameraViewModel.selectAuto()
                                        }
                                    )
                                }
                            }

                            // === 拍照模式控件 ===
                            if (subMode == CameraSubMode.PHOTO) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 24.dp),
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 切换前后置摄像头
                                    IconButton(
                                        onClick = { cameraViewModel.switchCamera() },
                                        enabled = detectorState is DetectorState.Ready && !isCapturing,
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(
                                            imageVector = CameraSwitchIcon,
                                            contentDescription = stringResource(R.string.switch_camera),
                                            tint = if (detectorState is DetectorState.Ready && !isCapturing) Color.White else Color.White.copy(
                                                alpha = 0.38f
                                            ),
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }

                                    // 拍照按钮（原生快门风格：白色外圈 + 透明间隔 + 白色内圈）
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(72.dp)
                                            .border(
                                                width = 4.dp,
                                                color = Color.White,
                                                shape = CircleShape
                                            )
                                            .padding(3.dp)
                                            .padding(3.dp)
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null
                                            ) { takePicture() }
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(60.dp)
                                                .background(Color.White, CircleShape)
                                        )
                                    }

                                    // 占位，保持对称
                                    Spacer(modifier = Modifier.size(48.dp))
                                }
                            }

                            // 子模式选择器（原生相机风格底部文字标签）
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CameraSubMode.entries.forEachIndexed { index, mode ->
                                    val selected = subMode == mode
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null
                                            ) { switchSubMode(mode) }
                                            .padding(horizontal = 20.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = stringResource(mode.labelRes),
                                            fontSize = 13.sp,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (selected) Color(0xFFFFEB3B) else Color.White
                                        )
                                        Spacer(modifier = Modifier.height(3.dp))
                                        // 选中指示器小圆点
                                        Box(
                                            modifier = Modifier
                                                .size(4.dp)
                                                .background(
                                                    color = if (selected) Color(0xFFFFEB3B) else Color.Transparent,
                                                    shape = CircleShape
                                                )
                                        )
                                    }
                                    // 分隔点
                                    if (index < CameraSubMode.entries.size - 1) {
                                        Text(
                                            text = "·",
                                            fontSize = 14.sp,
                                            color = Color(0xFF555555),
                                            modifier = Modifier.padding(horizontal = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                CameraUiState.CAPTURED -> {
                    // === 拍照结果模式（来源于 CaptureImageScreen 的 ImagePreviewContent） ===
                    DetectorStateBanner(detectorState)

                    CaptureResultContent(
                        capturedBitmap = capturedBitmap!!,
                        resultBitmap = resultBitmap,
                        detections = detections,
                        inferenceTimeMs = inferenceTimeMs,
                        isProcessing = isProcessing,
                        statusText = statusText,
                        errorText = errorText,
                        onRetake = { returnToLive() },
                        onDetect = { performDetection() },
                        onOpenFullScreen = { fullScreenPreview = true }
                    )
                }
            }
        }
    }
}

/**
 * 拍照结果展示（来源于 CaptureImageScreen 的 ImagePreviewContent）
 */
@Composable
private fun CaptureResultContent(
    capturedBitmap: Bitmap,
    resultBitmap: Bitmap?,
    detections: List<DetectionResult>,
    inferenceTimeMs: Int,
    isProcessing: Boolean,
    statusText: String?,
    errorText: String?,
    onRetake: () -> Unit,
    onDetect: () -> Unit,
    onOpenFullScreen: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // 结果图片卡片
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .padding(16.dp)
                .then(
                    if (resultBitmap != null) Modifier.clickable { onOpenFullScreen() }
                    else Modifier
                )
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Image(
                    bitmap = (resultBitmap ?: capturedBitmap).asImageBitmap(),
                    contentDescription = stringResource(R.string.preview),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
                if (isProcessing) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }

        // 错误信息卡片
        errorText?.let {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
            ) {
                Text(text = it, modifier = Modifier.padding(16.dp), color = Color.Red)
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // 状态文本卡片
        statusText?.let {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Text(
                    text = it,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // 推理耗时卡片
        if (inferenceTimeMs > 0) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = stringResource(R.string.inference_time), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "%.2f ms".format(inferenceTimeMs.toDouble()),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // 无结果提示卡片
        if (resultBitmap != null && detections.isEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1))
            ) {
                Text(
                    text = stringResource(R.string.no_targets_hint),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFF57F17)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // 检测结果列表卡片
        if (detections.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.detection_results_count, detections.size),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    detections.forEach { detection ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = detection.className,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "${(detection.confidence * 100).toInt()}%",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.Gray
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // 底部操作按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = onRetake, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.back_to_camera))
            }
            if (resultBitmap == null) {
                Button(
                    onClick = onDetect,
                    modifier = Modifier.weight(1f),
                    enabled = !isProcessing
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.start_detect))
                }
            }
        }
    }
}
