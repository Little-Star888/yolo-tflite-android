package com.little_star.pipeline

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Build
import android.util.Log

/**
 * 管线前提条件检测
 *
 * 负责：
 * 1. GL 上下文健康检测（是否可以创建 EGL 上下文 + GLSurfaceView）
 * 2. CL-GL 互操作检测（OpenCL 能否导入 GL 纹理，目前固定 false）
 * 3. NPU 平台检测（SoC 特征 → NpuBufferType）
 */
object PipelinePrerequisites {

    private const val TAG = "PipelinePrerequisites"

    /** GL 上下文是否可用（懒加载缓存，进程生命周期内不变） */
    private var _glAvailable: Boolean? = null

    /** NPU 缓冲区类型（懒加载缓存） */
    private var _npuBufferType: NpuBufferType? = null

    /**
     * 检测 GL 上下文是否可用
     * 通过尝试创建离屏 EGL 上下文来验证
     */
    fun isGlContextAvailable(): Boolean {
        _glAvailable?.let {
            Log.d(TAG, "isGlContextAvailable: 缓存命中=$it")
            return it
        }

        Log.d(TAG, "isGlContextAvailable: 开始探测...")
        var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var context: EGLContext = EGL14.EGL_NO_CONTEXT
        var surface: EGLSurface = EGL14.EGL_NO_SURFACE

        val result = try {
            // 使用 run + label 实现早期退出，替代 Kotlin 不支持的 return@try
            run probe@{
                display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                if (display == EGL14.EGL_NO_DISPLAY) {
                    Log.w(TAG, "isGlContextAvailable: eglGetDisplay 返回 NO_DISPLAY")
                    return@probe false
                }

                val version = IntArray(2)
                if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                    Log.w(TAG, "isGlContextAvailable: eglInitialize 失败")
                    return@probe false
                }

                val configAttribs = intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_NONE
                )
                val configs = arrayOfNulls<EGLConfig>(1)
                val numConfigs = IntArray(1)
                if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0)
                    || numConfigs[0] <= 0
                ) {
                    Log.w(TAG, "isGlContextAvailable: eglChooseConfig 失败 (numConfigs=${numConfigs[0]})")
                    return@probe false
                }

                val contextAttribs = intArrayOf(
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                    EGL14.EGL_NONE
                )
                context = EGL14.eglCreateContext(
                    display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0
                )
                if (context == EGL14.EGL_NO_CONTEXT) {
                    Log.w(TAG, "isGlContextAvailable: eglCreateContext 失败")
                    return@probe false
                }

                val surfaceAttribs = intArrayOf(
                    EGL14.EGL_WIDTH, 1,
                    EGL14.EGL_HEIGHT, 1,
                    EGL14.EGL_NONE
                )
                surface = EGL14.eglCreatePbufferSurface(display, configs[0], surfaceAttribs, 0)
                if (surface == EGL14.EGL_NO_SURFACE) {
                    Log.w(TAG, "isGlContextAvailable: eglCreatePbufferSurface 失败")
                    return@probe false
                }

                EGL14.eglMakeCurrent(display, surface, surface, context)
                val glError = GLES20.glGetError()
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)

                val ok = glError == GLES20.GL_NO_ERROR
                if (!ok) {
                    Log.w(TAG, "isGlContextAvailable: GL错误码=0x${glError.toString(16)}")
                }
                ok
            }
        } catch (e: Exception) {
            Log.w(TAG, "isGlContextAvailable: 探测异常: ${e.message}")
            false
        } finally {
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            if (display != EGL14.EGL_NO_DISPLAY) EGL14.eglTerminate(display)
        }

        _glAvailable = result
        Log.i(TAG, "isGlContextAvailable: 探测完成=$result (已缓存)")
        return result
    }

    /** AHWB 互操作缓存 */
    private var _ahwbInteropAvailable: Boolean? = null

    /**
     * AHWB 互操作是否可用
     * 通过 LiteRT native 层查询，检测 BLOB AHWB 零拷贝路径是否可行
     * 仅 MTK/Tensor 平台上有效（NPU 使用 AHWB buffer type）
     */
    fun isAhwbInteropAvailable(): Boolean {
        _ahwbInteropAvailable?.let {
            Log.d(TAG, "isAhwbInteropAvailable: 缓存命中=$it")
            return it
        }

        Log.d(TAG, "isAhwbInteropAvailable: 开始探测(native层)...")
        val result = try {
            com.little_star.detector.impl.tflite.LiteRtNativeDetector.isAhwbGlInteropSupported()
        } catch (e: Exception) {
            Log.w(TAG, "isAhwbInteropAvailable: 探测异常: ${e.message}")
            false
        }

        _ahwbInteropAvailable = result
        Log.i(TAG, "isAhwbInteropAvailable: 探测完成=$result (已缓存) → " +
            if (result) "GL_ZEROCOPY可启用(MTK/Tensor)" else "GL_ZEROCOPY不可用")
        return result
    }

    /**
     * 检测 NPU 缓冲区类型
     * 通过 SoC 特征判断：Qualcomm → FastRpc, MTK/Tensor → AHWB
     */
    fun detectNpuBufferType(): NpuBufferType {
        _npuBufferType?.let {
            Log.d(TAG, "detectNpuBufferType: 缓存命中=$it")
            return it
        }

        val hardware = Build.HARDWARE.lowercase()
        val platform = getSystemProp("ro.board.platform").lowercase()
        val socModel = getSystemProp("ro.soc.model").lowercase()

        Log.d(TAG, "detectNpuBufferType: 探测中... hardware=$hardware platform=$platform soc=$socModel")

        val result = when {
            // Google Tensor (GS101 Pixel 6 / GS201 Pixel 7)
            platform.contains("gs1") || platform.contains("gs2") ||
                hardware.contains("tensor") || socModel.contains("tensor") -> {
                Log.i(TAG, "detectNpuBufferType: 匹配 Google Tensor → AHWB")
                NpuBufferType.AHWB
            }

            // MTK APU (Dimensity / Helio)
            hardware.contains("mt") || platform.contains("mt") ||
                socModel.contains("mt") || socModel.contains("dimensity") -> {
                Log.i(TAG, "detectNpuBufferType: 匹配 MTK APU → AHWB")
                NpuBufferType.AHWB
            }

            // Qualcomm Hexagon
            hardware.contains("qcom") ||
                platform.contains("msm") || platform.contains("sm") ||
                platform.contains("sdm") || platform.contains("sc") -> {
                Log.i(TAG, "detectNpuBufferType: 匹配 Qualcomm Hexagon → FASTRPC")
                NpuBufferType.FASTRPC
            }

            else -> {
                Log.w(TAG, "detectNpuBufferType: 未知SoC, 默认为UNKNOWN " +
                    "(hardware=$hardware platform=$platform soc=$socModel)")
                NpuBufferType.UNKNOWN
            }
        }

        _npuBufferType = result
        Log.i(TAG, "detectNpuBufferType: 结果=$result (已缓存) → " +
            if (result == NpuBufferType.AHWB) "GL_ZEROCOPY理论可用" else "GL_ZEROCOPY不可用")
        return result
    }

    /**
     * 读取系统属性
     */
    private fun getSystemProp(key: String): String {
        return try {
            Runtime.getRuntime()
                .exec(arrayOf("/system/bin/getprop", key))
                .inputStream
                .bufferedReader()
                .use { it.readText().trim() }
        } catch (e: Exception) {
            ""
        }
    }
}
