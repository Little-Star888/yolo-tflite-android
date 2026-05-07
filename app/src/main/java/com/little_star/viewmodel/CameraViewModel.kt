package com.little_star.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import com.little_star.detector.model.AcceleratorMode
import com.little_star.detector.model.InferenceBackend
import com.little_star.pipeline.NpuBufferType
import com.little_star.pipeline.PipelinePrerequisites
import com.little_star.pipeline.PipelineStrategy
import com.little_star.pipeline.PipelineStrategySelector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 相机模式 ViewModel
 * 管理摄像头相关状态 + xselect 管线策略选择
 */
class CameraViewModel : ViewModel() {

    companion object {
        private const val TAG = "CameraViewModel"
    }

    /** 摄像头方向（0=后置, 1=前置） */
    private val _cameraFacing = MutableStateFlow(0)
    val cameraFacing: StateFlow<Int> = _cameraFacing.asStateFlow()

    /** 是否正在检测 */
    private val _isDetecting = MutableStateFlow(false)
    val isDetecting: StateFlow<Boolean> = _isDetecting.asStateFlow()

    /** 检测结果文本 */
    private val _resultText = MutableStateFlow("Camera ready. Tap \"Start Detect\" to begin.")
    val resultText: StateFlow<String> = _resultText.asStateFlow()

    // ─── xselect 管线策略管理 ───

    /** 完整策略链（含不可用项，用于 UI 展示灰色菜单项） */
    private val _strategyChain = MutableStateFlow<List<PipelineStrategy>>(emptyList())
    val strategyChain: StateFlow<List<PipelineStrategy>> = _strategyChain.asStateFlow()

    /** 当前可用策略列表（满足前提条件的子集） */
    private val _availableStrategies = MutableStateFlow<List<PipelineStrategy>>(emptyList())
    val availableStrategies: StateFlow<List<PipelineStrategy>> = _availableStrategies.asStateFlow()

    /** 用户手动选择的策略（null = 自动模式） */
    private val _selectedStrategy = MutableStateFlow<PipelineStrategy?>(null)
    val selectedStrategy: StateFlow<PipelineStrategy?> = _selectedStrategy.asStateFlow()

    /** 当前生效的策略（自动模式取最优可用，手动模式取用户选择） */
    private val _effectiveStrategy = MutableStateFlow(PipelineStrategy.GL_TRANSIT)
    val effectiveStrategy: StateFlow<PipelineStrategy> = _effectiveStrategy.asStateFlow()

    /** 是否处于自动模式 */
    private val _isAutoMode = MutableStateFlow(true)
    val isAutoMode: StateFlow<Boolean> = _isAutoMode.asStateFlow()

    /** 降级提示事件（一次性，UI 消费后重置） */
    private val _degradationTriggered = MutableStateFlow(false)
    val degradationTriggered: StateFlow<Boolean> = _degradationTriggered.asStateFlow()

    // ─── 内部缓存 ───

    private var cachedBackend: InferenceBackend? = null
    private var cachedAccelerator: AcceleratorMode? = null
    private var cachedNpuBufferType: NpuBufferType = NpuBufferType.UNKNOWN

    /**
     * 进入相机时调用，初始化策略上下文
     *
     * @param backend 推理后端
     * @param accelerator 加速器模式
     * @param glHealthy GL 上下文是否可用
     * @param ahwbOk AHWB 互操作是否可用（MTK/Tensor 零拷贝）
     * @param npuBufferType NPU 缓冲区类型（平台区分）
     */
    fun initializeContext(
        backend: InferenceBackend,
        accelerator: AcceleratorMode,
        glHealthy: Boolean = true,
        ahwbOk: Boolean = false,
        npuBufferType: NpuBufferType = NpuBufferType.UNKNOWN
    ) {
        cachedBackend = backend
        cachedAccelerator = accelerator
        cachedNpuBufferType = npuBufferType

        // 获取完整策略链（含不可用项，用于 UI 灰色显示）
        val fullChain = PipelineStrategySelector.getStrategyChain(backend, accelerator, npuBufferType)
        _strategyChain.value = fullChain

        // 获取可用策略
        val available = PipelineStrategySelector.getAvailableStrategies(
            backend, accelerator, glHealthy, ahwbOk,
            npuBufferType = npuBufferType
        )
        _availableStrategies.value = available

        // 自动模式：取最优可用策略
        if (_isAutoMode.value) {
            val best = available.minByOrNull { it.priority } ?: PipelineStrategy.CPU_PIPELINE
            _effectiveStrategy.value = best
            _selectedStrategy.value = null
        }

        // 详细诊断日志
        val backendLabel = when (backend) {
            InferenceBackend.LITERT_NATIVE -> "Native"
            InferenceBackend.LITERT_JAVA -> "Java"
        }
        val accelLabel = when (accelerator) {
            AcceleratorMode.CPU -> "CPU"
            AcceleratorMode.GPU -> "GPU"
            AcceleratorMode.NPU -> "NPU"
        }
        val chainDesc = fullChain.joinToString(" → ") { s ->
            val marker = if (s in available) "" else "✗"
            "$marker${s.name}"
        }
        Log.i(TAG, "═══════ xselect上下文初始化 ═══════")
        Log.i(TAG, "  组合: ${backendLabel}-${accelLabel} (npuBuf=$npuBufferType)")
        Log.i(TAG, "  前提: glHealthy=$glHealthy ahwbOk=$ahwbOk")
        Log.i(TAG, "  策略链: $chainDesc")
        Log.i(TAG, "  可用策略: ${available.map { it.name }}")
        Log.i(TAG, "  生效策略: ${_effectiveStrategy.value.name} (mode=${if (_isAutoMode.value) "auto" else "manual"})")
        Log.i(TAG, "════════════════════════════════════")
    }

