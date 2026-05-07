package com.little_star.detector.util

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 捕获 logcat 输出，解析 LiteRT delegate 日志
 * 用于检测 LiteRT 内部静默回退（不做异常，只记录日志的情况）
 */
object LogCapture {

    private const val TAG = "LogCapture"

    /** Delegate 类型关键词 */
    private const val XNNPACK_KEYWORD = "TfLiteXNNPackDelegate"
    private const val GPU_CL_KEYWORD = "LITERT_CL"
    private const val GPU_OPENGL_KEYWORD = "LITERT_OPENGL"
    private const val GPU_DELEGATE_KEYWORD = "TfLiteGpuDelegate"
    private const val NPU_KEYWORD = "NPU"

    /**
     * Delegate 类型
     */
    enum class DelegateType {
        NPU,
        GPU,
        CPU,
        UNKNOWN
    }

    /**
     * 静默回退原因（从 logcat 日志解析）
     */
    enum class FallbackReason {
        /** 高通 QNN 底层错误（上下文创建失败、DSP不可用等） */
        QNN_ERROR,
        /** JIT 编译失败（算子不支持、内核初始化失败等） */
        COMPILE_ERROR,
        /** QNN 编译时算子验证警告（编译"成功"但部分算子不支持，运行时推理可能失败） */
        QNN_VALIDATION_WARNING,
        /** 未检测到具体原因 */
        UNKNOWN
    }

    /**
     * 日志捕获结果，包含实际 delegate 类型、回退原因和 JIT 编译状态
     */
    data class CaptureResult(
        val delegateType: DelegateType,
        val fallbackReason: FallbackReason = FallbackReason.UNKNOWN,
        /** 是否命中了 JIT 编译缓存（"from cached model" 出现 = 命中） */
        val jitCacheHit: Boolean = false
    )

    /**
     * 捕获最近的 LiteRT 日志，解析实际使用的 delegate 和回退原因
     * 在 CompiledModel.create() 成功后调用
     *
     * 使用轮询策略：每 [pollIntervalMs] 毫秒 dump 一次 logcat，
     * 一旦检测到明确的 delegate 类型就立即返回，避免盲目等待。
     *
     * @param timeoutMs 最大等待毫秒数（超时返回已检测到的结果）
     * @param pollIntervalMs 轮询间隔毫秒数
     * @return 捕获结果（delegate 类型 + 回退原因）
     */
    @JvmStatic
    fun capture(timeoutMs: Long = 2000, pollIntervalMs: Long = 200): CaptureResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        var allResults = mutableListOf<String>()

        try {
            while (System.currentTimeMillis() < deadline) {
                // dump 当前 logcat buffer
                val results = dumpLogcat()
                allResults.addAll(results)

                // 尝试解析 delegate 类型
                val delegateType = parseDelegateType(results)

                // 检测到明确结果，立即返回
                if (delegateType != DelegateType.UNKNOWN) {
                    val fallbackReason = parseFallbackReason(results)
                    val jitCacheHit = parseJitCompilation(results)
                    Log.d(TAG, "LogCapture early return at ${timeoutMs - (deadline - System.currentTimeMillis())}ms")
                    return CaptureResult(delegateType, fallbackReason, jitCacheHit)
                }

                // 未检测到，等待下一轮轮询
                val remaining = deadline - System.currentTimeMillis()
                if (remaining > 0) {
                    Thread.sleep(minOf(pollIntervalMs, remaining))
                }
            }

            // 超时，用累积的全部日志做最终解析
            if (allResults.isEmpty()) {
                allResults = dumpLogcat()
            }
        } catch (e: InterruptedException) {
            Log.w(TAG, "LogCapture interrupted")
        } catch (e: Exception) {
            Log.e(TAG, "Error in LogCapture", e)
        }

