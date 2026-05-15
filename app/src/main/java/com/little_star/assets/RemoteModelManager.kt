package com.little_star.assets

import android.content.Context
import com.little_star.R
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * 远程模型下载管理器
 *
 * 从远程服务器获取模型列表并下载到本地。
 * 支持 JSON（file_manager API）和 HTML 目录列表两种响应格式，自动识别。
 *
 * 使用示例：
 *   val result = RemoteModelManager.fetchModelList(context = context)
 *   val result = RemoteModelManager.fetchModelList(
 *       url = "http://my.server.com/file_manager/my_models",
 *       context = context
 *   )
 */
object RemoteModelManager {

    /** 默认远程模型 URL（含目录路径，用户在输入框看到的默认值） */
    const val DEFAULT_URL = "http://192.168.50.83:1234/yolo26_models/"

    /** 当 URL 无法推断出目录路径时的默认路径 */
    private const val DEFAULT_MODEL_PATH = "/yolo26_models"

    // ──────────────────────────────────────────────────────────────
    // 数据类
    // ──────────────────────────────────────────────────────────────

    /** 远程模型信息 */
    data class RemoteModel(
        val name: String,   // 文件名（不含路径），如 yolo26-squid.zip
        val url: String,    // 完整下载 URL
        val size: Long      // 文件大小（字节）
    )

    /** 下载 + 导入一体化结果 */
    data class DownloadResult(
        val success: Boolean,
        val packageName: String?,
        val modelCount: Int,
        val error: String? = null
    )

    /** 仅下载到临时文件的结果 */
    data class DownloadToTempResult(
        val file: File? = null,
        val error: String? = null,
        val paused: Boolean = false,       // true = 被主动取消（可断点续传）
        val downloadedBytes: Long = 0L
    )

    // ──────────────────────────────────────────────────────────────
    // HTTP 客户端
    // ──────────────────────────────────────────────────────────────

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    @Volatile private var currentCall: okhttp3.Call? = null
    @Volatile private var fetchCall: okhttp3.Call? = null

    /** 取消当前文件下载（可用于暂停，支持断点续传） */
    fun cancelCurrentDownload() {
        currentCall?.cancel()
        currentCall = null
    }

    /** 取消当前列表加载 */
    fun cancelFetch() {
        fetchCall?.cancel()
        fetchCall = null
    }

    // ──────────────────────────────────────────────────────────────
    // URL 智能解析
    // ──────────────────────────────────────────────────────────────

    private data class ServerConfig(
        val serverRoot: String,
        val dirPath: String,
        val isApiMode: Boolean
    )

    /**
     * 从用户输入推断服务器配置，支持：
     *   - file_manager 根路径: http://host/file_manager
     *   - file_manager 含路径: http://host/file_manager/yolo26_models
     *   - 完整 API URL:        .../api/files?path=%2Fyolo26_models
     *   - 普通目录 URL:        http://host:1234/yolo26_models/
     */
    private fun resolveServerConfig(url: String): ServerConfig {
        val trimmed = url.trimEnd('/')

        // 已是完整 API URL
        if ("/api/files" in trimmed) {
            val serverRoot = trimmed.substringBefore("/api/files")
            val pathParam = url.substringAfter("path=", "").substringBefore("&")
            val dirPath = try {
                URLDecoder.decode(pathParam, "UTF-8")
            } catch (_: Exception) { pathParam }
            return ServerConfig(serverRoot, dirPath.ifEmpty { DEFAULT_MODEL_PATH }, true)
        }

        // file_manager 风格 URL（可能附带路径）
        val fmMarker = "/file_manager"
        if (fmMarker in trimmed) {
            val fmIdx = trimmed.indexOf(fmMarker)
            val serverRoot = trimmed.substring(0, fmIdx) + fmMarker
            val extraPath = trimmed.substring(fmIdx + fmMarker.length)
            val dirPath = if (extraPath.isNotEmpty()) extraPath else DEFAULT_MODEL_PATH
            return ServerConfig(serverRoot, dirPath, true)
        }

        // 普通目录 URL → 直接请求，按 HTML 解析
        return ServerConfig(trimmed, "", false)
    }

