package com.little_star.ui.video

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.little_star.R
import com.little_star.model.ImageDetectionResult
import com.little_star.model.VideoDetectionResult
import com.little_star.ui.components.DetectorStateBanner
import com.little_star.ui.components.FullScreenImageDialog
import com.little_star.viewmodel.SharedDetectorViewModel
import com.little_star.viewmodel.VideoViewModel

/**
 * 视频识别页面
 * 逐帧检测视频中的目标
 *
 * @param sharedViewModel 共享检测器 ViewModel（用于检测）
 * @param onBack 返回回调
 * @param modifier 修饰符
 * @param videoViewModel VideoViewModel 实例
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoScreen(
    sharedViewModel: SharedDetectorViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    videoViewModel: VideoViewModel = viewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val videoUri by videoViewModel.videoUri.collectAsState()
    val isProcessing by videoViewModel.isProcessing.collectAsState()
    val statusText by videoViewModel.statusText.collectAsState()
    val progress by videoViewModel.progress.collectAsState()
    val results by videoViewModel.results.collectAsState()
    val samplingIntervalMs by videoViewModel.samplingIntervalMs.collectAsState()
    val intervalUnit by videoViewModel.intervalUnit.collectAsState()
    val saveFrameBitmaps by videoViewModel.saveFrameBitmaps.collectAsState()
    val detectorState by sharedViewModel.detectorState.collectAsState()

    // 全屏预览状态（-1 表示不显示）
    var fullScreenIndex by remember { mutableStateOf(-1) }

    // 保存帧图片提示对话框
    var showSaveFrameTip by remember { mutableStateOf(false) }
    if (showSaveFrameTip) {
        AlertDialog(
            onDismissRequest = { showSaveFrameTip = false },
            title = { Text(stringResource(R.string.save_frame_tip_title)) },
            text = {
                Text(stringResource(R.string.save_frame_tip_message))
            },
            confirmButton = {
                TextButton(onClick = { showSaveFrameTip = false }) {
                    Text(stringResource(R.string.got_it))
                }
            }
        )
    }

    // 视频选择器
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            videoViewModel.setVideoUri(it)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.video_title),
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        // 全屏图片预览对话框（只传入有 Bitmap 的帧，过滤掉无图片的帧避免黑屏）
        if (fullScreenIndex >= 0 && results.isNotEmpty() && fullScreenIndex < results.size) {
            // 筛选有 resultBitmap 的帧，同时保留原始索引用于映射 initialIndex
            val validEntries = results.mapIndexedNotNull { index, frameResult ->
                if (frameResult.resultBitmap != null) {
                    index to ImageDetectionResult(
                        fileName = "${stringResource(R.string.frame_number, frameResult.frameIndex)} (${frameResult.timestampMs / 1000}${stringResource(R.string.seconds_suffix)})",
                        originalBitmap = null,
                        resultBitmap = frameResult.resultBitmap,
                        detections = frameResult.detections,
                        inferenceTimeMs = frameResult.inferenceTimeMs
                    )
                } else null
            }
            // 将点击的原始索引映射到筛选后列表中的位置
            val mappedIndex = validEntries.indexOfFirst { (origIndex, _) -> origIndex == fullScreenIndex }.coerceAtLeast(0)
            if (validEntries.isNotEmpty()) {
                FullScreenImageDialog(
                    results = validEntries.map { it.second },
                    initialIndex = mappedIndex,
                    onDismiss = { fullScreenIndex = -1 }
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // 检测器状态提示
            DetectorStateBanner(detectorState)

            // 选择视频按钮
            Button(
                onClick = { videoPickerLauncher.launch("video/*") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                enabled = !isProcessing && detectorState is com.little_star.viewmodel.DetectorState.Ready
            ) {
                Text(stringResource(R.string.select_video))
            }

            // 设置区域
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.detection_settings),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // 采样间隔
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.detection_interval),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        // 单位切换
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            FilterChip(
                                selected = intervalUnit == VideoViewModel.IntervalUnit.MS,
                                onClick = { videoViewModel.setIntervalUnit(VideoViewModel.IntervalUnit.MS) },
                                label = { Text("ms", style = MaterialTheme.typography.labelSmall) },
                                enabled = !isProcessing,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                )
                            )
                            FilterChip(
                                selected = intervalUnit == VideoViewModel.IntervalUnit.SECONDS,
                                onClick = { videoViewModel.setIntervalUnit(VideoViewModel.IntervalUnit.SECONDS) },
                                label = { Text("s", style = MaterialTheme.typography.labelSmall) },
                                enabled = !isProcessing,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                )
                            )
                        }
                    }

                    // 根据单位显示不同的滑块和文本
                    when (intervalUnit) {
                        VideoViewModel.IntervalUnit.MS -> {
                            Text(
                                text = stringResource(R.string.interval_ms, samplingIntervalMs),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Slider(
                                value = samplingIntervalMs.toFloat(),
                                onValueChange = { videoViewModel.setSamplingIntervalMs(it.toInt()) },
                                valueRange = 1f..5000f,
                                steps = 99,
                                enabled = !isProcessing,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        VideoViewModel.IntervalUnit.SECONDS -> {
                            val seconds = samplingIntervalMs / 1000
                            Text(
                                text = stringResource(R.string.interval_seconds, seconds),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Slider(
                                value = seconds.toFloat().coerceIn(1f, 30f),
                                onValueChange = { videoViewModel.setSamplingIntervalMs(it.toInt() * 1000) },
                                valueRange = 1f..30f,
                                steps = 28,
                                enabled = !isProcessing,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    // 是否保存帧图片
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.save_frame_images),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = stringResource(R.string.view_format_guide),
                                modifier = Modifier
                                    .size(14.dp)
                                    .clickable { showSaveFrameTip = true },
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Switch(
                            checked = saveFrameBitmaps,
                            onCheckedChange = { videoViewModel.setSaveFrameBitmaps(it) },
                            enabled = !isProcessing,
                            modifier = Modifier.height(20.dp),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            )
                        )
                    }
                }
            }

            // 开始/取消按钮
            if (videoUri != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isProcessing) {
                        Button(
                            onClick = { videoViewModel.cancelProcessing() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text(stringResource(R.string.cancel_detection))
                        }
                    } else {
                        Button(
                            onClick = {
                                videoViewModel.startProcessing(
                                    context = context,
                                    inputSize = sharedViewModel.currentInputSize,
                                    detectLambda = { bitmap, _ ->
                                        sharedViewModel.detect(bitmap, sharedViewModel.confThreshold.value)
                                    },
                                    drawBoxesLambda = if (saveFrameBitmaps) {
                                        { bitmap, results ->
                                            sharedViewModel.drawDetectionBoxes(bitmap, results)
                                        }
                                    } else null
                                )
                            },
                            modifier = Modifier.weight(1f),
                            enabled = detectorState is com.little_star.viewmodel.DetectorState.Ready
                        ) {
                            Text(stringResource(R.string.start_detection))
                        }
                    }
                }
            }

            // 状态显示
            statusText?.let {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isProcessing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.size(16.dp))
                            }
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        // 进度条（时间格式）
                        val (currentSec, totalSec) = progress
                        if (totalSec > 0) {
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { currentSec.toFloat() / totalSec },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = stringResource(R.string.seconds_progress, currentSec, totalSec),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 统计信息
            if (results.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.detection_statistics),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.frames_processed, results.size),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(R.string.total_detections, videoViewModel.totalDetections),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (videoViewModel.averageInferenceTime > 0) {
                            Text(
                                text = stringResource(R.string.avg_inference_time).format(videoViewModel.averageInferenceTime),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

            // 帧结果列表（使用 LazyColumn 避免全部渲染导致 ANR）
            if (results.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.frame_results, results.size),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = results,
                        key = { it.frameIndex }
                    ) { frameResult ->
                        FrameResultCard(
                            result = frameResult,
                            onClick = {
                                // 只有同时具备结果图片和检测目标时才可点击预览
                                if (frameResult.resultBitmap != null && frameResult.detections.isNotEmpty()) {
                                    fullScreenIndex = results.indexOf(frameResult)
                                }
                            }
                        )
                    }
                }
            }

        }
    }
}

/**
 * 帧检测结果卡片
 * @param result 帧检测结果
 * @param onClick 点击回调（用于全屏预览）
 */
