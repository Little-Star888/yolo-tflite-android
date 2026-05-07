package com.little_star.gl

import android.graphics.SurfaceTexture
import android.hardware.HardwareBuffer
import android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES
import android.opengl.GLES20
import android.opengl.GLES20.GL_CLAMP_TO_EDGE
import android.opengl.GLES20.GL_COLOR_ATTACHMENT0
import android.opengl.GLES20.GL_COLOR_BUFFER_BIT
import android.opengl.GLES20.GL_COMPILE_STATUS
import android.opengl.GLES20.GL_FLOAT
import android.opengl.GLES20.GL_FRAGMENT_SHADER
import android.opengl.GLES20.GL_FRAMEBUFFER
import android.opengl.GLES20.GL_FRAMEBUFFER_COMPLETE
import android.opengl.GLES20.GL_LINEAR
import android.opengl.GLES20.GL_LINK_STATUS
import android.opengl.GLES20.GL_RGBA
import android.opengl.GLES20.GL_TEXTURE0
import android.opengl.GLES20.GL_TEXTURE_2D
import android.opengl.GLES20.GL_TEXTURE_MAG_FILTER
import android.opengl.GLES20.GL_TEXTURE_MIN_FILTER
import android.opengl.GLES20.GL_TEXTURE_WRAP_S
import android.opengl.GLES20.GL_TEXTURE_WRAP_T
import android.opengl.GLES20.GL_TRIANGLE_STRIP
import android.opengl.GLES20.GL_UNSIGNED_BYTE
import android.opengl.GLES20.GL_VERTEX_SHADER
import android.opengl.GLES20.glActiveTexture
import android.opengl.GLES20.glAttachShader
import android.opengl.GLES20.glBindFramebuffer
import android.opengl.GLES20.glBindTexture
import android.opengl.GLES20.glCheckFramebufferStatus
import android.opengl.GLES20.glClear
import android.opengl.GLES20.glClearColor
import android.opengl.GLES20.glCompileShader
import android.opengl.GLES20.glCreateProgram
import android.opengl.GLES20.glCreateShader
import android.opengl.GLES20.glDeleteProgram
import android.opengl.GLES20.glDeleteShader
import android.opengl.GLES20.glDisableVertexAttribArray
import android.opengl.GLES20.glDrawArrays
import android.opengl.GLES20.glEnableVertexAttribArray
import android.opengl.GLES20.glFramebufferTexture2D
import android.opengl.GLES20.glGenFramebuffers
import android.opengl.GLES20.glGenTextures
import android.opengl.GLES20.glGetAttribLocation
import android.opengl.GLES20.glGetProgramInfoLog
import android.opengl.GLES20.glGetProgramiv
import android.opengl.GLES20.glGetShaderInfoLog
import android.opengl.GLES20.glGetShaderiv
import android.opengl.GLES20.glGetUniformLocation
import android.opengl.GLES20.glLinkProgram
import android.opengl.GLES20.glReadPixels
import android.opengl.GLES20.glShaderSource
import android.opengl.GLES20.glTexImage2D
import android.opengl.GLES20.glTexParameteri
import android.opengl.GLES20.glUniform1i
import android.opengl.GLES20.glUniform2f
import android.opengl.GLES20.glUniformMatrix4fv
import android.opengl.GLES20.glUseProgram
import android.opengl.GLES20.glVertexAttribPointer
import android.opengl.GLES20.glViewport
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class CameraGlRenderer(
    private val inputSize: Int,
    private val callback: Callback,
    /** 分类任务使用 center-crop（最短边缩放+中心裁切），其他任务使用 letterbox */
    private val centerCrop: Boolean = false,
    /** BLOB AHWB 模式：使用 BLOB AHardwareBuffer + SSBO + compute shader 实现零拷贝 */
    private val useBlobAhwb: Boolean = false
) : GLSurfaceView.Renderer {

    interface Callback {
        fun onFramePreprocessed(
            floatArray: FloatArray,
            imgWidth: Int,
            imgHeight: Int,
            letterboxScale: Float,
            letterboxOffsetX: Float,
            letterboxOffsetY: Float,
            rotationDegrees: Int
        )

        fun getRotationDegrees(): Int
        fun isFrontCamera(): Boolean

    }

    /**
     * AHWB零拷贝回调 — GPU渲染到AHWB后，直接传递AHardwareBuffer给NPU
     * 适用于 MTK / Google Tensor NPU（GPU→AHWB→NPU）
     * 返回 true 表示零拷贝已处理，false 表示回退到 CPU 回读路径
     */
    interface AhwbZeroCopyCallback {
        fun onFramePreprocessedAhwb(
            hardwareBuffer: HardwareBuffer,
            imgWidth: Int,
            imgHeight: Int,
            letterboxScale: Float,
            letterboxOffsetX: Float,
            letterboxOffsetY: Float,
            rotationDegrees: Int
        ): Boolean
    }

    companion object {
        private const val TAG = "CameraGlRenderer"
        private const val FLOAT_SIZE = 4
        private const val STRIDE = 4 * FLOAT_SIZE // x, y, s, t per vertex
    }

    // Camera SurfaceTexture
    private var cameraTexId = 0
    private var cameraTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null

    // FBO for preprocessing
    private var fboId = 0
    private var fboTexId = 0

    // Shader programs
    private var displayProgram = 0
    private var preprocessProgram = 0

    // Attribute/uniform locations
    private var displayPosLoc = 0
    private var displayTexCoordLoc = 0
    private var displayTransformLoc = 0
    private var displayTexLoc = 0
    private var displayDispScaleLoc = 0

    private var prepPosLoc = 0
    private var prepTexCoordLoc = 0
    private var prepTexLoc = 0
    private var prepScaleLoc = 0
    private var prepOffsetLoc = 0
    private var prepTransformLoc = 0
    private var prepNormScaleLoc = 0
    private var prepNormOffsetLoc = 0

    // Vertex buffer
    private var quadBuffer: FloatBuffer? = null

    // SurfaceTexture transform matrix
    private val stTransformMatrix = FloatArray(16)

    // Letterbox params — shader 用（归一化 [0,1]，x 和 y 方向独立）
    private var shaderScaleX = 0f
    private var shaderScaleY = 0f
    private var shaderOffsetX = 0f
    private var shaderOffsetY = 0f

    // Letterbox params — 回调用（像素空间，与 CPU 的 LetterboxTransform.compute 一致）
    private var pixelScale = 0f
    private var pixelOffsetX = 0f
    private var pixelOffsetY = 0f

    // 旋转后的有效图像尺寸（回调传给检测器用于 LetterboxTransform.imgW/imgH）
    private var effectiveWidth = 0
    private var effectiveHeight = 0

    private var frameWidth = 0
    private var frameHeight = 0

    // glReadPixels output buffer (RGBA bytes, uint8 path)
    private var pixelBuffer: ByteBuffer? = null

    // Decoded float array (inputSize * inputSize * 3)
    private var floatOutput: FloatArray? = null

    // Float FBO state
    private var useFloatFbo = false
    private var floatReadBuf: ByteBuffer? = null      // RGBA float readback buffer
    private var rgbaTemp: FloatArray? = null           // Pre-allocated temp for RGBA→RGB

    // Saved display surface dimensions
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    private var glSurfaceView: GLSurfaceView? = null

    /** AHWB零拷贝回调（null = 未启用AHWB零拷贝） */
    private var ahwbZeroCopyCallback: AhwbZeroCopyCallback? = null

    /** AHWB对象（AHWB模式下持有引用，防止GC） */
    @Volatile
    private var ahwbHardwareBuffer: HardwareBuffer? = null

    /** BLOB AHWB 模式资源（方案A零拷贝） */
    private var blobSsboId = 0
    @Volatile
    private var blobHardwareBuffer: HardwareBuffer? = null

    /** FBO 纹理 ID（公共只读，供零拷贝路径获取） */
    val fboTextureId: Int get() = fboTexId

    /** FBO 纹理内部格式：GLES30.GL_RGBA32F 或 GL_RGBA */
    val fboTextureInternalFormat: Int get() =
        if (useFloatFbo) GLES30.GL_RGBA32F else GL_RGBA

    /** FBO 纹理格式：始终为 GL_RGBA */
    val fboTextureFormat: Int get() = GL_RGBA

    /** FBO 纹理数据类型：GL_FLOAT 或 GL_UNSIGNED_BYTE */
    val fboTextureType: Int get() =
        if (useFloatFbo) GL_FLOAT else GL_UNSIGNED_BYTE

    fun setGlSurfaceView(view: GLSurfaceView) {
        glSurfaceView = view
    }

    /** 设置AHWB零拷贝回调（传 null 禁用AHWB零拷贝） */
    fun setAhwbZeroCopyCallback(callback: AhwbZeroCopyCallback?) {
        ahwbZeroCopyCallback = callback
    }

    /**
     * 设置 AHWB 模式的 HardwareBuffer（在 setRenderer 之前调用）
     * 必须在 onSurfaceCreated 之前设置
     */
    fun setAhwbHardwareBuffer(hardwareBuffer: HardwareBuffer) {
        this.ahwbHardwareBuffer = hardwareBuffer
    }

    fun isReady(): Boolean = cameraTexture != null

    fun createCameraSurface(width: Int, height: Int): Surface {
        frameWidth = width
        frameHeight = height
        computeLetterbox(width, height)
        val tex = cameraTexture ?: throw IllegalStateException("Renderer not initialized")
        tex.setDefaultBufferSize(width, height)
        val surface = Surface(tex)
        cameraSurface = surface
        return surface
    }

    fun release() {
        // 在 GL 线程上清理 BLOB AHWB 资源
        if (useBlobAhwb) {
            try {
                glSurfaceView?.queueEvent {
                    try {
                        com.little_star.detector.impl.tflite.LiteRtNativeDetector
                            .nativeDestroyBlobSsbo()
                    } catch (e: Exception) {
                        android.util.Log.w(TAG, "BLOB AHWB: release清理异常: ${e.message}")
                    }
                }
            } catch (_: Exception) {}
        }
        // 释放 Surface 和 SurfaceTexture
        cameraSurface?.release()
        cameraSurface = null
        cameraTexture?.release()
        cameraTexture = null
        glSurfaceView = null
    }


    // =========================================================================
    // GLSurfaceView.Renderer
    // =========================================================================

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Create camera external OES texture
        val texIds = IntArray(1)
        glGenTextures(1, texIds, 0)
        cameraTexId = texIds[0]
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, cameraTexId)
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)

        cameraTexture = SurfaceTexture(cameraTexId).apply {
            setOnFrameAvailableListener({ _ ->
                glSurfaceView?.requestRender()
            }, null)
        }

        // 注入 EGL 上下文到 native 层（仅 BLOB AHWB 零拷贝模式需要）
        // MTK/Tensor NPU 需要共享 GL 纹理，非零拷贝模式不需要
        if (useBlobAhwb) {
            try {
                val eglOk = com.little_star.detector.impl.tflite.LiteRtNativeDetector
                    .nativeInjectEglContext()
                android.util.Log.i(TAG, "EGL上下文注入: ${if (eglOk) "成功" else "失败"}")
            } catch (e: Exception) {
                android.util.Log.w(TAG, "EGL上下文注入异常: ${e.message}")
            }
        }

        // Setup FBO（BLOB AHWB 模式 > 常规模式）
        if (useBlobAhwb) {
            // BLOB AHWB 模式：创建 BLOB AHWB + SSBO + compute shader
            // 仍然需要常规 RGBA32F FBO 用于 fragment shader 预处理
            setupBlobAhwbSsbo()
            if (blobSsboId == 0) {
                // BLOB 创建失败，回退到常规 FBO
                android.util.Log.e(TAG, "BLOB AHWB: 初始化失败 → 回退常规FBO")
            }
            // 无论 BLOB 是否成功，都需要常规 FBO
            setupFbo()
        } else {
            setupFbo()
        }

        // Compile shaders
        displayProgram = createProgram(ShaderSource.DISPLAY_VS, ShaderSource.DISPLAY_FS)
        val preprocessFs =
            if (useFloatFbo) ShaderSource.PREPROCESS_FS_FLOAT else ShaderSource.PREPROCESS_FS
        preprocessProgram = createProgram(ShaderSource.PREPROCESS_VS, preprocessFs)

        // Get locations
        displayPosLoc = glGetAttribLocation(displayProgram, "aPosition")
        displayTexCoordLoc = glGetAttribLocation(displayProgram, "aTexCoord")
        displayTransformLoc = glGetUniformLocation(displayProgram, "uTransformMatrix")
        displayTexLoc = glGetUniformLocation(displayProgram, "uCameraTexture")
        displayDispScaleLoc = glGetUniformLocation(displayProgram, "uDispScale")

        prepPosLoc = glGetAttribLocation(preprocessProgram, "aPosition")
        prepTexCoordLoc = glGetAttribLocation(preprocessProgram, "aTexCoord")
        prepTexLoc = glGetUniformLocation(preprocessProgram, "uCameraTexture")
        prepScaleLoc = glGetUniformLocation(preprocessProgram, "uScale")
        prepOffsetLoc = glGetUniformLocation(preprocessProgram, "uOffset")
        prepTransformLoc = glGetUniformLocation(preprocessProgram, "uTransformMatrix")
        prepNormScaleLoc = glGetUniformLocation(preprocessProgram, "uNormScale")
        prepNormOffsetLoc = glGetUniformLocation(preprocessProgram, "uNormOffset")

        // Create vertex buffer
        quadBuffer = ByteBuffer.allocateDirect(ShaderSource.QUAD_VERTICES.size * FLOAT_SIZE)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(ShaderSource.QUAD_VERTICES)
            .apply { flip() }

        // Allocate output buffers
        floatOutput = FloatArray(inputSize * inputSize * 3)
        if (useFloatFbo) {
            floatReadBuf = ByteBuffer.allocateDirect(inputSize * inputSize * 4 * 4)
                .order(ByteOrder.nativeOrder())
            rgbaTemp = FloatArray(inputSize * inputSize * 4)
        } else {
            pixelBuffer = ByteBuffer.allocateDirect(inputSize * inputSize * 4)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
    }

    private var frameCount = 0

    override fun onDrawFrame(gl: GL10?) {
        val tex = cameraTexture ?: return
        tex.updateTexImage()
        tex.getTransformMatrix(stTransformMatrix)
        frameCount++

        // 1. Render camera to display
        renderToDisplay()

        // 2. Render camera to FBO (letterbox + normalize)
        renderToFbo()

        // 3. BLOB AHWB 零拷贝优先（compute shader → BLOB SSBO → GPU delegate）
        var zeroCopyHandled = false
        val blobHwb = blobHardwareBuffer
        val blobCb = ahwbZeroCopyCallback
        if (blobHwb != null && blobSsboId > 0 && blobCb != null) {
            // compute shader: FBO 纹理 → SSBO (BHWC float32)
            com.little_star.detector.impl.tflite.LiteRtNativeDetector
                .nativeRunComputeToSsbo(blobSsboId, fboTexId, inputSize)

            GLES20.glFlush()

            if (frameCount <= 3 || frameCount % 100 == 0) {
                android.util.Log.i(TAG, "[frame#$frameCount] BLOB零拷贝尝试: " +
                    "ssbo=$blobSsboId hwb=${blobHwb.hashCode()}")
            }

            zeroCopyHandled = blobCb.onFramePreprocessedAhwb(
                blobHwb,
                effectiveWidth, effectiveHeight,
                pixelScale, pixelOffsetX, pixelOffsetY,
                callback.getRotationDegrees()
            )

            if (frameCount <= 3 || frameCount % 100 == 0) {
                android.util.Log.i(TAG, "[frame#$frameCount] BLOB零拷贝结果: " +
                    if (zeroCopyHandled) "成功" else "回退→CPU路径")
            }
        }

        // 4. 零拷贝未处理时回退到 CPU 回读路径
        if (!zeroCopyHandled) {
            if (useFloatFbo) readAndDecodeFloat() else readAndDecode()
        }
    }

    // =========================================================================
    // Internal
    // =========================================================================

    private fun setupFbo() {
        val texIds = IntArray(1)
        glGenTextures(1, texIds, 0)
        fboTexId = texIds[0]
        glBindTexture(GL_TEXTURE_2D, fboTexId)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)

        // Try RGBA32F (float FBO — skip uint8→float decode)
        glTexImage2D(
            GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA32F,
            inputSize,
            inputSize,
            0,
            GL_RGBA,
            GL_FLOAT,
            null
        )

        val fbos = IntArray(1)
        glGenFramebuffers(1, fbos, 0)
        fboId = fbos[0]
        glBindFramebuffer(GL_FRAMEBUFFER, fboId)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, fboTexId, 0)

        val status = glCheckFramebufferStatus(GL_FRAMEBUFFER)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)

        if (status == GL_FRAMEBUFFER_COMPLETE) {
            useFloatFbo = true
            android.util.Log.i(TAG, "FBO: RGBA32F模式 (${inputSize}x${inputSize}) — 零拷贝GL纹理格式=GL_RGBA32F")
        } else {
            // Fallback to RGBA8
            useFloatFbo = false
            glBindTexture(GL_TEXTURE_2D, fboTexId)
            glTexImage2D(
                GL_TEXTURE_2D,
                0,
                GL_RGBA,
                inputSize,
                inputSize,
                0,
                GL_RGBA,
                GL_UNSIGNED_BYTE,
                null
            )
            glBindFramebuffer(GL_FRAMEBUFFER, fboId)
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, fboTexId, 0)
            glBindFramebuffer(GL_FRAMEBUFFER, 0)
            android.util.Log.w(TAG,
                "FBO: RGBA32F不支持 (status=0x${status.toString(16)}) → 降级为RGBA8 — 零拷贝GL纹理格式=GL_RGBA")
        }
    }

    /**
     * BLOB AHWB + SSBO 初始化（方案A零拷贝）
     * 创建 BLOB AHardwareBuffer 并映射为 GL SSBO
     * 仍然需要常规 RGBA32F FBO 用于 fragment shader 预处理
     */
    private fun setupBlobAhwbSsbo() {
        android.util.Log.i(TAG, "BLOB AHWB: 开始初始化 (${inputSize}x${inputSize})...")
        val result = try {
            com.little_star.detector.impl.tflite.LiteRtNativeDetector
                .nativeCreateBlobAhwbSsbo(inputSize)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "BLOB AHWB: nativeCreateBlobAhwbSsbo 异常: ${e.message}", e)
            null
        }

        if (result != null && result.size >= 2) {
            val hwb = result[0] as? HardwareBuffer
            val ssboId = (result[1] as? Int) ?: 0

            if (hwb != null && ssboId > 0) {
                blobHardwareBuffer = hwb
                blobSsboId = ssboId
                android.util.Log.i(TAG, "BLOB AHWB: ✓ 初始化成功 " +
                    "hwb=${hwb.hashCode()} ssbo=$ssboId")
            } else {
                android.util.Log.e(TAG, "BLOB AHWB: 返回值无效 → BLOB 零拷贝禁用")
            }
        } else {
            android.util.Log.e(TAG, "BLOB AHWB: 创建失败 → BLOB 零拷贝禁用")
        }
    }

    private fun renderToDisplay() {
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, surfaceWidth, surfaceHeight)
        glClearColor(0f, 0f, 0f, 1f)
        glClear(GL_COLOR_BUFFER_BIT)

        glUseProgram(displayProgram)

        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, cameraTexId)
        glUniform1i(displayTexLoc, 0)

        glUniformMatrix4fv(displayTransformLoc, 1, false, stTransformMatrix, 0)

        // FILL_CENTER：填满 viewport 并裁切溢出部分，与 PreviewView 行为一致
        val imageAspect = effectiveWidth.toFloat() / effectiveHeight.toFloat()
        val viewAspect = surfaceWidth.toFloat() / surfaceHeight.toFloat()
        val scaleX: Float
        val scaleY: Float
        if (imageAspect > viewAspect) {
            // 图像比视口宽 → 填满高度，裁切两侧
            scaleX = imageAspect / viewAspect
            scaleY = 1f
        } else {
            // 图像比视口高 → 填满宽度，裁切上下
            scaleX = 1f
            scaleY = viewAspect / imageAspect
        }
        glUniform2f(displayDispScaleLoc, scaleX, scaleY)

        val buf = quadBuffer ?: return
        buf.position(0)

        glEnableVertexAttribArray(displayPosLoc)
        buf.position(0)
        glVertexAttribPointer(displayPosLoc, 2, GL_FLOAT, false, STRIDE, buf)

        glEnableVertexAttribArray(displayTexCoordLoc)
        buf.position(2)
        glVertexAttribPointer(displayTexCoordLoc, 2, GL_FLOAT, false, STRIDE, buf)

        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)

        glDisableVertexAttribArray(displayPosLoc)
        glDisableVertexAttribArray(displayTexCoordLoc)
    }

    private fun renderToFbo() {
        glBindFramebuffer(GL_FRAMEBUFFER, fboId)
        glViewport(0, 0, inputSize, inputSize)
        glClearColor(0f, 0f, 0f, 1f)
        glClear(GL_COLOR_BUFFER_BIT)

        glUseProgram(preprocessProgram)

        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, cameraTexId)
        glUniform1i(prepTexLoc, 0)

        // Letterbox params（vec2 scale: x 和 y 方向独立）
        glUniform2f(prepScaleLoc, shaderScaleX, shaderScaleY)
        glUniform2f(prepOffsetLoc, shaderOffsetX, shaderOffsetY)

        // stTransformMatrix 统一处理旋转 + OES 变换，不再需要显式 uRotation/uMirror
        glUniformMatrix4fv(prepTransformLoc, 1, false, stTransformMatrix, 0)

        // 归一化范围：letterbox [-1,1]，center-crop [0,1]
        if (centerCrop) {
            GLES20.glUniform1f(prepNormScaleLoc, 1.0f)
            GLES20.glUniform1f(prepNormOffsetLoc, 0.0f)
        } else {
            GLES20.glUniform1f(prepNormScaleLoc, 1.9921875f)
            GLES20.glUniform1f(prepNormOffsetLoc, -1.0f)
        }

        val buf = quadBuffer ?: return
        buf.position(0)

        glEnableVertexAttribArray(prepPosLoc)
        buf.position(0)
        glVertexAttribPointer(prepPosLoc, 2, GL_FLOAT, false, STRIDE, buf)

        glEnableVertexAttribArray(prepTexCoordLoc)
        buf.position(2)
        glVertexAttribPointer(prepTexCoordLoc, 2, GL_FLOAT, false, STRIDE, buf)

        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)

        glDisableVertexAttribArray(prepPosLoc)
        glDisableVertexAttribArray(prepTexCoordLoc)

        glBindFramebuffer(GL_FRAMEBUFFER, 0)
    }

    private fun readAndDecodeFloat() {
        val buf = floatReadBuf ?: return
        val output = floatOutput ?: return
        val temp = rgbaTemp ?: return
        val size = inputSize * inputSize

        val t0 = System.nanoTime()
        glBindFramebuffer(GL_FRAMEBUFFER, fboId)
        buf.position(0)
        glReadPixels(0, 0, inputSize, inputSize, GL_RGBA, GL_FLOAT, buf)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        val tRead = System.nanoTime()

        // Extract RGB from RGBA float (skip alpha channel)
        val fb = buf.asFloatBuffer()
        fb.position(0)
        fb.get(temp, 0, size * 4)
        for (i in 0 until size) {
            output[i * 3] = temp[i * 4]
            output[i * 3 + 1] = temp[i * 4 + 1]
            output[i * 3 + 2] = temp[i * 4 + 2]
        }

        // Flip vertically (OpenGL origin is bottom-left)
        flipVertical(output, inputSize)
        val tDecode = System.nanoTime()

        val pathLabel = when {
            ahwbZeroCopyCallback != null -> "CPU回退(BLOB零拷贝失败)"
            else -> "CPU(RGBA32F)"
        }

        if (frameCount <= 3 || frameCount % 100 == 0) {
            val readMs = (tRead - t0) / 1_000_000.0
            val decodeMs = (tDecode - tRead) / 1_000_000.0
            android.util.Log.i(TAG, "[frame#$frameCount] $pathLabel | " +
                "eff=${effectiveWidth}x${effectiveHeight} " +
                "scale=$pixelScale offset=($pixelOffsetX,$pixelOffsetY) " +
                "rot=${callback.getRotationDegrees()} " +
                "read=${String.format("%.1f", readMs)}ms decode=${String.format("%.1f", decodeMs)}ms")
        }

        callback.onFramePreprocessed(
            output, effectiveWidth, effectiveHeight,
            pixelScale, pixelOffsetX, pixelOffsetY,
            callback.getRotationDegrees()
        )
    }

    private fun readAndDecode() {
        val buf = pixelBuffer ?: return
        val output = floatOutput ?: return

        val t0 = System.nanoTime()
        glBindFramebuffer(GL_FRAMEBUFFER, fboId)
        buf.position(0)
        glReadPixels(0, 0, inputSize, inputSize, GL_RGBA, GL_UNSIGNED_BYTE, buf)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        val tRead = System.nanoTime()

        // Decode RGBA8888 to float [-1, 1]: (uint8 / 255) * 2 - 1
        val size = inputSize * inputSize
        for (i in 0 until size) {
            val base = i * 4
            val r = (buf.get(base).toInt() and 0xFF) / 255f * 2f - 1f
            val g = (buf.get(base + 1).toInt() and 0xFF) / 255f * 2f - 1f
            val b = (buf.get(base + 2).toInt() and 0xFF) / 255f * 2f - 1f
            output[i * 3] = r
            output[i * 3 + 1] = g
            output[i * 3 + 2] = b
        }

        // Flip vertically (OpenGL origin is bottom-left)
        flipVertical(output, inputSize)
        val tDecode = System.nanoTime()

        val pathLabel = when {
            ahwbZeroCopyCallback != null -> "CPU回退(BLOB零拷贝失败)"
            else -> "CPU(RGBA8)"
        }

        if (frameCount <= 3 || frameCount % 100 == 0) {
            val readMs = (tRead - t0) / 1_000_000.0
            val decodeMs = (tDecode - tRead) / 1_000_000.0
            android.util.Log.i(TAG, "[frame#$frameCount] $pathLabel | " +
                "eff=${effectiveWidth}x${effectiveHeight} " +
                "read=${String.format("%.1f", readMs)}ms decode=${String.format("%.1f", decodeMs)}ms")
        }

        callback.onFramePreprocessed(
            output, effectiveWidth, effectiveHeight,
            pixelScale, pixelOffsetX, pixelOffsetY,
            callback.getRotationDegrees()
        )
    }

    private fun flipVertical(data: FloatArray, width: Int) {
        val height = width
        val rowFloats = width * 3
        val tmp = FloatArray(rowFloats)
        for (y in 0 until height / 2) {
            val topOffset = y * rowFloats
            val bottomOffset = (height - 1 - y) * rowFloats
            System.arraycopy(data, topOffset, tmp, 0, rowFloats)
            System.arraycopy(data, bottomOffset, data, topOffset, rowFloats)
            System.arraycopy(tmp, 0, data, bottomOffset, rowFloats)
        }
    }

    private fun computeLetterbox(srcW: Int, srcH: Int) {
        // 根据旋转角度计算有效图像尺寸
        val rotationDeg = callback.getRotationDegrees()
        val effectiveW: Int
        val effectiveH: Int
        if (rotationDeg == 90 || rotationDeg == 270) {
            effectiveW = srcH
            effectiveH = srcW
        } else {
            effectiveW = srcW
            effectiveH = srcH
        }
        this.effectiveWidth = effectiveW
        this.effectiveHeight = effectiveH

        // 缩放基准：letterbox 用最长边，center-crop 用最短边
        val scaleBase = if (centerCrop) {
            kotlin.math.min(effectiveW, effectiveH)
        } else {
            maxOf(effectiveW, effectiveH)
        }
        // 像素空间 letterbox/centercrop 参数
        pixelScale = inputSize.toFloat() / scaleBase
        val scaledW = effectiveW * pixelScale
        val scaledH = effectiveH * pixelScale
        pixelOffsetX = (inputSize - scaledW) / 2f
        pixelOffsetY = (inputSize - scaledH) / 2f

        // Shader uniform（归一化 [0,1] 坐标空间，x 和 y 方向独立）
        shaderScaleX = scaledW / inputSize
        shaderScaleY = scaledH / inputSize
        shaderOffsetX = (inputSize - scaledW) / (2f * inputSize)
        shaderOffsetY = (inputSize - scaledH) / (2f * inputSize)
    }

    // =========================================================================
    // Shader compilation helpers
    // =========================================================================

    private fun createProgram(vsSource: String, fsSource: String): Int {
        val vs = compileShader(GL_VERTEX_SHADER, vsSource)
        val fs = compileShader(GL_FRAGMENT_SHADER, fsSource)
        val program = glCreateProgram()
        glAttachShader(program, vs)
        glAttachShader(program, fs)
        glLinkProgram(program)
        val linked = IntArray(1)
        glGetProgramiv(program, GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            val log = glGetProgramInfoLog(program)
            glDeleteProgram(program)
            throw RuntimeException("Shader link failed: $log")
        }
        glDeleteShader(vs)
        glDeleteShader(fs)
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = glCreateShader(type)
        glShaderSource(shader, source)
        glCompileShader(shader)
        val compiled = IntArray(1)
        glGetShaderiv(shader, GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = glGetShaderInfoLog(shader)
            glDeleteShader(shader)
            throw RuntimeException("Shader compile failed: $log")
        }
        return shader
    }
}
