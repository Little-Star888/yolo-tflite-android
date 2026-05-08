package com.little_star.ui.home

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.little_star.R
import com.little_star.assets.RemoteModelManager
import com.little_star.assets.TaskModelScanner
import com.little_star.detector.model.AcceleratorMode
import com.little_star.detector.model.InferenceBackend
import com.little_star.detector.model.InferenceType
import com.little_star.detector.model.ModelFormat
import com.little_star.detector.model.ModelSize
import com.little_star.detector.model.TaskType
import com.little_star.model.DetectionMode
import com.little_star.ui.config.CascadingDropdowns
import com.little_star.ui.config.FormatHintDialog
import com.little_star.util.titleTextRes
import com.little_star.viewmodel.DetectorState
import com.little_star.viewmodel.SharedDetectorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首页：模型配置 + 检测模式选择
 *
 * 页面上半部分为级联下拉框（推理模式→加速器→模型代数→尺寸→模型文件），
 * 带有默认值（传统模式、NPU、YOLO26、X尺寸），模型自动加载。
 * 下半部分为4种检测模式卡片，模型就绪后可点击进入。
 *
 * @param sharedViewModel 共享检测器 ViewModel，管理模型加载和检测状态
 * @param taskType 任务类型（目标检测、关键点检测等）
 * @param onBack 返回主首页
 * @param onNavigateToCamera 导航到相机识别（实时检测 + 拍照检测）
 * @param onNavigateToImage 导航到图片识别
 * @param onNavigateToDirectory 导航到图片目录识别
 * @param onNavigateToVideo 导航到视频识别
 * @param modifier 修饰符
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    sharedViewModel: SharedDetectorViewModel,
    taskType: TaskType = TaskType.DETECTION,
    onBack: () -> Unit,
    onNavigateToCamera: () -> Unit,
    onNavigateToImage: () -> Unit,
    onNavigateToDirectory: () -> Unit,
    onNavigateToVideo: () -> Unit,
    modifier: Modifier = Modifier
) {
    val detectorState by sharedViewModel.detectorState.collectAsState()
    val scanner = sharedViewModel.modelScanner
    val context = LocalContext.current
    val isImporting by sharedViewModel.isImporting.collectAsState()
    val importProgress by sharedViewModel.importProgress.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // 返回主首页确认对话框
    var showBackConfirm by remember { mutableStateOf(false) }

    // 预缓存字符串，用于非 Composable 回调上下文
    val resCancelled = stringResource(R.string.error_download_cancelled)
    val resDownloadFailed = stringResource(R.string.download_failed)
    val resImportFailed = stringResource(R.string.import_failed)
    val resImportSuccessGeneric = stringResource(R.string.import_success_generic)
    val resUnknown = stringResource(R.string.unknown)
    val strings = remember(context) {
        object {
            val cancelled = resCancelled
            val downloadFailed = resDownloadFailed
            val importFailed = resImportFailed
            val importSuccessGeneric = resImportSuccessGeneric
            val unknown = resUnknown
            fun importSuccess(pkgInfo: String?, modelCount: Int) =
                context.getString(R.string.import_success, pkgInfo ?: "", modelCount)
        }
    }

    // 拦截系统返回手势，弹出确认对话框
    BackHandler(onBack = { showBackConfirm = true })

    // 监听 LiteRT 静默回退事件，显示 Toast 通知用户
    LaunchedEffect(Unit) {
        sharedViewModel.fallbackEvent.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    // 首次从主首页进入时显示加载动画，从识别模式返回时不显示
    // 使用 scannerReady.value（StateFlow，线程安全）而非 initialized（普通 var，IO/主线程间无内存屏障）
    var contentReady by remember { mutableStateOf(sharedViewModel.scannerReady.value) }
    // 监听 scannerReady StateFlow，scanner 就绪后才显示内容（替代不可靠的硬编码 delay）
    val scannerReady by sharedViewModel.scannerReady.collectAsState()
    LaunchedEffect(scannerReady) {
        if (!contentReady && scannerReady) {
            contentReady = true
        }
    }

    // 导入状态
    var importStatus by remember { mutableStateOf<String?>(null) }
    var showImportError by remember { mutableStateOf<String?>(null) }
    var showManageDialog by remember { mutableStateOf(false) }
    var showImportSourceDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf<String?>(null) } // 要删除的复合 key（pkg 或 pkg::remote）
    var showDeleteAllConfirmDialog by remember { mutableStateOf(false) }
    var modelRefreshKey by remember { mutableStateOf(0) }
    val importedPackages = remember(modelRefreshKey) { sharedViewModel.getImportedPackages() }
    val downloadedPackages = remember(modelRefreshKey) { sharedViewModel.getDownloadedPackages().toSet() }

    // 多选删除状态
    var selectedPackagesForDeletion by remember { mutableStateOf(setOf<String>()) }
    var isSelectionMode by remember { mutableStateOf(false) }

    // 远程下载相关状态
    var showRemoteDownloadDialog by remember { mutableStateOf(false) }
    var remoteUrlInput by remember { mutableStateOf(sharedViewModel.remoteServerUrl.value) }
    val urlHistory by sharedViewModel.urlHistory.collectAsState()
    var urlDropdownExpanded by remember { mutableStateOf(false) }
    val remoteModels by sharedViewModel.remoteModels.collectAsState()
    val isLoadingRemoteModels by sharedViewModel.isLoadingRemoteModels.collectAsState()
    val remoteError by sharedViewModel.remoteError.collectAsState()
    val downloadProgress by sharedViewModel.downloadProgress.collectAsState()
    val downloadSpeed by sharedViewModel.downloadSpeed.collectAsState()
    val isDownloadPaused by sharedViewModel.isDownloadPaused.collectAsState()
    var downloadingModelName by remember { mutableStateOf<String?>(null) }
    var isPostDownloadImporting by remember { mutableStateOf(false) }
    var showRemoteOverwriteConfirm by remember {
        mutableStateOf<RemoteModelManager.RemoteModel?>(
            null
        )
    } // 远程覆盖确认
    var showDownloadConfirm by remember { mutableStateOf<RemoteModelManager.RemoteModel?>(null) } // 下载前确认

    // 便利状态：区分本地导入和远程下载
    val isLocalImporting = isImporting && downloadingModelName == null
    val isRemoteDownloading = isImporting && downloadingModelName != null
    // 便利状态：模型加载中
    val isModelLoading = detectorState is DetectorState.Loading

    // 本地导入覆盖确认状态
    var showFormatHint by remember { mutableStateOf(false) }
    var showLocalOverwriteConfirm by remember { mutableStateOf<List<String>>(emptyList()) } // 冲突的包名列表
    var pendingDirectoryUri by remember { mutableStateOf<android.net.Uri?>(null) } // 待导入的目录 uri
    var pendingArchiveUris by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) } // 待导入的压缩包 uri 列表

    // 导入成功后 3 秒自动隐藏
    LaunchedEffect(importStatus) {
        if (importStatus != null) {
            kotlinx.coroutines.delay(3000)
            importStatus = null
        }
    }

    // 下载完成后显示"正在导入模型..."加载提示，1.5秒后自动变为成功提示
    LaunchedEffect(isPostDownloadImporting) {
        if (isPostDownloadImporting) {
            kotlinx.coroutines.delay(1500)
            isPostDownloadImporting = false
            importStatus = strings.importSuccessGeneric
            modelRefreshKey++
        }
    }

    // 本地导入选择器
    val importPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            sharedViewModel.preImport()
            coroutineScope.launch {
                val packages = withContext(Dispatchers.IO) {
                    com.little_star.assets.LocalModelManager.peekPackageNamesFromDirectory(
                        context,
                        it,
                        taskType
                    )
                }
                val conflicts = packages.filter { pkg ->
                    com.little_star.assets.LocalModelManager.importedPackageExists(
                        context,
                        pkg,
                        taskType
                    )
                }
                if (conflicts.isNotEmpty()) {
                    pendingDirectoryUri = it
                    showLocalOverwriteConfirm = conflicts
                } else {
                    sharedViewModel.importModels(it) { result ->
                        if (result.success) {
                            val pkgInfo =
                                if (result.packageNames.size > 1) result.packageNames.joinToString("、") else result.packageName
                            importStatus = strings.importSuccess(pkgInfo, result.modelCount)
                            modelRefreshKey++
                        } else {
                            showImportError = result.error ?: strings.importFailed
                        }
                    }
                }
            }
        }
    }

    // 压缩包选择器（支持多选）
    val archivePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.takeIf { it.isNotEmpty() }?.let { selectedUris ->
            sharedViewModel.preImport(R.string.extracting_zip, extracting = true)
            // 读取 zip 内容获取实际包名并检查冲突
            coroutineScope.launch {
                val allConflicts = mutableSetOf<String>()
                for (uri in selectedUris) {
                    val packages = withContext(Dispatchers.IO) {
                        com.little_star.assets.LocalModelManager.peekPackageNamesFromArchive(
                            context,
                            uri,
                            taskType
                        )
                    }
                    for (pkg in packages) {
                        if (com.little_star.assets.LocalModelManager.importedPackageExists(
                                context,
                                pkg,
                                taskType
                            )
                        ) {
                            allConflicts.add(pkg)
                        }
                    }
                }
                if (allConflicts.isNotEmpty()) {
                    pendingArchiveUris = selectedUris
                    showLocalOverwriteConfirm = allConflicts.toList()
                } else {
                    sharedViewModel.importArchives(selectedUris) { result ->
                        modelRefreshKey++
                        if (result.successCount > 0) {
                            val pkgInfo =
                                if (result.packageNames.size > 1) result.packageNames.joinToString("、") else result.packageName
                            importStatus = strings.importSuccess(pkgInfo, result.modelCount)
                        }
                        if (result.failCount > 0) {
                            showImportError = if (result.errors.size == 1) {
                                result.errors.first()
                            } else {
                                result.errors.mapIndexed { index, error -> "${index + 1}. $error" }
                                    .joinToString("\n")
                            }
                        }
                    }
                }
            }
        }
    }

    // 导入来源选择对话框（放在顶层，两个分支都能访问）
    if (showImportSourceDialog) {
        AlertDialog(
            onDismissRequest = { showImportSourceDialog = false },
            title = { Text(stringResource(R.string.select_import_source)) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Button(
                        onClick = {
                            showImportSourceDialog = false
                            importPickerLauncher.launch(null)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.select_model_directory))
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            showImportSourceDialog = false
                            archivePickerLauncher.launch(arrayOf("application/zip"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Archive, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.select_model_archive))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showImportSourceDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (!contentReady) {
        // 加载过渡动画：全屏居中
        Box(
            modifier = modifier
                .fillMaxSize()
                .statusBarsPadding(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.initializing),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    // 检查是否有任何可用模型（直接计算，无 remember 缓存，
    // 避免 scanner 就绪但 remember 缓存了旧 false 值的竞态）
    val hasModels = scanner != null && scanner.getAvailableFormats().isNotEmpty()

    // 下载前确认对话框（区分 WiFi / 移动数据）— 放在分支之前，两个界面都能看到
    showDownloadConfirm?.let { model ->
        val isWifi = remember {
            val cm =
                context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = cm?.activeNetwork
            val caps = network?.let { cm.getNetworkCapabilities(it) }
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        val sizeText = if (model.size > 0) formatFileSize(model.size) else strings.unknown
        AlertDialog(
            onDismissRequest = { showDownloadConfirm = null },
            title = { Text(stringResource(R.string.confirm_download)) },
            text = {
                if (isWifi) {
                    Text(stringResource(R.string.file_size, sizeText))
                } else {
                    Text(stringResource(R.string.download_cellular_warning, sizeText))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val m = showDownloadConfirm!!
                        showDownloadConfirm = null
                        downloadingModelName = m.name
                        sharedViewModel.downloadRemoteModel(m) { result ->
                            downloadingModelName = null
                            if (result.error != strings.cancelled) {
                                showRemoteDownloadDialog = false
                                sharedViewModel.clearRemoteModels()
                            }
                            if (result.success) {
                                val pkgInfo =
                                    if (result.packageNames.size > 1) result.packageNames.joinToString(
                                        "、"
                                    ) else result.packageName
                                importStatus = strings.importSuccess(pkgInfo, result.modelCount)
                                modelRefreshKey++
                            } else if (result.error != strings.cancelled) {
                                showImportError = result.error ?: strings.downloadFailed
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.continue_download))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDownloadConfirm = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    // 远程下载覆盖确认对话框 — 同样放在分支之前
    showRemoteOverwriteConfirm?.let { model ->
        val pkgGuess = model.name.removeSuffix(".zip")
        AlertDialog(
            onDismissRequest = { showRemoteOverwriteConfirm = null },
            title = { Text(stringResource(R.string.overwrite_confirm)) },
            text = { Text(stringResource(R.string.overwrite_remote_exists, pkgGuess)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRemoteOverwriteConfirm = null
                        downloadingModelName = model.name
                        sharedViewModel.downloadRemoteModel(model) { result ->
                            downloadingModelName = null
                            if (result.error != strings.cancelled) {
                                showRemoteDownloadDialog = false
                                sharedViewModel.clearRemoteModels()
                            }
                            if (result.success) {
                                val pkgInfo =
                                    if (result.packageNames.size > 1) result.packageNames.joinToString(
                                        "、"
                                    ) else result.packageName
                                importStatus = strings.importSuccess(pkgInfo, result.modelCount)
                                modelRefreshKey++
                            } else if (result.error != strings.cancelled) {
                                showImportError = result.error ?: strings.downloadFailed
                            }
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.overwrite))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoteOverwriteConfirm = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    // 返回主首页确认对话框
    if (showBackConfirm) {
        AlertDialog(
            onDismissRequest = { showBackConfirm = false },
            title = { Text(stringResource(R.string.back_to_main)) },
            text = {
                Text(
                    when {
                        isModelLoading -> stringResource(R.string.confirm_back_loading)
                        isLocalImporting -> stringResource(R.string.confirm_back_importing)
                        isRemoteDownloading -> stringResource(R.string.confirm_back_downloading, downloadingModelName ?: "")
                        else -> stringResource(R.string.confirm_back_default)
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBackConfirm = false
                        // 取消正在进行的下载或导入
                        if (isRemoteDownloading) sharedViewModel.cancelDownload()
                        if (isLocalImporting) sharedViewModel.cancelImport()
                        onBack()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(stringResource(R.string.confirm_back)) }
            },
            dismissButton = {
                TextButton(onClick = { showBackConfirm = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    // 格式说明对话框（两个界面共用）
    if (showFormatHint) {
        FormatHintDialog(taskType = taskType, onDismiss = { showFormatHint = false })
    }

    // 远程下载对话框（无模型/有模型共用）
    if (showRemoteDownloadDialog) {
        RemoteDownloadDialog(
            remoteUrlInput = remoteUrlInput,
            onRemoteUrlInputChanged = {
                if (it != remoteUrlInput) sharedViewModel.clearRemoteModels()
                remoteUrlInput = it
            },
            urlHistory = urlHistory,
            urlDropdownExpanded = urlDropdownExpanded,
            onUrlDropdownExpandedChanged = { urlDropdownExpanded = it },
            isLoadingRemoteModels = isLoadingRemoteModels,
            remoteModels = remoteModels,
            remoteError = remoteError,
            downloadingModelName = downloadingModelName,
            downloadProgress = downloadProgress,
            downloadSpeed = downloadSpeed,
            isDownloadPaused = isDownloadPaused,
            isImporting = isImporting,
            onDismiss = {
                showRemoteDownloadDialog = false
                if (!isImporting) sharedViewModel.clearRemoteModels()
            },
            onFetchModels = { sharedViewModel.fetchRemoteModels(remoteUrlInput) },
            onCancelFetch = { sharedViewModel.cancelFetchRemoteModels() },
            onDownloadClick = { model ->
                val pkgGuess = model.name.removeSuffix(".zip")
                if (pkgGuess in downloadedPackages) {
                    showRemoteOverwriteConfirm = model
                } else {
                    showDownloadConfirm = model
                }
            },
            onResumeDownload = { sharedViewModel.resumeDownload() },
            onPauseDownload = { sharedViewModel.pauseDownload() },
            onCancelDownload = {
                downloadingModelName = null
                isPostDownloadImporting = false
                sharedViewModel.cancelDownload()
            },
        )
    }

    // 本地导入覆盖确认对话框（放在 hasModels 分支之前，确保始终可渲染）
    if (showLocalOverwriteConfirm.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = {
                showLocalOverwriteConfirm = emptyList()
                pendingDirectoryUri = null
                pendingArchiveUris = emptyList()
                sharedViewModel.cancelImport()
            },
            title = { Text(stringResource(R.string.overwrite_confirm)) },
            text = {
                Column {
                    Text(stringResource(R.string.overwrite_local_exists))
                    Spacer(modifier = Modifier.height(4.dp))
                    showLocalOverwriteConfirm.forEach { pkg ->
                        Text("  • $pkg", style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.overwrite_confirm_question))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val dirUri = pendingDirectoryUri
                        val arcUris = pendingArchiveUris
                        showLocalOverwriteConfirm = emptyList()
                        pendingDirectoryUri = null
                        pendingArchiveUris = emptyList()
                        if (dirUri != null) {
                            sharedViewModel.importModels(dirUri) { result ->
                                if (result.success) {
                                    val pkgInfo =
                                        if (result.packageNames.size > 1) result.packageNames.joinToString(
                                            "、"
                                        ) else result.packageName
                                    importStatus =
                                        strings.importSuccess(pkgInfo, result.modelCount)
                                    modelRefreshKey++
                                } else {
                                    showImportError = result.error ?: strings.importFailed
                                }
                            }
                        } else if (arcUris.isNotEmpty()) {
                            sharedViewModel.importArchives(arcUris) { result ->
                                modelRefreshKey++
                                if (result.successCount > 0) {
                                    val pkgInfo =
                                        if (result.packageNames.size > 1) result.packageNames.joinToString(
                                            "、"
                                        ) else result.packageName
                                    importStatus =
                                        strings.importSuccess(pkgInfo, result.modelCount)
                                }
                                if (result.failCount > 0) {
                                    showImportError = if (result.errors.size == 1) {
                                        result.errors.first()
                                    } else {
                                        result.errors.mapIndexed { index, error -> "${index + 1}. $error" }
                                            .joinToString("\n")
                                    }
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.overwrite))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showLocalOverwriteConfirm = emptyList()
                    pendingDirectoryUri = null
                    pendingArchiveUris = emptyList()
                    sharedViewModel.cancelImport()
                }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (!hasModels) {
        // 无模型时：只显示标题栏 + 全屏居中的导入按钮
        Column(
            modifier = modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // 标题栏
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { showBackConfirm = true }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = stringResource(taskType.titleTextRes()),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.size(48.dp))
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.no_models_available),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = stringResource(R.string.please_add_model),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = stringResource(R.string.view_format_guide),
                            modifier = Modifier
                                .size(18.dp)
                                .clickable { showFormatHint = true },
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))

                    // 状态提示区域（在按钮上方）
                    if (isImporting && downloadingModelName != null && downloadProgress < 100) {
                        DownloadProgressCard(
                            downloadingModelName = downloadingModelName,
                            downloadProgress = downloadProgress,
                            downloadSpeed = downloadSpeed,
                            isDownloadPaused = isDownloadPaused,
                            onResume = { sharedViewModel.resumeDownload() },
                            onPause = { sharedViewModel.pauseDownload() },
                            onCancel = { sharedViewModel.cancelDownload() },
                            modifier = Modifier.fillMaxWidth(0.85f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    } else if (isImporting) {
                        LocalImportProgressCard(
                            importProgress = importProgress,
                            modifier = Modifier.fillMaxWidth(0.85f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // 模型管理功能卡片（始终可见）
                    ModelManageCards(
                        onImportClick = { showImportSourceDialog = true },
                        onDownloadClick = { showRemoteDownloadDialog = true },
                        onDeleteClick = { },
                        isImporting = isImporting,
                        isLocalImporting = isLocalImporting,
                        isPostDownloadImporting = isPostDownloadImporting,
                        hasManagedPackages = false,
                        modifier = Modifier.fillMaxWidth(0.85f)
                    )
                }
            }
        }

        // 导入错误对话框
        showImportError?.let { error ->
            AlertDialog(
                onDismissRequest = { showImportError = null },
                title = { Text(stringResource(R.string.import_failed)) },
                text = { Text(error) },
                confirmButton = {
                    TextButton(onClick = { showImportError = null }) { Text(stringResource(R.string.confirm)) }
                }
            )
        }

        // 导入状态提示（自动 3 秒后隐藏）
        importStatus?.let {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF2E7D32),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        return
    }

    // 整体可滚动，防止小屏幕内容溢出（避开状态栏）
    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // 标题栏（避开状态栏）
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { showBackConfirm = true }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = stringResource(taskType.titleTextRes()),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            // 右侧占位，保持标题居中
            Spacer(modifier = Modifier.size(48.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 模型管理区域
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.model_management),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = stringResource(R.string.view_format_guide),
                modifier = Modifier
                    .size(16.dp)
                    .clickable { showFormatHint = true },
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        // 导入/下载状态提示（在按钮上方）
        if (isImporting && downloadingModelName != null && downloadProgress < 100) {
            DownloadProgressCard(
                downloadingModelName = downloadingModelName,
                downloadProgress = downloadProgress,
                downloadSpeed = downloadSpeed,
                isDownloadPaused = isDownloadPaused,
                onResume = { sharedViewModel.resumeDownload() },
                onPause = { sharedViewModel.pauseDownload() },
                onCancel = { sharedViewModel.cancelDownload() }
            )
            Spacer(modifier = Modifier.height(8.dp))
        } else if (isImporting) {
            LocalImportProgressCard(importProgress = importProgress)
            Spacer(modifier = Modifier.height(8.dp))
        }

        // 模型管理功能卡片（始终可见）
        val hasManagedPackages =
            importedPackages.isNotEmpty() || downloadedPackages.isNotEmpty()
        ModelManageCards(
            onImportClick = { showImportSourceDialog = true },
            onDownloadClick = { showRemoteDownloadDialog = true },
            onDeleteClick = { if (hasManagedPackages) showManageDialog = true },
            isImporting = isImporting,
            isLocalImporting = isLocalImporting,
            isPostDownloadImporting = isPostDownloadImporting,
            isModelLoading = isModelLoading,
            hasManagedPackages = hasManagedPackages
        )

        // 导入状态提示
        importStatus?.let {
            Spacer(modifier = Modifier.height(4.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))
            ) {
                Text(
                    text = it,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF2E7D32)
                )
            }
        }

        // 导入错误对话框
        showImportError?.let { error ->
            AlertDialog(
                onDismissRequest = { showImportError = null },
                title = { Text(stringResource(R.string.import_failed)) },
                text = { Text(error) },
                confirmButton = {
                    TextButton(onClick = { showImportError = null }) { Text(stringResource(R.string.confirm)) }
                }
            )
        }

        // 管理导入模型对话框（支持多选删除）
        // 组合所有管理的包：本地用 pkg，远程用 pkg::remote（与 scanner 的复合 key 一致）
        val allManagedPackages = remember(importedPackages, downloadedPackages, modelRefreshKey) {
            importedPackages.map { it } + downloadedPackages.map { "$it::remote" }
        }
        if (showManageDialog) {
            AlertDialog(
                onDismissRequest = {
                    showManageDialog = false
                    isSelectionMode = false
                    selectedPackagesForDeletion = emptySet()
                },
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isSelectionMode) stringResource(R.string.select_models_to_delete) else stringResource(R.string.manage_imported_models),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (!isSelectionMode && allManagedPackages.size > 1) {
                            TextButton(onClick = { isSelectionMode = true }) {
                                Text(stringResource(R.string.multi_select))
                            }
                        }
                    }
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.imported_packages_count, allManagedPackages.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        allManagedPackages.forEach { key ->
                            val displayName =
                                TaskModelScanner.keyToDisplayName(key, importedPackages.toSet(), context)
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (selectedPackagesForDeletion.contains(key))
                                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
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
                                    if (isSelectionMode) {
                                        androidx.compose.material3.Checkbox(
                                            checked = selectedPackagesForDeletion.contains(key),
                                            onCheckedChange = { checked ->
                                                selectedPackagesForDeletion = if (checked) {
                                                    selectedPackagesForDeletion + key
                                                } else {
                                                    selectedPackagesForDeletion - key
                                                }
                                            }
                                        )
                                    }
                                    Text(
                                        text = displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (!isSelectionMode) {
                                        IconButton(onClick = { showDeleteConfirmDialog = key }) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = stringResource(R.string.delete),
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    if (isSelectionMode && selectedPackagesForDeletion.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                // 按来源分组删除
                                val localPkgs = selectedPackagesForDeletion.filter {
                                    !TaskModelScanner.isRemoteKey(it)
                                }
                                val remotePkgs = selectedPackagesForDeletion.filter {
                                    TaskModelScanner.isRemoteKey(it)
                                }
                                    .map { TaskModelScanner.keyToPackageName(it) }
                                if (localPkgs.isNotEmpty()) sharedViewModel.deleteImportedModels(
                                    localPkgs
                                )
                                if (remotePkgs.isNotEmpty()) sharedViewModel.deleteDownloadedModels(
                                    remotePkgs
                                )
                                importStatus = null
                                modelRefreshKey++
                                isSelectionMode = false
                                selectedPackagesForDeletion = emptySet()
                                showManageDialog = false
                            },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text(stringResource(R.string.delete_selected, selectedPackagesForDeletion.size))
                        }
                    } else {
                        TextButton(
                            onClick = {
                                showManageDialog = false
                                isSelectionMode = false
                                selectedPackagesForDeletion = emptySet()
                            }
                        ) {
                            Text(stringResource(R.string.close))
                        }
                    }
                },
                dismissButton = {
                    if (isSelectionMode) {
                        TextButton(
                            onClick = {
                                isSelectionMode = false
                                selectedPackagesForDeletion = emptySet()
                            }
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                }
            )
        }

        // 删除确认对话框
        showDeleteConfirmDialog?.let { key ->
            val displayName = TaskModelScanner.keyToDisplayName(key, importedPackages.toSet(), context)
            val isRemote = TaskModelScanner.isRemoteKey(key)
            val pkgName = TaskModelScanner.keyToPackageName(key)
            AlertDialog(
                onDismissRequest = { showDeleteConfirmDialog = null },
                title = { Text(stringResource(R.string.confirm_delete)) },
                text = { Text(stringResource(R.string.delete_package_confirm, displayName)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (isRemote) {
                                sharedViewModel.deleteDownloadedModels(pkgName)
                            } else {
                                sharedViewModel.deleteImportedModels(pkgName)
                            }
                            importStatus = null
                            modelRefreshKey++
                            showDeleteConfirmDialog = null
                            showManageDialog = false
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(stringResource(R.string.delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirmDialog = null }) { Text(stringResource(R.string.cancel)) }
                }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 导入/下载中时，模型配置和识别模式变暗不可操作
        val blockInteraction = isImporting || isPostDownloadImporting

        Box(modifier = Modifier.alpha(if (blockInteraction) 0.4f else 1f)) {
            Column {

                // 级联下拉框：模型配置（带默认值）
                if (scanner != null) {
                    CascadingDropdowns(
                        scanner = scanner,
                        enabled = detectorState !is DetectorState.Loading,
                        onConfigChanged = { model, inferenceType, accelerator, backend ->
                            // 两组都选完后触发模型初始化
                            sharedViewModel.loadDetector(model, inferenceType, accelerator, backend)
                        },
                        initialModel = sharedViewModel.getCurrentModel(),
                        initialInferenceType = sharedViewModel.getCurrentInferenceType(),
                        initialAccelerator = sharedViewModel.getCurrentAccelerator(),
                        initialBackend = sharedViewModel.getCurrentBackend(),
                        initialConfThreshold = sharedViewModel.getCurrentConfThreshold(),
                        defaultModelFormat = ModelFormat.TFLITE,
                        defaultSize = ModelSize.X,
                        defaultInferenceType = InferenceType.TRADITIONAL,
                        defaultAccelerator = AcceleratorMode.CPU,
                        defaultBackend = InferenceBackend.LITERT_NATIVE,
                        onConfThresholdChanged = { sharedViewModel.setConfThreshold(it) },
                        refreshKey = modelRefreshKey,
                        importedPackages = importedPackages.toSet(),
                        acceleratorConfirmedFlow = sharedViewModel.acceleratorConfirmed
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // 模型状态 + 模式选择区域：只在非 Idle 状态显示
                // 避免首次进入时 Idle→Loading→Ready 的快速状态切换导致视觉闪烁
                AnimatedVisibility(
                    visible = detectorState !is DetectorState.Idle,
                    enter = fadeIn(tween(250)) + expandVertically(tween(200))
                ) {
                    Column {
                        // 模型加载状态提示（推理配置和模式选择之间）
                        ModelStatusBar(detectorState = detectorState)

                        Spacer(modifier = Modifier.height(16.dp))

                        // 模式卡片标题
                        Text(
                            text = stringResource(R.string.select_detection_mode),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // 检测模式是否可用（模型已就绪）
                        val modeEnabled = detectorState is DetectorState.Ready

                        // 4种检测模式卡片
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            ModeCard(
                                mode = DetectionMode.REALTIME,
                                enabled = modeEnabled,
                                onClick = { if (modeEnabled) onNavigateToCamera() }
                            )

                            ModeCard(
                                mode = DetectionMode.SINGLE_IMAGE,
                                enabled = modeEnabled,
                                onClick = { if (modeEnabled) onNavigateToImage() }
                            )

                            ModeCard(
                                mode = DetectionMode.IMAGE_DIRECTORY,
                                enabled = modeEnabled,
                                onClick = { if (modeEnabled) onNavigateToDirectory() }
                            )

                            ModeCard(
                                mode = DetectionMode.VIDEO,
                                enabled = modeEnabled,
                                onClick = { if (modeEnabled) onNavigateToVideo() }
                            )
                        }
                    }
                }


            } // end inner Column
            // 覆盖层：导入/下载中拦截所有触摸事件
            if (blockInteraction) {
                Spacer(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable(enabled = true, onClick = {})
                )
            }
        } // end Box
    }
}

/**
 * 模型加载状态栏
 * 在模型配置区域上方显示当前加载状态
 *
 * @param detectorState 检测器当前状态
 */
@Composable
private fun ModelStatusBar(detectorState: DetectorState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (detectorState) {
                is DetectorState.Ready -> Color(0xFFE8F5E9)       // 浅绿
                is DetectorState.Loading -> Color(0xFFFFF8E1)     // 浅黄
                is DetectorState.Error -> Color(0xFFFFEBEE)       // 浅红
                is DetectorState.Idle -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (detectorState is DetectorState.Loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            Text(
                text = when (detectorState) {
                    is DetectorState.Idle -> stringResource(R.string.select_model_config)
                    is DetectorState.Loading -> stringResource(R.string.model_loading)
                    is DetectorState.Ready -> when (detectorState.cacheInfo) {
                        DetectorState.CacheInfo.JIT -> stringResource(R.string.model_ready_jit)
                        DetectorState.CacheInfo.AOT -> stringResource(R.string.model_ready_aot)
                        DetectorState.CacheInfo.NONE -> stringResource(R.string.model_ready)
                    }
                    is DetectorState.Error -> stringResource(R.string.model_load_failed, detectorState.message)
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = when (detectorState) {
                    is DetectorState.Ready -> Color(0xFF2E7D32)
                    is DetectorState.Loading -> Color(0xFFF57F17)
                    is DetectorState.Error -> Color(0xFFC62828)
                    is DetectorState.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

/**
 * 检测模式卡片
 * 显示图标、标题和描述，支持启用/禁用状态
 *
 * @param icon 图标内容
 * @param title 标题文本
 * @param description 描述文本
 * @param enabled 是否启用
 * @param onClick 点击回调
 * @param modifier 修饰符
 */
@Composable
private fun ModeCard(
    mode: DetectionMode,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val title = stringResource(mode.displayNameRes)
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图标
            Box(
                modifier = Modifier.size(44.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (mode) {
                        DetectionMode.REALTIME -> Icons.Default.Videocam
                        DetectionMode.SINGLE_IMAGE -> Icons.Default.Image
                        DetectionMode.IMAGE_DIRECTORY -> Icons.Default.Folder
                        DetectionMode.VIDEO -> Icons.Default.Movie
                    },
                    contentDescription = null,
                    tint = if (enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            // 标题和描述
            Column(verticalArrangement = Arrangement.Center) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
                Text(
                    text = stringResource(mode.descriptionRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                )
            }
        }
    }
}
