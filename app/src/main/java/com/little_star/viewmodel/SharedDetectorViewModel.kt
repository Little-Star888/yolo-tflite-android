package com.little_star.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.little_star.R
import com.little_star.assets.LocalModelManager
import com.little_star.assets.ModelRepository
import com.little_star.assets.RemoteModelManager
import com.little_star.assets.TaskModelScanner
import com.little_star.detector.IDetector
import com.little_star.detector.factory.DetectorFactory
import com.little_star.detector.model.AcceleratorMode
import com.little_star.detector.model.ClassificationModelConfig
import com.little_star.detector.model.DetectionModelConfig
import com.little_star.detector.model.DetectionResult
import com.little_star.detector.model.InferenceBackend
import com.little_star.detector.model.InferenceType
import com.little_star.detector.model.KeypointModelConfig
import com.little_star.detector.model.ModelConfig
import com.little_star.detector.model.ModelDescriptor
import com.little_star.detector.model.ModelFormat
import com.little_star.detector.model.ModelSize
import com.little_star.detector.model.OrientedBBoxModelConfig
import com.little_star.detector.model.SegmentationModelConfig
import com.little_star.detector.model.TaskType
import com.little_star.detector.util.loadLabelConfig
import com.little_star.detector.util.loadLabelConfigFromFile
import com.little_star.pipeline.PipelineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 共享检测器 ViewModel
 * 管理检测器实例的生命周期，在检测流程的多个页面间共享
 * 包括模型加载、状态管理和检测执行
 *
 * 每个任务类型使用独立的 ViewModel 实例，通过 Factory 创建
 *
 * @param application Android 应用上下文
 * @param taskType 任务类型（目标检测、关键点检测等）
 */
