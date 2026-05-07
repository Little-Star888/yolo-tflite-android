package com.little_star.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.little_star.R
import com.little_star.detector.model.DetectionResult
import com.little_star.model.VideoDetectionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * 视频识别模式 ViewModel
 * 管理视频逐帧检测的状态和结果
 */
class VideoViewModel : ViewModel() {

    /** 选中的视频 Uri */
    private val _videoUri = MutableStateFlow<Uri?>(null)
    val videoUri: StateFlow<Uri?> = _videoUri.asStateFlow()

    /** 是否正在处理 */
    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    /** 当前状态描述 */
    private val _statusText = MutableStateFlow<String?>(null)
    val statusText: StateFlow<String?> = _statusText.asStateFlow()

    /** 进度（当前秒数 / 总秒数） */
    private val _progress = MutableStateFlow(0 to 0)
    val progress: StateFlow<Pair<Int, Int>> = _progress.asStateFlow()

    /** 帧检测结果列表 */
    private val _results = MutableStateFlow<List<VideoDetectionResult>>(emptyList())
    val results: StateFlow<List<VideoDetectionResult>> = _results.asStateFlow()

    /** 当前检测任务 Job（用于取消） */
    private var processingJob: Job? = null

    /** 采样间隔单位 */
    enum class IntervalUnit { MS, SECONDS }

    /** 采样间隔（毫秒） */
    private val _samplingIntervalMs = MutableStateFlow(1000)
    val samplingIntervalMs: StateFlow<Int> = _samplingIntervalMs.asStateFlow()

    /** 采样间隔单位 */
    private val _intervalUnit = MutableStateFlow(IntervalUnit.SECONDS)
    val intervalUnit: StateFlow<IntervalUnit> = _intervalUnit.asStateFlow()

    /** 是否保存带检测框的帧图片 */
    private val _saveFrameBitmaps = MutableStateFlow(false)
    val saveFrameBitmaps: StateFlow<Boolean> = _saveFrameBitmaps.asStateFlow()

    /** 视频总时长（毫秒） */
    private val _videoDurationMs = MutableStateFlow(0L)
    val videoDurationMs: StateFlow<Long> = _videoDurationMs.asStateFlow()

    /** 总检测目标数 */
    val totalDetections: Int get() = _results.value.sumOf { it.detections.size }

    /** 平均推理耗时 */
    val averageInferenceTime: Double get() {
        val times = _results.value.map { it.inferenceTimeMs }.filter { it > 0 }
        return if (times.isNotEmpty()) times.average() else 0.0
    }

    /**
     * 设置视频 Uri
     */
    fun setVideoUri(uri: Uri?) {
        _videoUri.value = uri
        cancelProcessing()
        clearResults()
    }

    /**
     * 设置采样间隔（毫秒）
     */
    fun setSamplingIntervalMs(ms: Int) {
        _samplingIntervalMs.value = ms.coerceAtLeast(1)
    }

    /**
     * 设置采样间隔单位
     */
    fun setIntervalUnit(unit: IntervalUnit) {
        _intervalUnit.value = unit
    }

    /**
     * 设置是否保存帧图片
     */
    fun setSaveFrameBitmaps(save: Boolean) {
        _saveFrameBitmaps.value = save
    }

    /**
     * 开始处理视频
     * @param inputSize 模型输入尺寸（未使用，保留签名兼容）
     * @param detectLambda CPU 检测回调
     * @param drawBoxesLambda 绘制检测框回调
     */
    fun startProcessing(
        context: Context,
        inputSize: Int,
        detectLambda: suspend (Bitmap, Float) -> List<DetectionResult>,
        drawBoxesLambda: ((Bitmap, List<DetectionResult>) -> Bitmap)? = null
    ) {
        val uri = _videoUri.value
        if (uri == null) {
            _statusText.value = context.getString(R.string.video_select_first)
            return
        }

        cancelProcessing()

        processingJob = viewModelScope.launch {
            var wasCancelled = false
            _isProcessing.value = true
            _results.value = emptyList()
            _progress.value = 0 to 0
            _statusText.value = context.getString(R.string.video_analyzing)

            try {
                withContext(Dispatchers.IO) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, uri)
                        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        _videoDurationMs.value = durationMs

                        _progress.value = 0 to (durationMs / 1000).toInt()
                        val durationText = if (_intervalUnit.value == IntervalUnit.MS) "${durationMs}ms" else "${durationMs / 1000}s"
                        _statusText.value = context.getString(R.string.video_start_detect, durationText)

                        val intervalMs = _samplingIntervalMs.value
                        val mutableResults = mutableListOf<VideoDetectionResult>()
                        var sampleIndex = 0
                        var timeMs = 0L

                        while (timeMs < durationMs) {
                            yield()

                            val extractStart = System.nanoTime()
                            val bitmap = retriever.getFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            val extractMs = (System.nanoTime() - extractStart) / 1_000_000.0
                            if (bitmap != null) {
                                try {
                                    val startTime = System.nanoTime()
                                    val detections = detectLambda(bitmap, 0.25f)
                                    val inferenceTimeMs = extractMs + (System.nanoTime() - startTime) / 1_000_000.0

                                    val resultBitmap = if (_saveFrameBitmaps.value && drawBoxesLambda != null && detections.isNotEmpty()) {
                                        drawBoxesLambda(bitmap, detections)
                                    } else {
                                        null
                                    }

                                    mutableResults.add(VideoDetectionResult(
                                        frameIndex = sampleIndex,
                                        timestampMs = timeMs,
                                        detections = detections,
                                        inferenceTimeMs = inferenceTimeMs,
                                        resultBitmap = resultBitmap
                                    ))
                                    _results.value = mutableResults.toList()
                                } finally {
                                    bitmap.recycle()
                                }
                            }

                            sampleIndex++
                            _progress.value = (timeMs / 1000).toInt() to (durationMs / 1000).toInt()
                            val currentText = if (_intervalUnit.value == IntervalUnit.MS) "${timeMs}ms" else "${timeMs / 1000}s"
                            val durText = if (_intervalUnit.value == IntervalUnit.MS) "${durationMs}ms" else "${durationMs / 1000}s"
                            _statusText.value = context.getString(R.string.video_detecting, currentText, durText)
                            timeMs += intervalMs
                        }

                        if (_isProcessing.value) {
                            val totalDetections = mutableResults.sumOf { it.detections.size }
                            val durTextFinal = if (_intervalUnit.value == IntervalUnit.MS) "${durationMs}ms" else "${durationMs / 1000}s"
                            _statusText.value = context.getString(R.string.video_complete, mutableResults.size, totalDetections, durTextFinal)
                        }

                    } finally {
                        retriever.release()
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    wasCancelled = true
                } else {
                    _statusText.value = context.getString(R.string.video_process_failed, e.message)
                }
            } finally {
                if (!wasCancelled) {
                    _isProcessing.value = false
                } else if (!_isProcessing.value) {
                    _statusText.value = context.getString(R.string.video_cancelled)
                }
            }
        }
    }

    /**
     * 取消处理
     */
    fun cancelProcessing() {
        processingJob?.cancel()
        processingJob = null
        _isProcessing.value = false
    }

    /**
     * 清空结果
     */
    fun clearResults() {
        _results.value = emptyList()
        _progress.value = 0 to 0
        _statusText.value = null
    }

    /**
     * 清空所有状态
     */
    fun clearAll() {
        _videoUri.value = null
        cancelProcessing()
        clearResults()
    }
}
