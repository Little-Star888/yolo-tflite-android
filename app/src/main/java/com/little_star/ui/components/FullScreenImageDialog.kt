package com.little_star.ui.components

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.little_star.R
import com.little_star.model.ImageDetectionResult
import kotlinx.coroutines.launch

// 缩放范围常量
private const val MIN_SCALE = 0.5f
private const val MAX_SCALE = 5.0f
private const val DOUBLE_TAP_SCALE = 2.5f   // 双击放大目标缩放值
private const val ANIM_DURATION_MS = 300    // 动画持续时间（毫秒）

/**
 * 全屏图片查看对话框
 * 支持 Pager 左右滑动查看多张图片的检测结果
 * 支持双指捏合缩放、单指拖动平移、双击快速放大/还原
 *
 * @param results      所有检测结果列表
 * @param initialIndex 初始显示的图片索引
 * @param onDismiss    关闭对话框回调
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FullScreenImageDialog(
    results: List<ImageDetectionResult>,
    initialIndex: Int,
    onDismiss: () -> Unit
) {
    // 初始化 Pager 状态
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, results.size - 1),
        pageCount = { results.size }
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black.copy(alpha = 0.95f)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 标题栏：显示文件名和页码
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = results[pagerState.currentPage].fileName,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${pagerState.currentPage + 1} / ${results.size}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }

                    // 图片 Pager —— 每页独立管理缩放和平移状态
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) { page ->
                        val result = results[page]

                        // 每页独立维护缩放、偏移状态
                        var scale by remember { mutableFloatStateOf(1f) }
                        var offsetX by remember { mutableFloatStateOf(0f) }
                        var offsetY by remember { mutableFloatStateOf(0f) }

                        // ✅ 新增：记录 Box 的实际像素尺寸，用于计算双击放大的正确中心点
                        var boxSize by remember { mutableStateOf(IntSize.Zero) }

                        // 用于双击动画的 Animatable
                        val animatableScale = remember { Animatable(1f) }
                        val animatableOffsetX = remember { Animatable(0f) }
                        val animatableOffsetY = remember { Animatable(0f) }
                        val coroutineScope = rememberCoroutineScope()

                        // 当页面切换时，重置缩放和偏移
                        if (page != pagerState.currentPage) {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        }

                        // 获取当前显示的 Bitmap（优先使用检测结果图，其次使用原图）
                        val bitmap = result.resultBitmap ?: result.originalBitmap
                        if (bitmap != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    // ✅ 新增：监听尺寸变化，保存组件实际大小
                                    .onSizeChanged { boxSize = it }
                            ) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = stringResource(R.string.detection_result),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            scaleX = scale
                                            scaleY = scale
                                            translationX = offsetX
                                            translationY = offsetY
                                        }
                                        // 手势处理：双指捏合缩放 + 单指平移（放大时）
                                        .pointerInput(scale) {
                                            awaitEachGesture {
                                                var lastSpan = 0f
                                                var lastCentroid = Offset.Zero

                                                do {
                                                    val event = awaitPointerEvent()
                                                    val changes = event.changes

                                                    if (changes.size > 1) {
                                                        // 双指手势：捏合缩放 + 平移
                                                        val centroid = changes
                                                            .map { it.position }
                                                            .reduce { a, b -> a + b } / changes.size.toFloat()
                                                        val span = (changes[0].position - changes[1].position).getDistance()

                                                        if (lastSpan > 0f && span > 0f) {
                                                            val zoomChange = span / lastSpan
                                                            val newScale = (scale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
                                                            val panChange = centroid - lastCentroid
                                                            val scaleRatio = 1f - newScale / scale
                                                            val newOffsetX = offsetX + centroid.x * scaleRatio + panChange.x * newScale
                                                            val newOffsetY = offsetY + centroid.y * scaleRatio + panChange.y * newScale

                                                            scale = newScale
                                                            offsetX = newOffsetX
                                                            offsetY = newOffsetY
                                                        }

                                                        lastSpan = span
                                                        lastCentroid = centroid
                                                        changes.forEach { it.consume() }
                                                    } else if (scale > 1f) {
                                                        // 单指手势在放大后：拖动平移（与双指平移保持一致的位移系数）
                                                        val panChange = changes[0].position - changes[0].previousPosition
                                                        offsetX += panChange.x * scale
                                                        offsetY += panChange.y * scale
                                                        changes.forEach { it.consume() }
                                                    }
                                                } while (changes.any { it.pressed })
                                            }
                                        }
                                        // 双击手势：放大到点击位置 / 还原
                                        .pointerInput(scale) {
                                            detectTapGestures(
                                                onDoubleTap = { tapOffset ->
                                                    coroutineScope.launch {
                                                        if (scale > 1.05f) {
                                                            // 已放大 → 动画还原到初始状态
                                                            animateToTarget(
                                                                animatableScale = animatableScale,
                                                                animatableOffsetX = animatableOffsetX,
                                                                animatableOffsetY = animatableOffsetY,
                                                                targetScale = 1f,
                                                                targetOffsetX = 0f,
                                                                targetOffsetY = 0f,
                                                                currentScale = scale,
                                                                currentOffsetX = offsetX,
                                                                currentOffsetY = offsetY,
                                                                onUpdate = { s, ox, oy ->
                                                                    scale = s
                                                                    offsetX = ox
                                                                    offsetY = oy
                                                                }
                                                            )
                                                        } else {
                                                            // 未放大 → 以点击点为中心放大
                                                            //
                                                            // ✅ 修复说明：
                                                            // graphicsLayer 的缩放原点是组件中心 (centerX, centerY)，
                                                            // 而不是 (0, 0)。要让 tapOffset 在缩放前后保持屏幕位置不变，
                                                            // 需要基于组件中心来计算偏移量：
                                                            //
                                                            //   targetOffset = (tapOffset - center) * (1 - targetScale)
                                                            //
                                                            // 原错误公式：tapOffset.x * (1f - targetScale)
                                                            // 隐含了 center = (0,0) 的错误假设，导致点击非中心区域时偏移错误。
                                                            val targetScale = DOUBLE_TAP_SCALE
                                                            val centerX = boxSize.width / 2f
                                                            val centerY = boxSize.height / 2f
                                                            val targetOffsetX = (tapOffset.x - centerX) * (1f - targetScale)
                                                            val targetOffsetY = (tapOffset.y - centerY) * (1f - targetScale)

                                                            animateToTarget(
                                                                animatableScale = animatableScale,
                                                                animatableOffsetX = animatableOffsetX,
                                                                animatableOffsetY = animatableOffsetY,
                                                                targetScale = targetScale,
                                                                targetOffsetX = targetOffsetX,
                                                                targetOffsetY = targetOffsetY,
                                                                currentScale = scale,
                                                                currentOffsetX = offsetX,
                                                                currentOffsetY = offsetY,
                                                                onUpdate = { s, ox, oy ->
                                                                    scale = s
                                                                    offsetX = ox
                                                                    offsetY = oy
                                                                }
                                                            )
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                )
                            }
                        }
                    }

                    // 底部检测信息区域
                    val currentResult = results[pagerState.currentPage]
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        when {
                            currentResult.error != null -> {
                                Text(
                                    text = stringResource(R.string.error_prefix, currentResult.error),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Red
                                )
                            }

                            currentResult.detections.isNotEmpty() -> {
                                Text(
                                    text = stringResource(R.string.detected_count, currentResult.detections.size),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFF4CAF50)
                                )
                                Text(
                                    text = "%.2f".format(currentResult.inferenceTimeMs) + "ms",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.7f)
                                )
                            }

                            else -> {
                                Text(
                                    text = stringResource(R.string.no_targets),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.Gray
                                )
                            }
                        }
                    }

                    // 底部按钮区域：保存图片 + 关闭
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val context = LocalContext.current
                        // 保存图片按钮
                        OutlinedButton(
                            onClick = {
                                val bitmap = currentResult.resultBitmap ?: currentResult.originalBitmap
                                if (bitmap == null) {
                                    Toast.makeText(context, context.getString(R.string.save_image_no_bitmap), Toast.LENGTH_SHORT).show()
                                    return@OutlinedButton
                                }
                                val success = saveBitmapToAlbum(context, bitmap, currentResult.fileName)
                                Toast.makeText(
                                    context,
                                    if (success) context.getString(R.string.save_image_success) else context.getString(R.string.save_image_failed),
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) {
                            Text(stringResource(R.string.save_image))
                        }
                        // 关闭按钮
                        Button(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.close_dialog))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 使用动画平滑过渡到目标缩放值和偏移量。
 * 三个轴（scale / offsetX / offsetY）并行执行动画，互相读取对方的实时值更新状态，
 * 保证动画过程中 graphicsLayer 每帧都能获取到三轴的最新值。
 */
