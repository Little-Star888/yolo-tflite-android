package com.little_star.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.little_star.R
import com.little_star.model.ImageDetectionResult

/**
 * 检测结果卡片组件
 * 显示图片检测结果的详细信息
 * @param result 图片检测结果
 * @param onClick 点击回调（例如打开全屏查看）
 */
@Composable
fun DetectionResultCard(
    result: ImageDetectionResult,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 文件名 + 耗时
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = result.fileName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (result.inferenceTimeMs >= 0) "${"%.2f".format(result.inferenceTimeMs)}ms" else stringResource(R.string.failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 状态行
            when {
                result.error != null -> {
                    Text(
                        text = stringResource(R.string.error_prefix, result.error),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Red
                    )
                }

                result.detections.isNotEmpty() -> {
                    Text(
                        text = stringResource(R.string.detected_count, result.detections.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF4CAF50)
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

            Spacer(modifier = Modifier.height(8.dp))

            // 结果图片
            result.resultBitmap?.let { bitmap ->
                androidx.compose.foundation.Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.detection_result),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Fit
                )
            } ?: result.originalBitmap?.let { bitmap ->
                androidx.compose.foundation.Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.original_image),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Fit
                )
            }

            // 检测详情
            if (result.detections.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 150.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    result.detections.forEach { det ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 1.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = det.className,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${(det.confidence * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
