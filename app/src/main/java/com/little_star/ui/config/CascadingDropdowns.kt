package com.little_star.ui.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.little_star.R
import com.little_star.assets.TaskModelScanner
import com.little_star.detector.model.AcceleratorMode
import com.little_star.detector.model.InferenceBackend
import com.little_star.detector.model.InferenceType
import com.little_star.detector.model.ModelDescriptor
import com.little_star.detector.model.ModelFormat
import com.little_star.detector.model.ModelSize
import com.little_star.util.displayNameRes

/**
 * 级联下拉框组件（两组分离）
 *
 * 第一组「模型选择」：模型格式 → 模型包 → 模型尺寸 → 模型文件
 *   - 由 assets 目录约定自动填充，不触发模型初始化
 *
 * 第二组「推理配置」：推理模式 → 加速器
 *   - 选择完成后触发模型初始化
 *   - 推理模式由模型输出自动检测，不可手动选择
 *
 * @param scanner 任务模型扫描器
 * @param onConfigChanged 选择完成后的回调，参数为(模型, 推理模式, 加速器, 推理后端)
 * @param initialModel 初始已选中的模型（来自 ViewModel，用于恢复状态）
 * @param initialInferenceType 初始推理类型
 * @param initialAccelerator 初始加速器
 * @param initialBackend 初始推理后端
 * @param defaultModelFormat 默认模型格式
 * @param defaultPackageName 默认模型包名称
 * @param defaultSize 默认模型尺寸
 * @param defaultInferenceType 默认推理模式（仅在无法自动检测时使用）
 * @param defaultAccelerator 默认加速器
 * @param defaultBackend 默认推理后端
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CascadingDropdowns(
    scanner: TaskModelScanner,
    onConfigChanged: (ModelDescriptor, InferenceType, AcceleratorMode, InferenceBackend) -> Unit,
    modifier: Modifier = Modifier,
    /** 是否启用交互（模型加载中时禁用） */
    enabled: Boolean = true,
    initialModel: ModelDescriptor? = null,
    initialInferenceType: InferenceType? = null,
    initialAccelerator: AcceleratorMode? = null,
    initialBackend: InferenceBackend? = null,
    initialConfThreshold: Float = 0.25f,
    defaultModelFormat: ModelFormat = ModelFormat.TFLITE,
    defaultPackageName: String? = null,
    defaultSize: ModelSize = ModelSize.X,
    defaultInferenceType: InferenceType = InferenceType.TRADITIONAL,
    defaultAccelerator: AcceleratorMode = AcceleratorMode.CPU,
    defaultBackend: InferenceBackend = InferenceBackend.LITERT_NATIVE,
    onConfThresholdChanged: ((Float) -> Unit)? = null,
    refreshKey: Int = 0,
    importedPackages: Set<String> = emptySet(),
    /** 加载成功后确认的加速器事件（SharedFlow，不重放历史值） */
    acceleratorConfirmedFlow: kotlinx.coroutines.flow.SharedFlow<AcceleratorMode>? = null,
) {
    // ── 弹窗状态 ──
    var showModelSelectTip by remember { mutableStateOf(false) }
    var showInferenceConfigTip by remember { mutableStateOf(false) }

    // ── 预计算默认选择链（避免首次进入时空值→自动填充的多帧闪烁） ──
    val (initPkg, initSize, initModelDesc) = remember {
        val fmt = initialModel?.format ?: defaultModelFormat
        val pkgs = scanner.getAvailablePackages(fmt)
        val pkg = initialModel?.scannerKey ?: pkgs.firstOrNull()
        val sizes = pkg?.let { scanner.getAvailableSizes(fmt, it) } ?: emptyList()
        val size = initialModel?.size ?: sizes.minByOrNull { it.ordinal }
        val models = if (pkg != null && size != null)
            scanner.getAvailableModels(fmt, pkg, size)
        else emptyList()
        val model = initialModel ?: models.minByOrNull { it.fileSize } ?: models.firstOrNull()
        Triple(pkg, size, model)
    }

    // ── 状态变量 ──
    // 优先从 ViewModel 恢复配置，避免导航返回时重置为默认值
    var selectedFormat by remember { mutableStateOf(initialModel?.format ?: defaultModelFormat) }
    var expandedFormat by remember { mutableStateOf(false) }
    var selectedPackageName by remember { mutableStateOf(initPkg) }
    var expandedPackage by remember { mutableStateOf(false) }
    var selectedSize by remember { mutableStateOf(initSize) }
    var expandedSize by remember { mutableStateOf(false) }
    var selectedModel by remember { mutableStateOf(initModelDesc) }
    var expandedModel by remember { mutableStateOf(false) }
    var selectedInferenceType by remember { mutableStateOf(initialInferenceType ?: defaultInferenceType) }
    var selectedAccelerator by remember { mutableStateOf(initialAccelerator ?: defaultAccelerator) }
    var expandedAccelerator by remember { mutableStateOf(false) }
    var selectedBackend by remember { mutableStateOf(initialBackend ?: defaultBackend) }
    var expandedBackend by remember { mutableStateOf(false) }
    var selectedConfThreshold by remember { mutableStateOf(initialConfThreshold) }
    // 如果从 ViewModel 恢复了配置，跳过首次回调（不需要重新加载）
    var hasTriggeredCallback by remember { mutableStateOf(initialModel != null) }

    /** 用户已选择但尚未确认的加速器（null 表示无待确认变更） */
    var pendingAccelerator by remember { mutableStateOf<AcceleratorMode?>(null) }

    // 监听 acceleratorConfirmedFlow，加载完成后确认加速器显示
    LaunchedEffect(acceleratorConfirmedFlow) {
        acceleratorConfirmedFlow?.collect { confirmed ->
            selectedAccelerator = confirmed
            pendingAccelerator = null
        }
    }

    // ── 派生的可用选项 ──
    val availableFormats = remember { ModelFormat.entries }
    val availablePackages = remember(selectedFormat, refreshKey) { scanner.getAvailablePackages(selectedFormat) }
    val availableSizes = remember(selectedFormat, selectedPackageName, refreshKey) {
        val pkg = selectedPackageName
        pkg?.let { scanner.getAvailableSizes(selectedFormat, it) } ?: emptyList()
    }
    val availableModels = remember(selectedFormat, selectedPackageName, selectedSize, refreshKey) {
        val pkg = selectedPackageName
        val size = selectedSize
        if (pkg != null && size != null)
            scanner.getAvailableModels(selectedFormat, pkg, size)
        else emptyList()
    }

    /** 根据导入来源返回包名的显示文本 */
    val ctx = androidx.compose.ui.platform.LocalContext.current
    fun packageDisplayName(pkg: String): String = TaskModelScanner.keyToDisplayName(pkg, importedPackages, ctx)

    // 合并为单个 LaunchedEffect：自动选择 → 检测推理类型 → 触发回调
    // 避免三个串行 Effect 造成的 2-3 帧延迟
    LaunchedEffect(availablePackages, availableSizes, availableModels, refreshKey,
        selectedPackageName, selectedSize, selectedModel,
        selectedAccelerator, pendingAccelerator, selectedBackend) {
        // ── Step 1: 有效性检查 + 自动填充空值 ──
        if (selectedPackageName != null && selectedPackageName !in availablePackages) {
            selectedPackageName = null; selectedSize = null; selectedModel = null
        }
        if (selectedSize != null && selectedSize !in availableSizes) {
            selectedSize = null; selectedModel = null
        }
        if (selectedModel != null && selectedModel !in availableModels) {
            selectedModel = null
        }
        if (selectedPackageName == null && availablePackages.isNotEmpty()) {
            selectedPackageName = availablePackages.firstOrNull()
        }
        if (selectedSize == null && availableSizes.isNotEmpty()) {
            selectedSize = if (availableSizes.size == 1) availableSizes.first()
            else availableSizes.minByOrNull { it.ordinal }
        }
        if (selectedModel == null && availableModels.isNotEmpty()) {
            selectedModel = availableModels.minByOrNull { it.fileSize } ?: availableModels.firstOrNull()
        }

        // ── Step 2: 检测推理类型（IO 线程） ──
        val model = selectedModel
        if (model != null) {
            val detected = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                scanner.detectInferenceType(model)
            }
            selectedInferenceType = detected

            // ── Step 3: 触发回调（同一协程内顺序执行） ──
            if (!hasTriggeredCallback) {
                hasTriggeredCallback = true
                val accel = pendingAccelerator ?: selectedAccelerator
                onConfigChanged(model, detected, accel, selectedBackend)
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {

        // ── 第一组标题 ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.model_selection),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = stringResource(R.string.model_selection_info),
                modifier = Modifier
                    .size(16.dp)
                    .clickable { showModelSelectTip = true },
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.height(4.dp))

        // 模型格式
        ExposedDropdownMenuBox(
            expanded = expandedFormat,
            onExpandedChange = { if (enabled) expandedFormat = it }
        ) {
            OutlinedTextField(
                value = selectedFormat.displayName,
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                label = { Text(stringResource(R.string.model_format)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedFormat) },
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
            )
            ExposedDropdownMenu(expanded = expandedFormat, onDismissRequest = { expandedFormat = false }) {
                availableFormats.forEach { format ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(format.displayName) },
                        onClick = {
                            selectedFormat = format
                            selectedPackageName = null; selectedSize = null; selectedModel = null
                            selectedBackend = format.defaultBackend
                            selectedAccelerator = AcceleratorMode.CPU
                            pendingAccelerator = null
                            hasTriggeredCallback = false
                            expandedFormat = false
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 模型包
        ExposedDropdownMenuBox(
            expanded = expandedPackage,
            onExpandedChange = { if (enabled) expandedPackage = it }
        ) {
            OutlinedTextField(
                value = selectedPackageName?.let { packageDisplayName(it) } ?: "",
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                label = { Text(stringResource(R.string.model_package)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedPackage) },
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
            )
            ExposedDropdownMenu(expanded = expandedPackage, onDismissRequest = { expandedPackage = false }) {
                availablePackages.forEach { pkg ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(packageDisplayName(pkg)) },
                        onClick = {
                            selectedPackageName = pkg; selectedSize = null; selectedModel = null
                            selectedAccelerator = AcceleratorMode.CPU
                            pendingAccelerator = null
                            hasTriggeredCallback = false
                            expandedPackage = false
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 模型尺寸
        ExposedDropdownMenuBox(
            expanded = expandedSize,
            onExpandedChange = { if (enabled) expandedSize = it }
        ) {
            OutlinedTextField(
                value = selectedSize?.displayName ?: "",
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                label = { Text(stringResource(R.string.model_size)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedSize) },
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
            )
            ExposedDropdownMenu(expanded = expandedSize, onDismissRequest = { expandedSize = false }) {
                availableSizes.forEach { size ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(size.displayName) },
                        onClick = {
                            selectedSize = size; selectedModel = null
                            selectedAccelerator = AcceleratorMode.CPU
                            pendingAccelerator = null
                            hasTriggeredCallback = false
                            expandedSize = false
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 模型文件
        ExposedDropdownMenuBox(
            expanded = expandedModel,
            onExpandedChange = { if (enabled) expandedModel = it }
        ) {
            OutlinedTextField(
                value = selectedModel?.displayName ?: "",
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                label = { Text(stringResource(R.string.model_file)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedModel) },
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
            )
            ExposedDropdownMenu(expanded = expandedModel, onDismissRequest = { expandedModel = false }) {
                availableModels.forEach { model ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(model.displayName) },
                        onClick = {
                            selectedModel = model
                            selectedAccelerator = AcceleratorMode.CPU
                            pendingAccelerator = null
                            hasTriggeredCallback = false
                            expandedModel = false
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── 第二组标题 ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.inference_config),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = stringResource(R.string.inference_config_info),
                modifier = Modifier
                    .size(16.dp)
                    .clickable { showInferenceConfigTip = true },
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.height(4.dp))

        // 推理模式（自动检测，不可选择）
        OutlinedTextField(
            value = stringResource(selectedInferenceType.displayNameRes()),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(stringResource(R.string.inference_mode)) },
            modifier = Modifier.fillMaxWidth(),
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 推理后端（同一格式内的不同推理引擎）
        val availableBackends = remember(selectedFormat) { selectedFormat.availableBackends }
        if (availableBackends.size > 1) {
            // 多个后端：下拉选择
            ExposedDropdownMenuBox(
                expanded = expandedBackend,
                onExpandedChange = { if (enabled) expandedBackend = it }
            ) {
                OutlinedTextField(
                    value = selectedBackend.displayName,
                    onValueChange = {},
                    readOnly = true,
                    enabled = enabled,
                    label = { Text(stringResource(R.string.inference_backend)) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedBackend)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                )
                ExposedDropdownMenu(
                    expanded = expandedBackend,
                    onDismissRequest = { expandedBackend = false }
                ) {
                    availableBackends.forEach { backend ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(backend.displayName) },
                            onClick = {
                                selectedBackend = backend
                                // 切换后端时联动加速器重置为 CPU
                                selectedAccelerator = AcceleratorMode.CPU
                                pendingAccelerator = null
                                hasTriggeredCallback = false
                                expandedBackend = false
                            },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                        )
                    }
                }
            }
        } else {
            // 单个后端：只读显示
            OutlinedTextField(
                value = availableBackends.firstOrNull()?.displayName ?: "",
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                label = { Text(stringResource(R.string.inference_backend)) },
                modifier = Modifier.fillMaxWidth(),
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 加速器
        ExposedDropdownMenuBox(
            expanded = expandedAccelerator,
            onExpandedChange = { if (enabled) expandedAccelerator = it }
        ) {
            OutlinedTextField(
                value = selectedAccelerator.displayName,
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                label = { Text(stringResource(R.string.accelerator)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedAccelerator) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
            )
            ExposedDropdownMenu(expanded = expandedAccelerator, onDismissRequest = { expandedAccelerator = false }) {
                AcceleratorMode.entries.forEach { acc ->
                    DropdownMenuItem(
                        text = { Text(acc.displayName) },
                        onClick = {
                            pendingAccelerator = acc
                            hasTriggeredCallback = false
                            expandedAccelerator = false
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 置信度阈值
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.confidence_threshold),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "${(selectedConfThreshold * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = selectedConfThreshold,
            onValueChange = {
                selectedConfThreshold = it
                onConfThresholdChanged?.invoke(it)
            },
            enabled = enabled,
            valueRange = 0.05f..0.95f,
            steps = 89,
            modifier = Modifier.fillMaxWidth()
        )
    }

    // 模型选择说明弹窗
    if (showModelSelectTip) {
        ModelSelectTipDialog(onDismiss = { showModelSelectTip = false })
    }

    // 推理配置说明弹窗
    if (showInferenceConfigTip) {
        InferenceConfigTipDialog(onDismiss = { showInferenceConfigTip = false })
    }
}