private suspend fun animateToTarget(
    animatableScale: Animatable<Float, *>,
    animatableOffsetX: Animatable<Float, *>,
    animatableOffsetY: Animatable<Float, *>,
    targetScale: Float,
    targetOffsetX: Float,
    targetOffsetY: Float,
    currentScale: Float,
    currentOffsetX: Float,
    currentOffsetY: Float,
    onUpdate: (scale: Float, offsetX: Float, offsetY: Float) -> Unit
) {
    // 先将 Animatable 快照到当前值，避免从上次动画终点继续
    animatableScale.snapTo(currentScale)
    animatableOffsetX.snapTo(currentOffsetX)
    animatableOffsetY.snapTo(currentOffsetY)

    kotlinx.coroutines.coroutineScope {
        launch {
            animatableScale.animateTo(
                targetValue = targetScale,
                animationSpec = tween(durationMillis = ANIM_DURATION_MS)
            ) {
                onUpdate(value, animatableOffsetX.value, animatableOffsetY.value)
            }
        }
        launch {
            animatableOffsetX.animateTo(
                targetValue = targetOffsetX,
                animationSpec = tween(durationMillis = ANIM_DURATION_MS)
            ) {
                onUpdate(animatableScale.value, value, animatableOffsetY.value)
            }
        }
        launch {
            animatableOffsetY.animateTo(
                targetValue = targetOffsetY,
                animationSpec = tween(durationMillis = ANIM_DURATION_MS)
            ) {
                onUpdate(animatableScale.value, animatableOffsetX.value, value)
            }
        }
    }
}

/**
 * 将 Bitmap 保存到系统相册（Pictures/YOLO 目录）
 * 使用 MediaStore API，Android 10+ 无需存储权限
 */
private fun saveBitmapToAlbum(context: android.content.Context, bitmap: Bitmap, fileName: String): Boolean {
    return try {
        // 生成保存文件名：基于原始文件名添加 _result 后缀
        val dotIndex = fileName.lastIndexOf('.')
        val baseName = if (dotIndex > 0) fileName.substring(0, dotIndex) else fileName
        val extension = if (dotIndex > 0) fileName.substring(dotIndex) else ".jpg"
        val saveName = "${baseName}_result$extension"

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, saveName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/YOLO")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: return false

        resolver.openOutputStream(uri)?.use { outputStream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
        } ?: return false

        // 标记写入完成
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
        }
        true
    } catch (_: Exception) {
        false
    }
}