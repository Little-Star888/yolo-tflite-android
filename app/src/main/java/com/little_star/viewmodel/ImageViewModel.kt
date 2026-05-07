package com.little_star.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.little_star.R
import com.little_star.detector.model.DetectionResult
import com.little_star.model.ImageDetectionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 图片识别模式 ViewModel
 * 管理单张图片的检测状态和结果
 */
class ImageViewModel : ViewModel() {

    /** 选中的图片 Uri */
    private val _imageUri = MutableStateFlow<Uri?>(null)
    val imageUri: StateFlow<Uri?> = _imageUri.asStateFlow()

    /** 是否正在处理 */
    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    /** 当前状态描述 */
    private val _statusText = MutableStateFlow<String?>(null)
    val statusText: StateFlow<String?> = _statusText.asStateFlow()

    /** 原始图片 */
    private val _originalBitmap = MutableStateFlow<Bitmap?>(null)
    val originalBitmap: StateFlow<Bitmap?> = _originalBitmap.asStateFlow()

    /** 带检测框的结果图片 */
    private val _resultBitmap = MutableStateFlow<Bitmap?>(null)
    val resultBitmap: StateFlow<Bitmap?> = _resultBitmap.asStateFlow()

    /** 检测结果列表 */
    private val _detections = MutableStateFlow<List<DetectionResult>>(emptyList())
    val detections: StateFlow<List<DetectionResult>> = _detections.asStateFlow()

    /** 推理耗时（毫秒） */
    private val _inferenceTimeMs = MutableStateFlow(0.0)
    val inferenceTimeMs: StateFlow<Double> = _inferenceTimeMs.asStateFlow()

    /** 错误信息 */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * 设置选中的图片 Uri
     */
    fun setImageUri(uri: Uri?) {
        _imageUri.value = uri
        // 清空之前的结果
        clearResults()
    }

    /**
     * 执行检测
     * @param detectLambda 检测函数，接收 Bitmap 和阈值，返回检测结果
     * @param drawBoxesLambda 绘制检测框函数，接收 Bitmap 和检测结果，返回带框的 Bitmap
     * @param getInferenceTimeMs 获取推理耗时的函数，由调用者传入（如 sharedViewModel.lastInferenceTimeMs）
     */
    fun performDetection(
        context: Context,
        detectLambda: suspend (Bitmap, Float) -> List<DetectionResult>,
        drawBoxesLambda: (Bitmap, List<DetectionResult>) -> Bitmap,
        getInferenceTimeMs: () -> Double = { 0.0 }
    ) {
        val bitmap = _originalBitmap.value
        if (bitmap == null) {
            _statusText.value = context.getString(R.string.image_select_first)
            return
        }

        viewModelScope.launch {
            _isProcessing.value = true
            _statusText.value = context.getString(R.string.detecting)
            _error.value = null

            try {
                // 执行检测
                val detections = withContext(Dispatchers.IO) {
                    detectLambda(bitmap, 0.25f)
                }

                // 获取推理耗时（检测后立即获取）
                _inferenceTimeMs.value = getInferenceTimeMs()

                // 绘制检测框
                val resultBitmap = withContext(Dispatchers.IO) {
                    if (detections.isNotEmpty()) {
                        drawBoxesLambda(bitmap, detections)
                    } else {
                        null
                    }
                }

                _detections.value = detections
                _resultBitmap.value = resultBitmap
                _statusText.value = context.getString(R.string.image_detect_complete, detections.size)
            } catch (e: Exception) {
                _error.value = e.message
                _statusText.value = context.getString(R.string.image_detect_failed, e.message)
            } finally {
                _isProcessing.value = false
            }
        }
    }

    /**
     * 设置原始图片（从 Uri 加载后调用）
     */
    fun setOriginalBitmap(bitmap: Bitmap?) {
        _originalBitmap.value = bitmap
    }

    /**
     * 设置检测结果
     */
    fun setDetectionResult(context: Context, result: ImageDetectionResult) {
        _originalBitmap.value = result.originalBitmap
        _resultBitmap.value = result.resultBitmap
        _detections.value = result.detections
        _inferenceTimeMs.value = result.inferenceTimeMs
        _error.value = result.error
        _statusText.value = if (result.error != null) {
            context.getString(R.string.image_detect_failed, result.error)
        } else {
            context.getString(R.string.image_detect_complete, result.detections.size)
        }
    }

    /**
     * 清空结果
     */
    fun clearResults() {
        _resultBitmap.value = null
        _detections.value = emptyList()
        _inferenceTimeMs.value = 0.0
        _error.value = null
        _statusText.value = null
    }

    /**
     * 清空所有状态
     */
    fun clearAll() {
        _imageUri.value = null
        _originalBitmap.value = null
        clearResults()
    }
}
