package com.little_star.ui.directory

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.little_star.R
import com.little_star.model.ImageDetectionResult
import com.little_star.ui.components.DetectionResultCard
import com.little_star.ui.components.DetectorStateBanner
import com.little_star.ui.components.FullScreenImageDialog
import com.little_star.viewmodel.DirectoryViewModel
import com.little_star.viewmodel.SharedDetectorViewModel
import kotlinx.coroutines.launch

/**
 * 图片目录识别页面
 * 批量检测目录下所有图片
 *
 * @param sharedViewModel 共享检测器 ViewModel（用于检测）
 * @param onBack 返回回调
 * @param modifier 修饰符
 * @param directoryViewModel DirectoryViewModel 实例
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectoryScreen(
    sharedViewModel: SharedDetectorViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    directoryViewModel: DirectoryViewModel = viewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val isProcessing by directoryViewModel.isProcessing.collectAsState()
    val statusText by directoryViewModel.statusText.collectAsState()
    val processingProgress by directoryViewModel.processingProgress.collectAsState()
    val selectedDirectoryName by directoryViewModel.selectedDirectoryName.collectAsState()
    val results by directoryViewModel.results.collectAsState()
    val thumbnails by directoryViewModel.thumbnails.collectAsState()
    val detectorState by sharedViewModel.detectorState.collectAsState()

    var fullScreenIndex by remember { mutableStateOf<Int>(-1) }

    // 列表滚动状态（用于实时滚动：最新结果在顶部时自动滚动）
    val lazyListState = rememberLazyListState()

    // 实时滚动逻辑：新结果到达时，如果用户在顶部则自动滚动到最新项
    // 如果用户正在往下滑动查看历史，则保持不动
    LaunchedEffect(results) {
        if (lazyListState.firstVisibleItemIndex > 0) {
            // 用户正在查看历史数据，不打断，列表会自动扩展显示新结果
            return@LaunchedEffect
        }
        // 用户在顶部位置，滚动到最新结果
        lazyListState.scrollToItem(0)
    }

    // 目录选择器
    val directoryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: android.net.Uri? ->
        uri?.let {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(it, takeFlags)
            directoryViewModel.processDirectoryFromUri(
                context = context,
                treeUri = it,
                detectLambda = { fileName, bitmap, _ ->
                    // 使用 SharedDetectorViewModel 进行检测
                    val detections = sharedViewModel.detect(bitmap, sharedViewModel.confThreshold.value)
                    val inferenceTimeMs = sharedViewModel.lastInferenceTimeMs

                    ImageDetectionResult(
                        fileName = fileName,
                        originalBitmap = bitmap,
                        resultBitmap = null, // 稍后绘制
                        detections = detections,
                        inferenceTimeMs = inferenceTimeMs
                    )
                },
                drawBoxesLambda = { bitmap, detections ->
                    sharedViewModel.drawDetectionBoxes(bitmap, detections)
                }
            )
        }
    }

    // 全屏图片查看（只传入有缩略图的结果，过滤掉无图片的项避免黑屏）
    if (fullScreenIndex >= 0 && results.isNotEmpty()) {
        // 筛选有缩略图的结果，同时保留原始索引用于映射 initialIndex
        val validEntries = results.mapIndexedNotNull { index, item ->
            val thumb = thumbnails[item.fileName]
            if (thumb != null) {
                index to ImageDetectionResult(
                    fileName = item.fileName,
                    originalBitmap = null,
                    resultBitmap = thumb,
                    detections = item.detections,
                    inferenceTimeMs = item.inferenceTimeMs,
                    error = item.error
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

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.directory_title),
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 检测器状态提示
            DetectorStateBanner(detectorState)

            // 选择目录按钮
            Button(
                onClick = { directoryPickerLauncher.launch(null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                enabled = !isProcessing && detectorState is com.little_star.viewmodel.DetectorState.Ready
            ) {
                Text(stringResource(R.string.select_image_directory))
            }

            // 空状态提示（未选择目录且无结果时显示）
            if (selectedDirectoryName == null && results.isEmpty() && !isProcessing) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.please_select_directory),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 当前目录信息
            selectedDirectoryName?.let { name ->
                Text(
                    text = stringResource(R.string.directory_name, name),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 状态卡片
            if (statusText != null || isProcessing) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
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
                            Text(statusText ?: "")
                        }

                        // 进度条
                        val (done, total) = processingProgress
                        if (total > 0) {
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { done.toFloat() / total },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = "$done / $total",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 检测结果列表（包含头部统计信息）
            if (results.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                    state = lazyListState,  // 关联滚动状态，实现实时滚动
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 头部统计信息
                    item(key = "header") {
                        val detected = results.count { it.detections.isNotEmpty() }
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.directory_stats, results.size, detected),
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    itemsIndexed(
                        items = results,
                        key = { _, result -> result.fileName }
                    ) { index, item ->
                        DetectionResultCard(
                            result = ImageDetectionResult(
                                fileName = item.fileName,
                                originalBitmap = null,
                                resultBitmap = thumbnails[item.fileName],
                                detections = item.detections,
                                inferenceTimeMs = item.inferenceTimeMs,
                                error = item.error
                            ),
                            onClick = {
                                // 只有具备结果缩略图时才可点击预览
                                if (thumbnails[item.fileName] != null) {
                                    fullScreenIndex = index
                                }
                            }
                        )
                    }
                }
            }

            // 重新识别按钮（对当前目录重新执行检测）
            if (results.isNotEmpty() && !isProcessing) {
                Button(
                    onClick = {
                        directoryViewModel.currentDirectoryUri?.let { uri ->
                            coroutineScope.launch {
                                directoryViewModel.processDirectoryFromUri(
                                    context = context,
                                    treeUri = uri,
                                    detectLambda = { fileName, bitmap, _ ->
                                        val detections = sharedViewModel.detect(bitmap, sharedViewModel.confThreshold.value)
                                        val inferenceTimeMs = sharedViewModel.lastInferenceTimeMs
                                        ImageDetectionResult(
                                            fileName = fileName,
                                            originalBitmap = bitmap,
                                            resultBitmap = null,
                                            detections = detections,
                                            inferenceTimeMs = inferenceTimeMs
                                        )
                                    },
                                    drawBoxesLambda = { bitmap, detections ->
                                        sharedViewModel.drawDetectionBoxes(bitmap, detections)
                                    }
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text(stringResource(R.string.redetect))
                }
            }
        }
    }
}
