package com.little_star.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.little_star.R
import com.little_star.assets.RemoteModelManager

/**
 * 远程下载模型对话框
 * 供 HomeScreen 的有模型/无模型两个界面共用，消除重复代码
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteDownloadDialog(
    // URL 状态
    remoteUrlInput: String,
    onRemoteUrlInputChanged: (String) -> Unit,
    urlHistory: List<String>,
    urlDropdownExpanded: Boolean,
    onUrlDropdownExpandedChanged: (Boolean) -> Unit,
    // 远程模型列表状态
    isLoadingRemoteModels: Boolean,
    remoteModels: List<RemoteModelManager.RemoteModel>,
    remoteError: String?,
    // 下载状态
    downloadingModelName: String?,
    downloadProgress: Int,
    downloadSpeed: Long,
    isDownloadPaused: Boolean,
    isImporting: Boolean,
    // 操作回调
    onDismiss: () -> Unit,
    onFetchModels: () -> Unit,
    onCancelFetch: () -> Unit,
    onDownloadClick: (RemoteModelManager.RemoteModel) -> Unit,
    onResumeDownload: () -> Unit,
    onPauseDownload: () -> Unit,
    onCancelDownload: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remote_download_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 服务器 URL 输入（带历史下拉）
                ExposedDropdownMenuBox(
                    expanded = urlDropdownExpanded,
                    onExpandedChange = { onUrlDropdownExpandedChanged(it) }
                ) {
                    OutlinedTextField(
                        value = remoteUrlInput,
                        onValueChange = onRemoteUrlInputChanged,
                        label = { Text(stringResource(R.string.server_address)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                        singleLine = true,
                        enabled = !isLoadingRemoteModels && downloadProgress < 0
                    )
                    if (urlHistory.isNotEmpty()) {
                        ExposedDropdownMenu(
                            expanded = urlDropdownExpanded,
                            onDismissRequest = { onUrlDropdownExpandedChanged(false) }
                        ) {
                            urlHistory.forEach { url ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = {
                                        Text(
                                            url,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    },
                                    onClick = {
                                        onRemoteUrlInputChanged(url)
                                        onUrlDropdownExpandedChanged(false)
                                    },
                                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                // 加载按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (isLoadingRemoteModels) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.loading_ellipsis),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(onClick = onCancelFetch) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    } else {
                        TextButton(
                            onClick = onFetchModels,
                            enabled = remoteUrlInput.isNotBlank() && downloadProgress < 0
                        ) {
                            Text(stringResource(R.string.load_model_list))
                        }
                    }
                }

                // 错误提示
                remoteError?.let { error ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                // 模型列表
                if (remoteModels.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.available_models, remoteModels.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        remoteModels.forEach { model ->
                            val isDownloading = downloadingModelName == model.name
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isDownloading)
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = model.name,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        if (model.size > 0) {
                                            Text(
                                                text = formatFileSize(model.size),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (isDownloading) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            if (downloadProgress < 100) {
                                                LinearProgressIndicator(
                                                    progress = { downloadProgress / 100f },
                                                    modifier = Modifier.fillMaxWidth(),
                                                )
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Text(
                                                        text = "${downloadProgress}%",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                    if (downloadSpeed > 0) {
                                                        Text(
                                                            text = formatSpeed(downloadSpeed),
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.primary
                                                        )
                                                    }
                                                }
                                            } else {
                                                Text(
                                                    text = stringResource(R.string.importing_ellipsis),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    }
                                    if (isDownloading) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (downloadProgress < 100) {
                                                IconButton(
                                                    onClick = {
                                                        if (isDownloadPaused) onResumeDownload()
                                                        else onPauseDownload()
                                                    }
                                                ) {
                                                    Icon(
                                                        imageVector = if (isDownloadPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                                        contentDescription = if (isDownloadPaused) stringResource(R.string.resume) else stringResource(R.string.pause)
                                                    )
                                                }
                                            }
                                            IconButton(onClick = onCancelDownload) {
                                                Icon(
                                                    imageVector = Icons.Default.Close,
                                                    contentDescription = stringResource(R.string.cancel_download),
                                                    tint = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }
                                    } else if (isImporting) {
                                        // 其他模型正在下载，禁用下载按钮
                                        IconButton(
                                            onClick = {},
                                            enabled = false
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Download,
                                                contentDescription = stringResource(R.string.download),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                            )
                                        }
                                    } else {
                                        IconButton(
                                            onClick = { onDownloadClick(model) }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Download,
                                                contentDescription = stringResource(R.string.download),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}