class SharedDetectorViewModel(
    application: Application,
    private val taskType: TaskType
) : AndroidViewModel(application) {

    /** 任务类型（对外暴露） */
    val currentTaskType: TaskType get() = taskType

    /** 当前任务类型的默认模型输入尺寸 */
    val currentInputSize: Int
        get() = taskType.defaultInputSize

    /** 模型扫描器是否就绪 */
    private val _scannerReady = MutableStateFlow(false)
    val scannerReady: StateFlow<Boolean> = _scannerReady.asStateFlow()

    /** 任务模型扫描器，异步初始化（@Volatile 保证 IO/主线程可见性） */
    @Volatile
    private var _modelScanner: TaskModelScanner? = null
    val modelScanner: TaskModelScanner?
        get() = _modelScanner

    /** 检测器实例 */
    private var detector: IDetector? = null

    /** 加载互斥锁，防止并发加载导致资源冲突 */
    private val loadMutex = Mutex()

    /** 检测器状态 */
    private val _detectorState = MutableStateFlow<DetectorState>(DetectorState.Idle)
    val detectorState: StateFlow<DetectorState> = _detectorState.asStateFlow()

    /** 检测到 LiteRT 静默回退时的一次性事件（用于 UI 提示用户） */
    private val _fallbackEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val fallbackEvent: SharedFlow<String> = _fallbackEvent.asSharedFlow()

    /** 每次模型加载成功后发出确认的加速器（SharedFlow 不重放历史值，避免误触） */
    private val _acceleratorConfirmed = MutableSharedFlow<AcceleratorMode>(extraBufferCapacity = 1)
    val acceleratorConfirmed: SharedFlow<AcceleratorMode> = _acceleratorConfirmed.asSharedFlow()

    /** 导入状态：null 表示无导入，true 表示导入中 */
    private val _isImporting = MutableStateFlow<Boolean>(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    /** 提前显示导入中状态（在 peek/冲突检测阶段调用，让 UI 立即响应） */
    fun preImport(textResId: Int = R.string.importing_model_package, extracting: Boolean = false) {
        _isImporting.value = true
        _importProgress.value = LocalModelManager.ImportProgress(
            currentPackage = 0,
            totalPackages = 0,
            currentPackageName = getApplication<Application>().getString(textResId),
            isExtracting = extracting
        )
    }

    /** 导入进度 */
    private val _importProgress = MutableStateFlow<LocalModelManager.ImportProgress?>(null)
    val importProgress: StateFlow<LocalModelManager.ImportProgress?> = _importProgress.asStateFlow()

    /** 远程模型列表 */
    private val _remoteModels = MutableStateFlow<List<RemoteModelManager.RemoteModel>>(emptyList())
    val remoteModels: StateFlow<List<RemoteModelManager.RemoteModel>> = _remoteModels.asStateFlow()

    /** 远程下载进度 0-100，-1 表示无下载 */
    private val _downloadProgress = MutableStateFlow<Int>(-1)
    val downloadProgress: StateFlow<Int> = _downloadProgress.asStateFlow()

    private val _downloadSpeed = MutableStateFlow<Long>(0L)
    val downloadSpeed: StateFlow<Long> = _downloadSpeed.asStateFlow()

    // 暂停/续传状态
    private val _isDownloadPaused = MutableStateFlow<Boolean>(false)
    val isDownloadPaused: StateFlow<Boolean> = _isDownloadPaused.asStateFlow()
    private var pausedTempFilePath: String? = null
    private var pausedModel: RemoteModelManager.RemoteModel? = null
    private var pausedDownloadedBytes: Long = 0L
    private var downloadJob: kotlinx.coroutines.Job? = null
    private var downloadOnResult: ((LocalModelManager.ImportResult) -> Unit)? = null

    /** 下载世代计数器，用于忽略旧协程的过期状态更新 */
    @Volatile
    private var downloadGeneration = 0L

    /** 远程服务器 URL */
    private val _remoteServerUrl = MutableStateFlow(RemoteModelManager.DEFAULT_BASE_URL)
    val remoteServerUrl: StateFlow<String> = _remoteServerUrl.asStateFlow()

    /** URL 历史记录 */
    private val urlHistoryPrefs =
        application.getSharedPreferences("remote_url_history", android.content.Context.MODE_PRIVATE)
    private val _urlHistory = MutableStateFlow<List<String>>(emptyList())
    val urlHistory: StateFlow<List<String>> = _urlHistory.asStateFlow()

    /** 是否正在加载远程模型列表 */
    private val _isLoadingRemoteModels = MutableStateFlow(false)
    val isLoadingRemoteModels: StateFlow<Boolean> = _isLoadingRemoteModels.asStateFlow()

    /** 远程加载错误信息 */
    private val _remoteError = MutableStateFlow<String?>(null)
    val remoteError: StateFlow<String?> = _remoteError.asStateFlow()

    /** 远程下载的模型包名称集合 */

    /** 当前正在加载列表的 Job */
    private var fetchJob: kotlinx.coroutines.Job? = null

    /** 本地导入 Job（用于取消） */
    private var importJob: kotlinx.coroutines.Job? = null

    /** 本地导入取消标记（设置 cancelled=true 触发 LocalModelManager 停止并清理） */
    private var importCancelFlag: (() -> Unit)? = null

    /** 置信度阈值 */
    private val _confThreshold = MutableStateFlow(0.25f)
    val confThreshold: StateFlow<Float> = _confThreshold.asStateFlow()

    /** 当前已加载的模型（用于恢复状态） */
    private var currentLoadedModel: ModelDescriptor? = null

    /** 当前已加载的推理类型 */
    private var currentLoadedInferenceType: InferenceType? = null

    /** 当前已加载的推理后端 */
    private var currentLoadedBackend: InferenceBackend? = null

    /** 当前已加载的加速器 */
    private val _currentLoadedAccelerator = MutableStateFlow<AcceleratorMode?>(null)
    val currentLoadedAcceleratorFlow: StateFlow<AcceleratorMode?> =
        _currentLoadedAccelerator.asStateFlow()

    /** 当前模型配置（资产路径，用于比较） */
    private var currentModelPath: String? = null

    /** 当前加速器配置 */
    private var currentAccelerator: AcceleratorMode? = null

    /** 当前推理类型 */
    private var currentInferenceType: InferenceType? = null

    /** 目标模型配置（正在加载中或已加载，用于防重入） */
    private var targetModelPath: String? = null
    private var targetAccelerator: AcceleratorMode? = null
    private var targetInferenceType: InferenceType? = null
    private var targetBackend: InferenceBackend? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _modelScanner = ModelRepository.getOrCreate(getApplication(), taskType)
            _scannerReady.value = true
            launch { preloadDefaultDetector() }
        }
    }

    /**
     * 预加载默认模型的 detector（CPU + LITERT_NATIVE）
     * 在 scanner 就绪后立即在 IO 线程执行，不阻塞 UI
     * CPU 模式不需要 LogCapture（needLogCapture=false），天然快速
     * 当 CascadingDropdowns 回调触发时，loadDetector 发现已 Ready 且配置匹配 → 直接跳过
     */
    private suspend fun preloadDefaultDetector() {
        val scanner = _modelScanner ?: return
        try {
            val fmt = ModelFormat.TFLITE
            val pkgs = scanner.getAvailablePackages(fmt)
            if (pkgs.isEmpty()) return
            val pkg = pkgs.first()
            val sizes = scanner.getAvailableSizes(fmt, pkg)
            val size = sizes.minByOrNull { it.ordinal } ?: return
            val models = scanner.getAvailableModels(fmt, pkg, size)
            if (models.isEmpty()) return
            val model = models.minByOrNull { it.fileSize } ?: models.first()
            val inferenceType = scanner.detectInferenceType(model)

            // 直接调用 loadDetector，复用完整的标签加载、配置构建、状态管理逻辑
            loadDetector(model, inferenceType, AcceleratorMode.CPU, InferenceBackend.LITERT_NATIVE)
        } catch (_: Exception) { }
    }

    /**
     * 加载检测器
     * 根据选择的模型、加速器模式和推理后端初始化检测器
     * 只有配置真正变化了才会重新加载
     *
     * @param model 模型描述符
     * @param inferenceType 推理类型
     * @param accelerator 加速器模式
     * @param backend 推理后端
     */
    fun loadDetector(
        model: ModelDescriptor,
        inferenceType: InferenceType,
        accelerator: AcceleratorMode,
        backend: InferenceBackend
    ) {
        val modelIdentifier = if (model.isLocal) model.absolutePath else model.assetPath

        // 第一层防护：目标配置完全没变，跳过
        if (targetModelPath == modelIdentifier &&
            targetAccelerator == accelerator &&
            targetInferenceType == inferenceType &&
            targetBackend == backend &&
            _detectorState.value is DetectorState.Ready
        ) {
            return
        }

        // 第二层防护：检测器已就绪且模型相同，跳过
        if (currentModelPath == modelIdentifier &&
            _detectorState.value is DetectorState.Ready
        ) {
            if (currentAccelerator == accelerator && currentInferenceType == inferenceType && currentLoadedBackend == backend) {
                targetModelPath = modelIdentifier
                targetAccelerator = accelerator
                targetInferenceType = inferenceType
                targetBackend = backend
                return
            }
        }

        // 第三层防护：目标配置匹配且正在加载中，跳过（防止预加载与 UI 回调重复加载）
        if (targetModelPath == modelIdentifier &&
            targetAccelerator == accelerator &&
            targetInferenceType == inferenceType &&
            targetBackend == backend &&
            _detectorState.value is DetectorState.Loading
        ) {
            return
        }

        // 记录目标配置
        targetModelPath = modelIdentifier
        targetAccelerator = accelerator
        targetInferenceType = inferenceType
        targetBackend = backend

        viewModelScope.launch(Dispatchers.IO) {
            loadMutex.withLock {
                try {
                    _detectorState.value = DetectorState.Loading

                    // 释放旧的检测器
                    detector?.release()
                    detector = null

                    // 加载配置：统一从 label.txt 加载（本地/远程从文件系统，内置从 assets）
                    val labelConfig = if (model.isLocal) {
                        val localLabelPath = if (model.isRemote) {
                            LocalModelManager.getDownloadedLabelPath(
                                getApplication(), model.packageName, taskType
                            )
                        } else {
                            LocalModelManager.getImportedLabelPath(
                                getApplication(), model.packageName, taskType
                            )
                        }
                        if (localLabelPath != null) {
                            loadLabelConfigFromFile(localLabelPath)
                        } else {
                            // 回退到 assets 中的同名包标签
                            val labelPath = _modelScanner?.getLabelPath(model.packageName)
                                ?: "${taskType.assetDir}/tflite/${model.packageName}/label.txt"
                            loadLabelConfig(getApplication(), labelPath)
                        }
                    } else {
                        val labelPath = _modelScanner?.getLabelPath(model.packageName)
                            ?: "${taskType.assetDir}/tflite/${model.packageName}/label.txt"
                        loadLabelConfig(getApplication(), labelPath)
                    }

                    // 根据任务类型创建对应的 ModelConfig
                    val modelConfig: ModelConfig = when (taskType) {
                        TaskType.KEYPOINT -> {
                            val kptNames = labelConfig.keypoints
                                ?: throw IllegalArgumentException(getApplication<Application>().getString(R.string.error_label_missing_keypoints))
                            val flipIdx = labelConfig.flipIdx
                                ?: throw IllegalArgumentException(getApplication<Application>().getString(R.string.error_label_missing_flip_idx))
                            val keypointsLink = labelConfig.keypointsLink
                                ?: throw IllegalArgumentException(getApplication<Application>().getString(R.string.error_label_missing_keypoints_link))

                            KeypointModelConfig(
                                inputSize = 640,
                                classNames = labelConfig.classes,
                                inferenceType = inferenceType,
                                kptNames = kptNames,
                                flipIdx = flipIdx,
                                keypointsLink = keypointsLink
                            )
                        }

                        TaskType.DETECTION -> DetectionModelConfig(
                            inputSize = 640,
                            classNames = labelConfig.classes,
                            inferenceType = inferenceType
                        )

                        TaskType.SEGMENTATION -> SegmentationModelConfig(
                            inputSize = 640,
                            classNames = labelConfig.classes,
                            inferenceType = inferenceType
                        )

                        TaskType.CLASSIFICATION -> ClassificationModelConfig(
                            inputSize = 224,
                            classNames = labelConfig.classes,
                            inferenceType = inferenceType
                        )

                        TaskType.ORIENTED_BBOX -> OrientedBBoxModelConfig(
                            inputSize = 1024,
                            classNames = labelConfig.classes,
                            inferenceType = inferenceType
                        )
                    }

                    // 创建并初始化检测器（通过工厂根据后端类型创建）
                    val loadStartTime = System.currentTimeMillis()
                    val newDetector = DetectorFactory.create(getApplication(), backend)
                    if (model.isLocal) {
                        newDetector.initializeFromPath(
                            model.absolutePath!!,
                            modelConfig,
                            accelerator
                        )
                    } else {
                        newDetector.initialize(model.assetPath, modelConfig, accelerator)
                    }
                    val loadDuration = System.currentTimeMillis() - loadStartTime

                    if (!newDetector.isInitialized) {
                        newDetector.release()
                        _detectorState.value = DetectorState.Error(getApplication<Application>().getString(R.string.error_detector_init_failed))
                        return@launch
                    }
                    detector = newDetector

                    // 使用实际检测到的加速器（可能与用户选择的不同，如 NPU 回退到 CPU）
                    _currentLoadedAccelerator.value = newDetector.actualAccelerator
                    _acceleratorConfirmed.emit(newDetector.actualAccelerator)

                    currentModelPath = modelIdentifier
                    currentAccelerator = accelerator
                    currentInferenceType = inferenceType
                    currentLoadedModel = model
                    currentLoadedInferenceType = inferenceType
                    currentLoadedBackend = backend
                    targetAccelerator = accelerator

                    // 判断缓存命中：AOT 预编译 或 JIT 编译缓存
                    // JIT 缓存只针对 NPU（jitCacheHit = true 表示命中了 JIT 缓存），GPU/CPU 无 JIT 缓存概念
                    val cacheInfo = when {
                        newDetector.usedAotModel ->
                            DetectorState.CacheInfo.AOT
                        newDetector.actualAccelerator == AcceleratorMode.NPU && newDetector.jitCacheHit ->
                            DetectorState.CacheInfo.JIT
                        else -> DetectorState.CacheInfo.NONE
                    }
                    _detectorState.value = DetectorState.Ready(cacheInfo)
                } catch (e: Exception) {
                    detector?.release()
                    detector = null
                    val errorHint = buildLoadErrorHint(accelerator, e)
                    _detectorState.value = DetectorState.Error(errorHint)
                }
            }
        }
    }

    /**
     * 获取检测器实例
     * 仅在检测器就绪时返回非空值
     *
     * @return 检测器实例，如果未就绪则返回 null
     */
    fun getDetector(): IDetector? = detector

    /**
     * 获取当前已加载的模型（用于恢复状态）
     */
    fun getCurrentModel(): ModelDescriptor? = currentLoadedModel

    /**
     * 获取当前已加载的推理类型
     */
    fun getCurrentInferenceType(): InferenceType? = currentLoadedInferenceType

    /**
     * 获取当前已加载的推理后端
     */
    fun getCurrentBackend(): InferenceBackend? = currentLoadedBackend

    /**
     * 获取当前已加载的加速器
     */
    fun getCurrentAccelerator(): AcceleratorMode? = _currentLoadedAccelerator.value

    /**
     * 获取当前管线配置（便捷方法，供 CameraViewModel 初始化 xselect 上下文）
     * @return (backend, accelerator, inputSize, centerCrop) 四元组
     */
    fun getCurrentConfig(): PipelineConfig {
        val centerCrop = taskType == TaskType.CLASSIFICATION
        return PipelineConfig(
            backend = currentLoadedBackend,
            accelerator = _currentLoadedAccelerator.value,
            inputSize = currentInputSize,
            centerCrop = centerCrop
        )
    }

    /**
     * 获取当前置信度阈值
     */
    fun getCurrentConfThreshold(): Float = _confThreshold.value

    /**
     * 设置置信度阈值
     */
    fun setConfThreshold(value: Float) {
        _confThreshold.value = value.coerceIn(0.05f, 0.95f)
    }

    /**
     * 执行检测
     * 便捷方法，用于各模式 ViewModel 调用
     *
     * @param bitmap 输入图片
     * @param confThreshold 置信度阈值
     * @return 检测结果列表
     * @throws IllegalStateException 如果检测器未初始化
     */
    fun detect(bitmap: Bitmap, confThreshold: Float = _confThreshold.value): List<DetectionResult> {
        val det = detector
            ?: throw IllegalStateException(getApplication<Application>().getString(R.string.error_detector_not_initialized))
        return det.detect(bitmap, confThreshold)
    }

    fun detectFromBuffer(
        preprocessedData: FloatArray,
        inputSize: Int,
        imgWidth: Int,
        imgHeight: Int,
        letterboxScale: Float,
        letterboxOffsetX: Float,
        letterboxOffsetY: Float,
        confThreshold: Float = _confThreshold.value
    ): List<DetectionResult> {
        val det = detector
            ?: throw IllegalStateException(getApplication<Application>().getString(R.string.error_detector_not_initialized))
        return det.detectFromBuffer(
            preprocessedData, inputSize,
            imgWidth, imgHeight,
            letterboxScale, letterboxOffsetX, letterboxOffsetY,
            confThreshold
        )
    }

    /**
     * 获取最近一次推理的耗时（毫秒）
     * 在调用 detect() 之后访问此属性获取推理耗时
     */
    val lastInferenceTimeMs: Double
        get() = detector?.lastInferenceTimeMs ?: 0.0

    /**
     * 绘制检测框到 Bitmap
     * 用于图片识别、目录识别和视频识别模式
     * 根据任务类型绘制不同内容：
     * - 检测：边界框 + 标签
     * - 分割：边界框 + 标签 + 掩码
     * - 姿态：边界框 + 标签 + 关键点 + 关键点连线
     * - 分类：全图标签
     * - OBB：旋转边界框 + 标签
     *
     * @param bitmap 原图
     * @param results 检测结果列表
     * @param confThreshold 显示阈值（低于此置信度的检测结果不显示）
     * @return 绘制了检测框的 Bitmap
     */
    fun drawDetectionBoxes(
        bitmap: Bitmap,
        results: List<DetectionResult>,
        confThreshold: Float = _confThreshold.value
    ): Bitmap = com.little_star.model.DetectionRenderer.drawDetectionBoxes(bitmap, results, confThreshold)

    /**
     * 创建导入进度回调的工厂方法
     * 封装跨 zip 文件的累计进度追踪逻辑，减少重复代码
     *
     * @param trackCompletedPackages 是否追踪已完成的包列表（importArchives 需要）
     * @return ImportProgressCallback 实例
     */
    private fun createImportProgressCallback(
        trackCompletedPackages: Boolean = false
    ): LocalModelManager.ImportProgressCallback {
        // 跨 zip 文件的累计进度追踪状态
        var cumulativeTotal = 0       // 累计已知的总包数
        var cumulativeCompleted = 0   // 累计已完成包数
        var currentZipTotal = 0       // 当前 zip 的总包数
        // 已完成的包结果列表（仅 importArchives 使用）
        val completedPackages = mutableListOf<LocalModelManager.PackageResult>()

        return object : LocalModelManager.ImportProgressCallback {
            override fun onStart(totalPackages: Int) {
                currentZipTotal = totalPackages
                cumulativeTotal += totalPackages
                // 解压阶段结束，转为导入阶段
                if (_importProgress.value == null) {
                    _importProgress.value = LocalModelManager.ImportProgress(
                        currentPackage = 0,
                        totalPackages = cumulativeTotal,
                        currentPackageName = getApplication<Application>().getString(R.string.error_import_preparing),
                        isExtracting = false
                    )
                } else {
                    _importProgress.value = _importProgress.value?.copy(isExtracting = false)
                }
            }

            override fun onPackageStart(current: Int, total: Int, packageName: String) {
                val prevZipPackages = cumulativeTotal - currentZipTotal
                _importProgress.value = _importProgress.value?.copy(
                    currentPackage = prevZipPackages + current,
                    totalPackages = cumulativeTotal,
                    currentPackageName = getApplication<Application>().getString(R.string.error_importing_package, packageName)
                ) ?: LocalModelManager.ImportProgress(
                    currentPackage = current,
                    totalPackages = total,
                    currentPackageName = getApplication<Application>().getString(R.string.error_importing_package, packageName)
                )
            }

            override fun onPackageComplete(
                packageName: String,
                modelCount: Int,
                success: Boolean
            ) {
                cumulativeCompleted++
                if (trackCompletedPackages) {
                    completedPackages.add(
                        LocalModelManager.PackageResult(packageName, modelCount, success)
                    )
                }
                _importProgress.value = _importProgress.value?.copy(
                    currentPackage = cumulativeCompleted,
                    totalPackages = cumulativeTotal,
                    completedPackages = if (trackCompletedPackages) completedPackages.toList() else emptyList()
                )
            }
        }
    }

    /**
     * 导入本地模型
     * 自动判断选择的是目录还是文件
     *
     * @param uri SAF 选择器返回的 Uri（可以是目录或 .zip 文件）
     * @param onResult 导入结果回调（在主线程调用）
     */
    fun importModels(uri: Uri, onResult: (LocalModelManager.ImportResult) -> Unit) {
        _isImporting.value = true
        _importProgress.value = LocalModelManager.ImportProgress(
            currentPackage = 0,
            totalPackages = 0,
            currentPackageName = getApplication<Application>().getString(R.string.importing_model_package)
        )
        var cancelled = false
        importJob = viewModelScope.launch(Dispatchers.IO) {
            val progressCallback = createImportProgressCallback()

            val result = try {
                LocalModelManager.importAuto(
                    getApplication(),
                    uri,
                    taskType,
                    { cancelled },
                    progressCallback
                )
            } catch (e: LocalModelManager.ImportCancelledException) {
                return@launch
            }
            if (!isActive) return@launch
            if (result.success) {
                _modelScanner = ModelRepository.recreate(getApplication(), taskType)
            }
            withContext(Dispatchers.Main) {
                _isImporting.value = false
                _importProgress.value = null
                importJob = null
                onResult(result)
            }
        }
        importCancelFlag = { cancelled = true }
    }

    /**
     * 导入本地模型（批量）
     * 自动判断选择的是目录还是文件
     *
     * @param uris SAF 选择器返回的 Uri 列表（可以是目录或 .zip 文件）
     * @param onResult 导入结果回调（在主线程调用）
     */
    fun importModels(uris: List<Uri>, onResult: (LocalModelManager.ImportResult) -> Unit) {
        _isImporting.value = true
        _importProgress.value = LocalModelManager.ImportProgress(
            currentPackage = 0,
            totalPackages = 0,
            currentPackageName = getApplication<Application>().getString(R.string.extracting_zip),
            isExtracting = true
        )
        var cancelled = false
        importJob = viewModelScope.launch(Dispatchers.IO) {
            var totalModels = 0
            var lastPackage: String? = null
            var successCount = 0
            var failCount = 0
            val errors = mutableListOf<String>()
            val allPackageNames = mutableListOf<String>()  // 收集所有成功导入的包名

            val progressCallback = createImportProgressCallback()

            try {
                for (uri in uris) {
                    if (!isActive) return@launch
                    val result = LocalModelManager.importAuto(
                        getApplication(),
                        uri,
                        taskType,
                        { cancelled },
                        progressCallback
                    )
                    if (result.success) {
                        totalModels += result.modelCount
                        lastPackage = result.packageName
                        successCount++
                        allPackageNames.addAll(result.packageNames)
                    } else {
                        failCount++
                        result.error?.let { errors.add(it) }
                    }
                }
            } catch (e: LocalModelManager.ImportCancelledException) {
                return@launch
            }
            if (!isActive) return@launch
            if (totalModels > 0) {
                _modelScanner = ModelRepository.recreate(getApplication(), taskType)
            }
            withContext(Dispatchers.Main) {
                _isImporting.value = false
                _importProgress.value = null
                importJob = null
                onResult(
                    LocalModelManager.ImportResult(
                        success = successCount > 0,
                        packageName = lastPackage,
                        modelCount = totalModels,
                        error = if (failCount > 0) getApplication<Application>().getString(R.string.error_batch_import_failed, errors.size) else null,
                        successCount = successCount,
                        failCount = failCount,
                        errors = errors,
                        packageNames = allPackageNames
                    )
                )
            }
        }
        importCancelFlag = { cancelled = true }
    }

    /**
     * 从压缩包导入模型（批量）
     *
     * @param uris 压缩包 Uri 列表
     * @param onResult 导入结果回调（在主线程调用）
     */
    fun importArchives(uris: List<Uri>, onResult: (LocalModelManager.ImportResult) -> Unit) {
        _isImporting.value = true
        _importProgress.value = LocalModelManager.ImportProgress(
            currentPackage = 0,
            totalPackages = 0,
            currentPackageName = getApplication<Application>().getString(R.string.extracting_zip),
            isExtracting = true
        )
        var cancelled = false
        importJob = viewModelScope.launch(Dispatchers.IO) {
            var totalModels = 0
            var lastPackage: String? = null
            var successCount = 0
            var failCount = 0
            val errors = mutableListOf<String>()
            val allPackageNames = mutableListOf<String>()  // 收集所有成功导入的包名

            val progressCallback = createImportProgressCallback(trackCompletedPackages = true)

            try {
                for (uri in uris) {
                    if (!isActive) return@launch
                    val result = LocalModelManager.importFromArchive(
                        getApplication(), uri, taskType,
                        isCancelled = { cancelled },
                        progressCallback = progressCallback
                    )
                    if (result.success) {
                        totalModels += result.modelCount
                        lastPackage = result.packageName
                        successCount++
                        allPackageNames.addAll(result.packageNames)
                    } else {
                        failCount++
                        result.error?.let { errors.add(it) }
                    }
                }
            } catch (e: LocalModelManager.ImportCancelledException) {
                return@launch
            }
            if (!isActive) return@launch
            if (totalModels > 0) {
                _modelScanner = ModelRepository.recreate(getApplication(), taskType)
            }
            withContext(Dispatchers.Main) {
                _isImporting.value = false
                _importProgress.value = null
                importJob = null
                onResult(
                    LocalModelManager.ImportResult(
                        success = successCount > 0,
                        packageName = lastPackage,
                        modelCount = totalModels,
                        error = if (failCount > 0) getApplication<Application>().getString(R.string.error_batch_archive_import_failed, errors.size) else null,
                        successCount = successCount,
                        failCount = failCount,
                        errors = errors,
                        packageNames = allPackageNames
                    )
                )
            }
        }
        importCancelFlag = { cancelled = true }
    }

    /**
     * 加载远程模型列表
     *
     * @param baseUrl 服务器基础 URL
     */
    fun fetchRemoteModels(baseUrl: String = _remoteServerUrl.value) {
        _remoteServerUrl.value = baseUrl
        _isLoadingRemoteModels.value = true
        _remoteError.value = null
        fetchJob = viewModelScope.launch(Dispatchers.IO) {
            val result = RemoteModelManager.fetchModelList(baseUrl, getApplication())
            if (!isActive) return@launch
            result.fold(
                onSuccess = { models ->
                    _remoteModels.value = models
                    _isLoadingRemoteModels.value = false
                    saveUrlToHistory(baseUrl)
                },
                onFailure = { e ->
                    if (!isActive) return@launch
                    _remoteError.value = e.message ?: getApplication<Application>().getString(R.string.error_load_failed)
                    _remoteModels.value = emptyList()
                    _isLoadingRemoteModels.value = false
                }
            )
        }
    }

    /** 取消加载远程模型列表 */
    fun cancelFetchRemoteModels() {
        fetchJob?.cancel()
        fetchJob = null
        RemoteModelManager.cancelFetch()
        _isLoadingRemoteModels.value = false
        _remoteError.value = null
    }

    /**
     * 下载远程模型
     *
     * @param model 要下载的远程模型
     * @param onResult 下载结果回调（在主线程调用）
     */
    fun downloadRemoteModel(
        model: RemoteModelManager.RemoteModel,
        onResult: (LocalModelManager.ImportResult) -> Unit
    ) {
        // 取消现有下载，防止并发下载导致进度混乱
        RemoteModelManager.cancelCurrentDownload()
        downloadJob?.cancel()
        downloadJob = null
        pausedTempFilePath?.let { java.io.File(it).delete() }
        pausedTempFilePath = null
        pausedDownloadedBytes = 0L
        pausedModel = null
        downloadOnResult = null
        downloadGeneration++

        _isImporting.value = true
        _isDownloadPaused.value = false
        _downloadProgress.value = 0
        _downloadSpeed.value = 0L
        pausedModel = model
        downloadOnResult = onResult
        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            doDownload(model, null, 0L, onResult)
        }
    }

    /** 暂停下载 */
    fun pauseDownload() {
        if (_isImporting.value && !_isDownloadPaused.value) {
            _isDownloadPaused.value = true
            _downloadSpeed.value = 0L
            RemoteModelManager.cancelCurrentDownload()
        }
    }

    /** 恢复下载（断点续传） */
    fun resumeDownload() {
        if (!_isDownloadPaused.value) return
        val model = pausedModel ?: return
        val tempFilePath = pausedTempFilePath ?: return
        val onResult = downloadOnResult ?: return
        val tempFile = java.io.File(tempFilePath)
        // 文件已被删除，无法恢复
        if (!tempFile.exists()) {
            viewModelScope.launch(Dispatchers.Main) {
                _isImporting.value = false
                _isDownloadPaused.value = false
                _downloadProgress.value = -1
                _downloadSpeed.value = 0L
                pausedTempFilePath = null
                pausedModel = null
                pausedDownloadedBytes = 0L
                downloadOnResult = null
            }
            return
        }
        // 取消已有 job，防止重复恢复
        downloadJob?.cancel()
        downloadJob = null
        downloadGeneration++

        _isDownloadPaused.value = false
        _downloadSpeed.value = 0L
        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            doDownload(model, tempFile, pausedDownloadedBytes, onResult)
        }
    }

    /** 取消下载 */
    fun cancelDownload() {
        RemoteModelManager.cancelCurrentDownload()
        downloadJob?.cancel()
        downloadJob = null
        pausedTempFilePath?.let { java.io.File(it).delete() }
        pausedTempFilePath = null
        val callback = downloadOnResult
        viewModelScope.launch(Dispatchers.Main) {
            _isImporting.value = false
            _downloadProgress.value = -1
            _downloadSpeed.value = 0L
            _isDownloadPaused.value = false
            pausedModel = null
            pausedDownloadedBytes = 0L
            downloadOnResult = null
            callback?.invoke(
                LocalModelManager.ImportResult(
                    success = false,
                    packageName = null,
                    modelCount = 0,
                    error = getApplication<Application>().getString(R.string.error_download_cancelled),
                    successCount = 0,
                    failCount = 0
                )
            )
        }
    }

    /** 取消本地导入 */
    fun cancelImport() {
        // 先设置取消标记，让 LocalModelManager 在文件边界停止并清理
        importCancelFlag?.invoke()
        importCancelFlag = null
        importJob?.cancel()
        importJob = null
        viewModelScope.launch(Dispatchers.Main) {
            _isImporting.value = false
        }
    }

    /** 实际执行下载（支持断点续传） */
    private suspend fun doDownload(
        model: RemoteModelManager.RemoteModel,
        existingFile: java.io.File?,
        startOffset: Long,
        onResult: (LocalModelManager.ImportResult) -> Unit
    ) {
        val gen = downloadGeneration

        val dlResult = RemoteModelManager.downloadToTempFile(
            getApplication(),
            model.url,
            startOffset = startOffset,
            appendFile = existingFile
        ) { progress, speed ->
            // 忽略过期下载的进度更新
            if (downloadGeneration == gen) {
                _downloadProgress.value = progress
                _downloadSpeed.value = speed
            }
        }

        // 如果在此期间启动了新的下载，丢弃当前结果
        if (downloadGeneration != gen) return

        if (dlResult.paused) {
            // 用户暂停，保留临时文件和进度
            pausedTempFilePath = dlResult.file?.absolutePath
            pausedDownloadedBytes = dlResult.downloadedBytes
            return
        }

        if (dlResult.file == null) {
            viewModelScope.launch(Dispatchers.Main) {
                _isImporting.value = false
                _downloadProgress.value = -1
                _downloadSpeed.value = 0L
                onResult(
                    LocalModelManager.ImportResult(
                        success = false,
                        packageName = null,
                        modelCount = 0,
                        error = dlResult.error ?: getApplication<Application>().getString(R.string.error_download_failed),
                        successCount = 0,
                        failCount = 1
                    )
                )
            }
            return
        }
        val tempFile = dlResult.file

        // 阶段2：导入到远程专用目录
        if (downloadGeneration == gen) {
            _downloadProgress.value = 100
            _downloadSpeed.value = 0L
            // 提前设置解压中状态，UI 在 delay 期间就能正确显示
            _importProgress.value = LocalModelManager.ImportProgress(
                currentPackage = 0,
                totalPackages = 0,
                currentPackageName = getApplication<Application>().getString(R.string.extracting_zip),
                isExtracting = true
            )
        }
        kotlinx.coroutines.delay(300)

        // 检查是否已被取消（避免导入完成后又收到 cancel 导致重复回调）
        if (downloadGeneration != gen || !currentCoroutineContext().isActive) {
            pausedTempFilePath = null
            return
        }
        val importCb = createImportProgressCallback()
        val result = LocalModelManager.importRemoteFromArchive(
            getApplication(),
            tempFile,
            taskType,
            importCb
        )
        tempFile.delete()

        if (result.success) {
            _modelScanner = ModelRepository.recreate(getApplication(), taskType)
        }
        // 仅在未被取消时回调（已取消时由 cancelDownload() 统一处理回调）
        if (downloadGeneration == gen && currentCoroutineContext().isActive) {
            viewModelScope.launch(Dispatchers.Main) {
                _isImporting.value = false
                _downloadProgress.value = -1
                _downloadSpeed.value = 0L
                pausedTempFilePath = null
                pausedModel = null
                pausedDownloadedBytes = 0L
                downloadOnResult = null
                onResult(result)
            }
        } else {
            pausedTempFilePath = null
            pausedModel = null
            pausedDownloadedBytes = 0L
            downloadOnResult = null
        }
    }

    /**
     * 清除远程模型列表
     */
    fun clearRemoteModels() {
        _remoteModels.value = emptyList()
        _remoteError.value = null
    }

    // ── URL 历史记录 ──

    init {
        loadUrlHistory()
    }

    private fun loadUrlHistory() {
        val json = urlHistoryPrefs.getString("urls", null)
        _urlHistory.value = if (json != null) {
            try {
                val arr = org.json.JSONArray(json)
                (0 until arr.length()).map { arr.getString(it) }
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    fun saveUrlToHistory(url: String) {
        val current = _urlHistory.value.toMutableList()
        // 去重：同时匹配带/和不带/的版本
        current.removeAll { it.trimEnd('/') == url.trimEnd('/') }
        current.add(0, url)
        if (current.size > 10) current.removeAt(current.lastIndex)
        _urlHistory.value = current.toList()
        urlHistoryPrefs.edit().putString("urls", org.json.JSONArray(current).toString()).apply()
    }

    fun deleteUrlFromHistory(url: String) {
        val current = _urlHistory.value.toMutableList()
        current.remove(url)
        _urlHistory.value = current.toList()
        urlHistoryPrefs.edit().putString("urls", org.json.JSONArray(current).toString()).apply()
    }

    /**
     * 释放检测器资源的统一方法
     * 在删除模型时，如果当前加载的模型被删除，调用此方法释放检测器
     * 同时修复了 _currentLoadedAccelerator 在部分 delete 方法中未重置的 bug
     *
     * @param shouldRelease 是否需要释放检测器
     */
    private fun releaseDetectorIfNeeded(shouldRelease: Boolean) {
        if (shouldRelease) {
            detector?.release()
            detector = null
            _detectorState.value = DetectorState.Idle
            currentModelPath = null
            currentLoadedModel = null
            _currentLoadedAccelerator.value = null
        }
    }

    /**
     * 删除已导入的模型并刷新列表（批量）
     *
     * @param packageNames 要删除的模型包名称列表
     * @return 删除的模型包数量
     */
    fun deleteImportedModels(packageNames: List<String>): Int {
        // 如果当前加载的模型是被删除的模型，先释放
        releaseDetectorIfNeeded(
            currentLoadedModel?.isLocal == true && currentLoadedModel?.packageName in packageNames
        )
        val count = LocalModelManager.deleteImportedModels(getApplication(), packageNames, taskType)
        _modelScanner = ModelRepository.recreateBlocking(getApplication(), taskType)
        return count
    }

    /**
     * 删除已导入的模型并刷新列表
     *
     * @param packageName 要删除的模型包名称，null 表示删除全部
     * @return 删除的模型包数量
     */
    fun deleteImportedModels(packageName: String? = null): Int {
        // 如果当前加载的模型是被删除的模型，先释放
        releaseDetectorIfNeeded(
            currentLoadedModel?.isLocal == true &&
                (packageName == null || currentLoadedModel?.packageName == packageName)
        )
        val count = LocalModelManager.deleteImportedModels(getApplication(), packageName, taskType)
        _modelScanner = ModelRepository.recreateBlocking(getApplication(), taskType)
        return count
    }

    /** 删除远程下载的模型（批量） */
    fun deleteDownloadedModels(packageNames: List<String>): Int {
        releaseDetectorIfNeeded(
            currentLoadedModel?.isRemote == true && currentLoadedModel?.packageName in packageNames
        )
        val count =
            LocalModelManager.deleteDownloadedModels(getApplication(), packageNames, taskType)
        _modelScanner = ModelRepository.recreateBlocking(getApplication(), taskType)
        return count
    }

    /** 删除远程下载的模型（单个或全部） */
    fun deleteDownloadedModels(packageName: String? = null): Int {
        releaseDetectorIfNeeded(
            currentLoadedModel?.isRemote == true &&
                (packageName == null || currentLoadedModel?.packageName == packageName)
        )
        val count =
            LocalModelManager.deleteDownloadedModels(getApplication(), packageName, taskType)
        _modelScanner = ModelRepository.recreateBlocking(getApplication(), taskType)
        return count
    }

    /** 获取已导入的模型包列表（从 scanner modelTree 推导，无文件系统 I/O） */
    fun getImportedPackages(): List<String> =
        _modelScanner?.getImportedPackageNames() ?: emptyList()

    /** 获取远程下载的模型包列表（从 scanner modelTree 推导，无文件系统 I/O） */
    fun getDownloadedPackages(): List<String> =
        _modelScanner?.getDownloadedPackageNames() ?: emptyList()

    /**
     * ViewModel 清理时释放检测器资源
     */
    override fun onCleared() {
        super.onCleared()
        detector?.release()
        detector = null
    }

    /**
     * 手动释放检测器资源
     * 可在退出检测流程时调用
     */
    fun release() {
        detector?.release()
        detector = null
        _detectorState.value = DetectorState.Idle
    }

    /**
     * 根据异常类型生成用户友好的错误提示
     * 避免使用硬编码的错误消息，改为从实际异常中提取真实原因
     */
    private fun buildLoadErrorHint(accelerator: AcceleratorMode, e: Exception): String {
        val msg = e.message ?: e.cause?.message ?: ""
        val causeMsg = e.cause?.message ?: ""

        return when {
            // NPU 相关错误
            accelerator == AcceleratorMode.NPU && (
                    msg.contains("device create", ignoreCase = true) ||
                            causeMsg.contains("device create", ignoreCase = true) ||
                            msg.contains("Qnn", ignoreCase = true) ||
                            causeMsg.contains("Qnn", ignoreCase = true) ||
                            msg.contains("dispatch", ignoreCase = true) ||
                            causeMsg.contains("dispatch", ignoreCase = true) ||
                            msg.contains("Failed to compile model", ignoreCase = true) ||
                            causeMsg.contains("Failed to compile model", ignoreCase = true) ||
                            msg.contains("SIGABRT", ignoreCase = true) ||
                            msg.contains("Fatal signal", ignoreCase = true)
                    ) -> {
                // 优先检测是否包含权限相关的关键词
                if (msg.contains("avc", ignoreCase = true) ||
                    causeMsg.contains("avc", ignoreCase = true) ||
                    msg.contains("denied", ignoreCase = true) ||
                    msg.contains("SELinux", ignoreCase = true)
                ) {
                    getApplication<Application>().getString(R.string.error_npu_unsupported_dsp)
                } else {
                    getApplication<Application>().getString(R.string.error_npu_unsupported)
                }
            }
            // GPU 相关错误
            accelerator == AcceleratorMode.GPU && (
                    msg.contains("GPU", ignoreCase = true) ||
                            causeMsg.contains("GPU", ignoreCase = true) ||
                            msg.contains("OpenCL", ignoreCase = true) ||
                            causeMsg.contains("OpenCL", ignoreCase = true) ||
                            msg.contains("not supported by GPU delegate", ignoreCase = true) ||
                            causeMsg.contains("not supported by GPU delegate", ignoreCase = true) ||
                            msg.contains("Failed to compile model", ignoreCase = true) ||
                            causeMsg.contains("Failed to compile model", ignoreCase = true)
                    ) -> {
                getApplication<Application>().getString(R.string.error_gpu_unsupported)
            }
            // 默认：返回原始错误消息
            msg.isNotBlank() -> msg
            causeMsg.isNotBlank() -> causeMsg
            else -> getApplication<Application>().getString(R.string.error_model_load_failed, e::class.simpleName ?: "Unknown")
        }
    }

    /**
     * ViewModel Factory，支持传递 taskType 参数
     */
    class Factory(
        private val application: Application,
        private val taskType: TaskType
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(SharedDetectorViewModel::class.java)) {
                return SharedDetectorViewModel(application, taskType) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
