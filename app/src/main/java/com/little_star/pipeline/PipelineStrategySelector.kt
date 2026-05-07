package com.little_star.pipeline

import com.little_star.detector.model.AcceleratorMode
import com.little_star.detector.model.InferenceBackend

/**
 * NPU 缓冲区类型 — 用于区分 NPU 平台
 * 不同平台的 NPU 使用不同的缓冲区机制，决定了零拷贝路径是否可行
 */
enum class NpuBufferType {
    /** 非 NPU 场景 */
    UNKNOWN,
    /** Qualcomm Hexagon NPU — 使用 FastRpc 缓冲区，无法从 GL/AHWB 映射 */
    FASTRPC,
    /** MTK APU / Google Tensor TPU — 使用 AHardwareBuffer，支持 BLOB AHWB 零拷贝 */
    AHWB
}

/**
 * 管线策略选择器
 *
 * 根据推理后端、加速器、NPU 平台、前提条件，生成策略链并过滤可用项。
 * GL_ZEROCOPY（BLOB AHWB + compute shader）仅在 MTK/Tensor 平台且 AHWB 互操作可用时启用。
 */
object PipelineStrategySelector {

    /**
     * 获取完整策略链（不过滤前提条件）
     */
    fun getStrategyChain(
        backend: InferenceBackend,
        accelerator: AcceleratorMode,
        npuBufferType: NpuBufferType = NpuBufferType.UNKNOWN
    ): List<PipelineStrategy> {
        val base = listOf(PipelineStrategy.GL_TRANSIT, PipelineStrategy.CPU_PIPELINE)
        return when {
            // Native-NPU + MTK/Tensor（AHWB buffer）：GPU→BLOB AHWB→NPU 零拷贝
            backend == InferenceBackend.LITERT_NATIVE
                && accelerator == AcceleratorMode.NPU
                && npuBufferType == NpuBufferType.AHWB ->
                listOf(PipelineStrategy.GL_ZEROCOPY) + base

            else -> base
        }
    }

    /**
     * 获取可用策略链（过滤不满足前提条件的策略）
     */
    fun getAvailableStrategies(
        backend: InferenceBackend,
        accelerator: AcceleratorMode,
        glContextOk: Boolean,
        ahwbInteropOk: Boolean = false,
        npuBufferType: NpuBufferType = NpuBufferType.UNKNOWN
    ): List<PipelineStrategy> {
        return getStrategyChain(backend, accelerator, npuBufferType).filter { strategy ->
            // GL 产线策略需要 GL 上下文
            if (strategy.useGlPipeline && !glContextOk) return@filter false
            // BLOB AHWB 零拷贝需要 AHWB 互操作（MTK/Tensor 平台）
            if (strategy.requiresAhwbInterop && !ahwbInteropOk) return@filter false
            true
        }
    }
}