@Composable
private fun FrameResultCard(
    result: VideoDetectionResult,
    onClick: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 帧信息
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.frame_number, result.frameIndex),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = stringResource(R.string.frame_timestamp, result.timestampMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                    Text(
                        text = stringResource(R.string.inference_time_ms).format(result.inferenceTimeMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // 检测数量
            Text(
                text = stringResource(R.string.detected_targets, result.detections.size),
                style = MaterialTheme.typography.bodySmall,
                color = if (result.detections.isNotEmpty()) Color(0xFF4CAF50) else Color.Gray
            )

            // 帧图片（如果有），点击可全屏预览
            result.resultBitmap?.let { bitmap ->
                Spacer(modifier = Modifier.height(8.dp))
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.detection_result),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clickable { onClick() },
                    contentScale = ContentScale.Fit
                )
            }

            // 检测详情（默认折叠，点击展开）
            if (result.detections.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                var expanded by remember { mutableStateOf(false) }
                val showCount = if (expanded) result.detections.size else minOf(5, result.detections.size)
                result.detections.take(showCount).forEach { detection ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = detection.className,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "${(detection.confidence * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                if (result.detections.size > 5) {
                    Text(
                        text = if (expanded) stringResource(R.string.collapse) else stringResource(R.string.more_targets, result.detections.size - 5),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFFA000),
                        modifier = Modifier.clickable { expanded = !expanded }
                    )
                }
            }
        }
    }
}
