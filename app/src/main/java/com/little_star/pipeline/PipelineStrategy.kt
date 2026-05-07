package com.little_star.pipeline

import androidx.annotation.StringRes
import com.little_star.detector.model.AcceleratorMode
import com.little_star.detector.model.InferenceBackend
import com.little_star.R

data class PipelineConfig(
    val backend: InferenceBackend?,
    val accelerator: AcceleratorMode?,
    val inputSize: Int,
    val centerCrop: Boolean
)

enum class PipelineStrategy(
    @param:StringRes val displayNameRes: Int,
    @param:StringRes val subtextRes: Int,
    val useGlPipeline: Boolean,
    val priority: Int,
    val requiresNative: Boolean,
    val requiresAhwbInterop: Boolean,
    val isZeroCopy: Boolean
) {
    GL_ZEROCOPY(
        displayNameRes = R.string.strategy_gl_zerocopy,
        subtextRes = R.string.strategy_gl_zerocopy_sub,
        useGlPipeline = true,
        priority = 0,
        requiresNative = true,
        requiresAhwbInterop = true,
        isZeroCopy = true
    ),

    GL_TRANSIT(
        displayNameRes = R.string.strategy_gl_transit,
        subtextRes = R.string.strategy_gl_transit_sub,
        useGlPipeline = true,
        priority = 1,
        requiresNative = false,
        requiresAhwbInterop = false,
        isZeroCopy = false
    ),

    CPU_PIPELINE(
        displayNameRes = R.string.strategy_cpu_pipeline,
        subtextRes = R.string.strategy_cpu_pipeline_sub,
        useGlPipeline = false,
        priority = 2,
        requiresNative = false,
        requiresAhwbInterop = false,
        isZeroCopy = false
    );
}
