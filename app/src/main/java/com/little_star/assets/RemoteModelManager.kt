package com.little_star.assets

import android.content.Context
import com.little_star.R
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 远程模型下载管理器
 *
 * 从远程服务器获取模型列表并下载到本地
 */
object RemoteModelManager {

    const val DEFAULT_BASE_URL = "http://192.168.50.83:1234/yolo26_models/"
    private const val DOWNLOAD_DIR = "remote_models"

    /** 远程模型信息 */
    data class RemoteModel(
        val name: String,        // 文件名（不含路径）
        val url: String,          // 完整下载 URL
        val size: Long           // 文件大小（字节）
    )

    /** 下载结果 */
    data class DownloadResult(
        val success: Boolean,
        val packageName: String?,
        val modelCount: Int,
        val error: String? = null
    )

    /** 下载到临时文件的结果 */
    data class DownloadToTempResult(
        val file: File? = null,
        val error: String? = null,
        val paused: Boolean = false,
        val downloadedBytes: Long = 0L
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 当前正在进行的下载请求，用于取消 */
    @Volatile
    private var currentCall: okhttp3.Call? = null

    /** 当前正在进行的列表请求，用于取消 */
    @Volatile
    private var fetchCall: okhttp3.Call? = null

    /** 取消当前下载 */
    fun cancelCurrentDownload() {
        currentCall?.cancel()
        currentCall = null
    }

    /** 取消当前列表加载 */
    fun cancelFetch() {
        fetchCall?.cancel()
        fetchCall = null
    }

    /**
     * 获取远程模型列表
     *
     * @param baseUrl 服务器基础 URL
     * @return 模型列表（只包含 .zip 文件）
     */
    fun fetchModelList(baseUrl: String = DEFAULT_BASE_URL, context: Context): Result<List<RemoteModel>> {
        return try {
            // 先尝试获取目录列表页面
            val request = Request.Builder()
                .url(baseUrl)
                .build()

            fetchCall = client.newCall(request)
            val response = fetchCall!!.execute()
            fetchCall = null
            if (!response.isSuccessful) {
                return Result.failure(Exception(context.getString(R.string.remote_server_error, response.code)))
            }

            val body = response.body.string()

            // 解析 HTML 页面获取文件列表
            val models = parseHtmlForZipFiles(body, baseUrl)
            Result.success(models)
        } catch (e: Exception) {
            fetchCall = null
            Result.failure(e)
        }
    }

    /**
     * 解析 HTML 页面，提取 .zip 文件链接及文件大小
     */
    private fun parseHtmlForZipFiles(html: String, baseUrl: String): List<RemoteModel> {
        val models = mutableListOf<RemoteModel>()

        // 按行解析，匹配包含 .zip 链接的行及后续大小信息
        val linkPattern = """<a\s+href=["']([^"']+\.zip)["'][^>]*>([^<]*)</a>""".toRegex(RegexOption.IGNORE_CASE)
        // 匹配文件大小：纯数字或带单位的（25.6M, 1024K, 1.2G, 256字节等）
        val sizePattern = """(\d+(?:\.\d+)?)\s*([KMGT]?B?|字节?)""".toRegex(RegexOption.IGNORE_CASE)

        val lines = html.lines()
        for (i in lines.indices) {
            val line = lines[i]
            val match = linkPattern.find(line) ?: continue
            val fileName = match.groupValues[1]
            if (fileName.endsWith("/") || fileName.contains("/")) continue

            val fullUrl = if (baseUrl.endsWith("/")) "$baseUrl$fileName" else "$baseUrl/$fileName"

            // 从当前行及后两行中查找文件大小
            var size = 0L
            val searchRange = lines.slice(i..minOf(i + 2, lines.lastIndex))
            for (searchLine in searchRange) {
                val sizeMatch = sizePattern.findAll(searchLine).lastOrNull()
                if (sizeMatch != null) {
                    val value = sizeMatch.groupValues[1].toDoubleOrNull() ?: continue
                    val unit = sizeMatch.groupValues[2].uppercase()
                    size = when {
                        unit.startsWith("G") -> (value * 1024 * 1024 * 1024).toLong()
                        unit.startsWith("M") -> (value * 1024 * 1024).toLong()
                        unit.startsWith("K") -> (value * 1024).toLong()
                        unit.startsWith("T") -> (value * 1024 * 1024 * 1024 * 1024).toLong()
                        else -> value.toLong()
                    }
                    break
                }
            }

            models.add(RemoteModel(name = fileName, url = fullUrl, size = size))
        }

        return models
    }

    /**
     * 下载远程模型到临时文件（支持断点续传）
     *
     * @param context 上下文
     * @param modelUrl 模型下载 URL
     * @param startOffset 已下载的字节数（断点续传时 > 0）
     * @param appendFile 已有部分文件（断点续传时追加写入）
     * @param onProgress 进度回调（0-100, 字节/秒）
     * @return 下载结果
     */
    fun downloadToTempFile(
        context: Context,
        modelUrl: String,
        startOffset: Long = 0L,
        appendFile: File? = null,
        onProgress: ((Int, Long) -> Unit)? = null
    ): DownloadToTempResult {
        var call: okhttp3.Call? = null
        var tempFile: File? = null
        var totalBytesRead = startOffset

        try {
            val requestBuilder = Request.Builder().url(modelUrl)
            if (startOffset > 0) {
                requestBuilder.header("Range", "bytes=$startOffset-")
            }
            val request = requestBuilder.build()
            call = client.newCall(request)
            currentCall = call

            val response = call.execute()
            if (!response.isSuccessful && response.code != 206) {
                return DownloadToTempResult(error = context.getString(R.string.remote_http_error, response.code))
            }

            val body = response.body
            val contentLength = body.contentLength()
            val totalSize = if (startOffset > 0 && contentLength > 0) startOffset + contentLength else contentLength

            val fileName = modelUrl.substringAfterLast("/").substringAfterLast("%2F")
            if (!fileName.endsWith(".zip", ignoreCase = true)) {
                return DownloadToTempResult(error = context.getString(R.string.remote_not_zip))
            }

            tempFile = appendFile ?: File(context.cacheDir, "download_${System.currentTimeMillis()}.zip")
            val output = if (startOffset > 0 && appendFile != null) {
                java.io.FileOutputStream(appendFile, true)
            } else {
                tempFile.outputStream()
            }

            output.use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var lastSpeedTime = System.currentTimeMillis()
                    var lastSpeedBytes = startOffset
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        out.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        val now = System.currentTimeMillis()
                        val elapsed = now - lastSpeedTime
                        if (elapsed >= 500) {
                            val speed = (totalBytesRead - lastSpeedBytes) * 1000 / elapsed
                            lastSpeedTime = now
                            lastSpeedBytes = totalBytesRead
                            if (totalSize > 0) {
                                val progress = ((totalBytesRead * 100) / totalSize).toInt().coerceAtMost(99)
                                onProgress?.invoke(progress, speed)
                            } else {
                                onProgress?.invoke(-1, speed)
                            }
                        }
                    }
                }
            }
            currentCall = null
            onProgress?.invoke(100, 0L)
            return DownloadToTempResult(file = tempFile, downloadedBytes = totalBytesRead)
        } catch (e: java.net.SocketTimeoutException) {
            return DownloadToTempResult(error = context.getString(R.string.remote_download_timeout, e.message))
        } catch (e: java.net.UnknownHostException) {
            return DownloadToTempResult(error = context.getString(R.string.remote_unknown_host, e.message))
        } catch (e: java.net.ConnectException) {
            return DownloadToTempResult(error = context.getString(R.string.remote_connect_failed, e.message))
        } catch (e: java.io.IOException) {
            val isCanceled = try { call?.isCanceled() == true } catch (_: Throwable) { false }
            if (isCanceled) {
                val actualFile = if (appendFile != null) appendFile
                    else context.cacheDir.listFiles()?.maxByOrNull { it.lastModified() }
                return DownloadToTempResult(paused = true, downloadedBytes = actualFile?.length() ?: 0L, file = actualFile)
            } else {
                return DownloadToTempResult(error = context.getString(R.string.remote_download_error, e.javaClass.simpleName, e.message))
            }
        } catch (e: Exception) {
            return DownloadToTempResult(error = context.getString(R.string.remote_download_error, e.javaClass.simpleName, e.message))
        } finally {
            currentCall = null
        }
    }

    /**
     * 下载远程模型（下载 + 导入一体化）
     *
     * @param context 上下文
     * @param modelUrl 模型下载 URL
     * @param onProgress 进度回调（0-100）
     * @return 下载结果
     */
    fun downloadModel(
        context: Context,
        modelUrl: String,
        onProgress: ((Int, Long) -> Unit)? = null
    ): DownloadResult {
        val dlResult = downloadToTempFile(context, modelUrl, onProgress = onProgress)
        if (dlResult.file == null) {
            return DownloadResult(false, null, 0, dlResult.error ?: context.getString(R.string.error_download_failed))
        }
        val tempFile = dlResult.file

        return try {
            val result = LocalModelManager.importFromArchive(context, android.net.Uri.fromFile(tempFile))
            DownloadResult(
                success = result.success,
                packageName = result.packageName,
                modelCount = result.modelCount,
                error = result.error
            )
        } catch (e: Exception) {
            DownloadResult(false, null, 0, context.getString(R.string.remote_import_error, e.message))
        } finally {
            tempFile.delete()
        }
    }
}
