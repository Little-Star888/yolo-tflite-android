package com.little_star.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.little_star.R
import com.little_star.detector.model.DetectionResult
import com.little_star.model.DirectoryItem
import com.little_star.model.ImageDetectionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 图片目录识别模式 ViewModel
 * 管理目录批量检测的状态和结果
 */
class DirectoryViewModel : ViewModel() {

    /** 是否正在处理 */
    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    /** 当前状态描述 */
    private val _statusText = MutableStateFlow<String?>(null)
    val statusText: StateFlow<String?> = _statusText.asStateFlow()

    /** 进度（已处理数量 / 总数量） */
    private val _processingProgress = MutableStateFlow(0 to 0)
    val processingProgress: StateFlow<Pair<Int, Int>> = _processingProgress.asStateFlow()

    /** 选中的目录名称 */
    private val _selectedDirectoryName = MutableStateFlow<String?>(null)
    val selectedDirectoryName: StateFlow<String?> = _selectedDirectoryName.asStateFlow()

    /** 检测结果列表（最新在前） */
    private val _results = MutableStateFlow<List<DirectoryItem>>(emptyList())
    val results: StateFlow<List<DirectoryItem>> = _results.asStateFlow()

    /** 检测结果缩略图 Map */
    private val _thumbnails = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val thumbnails: StateFlow<Map<String, Bitmap>> = _thumbnails.asStateFlow()

    /** 已处理数量 */
    val processedCount: Int get() = _results.value.size

    /** 总数量 */
    val totalCount: Int get() = _processingProgress.value.second

    /** 当前目录 Uri（用于重新识别） */
    private var _currentDirectoryUri: Uri? = null
    val currentDirectoryUri: Uri? get() = _currentDirectoryUri

    /** 当前内存中的完整结果列表（用于头部插入） */
    private var allResults = mutableListOf<DirectoryItem>()

    /**
     * 从 SAF Uri 处理目录（扫描 + 逐张识别，结果即时插入头部）
     */
    fun processDirectoryFromUri(
        context: Context,
        treeUri: Uri,
        detectLambda: suspend (String, Bitmap, Float) -> ImageDetectionResult,
        drawBoxesLambda: (Bitmap, List<DetectionResult>) -> Bitmap
    ) {
        _currentDirectoryUri = treeUri
        viewModelScope.launch {
            allResults.clear()
            _results.value = emptyList()
            _thumbnails.value = emptyMap()
            _processingProgress.value = 0 to 0
            _isProcessing.value = true

            _statusText.value = context.getString(R.string.dir_scanning)

            val imageFiles = withContext(Dispatchers.IO) {
                try {
                    val pickedDir = DocumentFile.fromTreeUri(context, treeUri)
                    if (pickedDir == null || !pickedDir.isDirectory) null
                    else {
                        _selectedDirectoryName.value = pickedDir.name
                        pickedDir.listFiles()
                            .filter { it.isFile && isImageFile(it.name ?: "") }
                            .sortedBy { it.name }
                    }
                } catch (e: Exception) { null }
            }

            if (imageFiles == null) {
                _statusText.value = context.getString(R.string.dir_no_access)
                _isProcessing.value = false
                return@launch
            }

            if (imageFiles.isEmpty()) {
                _statusText.value = context.getString(R.string.dir_no_images)
                _isProcessing.value = false
                return@launch
            }

            val total = imageFiles.size
            _processingProgress.value = 0 to total
            _statusText.value = context.getString(R.string.dir_detecting, 0, total)

            for ((index, file) in imageFiles.withIndex()) {
                val fileName = file.name ?: "unknown"

                val (item, thumbnail) = withContext(Dispatchers.IO) {
                    try {
                        val inputStream = context.contentResolver.openInputStream(file.uri)
                            ?: return@withContext (DirectoryItem(fileName, emptyList(), -1.0, context.getString(R.string.dir_cannot_open)) to null)

                        val options = android.graphics.BitmapFactory.Options().apply {
                            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                        }
                        val originalBitmap = android.graphics.BitmapFactory.decodeStream(inputStream, null, options)
                        inputStream.close()

                        if (originalBitmap == null) {
                            return@withContext (DirectoryItem(fileName, emptyList(), -1.0, context.getString(R.string.dir_cannot_decode)) to null)
                        }

                        val detectionResult = detectLambda(fileName, originalBitmap, 0.25f)
                        val resultBitmap = if (detectionResult.detections.isNotEmpty()) {
                            drawBoxesLambda(originalBitmap, detectionResult.detections)
                        } else null

                        (DirectoryItem(
                            fileName = fileName,
                            detections = detectionResult.detections,
                            inferenceTimeMs = detectionResult.inferenceTimeMs,
                            error = detectionResult.error
                        ) to resultBitmap)
                    } catch (e: Exception) {
                        (DirectoryItem(fileName, emptyList(), -1.0, e.message) to null)
                    }
                }

                // 每张图处理完立即插入到列表头部
                allResults.add(0, item)
                _results.value = allResults.toList()

                // 合并缩略图
                thumbnail?.let {
                    _thumbnails.value = _thumbnails.value.toMutableMap().apply {
                        put(fileName, it)
                    }
                }

                _processingProgress.value = (index + 1) to total
                _statusText.value = context.getString(R.string.dir_detecting, index + 1, total)
            }

            val detectedCount = _results.value.count { it.detections.isNotEmpty() }
            _statusText.value = context.getString(R.string.dir_complete, detectedCount, total)
            _isProcessing.value = false
        }
    }

    /**
     * 清空结果
     */
    fun clearResults() {
        allResults.clear()
        _results.value = emptyList()
        _thumbnails.value = emptyMap()
        _selectedDirectoryName.value = null
        _processingProgress.value = 0 to 0
        _statusText.value = null
    }

    /** 判断是否为图片文件 */
    private fun isImageFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("jpg", "jpeg", "png", "bmp", "webp")
    }
}