    // ──────────────────────────────────────────────────────────────
    // URL 内部工具
    // ──────────────────────────────────────────────────────────────

    private fun encodePath(path: String): String =
        path.split("/")
            .filter { it.isNotEmpty() }
            .joinToString(separator = "%2F", prefix = "%2F") {
                URLEncoder.encode(it, "UTF-8").replace("+", "%20")
            }

    private fun buildListUrl(serverRoot: String, dirPath: String): String =
        "$serverRoot/api/files?path=${encodePath(dirPath)}"

    private fun buildDownloadUrl(serverRoot: String, dirPath: String, fileName: String): String =
        "$serverRoot/api/download?path=${encodePath("$dirPath/$fileName")}"

    // ──────────────────────────────────────────────────────────────
    // 获取模型列表（自动识别 JSON / HTML）
    // ──────────────────────────────────────────────────────────────

    /**
     * 获取远程模型列表。
     *
     * @param url     服务器 URL（自动推断 API 模式或 HTML 模式）
     * @param context Android Context，用于读取字符串资源
     * @return        Result 包含 RemoteModel 列表，或失败原因
     */
    fun fetchModelList(
        url: String = DEFAULT_URL,
        context: Context
    ): Result<List<RemoteModel>> {
        val config = resolveServerConfig(url)
        val listUrl = if (config.isApiMode)
            buildListUrl(config.serverRoot, config.dirPath)
        else
            config.serverRoot
        return try {
            val request = Request.Builder().url(listUrl).build()
            fetchCall = client.newCall(request)
            val response = fetchCall!!.execute()
            fetchCall = null

            if (!response.isSuccessful) {
                return Result.failure(
                    Exception(context.getString(R.string.remote_server_error, response.code))
                )
            }

            val contentType = response.header("Content-Type") ?: ""
            val body = response.body.string()
            val trimmed = body.trimStart()

            val models = if (
                contentType.contains("application/json", ignoreCase = true) ||
                trimmed.startsWith("{") ||
                trimmed.startsWith("[")
            ) {
                // JSON 响应（file_manager API）
                parseJsonForZipFiles(body, config)
            } else {
                // HTML 响应（兼容 Nginx/Apache 目录列表等旧服务器）
                parseHtmlForZipFiles(body, listUrl)
            }

            Result.success(models)
        } catch (e: Exception) {
            fetchCall = null
            Result.failure(e)
        }
    }

    // ──────────────────────────────────────────────────────────────
    // 内部：解析 JSON（file_manager API 格式）
    // ──────────────────────────────────────────────────────────────

