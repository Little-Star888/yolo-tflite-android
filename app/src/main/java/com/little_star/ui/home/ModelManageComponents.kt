package com.little_star.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.little_star.R
import com.little_star.assets.LocalModelManager

// ═══════════════════════════════════════════════════════════════
// 导入/下载进度卡片
// ═══════════════════════════════════════════════════════════════

/**
 * 下载完成后导入中状态卡片
 */
@Composable
fun PostDownloadImportCard(
    importProgress: LocalModelManager.ImportProgress?,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = when {
                        importProgress?.isExtracting == true -> stringResource(R.string.extracting_zip)
                        else -> importProgress?.currentPackageName ?: stringResource(R.string.importing_model_package)
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            importProgress?.let { progress ->
                if (!progress.isExtracting) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { progress.progressPercent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.package_progress, progress.currentPackage, progress.totalPackages),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 远程下载进行中状态卡片
 */
@Composable
fun DownloadProgressCard(
    downloadingModelName: String?,
    downloadProgress: Int,
    downloadSpeed: Long,
    isDownloadPaused: Boolean,
    onResume: () -> Unit,
    onPause: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!isDownloadPaused && downloadProgress < 100) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Text(
                    text = when {
                        downloadProgress >= 100 -> stringResource(R.string.importing_ellipsis)
                        isDownloadPaused -> stringResource(R.string.download_paused, downloadingModelName ?: "")
                        else -> stringResource(R.string.downloading, downloadingModelName ?: "")
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (downloadProgress in 1..99 || isDownloadPaused) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { downloadProgress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${downloadProgress}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (downloadSpeed > 0) {
                        Text(
                            text = formatSpeed(downloadSpeed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (downloadProgress < 100) {
                        TextButton(
                            onClick = { if (isDownloadPaused) onResume() else onPause() }
                        ) {
                            Text(if (isDownloadPaused) stringResource(R.string.resume) else stringResource(R.string.pause))
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        TextButton(
                            onClick = onCancel,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 本地导入进行中状态卡片
 */
@Composable
fun LocalImportProgressCard(
    importProgress: LocalModelManager.ImportProgress?,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = when {
                        importProgress?.isExtracting == true -> stringResource(R.string.extracting_zip)
                        else -> importProgress?.currentPackageName ?: stringResource(R.string.importing_model_package)
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            importProgress?.let { progress ->
                if (!progress.isExtracting) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { progress.progressPercent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.package_progress, progress.currentPackage, progress.totalPackages),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// 模型管理功能卡片
// ═══════════════════════════════════════════════════════════════

/**
 * 模型管理三按钮卡片（本地导入 / 远程下载 / 管理删除）
 * 供有模型和无模型两个界面共用
 *
 * @param onImportClick 本地导入回调
 * @param onDownloadClick 远程下载回调
 * @param onDeleteClick 管理删除回调
 * @param isImporting 是否正在导入（包含本地导入和远程下载后的导入）
 * @param isLocalImporting 是否正在本地导入（远程下载进行中时为 false，不影响远程下载按钮）
 * @param isPostDownloadImporting 是否正在下载后导入中
 * @param isModelLoading 模型是否正在加载（有模型界面需要，无模型界面传 false）
 * @param hasManagedPackages 是否有可管理的导入包
 */
@Composable
fun ModelManageCards(
    onImportClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onDeleteClick: () -> Unit,
    isImporting: Boolean,
    isLocalImporting: Boolean = false,
    isPostDownloadImporting: Boolean = false,
    isModelLoading: Boolean = false,
    hasManagedPackages: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // 本地导入：任何导入中、下载后导入中、模型加载中 都禁用
    val importEnabled = !isImporting && !isPostDownloadImporting && !isModelLoading
    // 远程下载：仅本地导入中、下载后导入中、模型加载中 禁用（远程下载进行中仍可打开）
    val downloadEnabled = !isLocalImporting && !isPostDownloadImporting && !isModelLoading
    // 管理删除：任何导入中、模型加载中 禁用，且需要有可管理的包
    val deleteEnabled = !isImporting && hasManagedPackages && !isModelLoading

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 本地导入卡片
        Card(
            modifier = Modifier
                .weight(1f)
                .clickable(enabled = importEnabled) { onImportClick() },
            colors = CardDefaults.cardColors(
                containerColor = if (importEnabled) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = null,
                    tint = if (importEnabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.local_import),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (importEnabled) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // 远程下载卡片
        Card(
            modifier = Modifier
                .weight(1f)
                .clickable(enabled = downloadEnabled) { onDownloadClick() },
            colors = CardDefaults.cardColors(
                containerColor = if (downloadEnabled) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    tint = if (downloadEnabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.remote_download),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (downloadEnabled) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // 管理删除卡片
        Card(
            modifier = Modifier
                .weight(1f)
                .clickable(enabled = deleteEnabled) { onDeleteClick() },
            colors = CardDefaults.cardColors(
                containerColor = if (deleteEnabled) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = if (deleteEnabled) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.manage_delete),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (deleteEnabled) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