    /**
     * GL 上下文就绪回调（GLSurfaceView 创建成功后）
     * 当 GL 从不可用恢复时，重新计算可用策略
     */
    fun onGlContextReady() {
        val backend = cachedBackend ?: return
        val accelerator = cachedAccelerator ?: return

        val previousAvailable = _availableStrategies.value
        val previousEffective = _effectiveStrategy.value

        val available = PipelineStrategySelector.getAvailableStrategies(
            backend, accelerator,
            glContextOk = true,
            ahwbInteropOk = PipelinePrerequisites.isAhwbInteropAvailable(),
            npuBufferType = cachedNpuBufferType
        )
        _availableStrategies.value = available

        // 检查是否有新的 GL 策略恢复可用
        val recovered = available.filter { it !in previousAvailable }
        if (recovered.isNotEmpty()) {
            Log.i(TAG, "GL上下文就绪: 恢复可用策略 ${recovered.map { it.name }}")
        }

        // 自动模式下重新选取最优
        if (_isAutoMode.value) {
            val best = available.minByOrNull { it.priority } ?: PipelineStrategy.CPU_PIPELINE
            if (best != previousEffective) {
                _effectiveStrategy.value = best
                Log.i(TAG, "GL上下文就绪, 自动切换: ${previousEffective.name} → ${best.name}")
            }
        }
        Log.i(TAG, "GL上下文就绪, 可用策略: ${available.map { it.name }}")
    }

    /**
     * 手动选择策略，退出自动模式
     * 切换前先停止检测，避免管线在活跃状态下被销毁导致黑屏
     */
    fun selectStrategy(strategy: PipelineStrategy) {
        val previous = _effectiveStrategy.value
        _isDetecting.value = false
        _selectedStrategy.value = strategy
        _isAutoMode.value = false
        _effectiveStrategy.value = strategy
        Log.i(TAG, "手动选择策略: ${previous.name} → ${strategy.name} (auto→off)")
    }

    /**
     * 切回自动模式，自动选取最优可用策略
     * 切换前先停止检测，避免管线在活跃状态下被销毁导致黑屏
     */
    fun selectAuto() {
        val previous = _effectiveStrategy.value
        _isDetecting.value = false
        _selectedStrategy.value = null
        _isAutoMode.value = true
        val best = _availableStrategies.value.minByOrNull { it.priority }
            ?: PipelineStrategy.CPU_PIPELINE
        _effectiveStrategy.value = best
        Log.i(TAG, "切回自动模式: ${previous.name} → ${best.name} " +
            "(可用: ${_availableStrategies.value.map { it.name }})")
    }

    /**
     * 运行时降级：当前策略失败 → 移除 GL 产线 → 切换到 CPU 产线
     */
    fun onCurrentStrategyFailed() {
        val current = _effectiveStrategy.value
        val available = _availableStrategies.value.toMutableList()

        // 先停止检测，避免管线在活跃状态下被销毁
        _isDetecting.value = false

        // 从可用列表移除所有 GL 产线策略（防止无限循环降级）
        val removed = available.filter { it.useGlPipeline }
        available.removeAll { it.useGlPipeline }
        _availableStrategies.value = available

        // 切换到 CPU 产线
        val fallback = available.firstOrNull() ?: PipelineStrategy.CPU_PIPELINE
        _effectiveStrategy.value = fallback
        _selectedStrategy.value = null
        _isAutoMode.value = true

        // 发出降级提示
        _degradationTriggered.value = true

        Log.w(TAG, "═══════ 运行时降级 ═══════")
        Log.w(TAG, "  触发原因: ${current.name} 策略失败")
        Log.w(TAG, "  降级路径: ${current.name} → ${fallback.name}")
        Log.w(TAG, "  移除策略: ${removed.map { it.name }}")
        Log.w(TAG, "  剩余策略: ${available.map { it.name }}")
        Log.w(TAG, "  模式切换: 手动 → 自动（降级强制切回自动）")
        Log.w(TAG, "══════════════════════════════")
    }

    /** 消费降级事件（UI 显示后调用，防止重复弹出 Toast） */
    fun consumeDegradation() {
        _degradationTriggered.value = false
    }

    // ─── 原有方法 ───

    /** 切换前后摄像头 */
    fun switchCamera() {
        _cameraFacing.value = 1 - _cameraFacing.value
    }

    /** 切换检测状态（开始/停止） */
    fun toggleDetecting() {
        _isDetecting.value = !_isDetecting.value
        if (!_isDetecting.value) {
            _resultText.value = "Detection stopped"
        }
    }

    /** 更新检测结果文本 */
    fun updateResultText(text: String) {
        _resultText.value = text
    }
}