        val delegateType = parseDelegateType(allResults)
        val fallbackReason = parseFallbackReason(allResults)
        val jitCacheHit = parseJitCompilation(allResults)
        return CaptureResult(delegateType, fallbackReason, jitCacheHit)
    }

    /**
     * 一次性 dump 当前 logcat buffer，过滤 LiteRT 相关日志行
     */
    private fun dumpLogcat(): MutableList<String> {
        val results = mutableListOf<String>()
        try {
            val process = Runtime.getRuntime().exec(arrayOf(
                "logcat", "-d", "-v", "threadtime"
            ))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (true) {
                line = reader.readLine()
                if (line == null) break
                // 保留 litert、tflite 以及 MediaTek NPU 相关的日志行
                if (line.contains("litert", ignoreCase = true) ||
                    line.contains("tflite", ignoreCase = true) ||
                    line.contains("neuron", ignoreCase = true) ||
                    line.contains("mediatek", ignoreCase = true)) {
                    results.add(line)
                }
            }
            process.waitFor()
            process.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error dumping logcat", e)
        }
        return results
    }

    /**
     * 清空 logcat buffer
     * 在 CompiledModel.create() 之前调用，确保只读到 create() 产生的日志
     */
    @JvmStatic
    fun clearBuffer() {
        try {
            Runtime.getRuntime().exec(arrayOf("logcat", "-c")).waitFor()
            Log.d(TAG, "Logcat buffer cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear logcat buffer", e)
        }
    }

    /**
     * 从日志行列表中解析 delegate 类型
     * 按优先级：XNNPack (CPU) > LITERT_CL (GPU) > NPU
     */
    private fun parseDelegateType(lines: List<String>): DelegateType {
        var foundType = DelegateType.UNKNOWN

        // 调试：打印所有日志行
        Log.d(TAG, "LogCapture received ${lines.size} log lines")
        for (line in lines) {
            Log.d(TAG, "LOG: $line")
            // XNNPack 出现说明 fallback 到了 CPU
            if (line.contains(XNNPACK_KEYWORD)) {
                foundType = DelegateType.CPU
                Log.d(TAG, "Detected XNNPack (CPU)")
                break // CPU 优先级最高，确认就退出
            }
            // LITERT_CL / LITERT_OPENGL 都是 GPU delegate
            if (line.contains(GPU_CL_KEYWORD) || line.contains(GPU_OPENGL_KEYWORD) || line.contains(GPU_DELEGATE_KEYWORD)) {
                foundType = DelegateType.GPU
                Log.d(TAG, "Detected LITERT_CL/LITERT_OPENGL/GPU (GPU)")
                // 不 break，继续找是否有 XNNPack
            }
            // NPU 检测：
            // 1. 直接关键词 "NPU"
            // 2. 高通 AOT 预编译特征（DispatchDelegate / qnn graph / qnn_partition）
            // 3. MediaTek Neuron SDK 特征（mediatek / neuron / LiteRtDispatch_MediaTek）
            val isNpuIndicator = line.contains(NPU_KEYWORD) ||
                    line.contains("DispatchDelegate", ignoreCase = true) ||
                    line.contains("qnn graph", ignoreCase = true) ||
                    line.contains("qnn_partition", ignoreCase = true) ||
                    line.contains("mediatek", ignoreCase = true) ||
                    line.contains("LiteRtDispatch_MediaTek", ignoreCase = true) ||
                    (line.contains("neuron", ignoreCase = true) &&
                            line.contains("litert", ignoreCase = true))
            if (isNpuIndicator && foundType != DelegateType.GPU) {
                foundType = DelegateType.NPU
                Log.d(TAG, "Detected NPU keyword (NPU)")
            }
        }

        Log.d(TAG, "Final delegate type: $foundType")
        return foundType
    }

    /**
     * 从日志行列表中解析回退原因
     * 检测 QNN 底层错误或 JIT 编译失败的关键特征
     */
    private fun parseFallbackReason(lines: List<String>): FallbackReason {
        // 第一遍：检测明确的错误（QNN_ERROR > COMPILE_ERROR）
        for (line in lines) {
            // 高通 QNN 底层错误：上下文创建失败、shell 打开失败
            if (line.contains("Failed to create QNN context", ignoreCase = true) ||
                line.contains("open_shell failed", ignoreCase = true) ||
                line.contains("Failed to create context from context binary", ignoreCase = true) ||
                line.contains("device create", ignoreCase = true)) {
                Log.d(TAG, "Detected QNN fallback reason")
                return FallbackReason.QNN_ERROR
            }
            // JIT 编译失败：内核初始化失败、算子不支持、模型创建失败
            if (line.contains("Failed to initialize kernel", ignoreCase = true) ||
                line.contains("Failed to compile", ignoreCase = true) ||
                line.contains("Failed to create CompiledModel", ignoreCase = true) ||
                line.contains("unresolved custom op", ignoreCase = true) ||
                line.contains("failed to prepare", ignoreCase = true) ||
                line.contains("not supported by GPU delegate", ignoreCase = true)) {
                Log.d(TAG, "Detected compile fallback reason")
                return FallbackReason.COMPILE_ERROR
            }
        }

        // 第二遍：检测 QNN 算子验证警告（编译可能"成功"但运行时会失败）
        // 支持多行解析："Failed to validate op Conv2d_831" + 后几行中的 ", error: 3110"
        val validationWarningIndices = mutableListOf<Int>()
        for (i in lines.indices) {
            val line = lines[i]
            // 检测算子验证失败标记（可能在多行中的第一行）
            if (line.contains("Failed to validate op", ignoreCase = true) ||
                line.contains("failed to validate op", ignoreCase = true)) {
                validationWarningIndices.add(i)
            }
        }
        // 如果找到 "Failed to validate op"，检查其后若干行是否包含 error: 3110
        for (startIdx in validationWarningIndices) {
            val searchRange = lines.slice(startIdx until minOf(startIdx + 5, lines.size))
            for (searchLine in searchRange) {
                if (searchLine.contains("error", ignoreCase = true) &&
                    searchLine.contains("3110", ignoreCase = true)) {
                    Log.d(TAG, "Detected QNN validation warning (multi-line)")
                    return FallbackReason.QNN_VALIDATION_WARNING
                }
            }
        }
        // 也检测单行的 error_code / error 3110 qnn 组合
        for (line in lines) {
            val hasOpValidationFailure =
                (line.contains("op code", ignoreCase = true) &&
                        line.contains("not supported", ignoreCase = true)) ||
                (line.contains("opcode", ignoreCase = true) &&
                        line.contains("not supported", ignoreCase = true)) ||
                (line.contains("error_code", ignoreCase = true) &&
                        line.contains("3110", ignoreCase = true)) ||
                (line.contains("error", ignoreCase = true) &&
                        line.contains("3110", ignoreCase = true) &&
                        line.contains("qnn", ignoreCase = true))
            if (hasOpValidationFailure) {
                Log.d(TAG, "Detected QNN validation warning (single-line)")
                return FallbackReason.QNN_VALIDATION_WARNING
            }
        }

        return FallbackReason.UNKNOWN
    }

    /**
     * 检测是否命中了 JIT 编译缓存
     * 日志中出现 "Flatbuffer model initialized from cached model" 说明从缓存加载（命中缓存，无需新编译）
     * 如果没有出现，说明需要新 JIT 编译或使用了 AOT 预编译模型
     */
    private fun parseJitCompilation(lines: List<String>): Boolean {
        for (line in lines) {
            if (line.contains("from cached model", ignoreCase = true)) {
                Log.d(TAG, "Detected JIT cache hit (from cached model)")
                return true
            }
        }
        Log.d(TAG, "No JIT cache hit detected (will require new compilation)")
        return false
    }
}
