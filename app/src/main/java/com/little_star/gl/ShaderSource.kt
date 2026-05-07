package com.little_star.gl

object ShaderSource {

    // =========================================================================
    // Display shaders: render camera texture to screen
    // =========================================================================

    const val DISPLAY_VS = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        uniform mat4 uTransformMatrix;
        uniform vec2 uDispScale;
        void main() {
            // stTransformMatrix 对前置摄像头已内置镜像，无需额外翻转
            vTexCoord = (uTransformMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            // 保持宽高比，防止画面拉伸
            gl_Position = vec4(aPosition.xy * uDispScale, 0.0, 1.0);
        }
    """

    const val DISPLAY_FS = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTexCoord;
        uniform samplerExternalOES uCameraTexture;
        void main() {
            gl_FragColor = texture2D(uCameraTexture, vTexCoord);
        }
    """

    // =========================================================================
    // Preprocess shaders: letterbox + normalize to [-1, 1]
    // stTransformMatrix 内部已编码摄像头传感器旋转，无需显式 uRotation/uMirror
    //
    // 坐标变换链：
    // FBO texcoord (y=0底部) → Y翻转 → 逆letterbox → Y翻转回screen空间 → stTransformMatrix → OES采样
    // =========================================================================

    const val PREPROCESS_VS = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            vTexCoord = aTexCoord;
            gl_Position = aPosition;
        }
    """

    // RGBA8 路径：输出 uint8 编码的归一化值
    // uNormScale/uNormOffset 控制归一化范围：检测类 [-1,1]，分类 [0,1]
    const val PREPROCESS_FS = """
        #extension GL_OES_EGL_image_external : require
        precision highp float;
        varying vec2 vTexCoord;
        uniform samplerExternalOES uCameraTexture;
        uniform vec2 uScale;
        uniform vec2 uOffset;
        uniform mat4 uTransformMatrix;
        uniform float uNormScale;
        uniform float uNormOffset;

        void main() {
            // Step 1: FBO texcoord (y=0底部) → 图像坐标 (y=0顶部)
            float u_img = vTexCoord.x;
            float v_img = 1.0 - vTexCoord.y;

            // Step 2: 逆 letterbox/centercrop → portrait 图像UV [0,1]
            float u_p = (u_img - uOffset.x) / uScale.x;
            float v_p = (v_img - uOffset.y) / uScale.y;

            // Step 3: padding 检测（center-crop 模式下不会触发）
            if (u_p < 0.0 || u_p > 1.0 || v_p < 0.0 || v_p > 1.0) {
                gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                return;
            }

            // Step 4: portrait 图像UV (y=0顶部) → screen texcoord UV (y=0底部)
            // stTransformMatrix 期望的输入空间与 display shader 的 aTexCoord 相同
            vec2 screenTexUV = vec2(u_p, 1.0 - v_p);

            // Step 5: stTransformMatrix 统一处理旋转 + OES所有变换，无需显式 uRotation
            vec2 texCoord = (uTransformMatrix * vec4(screenTexUV, 0.0, 1.0)).xy;

            // Step 6: 采样 + 归一化，编码为 uint8
            vec4 color = texture2D(uCameraTexture, texCoord);
            float r = color.r * uNormScale + uNormOffset;
            float g = color.g * uNormScale + uNormOffset;
            float b = color.b * uNormScale + uNormOffset;
            gl_FragColor = vec4(r * 0.5 + 0.5, g * 0.5 + 0.5, b * 0.5 + 0.5, 1.0);
        }
    """

    // =========================================================================
    // Preprocess shader (float FBO): letterbox/centercrop + normalize
    // 直接输出 float，无 uint8 编解码
    // stTransformMatrix 统一处理旋转，无需显式 uRotation/uMirror
    // uNormScale/uNormOffset 控制归一化范围：检测类 [-1,1]，分类 [0,1]
    // =========================================================================

    const val PREPROCESS_FS_FLOAT = """
        #extension GL_OES_EGL_image_external : require
        precision highp float;
        varying vec2 vTexCoord;
        uniform samplerExternalOES uCameraTexture;
        uniform vec2 uScale;
        uniform vec2 uOffset;
        uniform mat4 uTransformMatrix;
        uniform float uNormScale;
        uniform float uNormOffset;

