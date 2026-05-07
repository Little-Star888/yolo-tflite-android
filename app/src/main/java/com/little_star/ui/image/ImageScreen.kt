package com.little_star.ui.image

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.little_star.ui.components.DetectorStateBanner
import com.little_star.ui.components.FullScreenImageDialog
import com.little_star.viewmodel.ImageViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 单张图片识别页面
 * 选择单张图片进行目标检测，显示检测结果和带框图片
 *
 * @param sharedViewModel 共享检测器 ViewModel（用于检测）
 * @param onBack 返回回调
 * @param modifier 修饰符
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageScreen(
    sharedViewModel: com.little_star.viewmodel.SharedDetectorViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    imageViewModel: ImageViewModel = viewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val imageUri by imageViewModel.imageUri.collectAsState()
    val originalBitmap by imageViewModel.originalBitmap.collectAsState()
    val resultBitmap by imageViewModel.resultBitmap.collectAsState()
    val detections by imageViewModel.detections.collectAsState()
    val isProcessing by imageViewModel.isProcessing.collectAsState()
    val statusText by imageViewModel.statusText.collectAsState()
    val error by imageViewModel.error.collectAsState()
    val inferenceTimeMs by imageViewModel.inferenceTimeMs.collectAsState()
    val detectorState by sharedViewModel.detectorState.collectAsState()

    // 全屏预览状态
    var fullScreenPreview by remember { mutableStateOf(false) }

    // 图片选择器
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            imageViewModel.setImageUri(it)
            // 在后台线程加载图片
            coroutineScope.launch {
                try {
                    val inputStream = context.contentResolver.openInputStream(it)
                    val bitmap = withContext(Dispatchers.IO) {
                        BitmapFactory.decodeStream(inputStream)
                    }
                    inputStream?.close()
                    bitmap?.let { bmp ->
                        imageViewModel.setOriginalBitmap(bmp)
                    }
                } catch (e: Exception) {
                    imageViewModel.clearResults()
                }
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.image_title),
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
        // 全屏图片预览对话框
        if (fullScreenPreview && resultBitmap != null) {
            FullScreenImageDialog(
                results = listOf(
                    ImageDetectionResult(
                        fileName = stringResource(R.string.image_label),
                        originalBitmap = originalBitmap,
                        resultBitmap = resultBitmap,
                        detections = detections,
                        inferenceTimeMs = inferenceTimeMs
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
            // ── 滚动内容区（占据中间所有剩余空间）─────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                // 检测器状态提示
                DetectorStateBanner(detectorState)

                // 选择图片按钮
                Button(
                    onClick = { imagePickerLauncher.launch("image/*") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    enabled = !isProcessing && detectorState is com.little_star.viewmodel.DetectorState.Ready
                ) {
                    Text(stringResource(R.string.select_image))
                }

                // 状态显示
                statusText?.let {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
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
                    }
                }

                // 图片预览区域
                if (originalBitmap != null) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .height(300.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (resultBitmap != null) {
                                        Modifier.clickable { fullScreenPreview = true }
                                    } else {
                                        Modifier
                                    }
                                )
                        ) {
                            val displayBitmap = resultBitmap ?: originalBitmap
                            Image(
                                bitmap = displayBitmap!!.asImageBitmap(),
                                contentDescription = stringResource(R.string.image_preview),
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )

                            // 检测中进度指示
                            if (isProcessing) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        CircularProgressIndicator()
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = stringResource(R.string.detecting),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 检测按钮
                    if (!isProcessing && resultBitmap == null) {
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    imageViewModel.performDetection(
                                        context = context,
                                        detectLambda = { bitmap, _ ->
                                            sharedViewModel.detect(bitmap, sharedViewModel.confThreshold.value)
                                        },
                                        drawBoxesLambda = { bitmap, results ->
                                            sharedViewModel.drawDetectionBoxes(bitmap, results)
                                        },
                                        getInferenceTimeMs = { sharedViewModel.lastInferenceTimeMs }
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                        ) {
                            Text(stringResource(R.string.start_detect))
                        }
                    }

                    // 推理耗时
                    if (inferenceTimeMs > 0) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = stringResource(R.string.inference_time),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "%.2f ms".format(inferenceTimeMs),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // 检测结果列表
                    if (detections.isNotEmpty()) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = stringResource(R.string.detection_results_count, detections.size),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 200.dp)
                                        .verticalScroll(rememberScrollState())
                                ) {
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
                        }
                    }

                    // 错误信息
                    error?.let { errMsg ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFFFFEBEE)
                            )
                        ) {
                            Text(
                                text = stringResource(R.string.error_prefix, errMsg),
                                modifier = Modifier.padding(16.dp),
                                color = Color.Red
                            )
                        }
                    }
                } else if (detectorState is com.little_star.viewmodel.DetectorState.Ready) {
                    // 空状态提示（居中显示）
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.please_select_image),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ── 底部固定按钮区 ────────────────────────────────────
            // 重新识别按钮（使用原图重新执行检测）
            if (resultBitmap != null && !isProcessing) {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            imageViewModel.performDetection(
                                context = context,
                                detectLambda = { bitmap, _ ->
                                    sharedViewModel.detect(bitmap, sharedViewModel.confThreshold.value)
                                },
                                drawBoxesLambda = { bitmap, results ->
                                    sharedViewModel.drawDetectionBoxes(bitmap, results)
                                },
                                getInferenceTimeMs = { sharedViewModel.lastInferenceTimeMs }
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text(stringResource(R.string.redetect))
                }
            }
        }
    }
}