    /**
     * 解析 file_manager API 返回的 JSON，提取 .zip 文件列表。
     *
     * JSON 结构示例：
     * {
     *   "path": "/yolo26_models",
     *   "items": [
     *     { "id": 98, "name": "xxx.zip", "type": "file", "size": 333879178, ... },
     *     ...
     *   ]
     * }
     */
    private fun parseJsonForZipFiles(
        json: String,
        config: ServerConfig
    ): List<RemoteModel> {
        val models = mutableListOf<RemoteModel>()
        return try {
            val root = JSONObject(json)
            val items = root.optJSONArray("items") ?: return models

            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                if (item.optString("type") != "file") continue
                val name = item.optString("name")
                if (!name.endsWith(".zip", ignoreCase = true)) continue

                val size = item.optLong("size", 0L)
                val downloadUrl = buildDownloadUrl(config.serverRoot, config.dirPath, name)

                models.add(RemoteModel(name = name, url = downloadUrl, size = size))
            }
            models
        } catch (_: Exception) {
            models
        }
    }

    // ──────────────────────────────────────────────────────────────
    // 内部：解析 HTML（兼容旧服务器目录列表）
    // ──────────────────────────────────────────────────────────────

    /**
     * 从 HTML 目录页中提取 .zip 文件链接及文件大小。
     * 兼容 Nginx / Apache 等自动生成的目录列表页面。
     */
    private fun parseHtmlForZipFiles(html: String, listUrl: String): List<RemoteModel> {
        val models = mutableListOf<RemoteModel>()
        val linkPattern =
            """<a\s+href=["']([^"']+\.zip)["'][^>]*>([^<]*)</a>""".toRegex(RegexOption.IGNORE_CASE)
        val sizePattern =
            """(\d+(?:\.\d+)?)\s*([KMGT]?B?|字节?)""".toRegex(RegexOption.IGNORE_CASE)

        // 取 baseUrl（去掉 query 部分）用于拼接相对路径
        val baseUrl = listUrl.substringBefore("?")

        val lines = html.lines()
        for (i in lines.indices) {
            val line = lines[i]
            val match = linkPattern.find(line) ?: continue
            val href = match.groupValues[1]

            // 跳过目录链接（以 / 结尾或包含路径分隔符）
            if (href.endsWith("/") || href.contains("/")) continue

            val fullUrl = if (baseUrl.endsWith("/")) "$baseUrl$href" else "$baseUrl/$href"

            // 从当前行及后两行中匹配文件大小
            var size = 0L
            for (searchLine in lines.slice(i..minOf(i + 2, lines.lastIndex))) {
                val sizeMatch = sizePattern.findAll(searchLine).lastOrNull() ?: continue
                val value = sizeMatch.groupValues[1].toDoubleOrNull() ?: continue
                val unit = sizeMatch.groupValues[2].uppercase()
                size = when {
                    unit.startsWith("T") -> (value * 1024 * 1024 * 1024 * 1024).toLong()
                    unit.startsWith("G") -> (value * 1024 * 1024 * 1024).toLong()
                    unit.startsWith("M") -> (value * 1024 * 1024).toLong()
                    unit.startsWith("K") -> (value * 1024).toLong()
                    else -> value.toLong()
                }
                break
            }

            // 文件名：对 href 做 URL decode（兼容中文/空格等）
            val name = try { URLDecoder.decode(href, "UTF-8") } catch (_: Exception) { href }
            models.add(RemoteModel(name = name, url = fullUrl, size = size))
        }
        return models
    }

    // ──────────────────────────────────────────────────────────────
    // 下载到临时文件（支持断点续传）
    // ──────────────────────────────────────────────────────────────

    /**
     * 将远程模型下载到临时文件，支持断点续传。
     *
     * @param context         Android Context
     * @param modelUrl        下载 URL（由 buildDownloadUrl 生成，或 RemoteModel.url）
     * @param startOffset     已下载字节数，断点续传时传入上次 downloadedBytes（默认 0）
     * @param appendFile      断点续传时，上次已下载的部分文件（追加写入）
     * @param onProgress      进度回调：(进度 0-100，速度 字节/秒)；进度为 -1 表示未知总大小
     * @return                DownloadToTempResult
     */
    fun downloadToTempFile(
        context: Context,
        modelUrl: String,
        startOffset: Long = 0L,
        appendFile: File? = null,
        onProgress: ((progress: Int, speedBps: Long) -> Unit)? = null
    ): DownloadToTempResult {
        var call: okhttp3.Call? = null
        var totalBytesRead = startOffset

        try {
            val requestBuilder = Request.Builder().url(modelUrl)
            if (startOffset > 0) {
                requestBuilder.header("Range", "bytes=$startOffset-")
            }
            call = client.newCall(requestBuilder.build())
            currentCall = call

            val response = call.execute()

            // 200 OK（全量）或 206 Partial Content（续传）都算成功
            if (!response.isSuccessful && response.code != 206) {
                return DownloadToTempResult(
                    error = context.getString(R.string.remote_http_error, response.code)
                )
            }

            val body = response.body
            val contentLength = body.contentLength()
            // 总大小 = 已下载 + 本次剩余
            val totalSize = if (startOffset > 0 && contentLength > 0)
                startOffset + contentLength else contentLength

            // 从 URL 解析文件名（兼容 ?path=... 和普通路径）
            val fileName = resolveFileNameFromUrl(modelUrl)
            if (!fileName.endsWith(".zip", ignoreCase = true)) {
                return DownloadToTempResult(error = context.getString(R.string.remote_not_zip))
            }

            val tempFile = appendFile
                ?: File(context.cacheDir, "dl_${System.currentTimeMillis()}.zip")
            val output = if (startOffset > 0 && appendFile != null)
                FileOutputStream(appendFile, true)
            else
                tempFile.outputStream()

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
                            val progress = if (totalSize > 0)
                                ((totalBytesRead * 100) / totalSize).toInt().coerceAtMost(99)
                            else -1
                            onProgress?.invoke(progress, speed)
                        }
                    }
                }
            }

            currentCall = null
            onProgress?.invoke(100, 0L)
            return DownloadToTempResult(file = tempFile, downloadedBytes = totalBytesRead)

        } catch (e: SocketTimeoutException) {
            return DownloadToTempResult(
                error = context.getString(R.string.remote_download_timeout, e.message)
            )
        } catch (e: UnknownHostException) {
            return DownloadToTempResult(
                error = context.getString(R.string.remote_unknown_host, e.message)
            )
        } catch (e: ConnectException) {
            return DownloadToTempResult(
                error = context.getString(R.string.remote_connect_failed, e.message)
            )
        } catch (e: IOException) {
            val isCanceled = try { call?.isCanceled() == true } catch (_: Throwable) { false }
            return if (isCanceled) {
                // 主动取消 → 暂停，返回已下载部分供断点续传
                val partialFile = appendFile
                    ?: context.cacheDir.listFiles()?.maxByOrNull { it.lastModified() }
                DownloadToTempResult(
                    paused = true,
                    downloadedBytes = partialFile?.length() ?: 0L,
                    file = partialFile
                )
            } else {
                DownloadToTempResult(
                    error = context.getString(
                        R.string.remote_download_error, e.javaClass.simpleName, e.message
                    )
                )
            }
        } catch (e: Exception) {
            return DownloadToTempResult(
                error = context.getString(
                    R.string.remote_download_error, e.javaClass.simpleName, e.message
                )
            )
        } finally {
            currentCall = null
        }
    }

    // ──────────────────────────────────────────────────────────────
    // 下载 + 导入一体化
    // ──────────────────────────────────────────────────────────────

    /**
     * 下载远程模型并自动导入（下载完成后删除临时文件）。
     *
     * @param context    Android Context
     * @param modelUrl   下载 URL（由 buildDownloadUrl 生成，或 RemoteModel.url）
     * @param onProgress 进度回调
     * @return           DownloadResult
     */
    fun downloadModel(
        context: Context,
        modelUrl: String,
        onProgress: ((progress: Int, speedBps: Long) -> Unit)? = null
    ): DownloadResult {
        val dlResult = downloadToTempFile(context, modelUrl, onProgress = onProgress)
        if (dlResult.file == null) {
            return DownloadResult(
                success = false,
                packageName = null,
                modelCount = 0,
                error = dlResult.error ?: context.getString(R.string.error_download_failed)
            )
        }

        return try {
            val result = LocalModelManager.importFromArchive(
                context, android.net.Uri.fromFile(dlResult.file)
            )
            DownloadResult(
                success = result.success,
                packageName = result.packageName,
                modelCount = result.modelCount,
                error = result.error
            )
        } catch (e: Exception) {
            DownloadResult(
                success = false,
                packageName = null,
                modelCount = 0,
                error = context.getString(R.string.remote_import_error, e.message)
            )
        } finally {
            dlResult.file.delete()
        }
    }

    // ──────────────────────────────────────────────────────────────
    // 私有工具
    // ──────────────────────────────────────────────────────────────

    /**
     * 从下载 URL 解析文件名。
     * 兼容两种形式：
     *   - .../api/download?path=%2Fyolo26_models%2Fxxx.zip  →  xxx.zip
     *   - .../direct/path/xxx.zip                           →  xxx.zip
     */
    private fun resolveFileNameFromUrl(url: String): String {
        // 优先从 path 参数解析
        val pathParam = try {
            val encoded = url.substringAfter("path=", "").substringBefore("&")
            if (encoded.isNotEmpty())
                URLDecoder.decode(encoded, "UTF-8").substringAfterLast("/")
            else ""
        } catch (_: Exception) { "" }

        return pathParam.ifEmpty {
            // 回退：取 URL 最后一段
            url.substringAfterLast("/").substringAfterLast("%2F")
        }
    }
}