        void main() {
            // Step 1: FBO texcoord (y=0底部) → 图像坐标 (y=0顶部)
            float u_img = vTexCoord.x;
            float v_img = 1.0 - vTexCoord.y;

            // Step 2: 逆 letterbox/centercrop → portrait 图像UV [0,1]
            float u_p = (u_img - uOffset.x) / uScale.x;
            float v_p = (v_img - uOffset.y) / uScale.y;

            // Step 3: padding 检测（center-crop 模式下不会触发）
            if (u_p < 0.0 || u_p > 1.0 || v_p < 0.0 || v_p > 1.0) {
                gl_FragColor = vec4(uNormOffset, uNormOffset, uNormOffset, 1.0);
                return;
            }

            // Step 4: portrait 图像UV (y=0顶部) → screen texcoord UV (y=0底部)
            vec2 screenTexUV = vec2(u_p, 1.0 - v_p);

            // Step 5: stTransformMatrix 统一处理旋转 + OES所有变换
            vec2 texCoord = (uTransformMatrix * vec4(screenTexUV, 0.0, 1.0)).xy;

            // Step 6: 采样 + 归一化（范围由 uniform 控制）
            vec4 color = texture2D(uCameraTexture, texCoord);
            gl_FragColor = vec4(color.r * uNormScale + uNormOffset,
                                color.g * uNormScale + uNormOffset,
                                color.b * uNormScale + uNormOffset, 1.0);
        }
    """

    // =========================================================================
    // Preprocess shader for Bitmap input (sampler2D, no rotation/mirror)
    // Used by VideoGlPreprocessor for video frame preprocessing
    // =========================================================================

    const val PREPROCESS_FS_BITMAP = """
        precision highp float;
        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        uniform vec2 uScale;
        uniform vec2 uOffset;

        void main() {
            vec2 srcCoord = (vTexCoord - uOffset) / uScale;

            if (srcCoord.x < 0.0 || srcCoord.x > 1.0 ||
                srcCoord.y < 0.0 || srcCoord.y > 1.0) {
                gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                return;
            }

            vec4 color = texture2D(uTexture, srcCoord);
            float r = color.r * 1.9921875 - 1.0;
            float g = color.g * 1.9921875 - 1.0;
            float b = color.b * 1.9921875 - 1.0;
            gl_FragColor = vec4(r * 0.5 + 0.5, g * 0.5 + 0.5, b * 0.5 + 0.5, 1.0);
        }
    """

    // =========================================================================
    // Compute shader: FBO 纹理 → SSBO (BHWC float32)
    // 从 RGBA32F FBO 纹理提取 RGB 通道，写入 SSBO 的 BHWC 布局
    // 用于 BLOB AHWB 零拷贝路径：GL 渲染 → compute shader → BLOB SSBO → GPU delegate
    // =========================================================================

    const val COMPUTE_FS_TO_SSBO = """
        #version 310 es
        layout(local_size_x = 8, local_size_y = 8) in;
        layout(std430, binding = 0) buffer OutputBuffer {
            float data[];
        } output_buf;
        uniform sampler2D uInputTex;
        uniform int uWidth;
        uniform int uHeight;

        void main() {
            int x = int(gl_GlobalInvocationID.x);
            int y = int(gl_GlobalInvocationID.y);
            if (x >= uWidth || y >= uHeight) return;

            // Y翻转：OpenGL origin 在底部，模型期望顶部为第一行
            int y_flipped = uHeight - 1 - y;

            // 采样 FBO 纹理（中心对齐 texel）
            vec2 texCoord = (vec2(x, y) + 0.5) / vec2(uWidth, uHeight);
            vec4 color = texture(uInputTex, texCoord);

            // BHWC布局写入 SSBO：data[n * H * W * 3 + y * W * 3 + x * 3 + c]
            // n=0（单batch），省略 n 维度
            int baseIdx = (y_flipped * uWidth + x) * 3;
            output_buf.data[baseIdx + 0] = color.r;
            output_buf.data[baseIdx + 1] = color.g;
            output_buf.data[baseIdx + 2] = color.b;
        }
    """

    // =========================================================================
    // Full-screen quad vertices (position + texcoord)
    // =========================================================================

    val QUAD_VERTICES = floatArrayOf(
        // position (x, y)  texcoord (s, t)
        -1f, -1f, 0f, 0f,
        1f, -1f, 1f, 0f,
        -1f, 1f, 0f, 1f,
        1f, 1f, 1f, 1f
    )
}
