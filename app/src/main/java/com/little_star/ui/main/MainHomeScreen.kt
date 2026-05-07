package com.little_star.ui.main

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.little_star.R

/**
 * 主首页：功能选择
 *
 * 顶部居中标题"YOLO 识别"，下方列出 5 种 YOLO 功能卡片：
 * - 目标检测、关键点检测、实例分割、图像分类、旋转框检测
 *
 * @param onNavigateToDetection 导航到目标检测首页
 * @param onNavigateToKeypoint 导航到关键点检测首页
 * @param onNavigateToSegmentation 导航到实例分割首页
 * @param onNavigateToClassification 导航到图像分类首页
 * @param onNavigateToOrientedBbox 导航到旋转框检测首页
 * @param modifier 修饰符
 */
@Composable
fun MainHomeScreen(
    onNavigateToDetection: () -> Unit,
    onNavigateToKeypoint: () -> Unit,
    onNavigateToSegmentation: () -> Unit,
    onNavigateToClassification: () -> Unit,
    onNavigateToOrientedBbox: () -> Unit,
    onNavigateToSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // 标题栏：标题 + 设置按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.main_home_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onNavigateToSettings) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = stringResource(R.string.settings),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 功能卡片列表（纵向紧凑排列）
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FeatureCard(
                icon = { Icon(Icons.Default.Visibility, contentDescription = null) },
                title = stringResource(R.string.task_detection),
                description = stringResource(R.string.task_detection_desc),
                onClick = onNavigateToDetection
            )

            FeatureCard(
                icon = { Icon(Icons.Default.GpsFixed, contentDescription = null) },
                title = stringResource(R.string.task_keypoint),
                description = stringResource(R.string.task_keypoint_desc),
                onClick = onNavigateToKeypoint
            )

            FeatureCard(
                icon = { Icon(Icons.Default.CenterFocusStrong, contentDescription = null) },
                title = stringResource(R.string.task_segmentation),
                description = stringResource(R.string.task_segmentation_desc),
                onClick = onNavigateToSegmentation
            )

            FeatureCard(
                icon = { Icon(Icons.Default.Category, contentDescription = null) },
                title = stringResource(R.string.task_classification),
                description = stringResource(R.string.task_classification_desc),
                onClick = onNavigateToClassification
            )

            FeatureCard(
                icon = { Icon(Icons.Default.ImageSearch, contentDescription = null) },
                title = stringResource(R.string.task_oriented_bbox),
                description = stringResource(R.string.task_oriented_bbox_desc),
                onClick = onNavigateToOrientedBbox
            )
        }
    }
}

/**
 * 功能卡片
 */
@Composable
private fun FeatureCard(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                icon()
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(verticalArrangement = Arrangement.Center) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
