#include "LiteRtJni.h"
#include "LiteRtDetector.h"
#include "PostprocessorFactory.h"
#include <android/log.h>
#include <android/bitmap.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <string>
#include <memory>
#include <chrono>
#include <vector>
// EGL fence sync: 用于 GL→GPU delegate 异步同步
#include <EGL/egl.h>
#include <EGL/eglext.h>
// GLES3: glEGLImageTargetTexture2DOES, glGenTextures, glGenFramebuffers 等
#include <GLES3/gl3.h>
#include <GLES3/gl3ext.h>
// AHWB 零拷贝: AHardwareBuffer API
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include "litert/cc/litert_event.h"

// ============================================================================
// EGL/GLES 扩展函数声明（NDK EGL 1.5 头文件不包含 Android/OES 扩展函数）
// 这些函数在 libEGL.so / libGLESv2.so 中可用，但需要手动声明原型
// ============================================================================

// EGL_ANDROID_get_native_client_buffer
#ifndef EGL_NATIVE_BUFFER_ANDROID
#define EGL_NATIVE_BUFFER_ANDROID 0x3140
#endif
extern "C" {
    EGLClientBuffer eglGetNativeClientBufferANDROID(const AHardwareBuffer* buffer);
}

// GL_OES_EGL_image — glEGLImageTargetTexture2DOES
typedef void* GLeglImageOES;
extern "C" {
    void glEGLImageTargetTexture2DOES(GLenum target, GLeglImageOES image);
}

// GL_EXT_external_buffer — 将 AHardwareBuffer BLOB 映射为 GL SSBO
// 函数指针类型（与 LiteRT async_buffers.cc 相同的扩展）
typedef void (GL_APIENTRY* PFNGLBUFFERSTORAGEEXTERNALEXTPROC)(
    GLenum target, GLintptr offset, GLsizeiptr size, EGLClientBuffer buffer, GLbitfield flags);
static PFNGLBUFFERSTORAGEEXTERNALEXTPROC g_glBufferStorageExternalEXT = nullptr;

// GL_EXT_external_buffer 扩展标志位
#ifndef GL_MAP_COHERENT_BIT_EXT
#define GL_MAP_COHERENT_BIT_EXT 0x0080
#endif
#ifndef GL_MAP_PERSISTENT_BIT_EXT
#define GL_MAP_PERSISTENT_BIT_EXT 0x0040
#endif

// AHARDWAREBUFFER_FORMAT_BLOB 定义（API 26+）
#ifndef AHARDWAREBUFFER_FORMAT_BLOB
#define AHARDWAREBUFFER_FORMAT_BLOB 0x21
#endif

// AHardwareBuffer_allocate（NDK 中函数名为 AHardwareBuffer_allocate，不是 create）
// Android API 26+ 提供

// GLES31 compute shader 相关（glDispatchCompute 等）
#include <GLES3/gl31.h>

#define LOG_TAG "LiteRtJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static void ThrowJavaException(JNIEnv* env, const char* class_name, const char* msg) {
    jclass cls = env->FindClass(class_name);
    if (cls) env->ThrowNew(cls, msg);
    else LOGE("FindClass(%s) failed, cannot throw: %s", class_name, msg);
}

// Buffer 类型名称转换（用于诊断日志）
static const char* BufferTypeName(int type) {
    switch (type) {
        case 1:  return "HostMemory(1)";
        case 2:  return "Ahwb(2)";
        case 3:  return "Ion(3)";
        case 4:  return "DmaBuf(4)";
        case 5:  return "FastRpc(5)";
        case 6:  return "GlBuffer(6)";
        case 7:  return "GlTexture(7)";
        case 10: return "OpenClBuffer(10)";
        case 11: return "OpenClBufferFp16(11)";
        case 20: return "WebGpuBuffer(20)";
        case 30: return "MetalBuffer(30)";
        case 40: return "VulkanBuffer(40)";
        default: return "Unknown";
    }
}

// 加速器名称转换
static const char* AcceleratorName(int mode) {
    switch (mode) {
        case 0: return "CPU";
        case 1: return "GPU";
        case 2: return "NPU";
        default: return "Unknown";
    }
}

namespace {
    std::unique_ptr<NativeDetectorState> g_detector_state;
    JavaVM* g_jvm = nullptr;

    // 帧计数器（perf 日志节流用，所有检测路径共用）
    static int g_perf_frame_seq = 0;

    // 是否应输出 perf 日志（前 3 帧必打，之后每 100 帧打一次）
    static bool ShouldLogPerf() {
        g_perf_frame_seq++;
        return g_perf_frame_seq <= 3 || g_perf_frame_seq % 100 == 0;
    }

    std::unique_ptr<litert::Environment> g_shared_env;
    int g_env_accel_mode = -1;
    std::string g_env_npu_lib_dir;
    std::string g_env_npu_cache_dir;

    // EGL 上下文（GL 线程注入，用于 MTK/Tensor 零拷贝）
    // 在 onSurfaceCreated 中通过 nativeInjectEglContext() 设置
    void* g_egl_display_ptr = nullptr;
    void* g_egl_context_ptr = nullptr;
    // EGL-aware Environment：用于 CreateFromAhwb 和 CreateFromEglSyncFence
    // 与 g_shared_env 共存，不需要重新编译模型
    std::unique_ptr<litert::Environment> g_egl_env;

    // BLOB AHWB + SSBO 资源（方案A零拷贝）
    // BLOB 格式 AHardwareBuffer 映射为 GL SSBO，compute shader 写入 BHWC float32
    GLuint g_blob_ssbo_id = 0;              // BLOB AHWB 映射的 SSBO
    AHardwareBuffer* g_blob_ahwb = nullptr;  // BLOB AHWB 原生指针
    jobject g_blob_ahwb_java = nullptr;      // Java HardwareBuffer 全局引用
    GLuint g_compute_program = 0;            // compute shader 程序
    GLint g_compute_tex_loc = -1;            // uInputTex uniform 位置
    GLint g_compute_width_loc = -1;          // uWidth uniform 位置
    GLint g_compute_height_loc = -1;         // uHeight uniform 位置

    litert::HwAccelerators GetAcceleratorFromInt(int mode) {
        switch (mode) {
            case 1: return litert::HwAccelerators::kGpu;
            case 2: return litert::HwAccelerators::kNpu;
            default: return litert::HwAccelerators::kCpu;
        }
    }

    bool EnvNeedsRecreate(int new_mode, const std::string& npu_lib, const std::string& npu_cache) {
        if (!g_shared_env) return true;
        bool was_npu = (g_env_accel_mode == 2);
        bool is_npu = (new_mode == 2);
        if (was_npu != is_npu) {
            // 加速器类型变化时，清理 EGL Environment（NPU 选项不再适用）
            g_egl_env.reset();
            g_egl_display_ptr = nullptr;
            g_egl_context_ptr = nullptr;
            return true;
        }
        if (is_npu && (npu_lib != g_env_npu_lib_dir || npu_cache != g_env_npu_cache_dir)) {
            g_egl_env.reset();
            return true;
        }
        return false;
    }
} // anonymous namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    LOGI("JNI_OnLoad: LiteRT JNI loaded");
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* reserved) {
    g_jvm = nullptr;
    g_detector_state.reset();
    g_shared_env.reset();
    g_env_accel_mode = -1;
}

// ============================================================================
// Lifecycle
// ============================================================================

JNIEXPORT jboolean JNICALL
Java_com_little_1star_detector_impl_tflite_LiteRtNativeDetector_nativeInit(
    JNIEnv* env, jobject thiz,
    jstring model_path, jint input_size, jint num_classes,
    jobjectArray class_names, jint task_type, jint inference_type,
    jint accelerator_mode, jboolean is_from_assets, jobject asset_manager,
    jstring npu_lib_dir, jstring npu_cache_dir,
    jint num_keypoints) {

    LOGI("nativeInit: input_size=%d, num_classes=%d, task_type=%d, inference=%d, accel=%d, kpts=%d",
          input_size, num_classes, task_type, inference_type, accelerator_mode, num_keypoints);

    try {
        if (g_detector_state) {
            g_detector_state->model.reset();
            if (g_detector_state->model_asset) {
                AAsset_close(g_detector_state->model_asset);
                g_detector_state->model_asset = nullptr;
            }
        }
        g_detector_state.reset();

        auto state = std::make_unique<NativeDetectorState>();
        state->input_size = input_size;
        state->num_classes = num_classes;
        state->num_keypoints = num_keypoints;
        state->task_type = task_type;
        state->inference_type = inference_type;
        state->accelerator_mode = accelerator_mode;

        const char* path_chars = env->GetStringUTFChars(model_path, nullptr);
        if (!path_chars) {
            LOGE("GetStringUTFChars returned null for model_path");
            return JNI_FALSE;
        }
        std::string model_path_str(path_chars);
        env->ReleaseStringUTFChars(model_path, path_chars);

        jsize name_count = env->GetArrayLength(class_names);
        state->class_names.reserve(name_count);
        for (jsize i = 0; i < name_count; i++) {
            jstring name = (jstring)env->GetObjectArrayElement(class_names, i);
            const char* name_chars = env->GetStringUTFChars(name, nullptr);
            if (!name_chars) {
                env->DeleteLocalRef(name);
                LOGE("GetStringUTFChars returned null for class_name[%d]", i);
                return JNI_FALSE;
            }
            state->class_names.emplace_back(name_chars);
            env->ReleaseStringUTFChars(name, name_chars);
            env->DeleteLocalRef(name);
        }

        // Create postprocessor
        auto yolo_task = static_cast<yolo::TaskType>(task_type);
        auto yolo_infer = static_cast<yolo::InferenceType>(inference_type);
        state->postprocessor = yolo::CreatePostprocessor(yolo_task, yolo_infer);
        if (!state->postprocessor) {
            LOGE("Failed to create postprocessor for task_type=%d", task_type);
            return JNI_FALSE;
        }

        auto accelerator = GetAcceleratorFromInt(accelerator_mode);

        std::string npu_lib_dir_str;
        if (npu_lib_dir) {
            const char* dir_chars = env->GetStringUTFChars(npu_lib_dir, nullptr);
            if (!dir_chars) {
                LOGE("GetStringUTFChars returned null for npu_lib_dir");
                return JNI_FALSE;
            }
            npu_lib_dir_str = dir_chars;
            env->ReleaseStringUTFChars(npu_lib_dir, dir_chars);
        }

        std::string npu_cache_dir_str;
        if (npu_cache_dir) {
            const char* cache_chars = env->GetStringUTFChars(npu_cache_dir, nullptr);
            if (!cache_chars) {
                LOGE("GetStringUTFChars returned null for npu_cache_dir");
                return JNI_FALSE;
            }
            npu_cache_dir_str = cache_chars;
            env->ReleaseStringUTFChars(npu_cache_dir, cache_chars);
        }

        // Reuse or create Environment
        if (EnvNeedsRecreate(accelerator_mode, npu_lib_dir_str, npu_cache_dir_str)) {
            g_shared_env.reset();
            if (!npu_lib_dir_str.empty()) {
                LOGI("Creating NPU Environment with lib dir: %s, cache dir: %s",
                     npu_lib_dir_str.c_str(), npu_cache_dir_str.c_str());
                std::vector<litert::EnvironmentOptions::Option> opts;
                opts.push_back(litert::EnvironmentOptions::Option(
                    litert::EnvironmentOptions::Tag::kDispatchLibraryDir,
                    npu_lib_dir_str.c_str()));
                opts.push_back(litert::EnvironmentOptions::Option(
                    litert::EnvironmentOptions::Tag::kCompilerPluginLibraryDir,
                    npu_lib_dir_str.c_str()));
                if (!npu_cache_dir_str.empty()) {
                    opts.push_back(litert::EnvironmentOptions::Option(
                        litert::EnvironmentOptions::Tag::kCompilerCacheDir,
                        npu_cache_dir_str.c_str()));
                }
                auto env_result = litert::Environment::Create(
                    litert::EnvironmentOptions(opts));
                if (!env_result.HasValue()) {
                    LOGE("Failed to create NPU Environment with dir: %s", npu_lib_dir_str.c_str());
                    return JNI_FALSE;
                }
                g_shared_env = std::make_unique<litert::Environment>(
                    std::move(env_result.Value()));
            } else {
                litert::EnvironmentOptions env_opts(
                    absl::Span<const litert::EnvironmentOptions::Option>{});
                auto env_result = litert::Environment::Create(env_opts);
                if (!env_result.HasValue()) {
                    LOGE("Failed to create LiteRT Environment");
                    return JNI_FALSE;
                }
                g_shared_env = std::make_unique<litert::Environment>(
                    std::move(env_result.Value()));
            }
            g_env_accel_mode = accelerator_mode;
            g_env_npu_lib_dir = npu_lib_dir_str;
            g_env_npu_cache_dir = npu_cache_dir_str;
            LOGI("Created new Environment (accel=%d)", accelerator_mode);
        } else {
            LOGI("Reusing Environment (accel=%d)", accelerator_mode);
        }

        // NPU options
        std::unique_ptr<litert::Options> npu_options;
        if (accelerator_mode == 2) {
            auto opts_result = litert::Options::Create();
            if (!opts_result.HasValue()) {
                LOGE("Failed to create Options");
                return JNI_FALSE;
            }
            npu_options = std::make_unique<litert::Options>(std::move(opts_result.Value()));
            npu_options->SetHardwareAccelerators(litert::HwAccelerators::kNpu);
            auto qcom_result = npu_options->GetQualcommOptions();
            if (!qcom_result.HasValue()) {
                LOGE("Failed to get QualcommOptions");
                return JNI_FALSE;
            }
            qcom_result.Value().SetHtpPerformanceMode(
                litert::qualcomm::QualcommOptions::HtpPerformanceMode::kBurst);
        }

        // GPU options：使用默认后端（OpenCL），不做强制切换
        // 注：之前的 kOpenGl 强制切换是为 GL 纹理零拷贝方案（已删除）添加的，
        // 会创建 GlBuffer 导致 CPU Write 失败，因此移除
        std::unique_ptr<litert::Options> gpu_options;
        if (accelerator_mode == 1) {
            auto opts_result = litert::Options::Create();
            if (!opts_result.HasValue()) {
                LOGE("Failed to create GPU Options");
                return JNI_FALSE;
            }
            gpu_options = std::make_unique<litert::Options>(std::move(opts_result.Value()));
            gpu_options->SetHardwareAccelerators(litert::HwAccelerators::kGpu);
            LOGI("GPU: 使用默认后端（OpenCL），不强制 OpenGL ES");
        }

        // Create CompiledModel
        if (is_from_assets) {
            AAssetManager* mgr = AAssetManager_fromJava(env, asset_manager);
            if (!mgr) {
                LOGE("Failed to get AAssetManager");
                return JNI_FALSE;
            }
            AAsset* asset = AAssetManager_open(mgr, model_path_str.c_str(), AASSET_MODE_BUFFER);
            if (!asset) {
                LOGE("Failed to open asset: %s", model_path_str.c_str());
                return JNI_FALSE;
            }
            size_t asset_size = AAsset_getLength(asset);
            const uint8_t* asset_buf = reinterpret_cast<const uint8_t*>(AAsset_getBuffer(asset));
            if (!asset_buf || asset_size == 0) {
                LOGE("Failed to read asset buffer (size=%zu)", asset_size);
                AAsset_close(asset);
                return JNI_FALSE;
            }

            litert::BufferRef<uint8_t> model_buffer(asset_buf, asset_size);
            litert::Options* active_options = npu_options ? npu_options.get()
                                             : gpu_options ? gpu_options.get()
                                             : nullptr;
            auto model_result = active_options
                ? litert::CompiledModel::Create(*g_shared_env, model_buffer, *active_options)
                : litert::CompiledModel::Create(*g_shared_env, model_buffer, accelerator);

            if (!model_result.HasValue()) {
                AAsset_close(asset);
                LOGE("Failed to create CompiledModel from asset buffer");
                return JNI_FALSE;
            }
            state->model_asset = asset;
            state->model = std::make_unique<litert::CompiledModel>(
                std::move(model_result.Value()));
        } else {
            litert::Options* active_options = npu_options ? npu_options.get()
                                             : gpu_options ? gpu_options.get()
                                             : nullptr;
            auto model_result = active_options
                ? litert::CompiledModel::Create(*g_shared_env, model_path_str, *active_options)
                : litert::CompiledModel::Create(*g_shared_env, model_path_str, accelerator);
            if (!model_result.HasValue()) {
                LOGE("Failed to create CompiledModel from file: %s", model_path_str.c_str());
                return JNI_FALSE;
            }
            state->model = std::make_unique<litert::CompiledModel>(
                std::move(model_result.Value()));
        }

        // Input buffer
        auto input_result = state->model->CreateInputBuffers();
        if (!input_result.HasValue()) {
            LOGE("Failed to create input buffers");
            return JNI_FALSE;
        }
        state->input_buffer = std::make_unique<litert::TensorBuffer>(
            std::move(input_result.Value()[0]));

        // Output buffers (1 for most tasks, 2 for SEG)
        int num_outputs = state->postprocessor->getOutputBufferCount();
        auto output_result = state->model->CreateOutputBuffers();
        if (!output_result.HasValue()) {
            LOGE("Failed to create output buffers");
            return JNI_FALSE;
        }
        auto& output_buffers = output_result.Value();
        state->output_buffer = std::make_unique<litert::TensorBuffer>(
            std::move(output_buffers[0]));
        if (num_outputs > 1 && output_buffers.size() > 1) {
            state->output_buffer_2 = std::make_unique<litert::TensorBuffer>(
                std::move(output_buffers[1]));
        }

        // ── Buffer 类型诊断日志 ──
        // 用于确认 NPU 是否使用 FastRpc 零拷贝，GPU 是否使用 OpenCL 等
        {
            auto in_type = state->input_buffer->BufferType();
            auto out_type = state->output_buffer->BufferType();
            const char* in_type_name = in_type.HasValue()
                ? BufferTypeName(static_cast<int>(in_type.Value())) : "UNKNOWN";
            const char* out_type_name = out_type.HasValue()
                ? BufferTypeName(static_cast<int>(out_type.Value())) : "UNKNOWN";
            // Buffer 类型诊断日志
            LOGI("═══ Buffer类型诊断 ═══");
            LOGI("  加速器: %s (mode=%d)", AcceleratorName(accelerator_mode), accelerator_mode);
            LOGI("  InputBuffer类型:  %s", in_type_name);
            LOGI("  OutputBuffer类型: %s", out_type_name);
            LOGI("  → 如果InputBuffer=FastRpc且NPU模式，推理阶段为零拷贝");
            LOGI("  → 如果InputBuffer=HostMemory，推理阶段需CPU同步（额外拷贝）");
        }

        // Pre-allocate input data
        state->input_data.resize(
            static_cast<size_t>(input_size) * static_cast<size_t>(input_size) * 3);

        state->is_initialized = true;
        g_detector_state = std::move(state);

        LOGI("nativeInit: SUCCESS (task=%d, outputs=%d)", task_type, num_outputs);
        return JNI_TRUE;

    } catch (const std::exception& e) {
        LOGE("nativeInit EXCEPTION: %s", e.what());
        ThrowJavaException(env, "java/lang/RuntimeException", e.what());
        return JNI_FALSE;
    }
}

JNIEXPORT void JNICALL
Java_com_little_1star_detector_impl_tflite_LiteRtNativeDetector_nativeRelease(
    JNIEnv* env, jobject thiz) {
    LOGI("nativeRelease");
    if (g_detector_state) {
        g_detector_state->model.reset();
        if (g_detector_state->model_asset) {
            AAsset_close(g_detector_state->model_asset);
            g_detector_state->model_asset = nullptr;
        }
    }
    g_detector_state.reset();
    // 清理 EGL-aware Environment
    g_egl_env.reset();
    g_egl_display_ptr = nullptr;
    g_egl_context_ptr = nullptr;
}

// ============================================================================
// Detection
// ============================================================================

JNIEXPORT jfloatArray JNICALL
Java_com_little_1star_detector_impl_tflite_LiteRtNativeDetector_nativeDetect(
    JNIEnv* env, jobject thiz,
    jobject bitmap, jfloat conf_threshold) {

    if (!g_detector_state || !g_detector_state->is_initialized) {
        ThrowJavaException(env, "java/lang/IllegalStateException",
                           "Detector not initialized");
        return nullptr;
    }

    auto& state = *g_detector_state;

    try {
        auto t0 = std::chrono::high_resolution_clock::now();

        AndroidBitmapInfo info;
        AndroidBitmap_getInfo(env, bitmap, &info);

        void* pixels = nullptr;
        if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
            ThrowJavaException(env, "java/lang/RuntimeException",
                               "Failed to lock bitmap pixels");
            return nullptr;
        }

        // RAII: ensure bitmap pixels are always unlocked
        struct BitmapUnlocker {
            JNIEnv* e; jobject b;
            ~BitmapUnlocker() { AndroidBitmap_unlockPixels(e, b); }
        } unlocker{env, bitmap};

        // Preprocessing dispatch via postprocessor
        if (state.postprocessor->getPreprocessMode() == yolo::PreprocessMode::CENTER_CROP) {
            PreprocessCenterCropAndNormalize(
                pixels, info.width, info.height,
                state.input_data.data(), state.input_size);
            state.letterbox_scale = 1.0f;
            state.letterbox_offset_x = 0.0f;
            state.letterbox_offset_y = 0.0f;
        } else {
            PreprocessLetterboxAndNormalize(
                pixels, info.width, info.height,
                state.input_data.data(), state.input_size,
                &state.letterbox_scale, &state.letterbox_offset_x, &state.letterbox_offset_y);
        }

        auto t1 = std::chrono::high_resolution_clock::now();

        // Write input
        auto write_result = state.input_buffer->Write<float>(
            absl::MakeConstSpan(state.input_data));
        if (!write_result.HasValue()) {
            LOGE("Failed to write input data");
            return nullptr;
        }
        auto t2 = std::chrono::high_resolution_clock::now();

        // Inference
        std::vector<litert::TensorBuffer> inputs;
        inputs.push_back(std::move(*state.input_buffer));
        std::vector<litert::TensorBuffer> outputs;
        outputs.push_back(std::move(*state.output_buffer));
        if (state.output_buffer_2) {
            outputs.push_back(std::move(*state.output_buffer_2));
        }

        auto run_result = state.model->Run(inputs, outputs);

        // Reclaim buffers (must happen regardless of Run success/failure)
        state.input_buffer = std::make_unique<litert::TensorBuffer>(std::move(inputs[0]));
        state.output_buffer = std::make_unique<litert::TensorBuffer>(std::move(outputs[0]));
        state.output_buffer_2.reset();
        if (outputs.size() > 1) {
            state.output_buffer_2 = std::make_unique<litert::TensorBuffer>(std::move(outputs[1]));
        }

        if (!run_result.HasValue()) {
            LOGE("Failed to run inference");
            return nullptr;
        }
        auto t3 = std::chrono::high_resolution_clock::now();

        // Read output(s)
        int num_outputs = state.postprocessor->getOutputBufferCount();

        yolo::TensorOutput tensor_outputs[2]{};

        auto size_result = state.output_buffer->Size();
        tensor_outputs[0].size = size_result.HasValue() ? size_result.Value() / sizeof(float) : 0;
        state.output_data.resize(tensor_outputs[0].size);
        auto read_result = state.output_buffer->Read<float>(
            absl::MakeSpan(state.output_data));
        if (!read_result.HasValue()) {
            LOGE("Failed to read output data");
            return nullptr;
        }
        tensor_outputs[0].data = state.output_data.data();

        if (num_outputs > 1 && state.output_buffer_2) {
            auto size2 = state.output_buffer_2->Size();
            tensor_outputs[1].size = size2.HasValue() ? size2.Value() / sizeof(float) : 0;
            state.output_data_2.resize(tensor_outputs[1].size);
            auto read2_result = state.output_buffer_2->Read<float>(
                absl::MakeSpan(state.output_data_2));
            if (!read2_result.HasValue()) {
                LOGE("Failed to read second output data");
                return nullptr;
            }
            tensor_outputs[1].data = state.output_data_2.data();
        }
        auto t4 = std::chrono::high_resolution_clock::now();

        state.last_inference_time_ms =
            std::chrono::duration<double, std::milli>(t4 - t0).count();

        // Postprocess
        yolo::PostprocessConfig config;
        config.input_size = state.input_size;
        config.num_classes = state.num_classes;
        config.num_keypoints = state.num_keypoints;
        config.task_type = static_cast<yolo::TaskType>(state.task_type);
        config.inference_type = static_cast<yolo::InferenceType>(state.inference_type);
        config.letterbox = {state.letterbox_scale, state.letterbox_offset_x, state.letterbox_offset_y};

        std::vector<float> results = state.postprocessor->postprocess(
            tensor_outputs, num_outputs,
            info.width, info.height,
            conf_threshold, config);
        auto t5 = std::chrono::high_resolution_clock::now();

        double preprocess_ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
        double write_ms = std::chrono::duration<double, std::milli>(t2 - t1).count();
        double inference_ms = std::chrono::duration<double, std::milli>(t3 - t2).count();
        double read_ms = std::chrono::duration<double, std::milli>(t4 - t3).count();
        double postprocess_ms = std::chrono::duration<double, std::milli>(t5 - t4).count();
        double total_ms = std::chrono::duration<double, std::milli>(t5 - t0).count();
        if (ShouldLogPerf()) {
            LOGI("perf(bm) [#%d] | prep=%.1f write=%.1f infer=%.1f read=%.1f post=%.1f | total=%.1f",
                 g_perf_frame_seq, preprocess_ms, write_ms, inference_ms, read_ms, postprocess_ms, total_ms);
        }

        jfloatArray result = env->NewFloatArray(results.size());
        if (result) {
            env->SetFloatArrayRegion(result, 0, results.size(), results.data());
        }
        return result;

    } catch (const std::exception& e) {
        LOGE("nativeDetect EXCEPTION: %s", e.what());
        ThrowJavaException(env, "java/lang/RuntimeException", e.what());
        return nullptr;
    }
}

// ============================================================================
// Detection from preprocessed float buffer (GL pipeline)
// ============================================================================

JNIEXPORT jfloatArray JNICALL
Java_com_little_1star_detector_impl_tflite_LiteRtNativeDetector_nativeDetectFromBuffer(
    JNIEnv* env, jobject thiz,
    jfloatArray preprocessed_data, jint input_size,
    jint img_width, jint img_height,
    jfloat letterbox_scale, jfloat letterbox_offset_x, jfloat letterbox_offset_y,
    jfloat conf_threshold) {

    if (!g_detector_state || !g_detector_state->is_initialized) {
        ThrowJavaException(env, "java/lang/IllegalStateException",
                           "Detector not initialized");
        return nullptr;
    }

    auto& state = *g_detector_state;

    try {
        auto t0 = std::chrono::high_resolution_clock::now();

        // Copy preprocessed float data directly (skip Bitmap lock + NEON)
        jsize data_len = env->GetArrayLength(preprocessed_data);
        jfloat* data = env->GetFloatArrayElements(preprocessed_data, nullptr);
        if (!data) {
            LOGE("GetFloatArrayElements returned null");
            return nullptr;
        }
        size_t copy_size = std::min(
            static_cast<size_t>(data_len),
            static_cast<size_t>(input_size) * input_size * 3);
        memcpy(state.input_data.data(), data, copy_size * sizeof(float));
        env->ReleaseFloatArrayElements(preprocessed_data, data, JNI_ABORT);

        // Use caller-provided letterbox params (skip preprocess computation)
        state.letterbox_scale = letterbox_scale;
        state.letterbox_offset_x = letterbox_offset_x;
        state.letterbox_offset_y = letterbox_offset_y;

        auto t1 = std::chrono::high_resolution_clock::now();

        // Write input
        auto write_result = state.input_buffer->Write<float>(
            absl::MakeConstSpan(state.input_data));
        if (!write_result.HasValue()) {
            LOGE("Failed to write input data");
            return nullptr;
        }
        auto t2 = std::chrono::high_resolution_clock::now();

        // Inference
        std::vector<litert::TensorBuffer> inputs;
        inputs.push_back(std::move(*state.input_buffer));
        std::vector<litert::TensorBuffer> outputs;
        outputs.push_back(std::move(*state.output_buffer));
        if (state.output_buffer_2) {
            outputs.push_back(std::move(*state.output_buffer_2));
        }

        auto run_result = state.model->Run(inputs, outputs);

        // Reclaim buffers
        state.input_buffer = std::make_unique<litert::TensorBuffer>(std::move(inputs[0]));
        state.output_buffer = std::make_unique<litert::TensorBuffer>(std::move(outputs[0]));
        state.output_buffer_2.reset();
        if (outputs.size() > 1) {
            state.output_buffer_2 = std::make_unique<litert::TensorBuffer>(std::move(outputs[1]));
        }

        if (!run_result.HasValue()) {
            LOGE("Failed to run inference");
            return nullptr;
        }
        auto t3 = std::chrono::high_resolution_clock::now();

        // Read output(s)
        int num_outputs = state.postprocessor->getOutputBufferCount();

        yolo::TensorOutput tensor_outputs[2]{};

        auto size_result = state.output_buffer->Size();
        tensor_outputs[0].size = size_result.HasValue() ? size_result.Value() / sizeof(float) : 0;
        state.output_data.resize(tensor_outputs[0].size);
        auto read_result = state.output_buffer->Read<float>(
            absl::MakeSpan(state.output_data));
        if (!read_result.HasValue()) {
            LOGE("Failed to read output data");
            return nullptr;
        }
        tensor_outputs[0].data = state.output_data.data();

        if (num_outputs > 1 && state.output_buffer_2) {
            auto size2 = state.output_buffer_2->Size();
            tensor_outputs[1].size = size2.HasValue() ? size2.Value() / sizeof(float) : 0;
            state.output_data_2.resize(tensor_outputs[1].size);
            auto read2_result = state.output_buffer_2->Read<float>(
                absl::MakeSpan(state.output_data_2));
            if (!read2_result.HasValue()) {
                LOGE("Failed to read second output data");
                return nullptr;
            }
            tensor_outputs[1].data = state.output_data_2.data();
        }
        auto t4 = std::chrono::high_resolution_clock::now();

        state.last_inference_time_ms =
            std::chrono::duration<double, std::milli>(t4 - t0).count();

        // Postprocess
        yolo::PostprocessConfig config;
        config.input_size = state.input_size;
        config.num_classes = state.num_classes;
        config.num_keypoints = state.num_keypoints;
        config.task_type = static_cast<yolo::TaskType>(state.task_type);
        config.inference_type = static_cast<yolo::InferenceType>(state.inference_type);
        config.letterbox = {state.letterbox_scale, state.letterbox_offset_x, state.letterbox_offset_y};

        std::vector<float> results = state.postprocessor->postprocess(
            tensor_outputs, num_outputs,
            img_width, img_height,
            conf_threshold, config);
        auto t5 = std::chrono::high_resolution_clock::now();

        double copy_ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
        double write_ms = std::chrono::duration<double, std::milli>(t2 - t1).count();
        double inference_ms = std::chrono::duration<double, std::milli>(t3 - t2).count();
        double read_ms = std::chrono::duration<double, std::milli>(t4 - t3).count();
        double postprocess_ms = std::chrono::duration<double, std::milli>(t5 - t4).count();
        double total_ms = std::chrono::duration<double, std::milli>(t5 - t0).count();
        if (ShouldLogPerf()) {
            LOGI("perf(buf) [#%d] | copy=%.1f write=%.1f infer=%.1f read=%.1f post=%.1f | total=%.1f",
                 g_perf_frame_seq, copy_ms, write_ms, inference_ms, read_ms, postprocess_ms, total_ms);
        }

        jfloatArray result = env->NewFloatArray(results.size());
        if (result) {
            env->SetFloatArrayRegion(result, 0, results.size(), results.data());
        }
        return result;

    } catch (const std::exception& e) {
        LOGE("nativeDetectFromBuffer EXCEPTION: %s", e.what());
        ThrowJavaException(env, "java/lang/RuntimeException", e.what());
        return nullptr;
    }
}

// ============================================================================
// AHWB零拷贝检测 — NPU直接从AHWB读取，数据不离硬件
// ============================================================================

JNIEXPORT jfloatArray JNICALL
Java_com_little_1star_detector_impl_tflite_LiteRtNativeDetector_nativeDetectFromAhwb(
    JNIEnv* env, jobject thiz,
    jobject hardware_buffer,
    jint input_size,
    jint img_width, jint img_height,
    jfloat letterbox_scale, jfloat letterbox_offset_x, jfloat letterbox_offset_y,
    jfloat conf_threshold) {

    if (!g_detector_state || !g_detector_state->is_initialized) {
        ThrowJavaException(env, "java/lang/IllegalStateException",
                           "Detector not initialized");
        return nullptr;
    }

    auto& state = *g_detector_state;
    // AHWB 零拷贝同时支持 GPU 和 NPU：
    // - NPU: NPU delegate 直接读取 AHWB 数据
    // - GPU: GPU delegate 支持 AHardwareBufferBlob buffer type (delegate.cc:911)
    if (state.accelerator_mode == 0) {
        LOGE("AHWB: CPU模式不支持AHWB零拷贝");
        return nullptr;
    }

    // 节流：仅在首几帧和每100帧输出诊断日志
    bool log_diag = ShouldLogPerf();

    try {
        auto t0 = std::chrono::high_resolution_clock::now();

        // 1. 从Java HardwareBuffer获取原生AHWB指针
        AHardwareBuffer* ahwb = AHardwareBuffer_fromHardwareBuffer(env, hardware_buffer);
        if (!ahwb) {
            if (log_diag) LOGE("AHWB: AHardwareBuffer_fromHardwareBuffer 返回 null");
            return nullptr;
        }

        // 2. 构建输入Tensor类型: NHWC float32 [1, input_size, input_size, 3]
        litert::Layout::Dim h = input_size;
        litert::Layout::Dim w = input_size;
        auto tensor_type = litert::MakeRankedTensorType<float>({1, h, w, 3});

        // 3. 从AHWB创建零拷贝TensorBuffer
        //    CreateFromAhwb 使用模型编译时的 g_shared_env（确保与模型同源）
        //    AHWB 是标准 Android buffer，不依赖 EGL-aware Environment
        if (log_diag && g_egl_env) {
            LOGI("AHWB zero-copy: EGL-aware Environment 已就绪 (display=%p context=%p)",
                 g_egl_display_ptr, g_egl_context_ptr);
        }
        auto ahwb_input_result = litert::TensorBuffer::CreateFromAhwb(
            *g_shared_env, tensor_type, ahwb, /*offset=*/0);

        if (!ahwb_input_result.HasValue()) {
            // 仅首帧详细输出失败原因，后续静默
            static bool s_ahwb_create_logged = false;
            if (!s_ahwb_create_logged) {
                LOGE("AHWB zero-copy: CreateFromAhwb FAILED → 将回退CPU路径");
                LOGE("  可能原因: AHWB usage flags不兼容 / delegate不支持AHWB导入 / RGBA→RGB通道不匹配");
                s_ahwb_create_logged = true;
            }
            return nullptr;
        }

        if (log_diag) LOGI("AHWB zero-copy: CreateFromAhwb SUCCESS");

        auto ahwb_input_buffer = std::make_unique<litert::TensorBuffer>(
            std::move(ahwb_input_result.Value()));

        // 4. EGL fence sync — 替代glFinish，NPU/GPU 异步等待
        //    使用 EGL-aware Environment 创建 sync event（需要 GL 上下文感知）
        //    如果没有 EGL Environment，回退到普通 Environment
        {
            EGLDisplay egl_display = eglGetCurrentDisplay();
            if (egl_display != EGL_NO_DISPLAY) {
                EGLSyncKHR egl_sync = eglCreateSync(
                    egl_display, EGL_SYNC_FENCE_KHR, nullptr);
                if (egl_sync != EGL_NO_SYNC_KHR) {
                    // EGL-aware Environment 优先，用于 CreateFromEglSyncFence
                    litert::Environment& sync_env = g_egl_env ? *g_egl_env : *g_shared_env;
                    auto event_result = litert::Event::CreateFromEglSyncFence(
                        sync_env,
                        reinterpret_cast<LiteRtEglSyncKhr>(egl_sync));
                    if (event_result.HasValue()) {
                        auto set_result = ahwb_input_buffer->SetEvent(
                            std::move(event_result.Value()));
                        if (log_diag && !set_result.HasValue()) {
                            LOGE("AHWB: SetEvent 失败: %s",
                                 set_result.Error().Message().c_str());
                        }
                    } else {
                        if (log_diag) LOGE("AHWB: CreateFromEglSyncFence 失败: %s",
                             event_result.Error().Message().c_str());
                        eglDestroySync(egl_display, egl_sync);
                    }
                } else if (log_diag) {
                    LOGW("AHWB: eglCreateSync 失败 (err=0x%x)", eglGetError());
                }
            }
        }

        // 5. 使用调用方提供的letterbox参数
        state.letterbox_scale = letterbox_scale;
        state.letterbox_offset_x = letterbox_offset_x;
        state.letterbox_offset_y = letterbox_offset_y;

        auto t1 = std::chrono::high_resolution_clock::now();

        // 6. 推理：AHWB作为输入
        std::vector<litert::TensorBuffer> inputs;
        inputs.push_back(std::move(*ahwb_input_buffer));
        ahwb_input_buffer.reset();
        std::vector<litert::TensorBuffer> outputs;
        outputs.push_back(std::move(*state.output_buffer));
        if (state.output_buffer_2) {
            outputs.push_back(std::move(*state.output_buffer_2));
            state.output_buffer_2.reset();
        }

        auto run_result = state.model->Run(inputs, outputs);

        state.output_buffer = std::make_unique<litert::TensorBuffer>(
            std::move(outputs[0]));
        if (outputs.size() > 1) {
            state.output_buffer_2 = std::make_unique<litert::TensorBuffer>(
                std::move(outputs[1]));
        }

        if (!run_result.HasValue()) {
            // 仅首帧输出失败原因
            static bool s_ahwb_run_logged = false;
            if (!s_ahwb_run_logged) {
                LOGE("AHWB zero-copy: model->Run() FAILED → 将回退CPU");
                s_ahwb_run_logged = true;
            }
            return nullptr;
        }
        auto t2 = std::chrono::high_resolution_clock::now();

        // 7. 读取输出（与GL零拷贝路径相同）
        int num_outputs = state.postprocessor->getOutputBufferCount();
        yolo::TensorOutput tensor_outputs[2]{};

        auto size_result = state.output_buffer->Size();
        tensor_outputs[0].size = size_result.HasValue()
            ? size_result.Value() / sizeof(float) : 0;
        state.output_data.resize(tensor_outputs[0].size);
        auto read_result = state.output_buffer->Read<float>(
            absl::MakeSpan(state.output_data));
        if (!read_result.HasValue()) {
            if (log_diag) LOGE("AHWB: 读取输出失败");
            return nullptr;
        }
        tensor_outputs[0].data = state.output_data.data();

        if (num_outputs > 1 && state.output_buffer_2) {
            auto size2 = state.output_buffer_2->Size();
            tensor_outputs[1].size = size2.HasValue()
                ? size2.Value() / sizeof(float) : 0;
            state.output_data_2.resize(tensor_outputs[1].size);
            auto read2_result = state.output_buffer_2->Read<float>(
                absl::MakeSpan(state.output_data_2));
            if (!read2_result.HasValue()) {
                if (log_diag) LOGE("AHWB: 读取第二输出失败");
                return nullptr;
            }
            tensor_outputs[1].data = state.output_data_2.data();
        }
        auto t3 = std::chrono::high_resolution_clock::now();

        state.last_inference_time_ms =
            std::chrono::duration<double, std::milli>(t3 - t0).count();

        // 8. 后处理
        yolo::PostprocessConfig config;
        config.input_size = state.input_size;
        config.num_classes = state.num_classes;
        config.num_keypoints = state.num_keypoints;
        config.task_type = static_cast<yolo::TaskType>(state.task_type);
        config.inference_type = static_cast<yolo::InferenceType>(state.inference_type);
        config.letterbox = {state.letterbox_scale, state.letterbox_offset_x,
                           state.letterbox_offset_y};

        std::vector<float> results = state.postprocessor->postprocess(
            tensor_outputs, num_outputs,
            img_width, img_height,
            conf_threshold, config);
        auto t4 = std::chrono::high_resolution_clock::now();

        double setup_ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
        double inference_ms = std::chrono::duration<double, std::milli>(t2 - t1).count();
        double read_ms = std::chrono::duration<double, std::milli>(t3 - t2).count();
        double postprocess_ms = std::chrono::duration<double, std::milli>(t4 - t3).count();
        double total_ms = std::chrono::duration<double, std::milli>(t4 - t0).count();
        if (ShouldLogPerf()) {
            LOGI("perf(ahwb-zc) [#%d] | setup=%.1f infer=%.1f read=%.1f post=%.1f | total=%.1f",
                 g_perf_frame_seq, setup_ms, inference_ms, read_ms, postprocess_ms, total_ms);
        }

        jfloatArray result = env->NewFloatArray(results.size());
        if (result) {
            env->SetFloatArrayRegion(result, 0, results.size(), results.data());
        }
        return result;

    } catch (const std::exception& e) {
        LOGE("nativeDetectFromAhwb EXCEPTION: %s", e.what());
        return nullptr;
    }
}

// ============================================================================
// AHWB-GL互操作能力检测
// ============================================================================

JNIEXPORT jboolean JNICALL
Java_com_little_1star_detector_impl_tflite_LiteRtNativeDetector_nativeSupportsAhwbGlInterop(
    JNIEnv* env, jclass clazz) {
    try {
        litert::EnvironmentOptions opts(
            absl::Span<const litert::EnvironmentOptions::Option>{});
        auto env_result = litert::Environment::Create(opts);
        if (!env_result.HasValue()) {
            LOGE("nativeSupportsAhwbGlInterop: 无法创建临时Environment");
            return JNI_FALSE;
        }
        bool supported = env_result.Value().SupportsAhwbGlInterop();
        LOGI("nativeSupportsAhwbGlInterop: AHWB-GL互操作=%s",
             supported ? "支持✓" : "不支持✗");
        return supported ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& e) {
        LOGE("nativeSupportsAhwbGlInterop: 异常: %s", e.what());
        return JNI_FALSE;
    }
}

JNIEXPORT jdouble JNICALL
Java_com_little_1star_detector_impl_tflite_LiteRtNativeDetector_nativeGetLastInferenceMs(
    JNIEnv* env, jobject thiz) {
    if (!g_detector_state) return 0.0;
    return g_detector_state->last_inference_time_ms;
}

// ============================================================================
// EGL 上下文注入（MTK/Tensor 零拷贝支持）
// 在 GL 线程的 onSurfaceCreated 中调用，将当前 EGL display/context 注入 native 层
// 创建 EGL-aware Environment 用于 CreateFromAhwb + CreateFromEglSyncFence
// ============================================================================

JNIEXPORT jboolean JNICALL
Java_com_little_1star_detector_impl_tflite_LiteRtNativeDetector_nativeInjectEglContext(
    JNIEnv* env, jclass clazz) {
    // 获取当前 GL 线程的 EGL display/context
    EGLDisplay egl_display = eglGetCurrentDisplay();
    EGLContext egl_context = eglGetCurrentContext();

    if (egl_display == EGL_NO_DISPLAY || egl_context == EGL_NO_CONTEXT) {
        LOGW("nativeInjectEglContext: EGL上下文不可用 (display=%p context=%p)",
             egl_display, egl_context);
        return JNI_FALSE;
    }

    LOGI("nativeInjectEglContext: 获取到EGL上下文 display=%p context=%p",
         egl_display, egl_context);

    // 如果已有 EGL-aware Environment 且上下文没变，跳过
    if (g_egl_env && g_egl_display_ptr == egl_display && g_egl_context_ptr == egl_context) {
        LOGI("nativeInjectEglContext: EGL上下文未变，复用现有 EGL Environment");
        return JNI_TRUE;
    }

    // 存储 EGL 指针
    g_egl_display_ptr = egl_display;
    g_egl_context_ptr = egl_context;

    // 创建 EGL-aware Environment，复制现有 NPU 选项并添加 EGL 上下文
    std::vector<litert::EnvironmentOptions::Option> opts;

    // 复制 NPU 选项（如果有的话）
    if (!g_env_npu_lib_dir.empty()) {
        opts.push_back(litert::EnvironmentOptions::Option(
            litert::EnvironmentOptions::Tag::kDispatchLibraryDir,
            g_env_npu_lib_dir.c_str()));
        opts.push_back(litert::EnvironmentOptions::Option(
            litert::EnvironmentOptions::Tag::kCompilerPluginLibraryDir,
            g_env_npu_lib_dir.c_str()));
    }
    if (!g_env_npu_cache_dir.empty()) {
        opts.push_back(litert::EnvironmentOptions::Option(
            litert::EnvironmentOptions::Tag::kCompilerCacheDir,
            g_env_npu_cache_dir.c_str()));
    }

    // 注入 EGL display 和 context
    opts.push_back(litert::EnvironmentOptions::Option(
        litert::EnvironmentOptions::Tag::kEglDisplay,
        reinterpret_cast<int64_t>(egl_display)));
    opts.push_back(litert::EnvironmentOptions::Option(
        litert::EnvironmentOptions::Tag::kEglContext,
        reinterpret_cast<int64_t>(egl_context)));

    auto env_result = litert::Environment::Create(
        litert::EnvironmentOptions(opts));
    if (!env_result.HasValue()) {
        LOGE("nativeInjectEglContext: 创建EGL-aware Environment失败");
        g_egl_display_ptr = nullptr;
        g_egl_context_ptr = nullptr;
        return JNI_FALSE;
    }

    g_egl_env = std::make_unique<litert::Environment>(
        std::move(env_result.Value()));

    LOGI("nativeInjectEglContext: EGL-aware Environment创建成功 (NPU lib=%s)",
         g_env_npu_lib_dir.empty() ? "无" : g_env_npu_lib_dir.c_str());
    return JNI_TRUE;
}

// ============================================================================
// BLOB AHWB + SSBO 创建（方案A零拷贝）
// 在 GL 线程上调用（onSurfaceCreated），创建 BLOB AHardwareBuffer 并映射为 GL SSBO
// ============================================================================

JNIEXPORT jobjectArray JNICALL
Java_com_little_1star_detector_impl_tflite_LiteRtNativeDetector_nativeCreateBlobAhwbSsbo(
    JNIEnv* env, jclass clazz, jint input_size) {

    LOGI("BLOB AHWB: 开始创建 BLOB AHWB + SSBO (inputSize=%d)...", input_size);

    // 1. 计算BLOB大小：NHWC [1, H, W, 3] float32
    uint32_t blob_size = input_size * input_size * 3 * sizeof(float);
    LOGI("BLOB AHWB: blob_size=%u bytes (=%u*%u*3*4)", blob_size, input_size, input_size);

    // 2. 创建 BLOB 格式 AHardwareBuffer
    AHardwareBuffer_Desc desc = {};
    desc.width = blob_size;   // BLOB 格式下 width = 字节数
    desc.height = 1;
    desc.layers = 1;
    desc.format = AHARDWAREBUFFER_FORMAT_BLOB;
    desc.usage = AHARDWAREBUFFER_USAGE_GPU_DATA_BUFFER |
                 AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY |
                 AHARDWAREBUFFER_USAGE_CPU_READ_RARELY;

    AHardwareBuffer* ahwb = nullptr;
    int ret = AHardwareBuffer_allocate(&desc, &ahwb);
    if (ret != 0 || !ahwb) {
        LOGE("BLOB AHWB: AHardwareBuffer_create 失败 (ret=%d, errno=%d)", ret, errno);
        return nullptr;
    }
    LOGI("BLOB AHWB: AHardwareBuffer 创建成功 ahwb=%p size=%u", ahwb, blob_size);

    // 3. 转为 Java HardwareBuffer
    jobject ahwb_java = AHardwareBuffer_toHardwareBuffer(env, ahwb);
    if (!ahwb_java) {
        LOGE("BLOB AHWB: AHardwareBuffer_toHardwareBuffer 失败");
        AHardwareBuffer_release(ahwb);
        return nullptr;
    }
    // 创建全局引用（跨线程使用，在 onDrawFrame 中传递给 detectFromAhwb）
    jobject ahwb_global = env->NewGlobalRef(ahwb_java);
    env->DeleteLocalRef(ahwb_java);
    if (!ahwb_global) {
        LOGE("BLOB AHWB: NewGlobalRef 失败");
        AHardwareBuffer_release(ahwb);
        return nullptr;
    }

    // 4. 动态加载 glBufferStorageExternalEXT 扩展
    if (!g_glBufferStorageExternalEXT) {
        g_glBufferStorageExternalEXT = reinterpret_cast<PFNGLBUFFERSTORAGEEXTERNALEXTPROC>(
            eglGetProcAddress("glBufferStorageExternalEXT"));
        if (!g_glBufferStorageExternalEXT) {
            LOGE("BLOB AHWB: glBufferStorageExternalEXT 扩展不可用");
            env->DeleteGlobalRef(ahwb_global);
            AHardwareBuffer_release(ahwb);
            return nullptr;
        }
        LOGI("BLOB AHWB: glBufferStorageExternalEXT 加载成功");
    }

    // 5. AHWB → EGLClientBuffer → GL SSBO
    EGLClientBuffer native_buffer = eglGetNativeClientBufferANDROID(ahwb);
    if (!native_buffer) {
        LOGE("BLOB AHWB: eglGetNativeClientBufferANDROID 失败 (err=0x%x)", eglGetError());
        env->DeleteGlobalRef(ahwb_global);
        AHardwareBuffer_release(ahwb);
        return nullptr;
    }

    GLuint ssbo = 0;
    glGenBuffers(1, &ssbo);
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo);

    g_glBufferStorageExternalEXT(GL_SHADER_STORAGE_BUFFER, 0, blob_size, native_buffer,
        GL_MAP_READ_BIT | GL_MAP_WRITE_BIT | GL_MAP_COHERENT_BIT_EXT | GL_MAP_PERSISTENT_BIT_EXT);

    GLenum gl_err = glGetError();
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
    if (gl_err != GL_NO_ERROR) {
        LOGE("BLOB AHWB: glBufferStorageExternalEXT 失败 (GL err=0x%x)", gl_err);
        glDeleteBuffers(1, &ssbo);
        env->DeleteGlobalRef(ahwb_global);
        AHardwareBuffer_release(ahwb);
        return nullptr;
    }
    LOGI("BLOB AHWB: SSBO 映射成功 ssbo=%u (AHWB BLOB → GL SSBO)", ssbo);

    // 6. 编译 compute shader（FBO纹理 → SSBO BHWC float32）
    const char* compute_src =
        "#version 310 es\n"
        "layout(local_size_x = 8, local_size_y = 8) in;\n"
        "layout(std430, binding = 0) buffer OutputBuffer { float data[]; } output_buf;\n"
        "uniform sampler2D uInputTex;\n"
        "uniform int uWidth;\n"
        "uniform int uHeight;\n"
        "void main() {\n"
        "    int x = int(gl_GlobalInvocationID.x);\n"
        "    int y = int(gl_GlobalInvocationID.y);\n"
        "    if (x >= uWidth || y >= uHeight) return;\n"
        "    int y_flipped = uHeight - 1 - y;\n"
        "    vec2 texCoord = (vec2(x, y) + 0.5) / vec2(uWidth, uHeight);\n"
        "    vec4 color = texture(uInputTex, texCoord);\n"
        "    int baseIdx = (y_flipped * uWidth + x) * 3;\n"
        "    output_buf.data[baseIdx + 0] = color.r;\n"
        "    output_buf.data[baseIdx + 1] = color.g;\n"
        "    output_buf.data[baseIdx + 2] = color.b;\n"
        "}\n";

    GLuint cs = glCreateShader(GL_COMPUTE_SHADER);
    glShaderSource(cs, 1, &compute_src, nullptr);
    glCompileShader(cs);

    GLint compiled = 0;
    glGetShaderiv(cs, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        char log[512];
        glGetShaderInfoLog(cs, sizeof(log), nullptr, log);
        LOGE("BLOB AHWB: compute shader 编译失败: %s", log);
        glDeleteShader(cs);
        glDeleteBuffers(1, &ssbo);
        env->DeleteGlobalRef(ahwb_global);
        AHardwareBuffer_release(ahwb);
        return nullptr;
    }

    GLuint program = glCreateProgram();
    glAttachShader(program, cs);
    glLinkProgram(program);
    GLint linked = 0;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (!linked) {
        char log[512];
        glGetProgramInfoLog(program, sizeof(log), nullptr, log);
        LOGE("BLOB AHWB: compute shader 链接失败: %s", log);
        glDeleteProgram(program);
        glDeleteShader(cs);
        glDeleteBuffers(1, &ssbo);
        env->DeleteGlobalRef(ahwb_global);
        AHardwareBuffer_release(ahwb);
        return nullptr;
    }
    glDeleteShader(cs);

    // 获取 uniform 位置
    g_compute_tex_loc = glGetUniformLocation(program, "uInputTex");
    g_compute_width_loc = glGetUniformLocation(program, "uWidth");
    g_compute_height_loc = glGetUniformLocation(program, "uHeight");

    // 7. 保存全局状态
    g_blob_ahwb = ahwb;
    g_blob_ahwb_java = ahwb_global;
    g_blob_ssbo_id = ssbo;
    g_compute_program = program;

    LOGI("BLOB AHWB: ✓ 创建完成 ahwb=%p ssbo=%u program=%u blob_size=%u",
         ahwb, ssbo, program, blob_size);

    // 8. 返回 Object[]{HardwareBuffer, Integer(ssboId)}
    jclass objectClass = env->FindClass("java/lang/Object");
    jobjectArray result = env->NewObjectArray(2, objectClass, nullptr);

    env->SetObjectArrayElement(result, 0, ahwb_global);

    jclass integerClass = env->FindClass("java/lang/Integer");
    jmethodID valueOf = env->GetStaticMethodID(integerClass, "valueOf", "(I)Ljava/lang/Integer;");
    jobject ssboInteger = env->CallStaticObjectMethod(integerClass, valueOf, static_cast<jint>(ssbo));
    env->SetObjectArrayElement(result, 1, ssboInteger);
    env->DeleteLocalRef(ssboInteger);

    return result;
}

// ============================================================================
// BLOB SSBO compute shader 执行（FBO纹理 → SSBO）
// 在 GL 线程上调用（onDrawFrame），将 FBO 纹理数据写入 BLOB SSBO
// ============================================================================

JNIEXPORT void JNICALL
Java_com_little_1star_detector_impl_tflite_LiteRtNativeDetector_nativeRunComputeToSsbo(
    JNIEnv* env, jclass clazz, jint ssbo_id, jint fbo_tex_id, jint input_size) {

    if (g_compute_program == 0) return;

    // 使用 compute shader 程序
    glUseProgram(g_compute_program);

    // 绑定 SSBO 到 binding 0
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, static_cast<GLuint>(ssbo_id));

    // 绑定 FBO 纹理到 texture unit 0
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(fbo_tex_id));
    glUniform1i(g_compute_tex_loc, 0);

    // 设置尺寸 uniform
    glUniform1i(g_compute_width_loc, input_size);
    glUniform1i(g_compute_height_loc, input_size);

    // 派发 compute shader（8x8 工作组）
    int groups = (input_size + 7) / 8;
    glDispatchCompute(groups, groups, 1);

    // 确保 SSBO 写入完成
    glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
}

// ============================================================================
// BLOB AHWB + SSBO 资源清理
// 需要在 GL context 有效的线程上调用
// ============================================================================

JNIEXPORT void JNICALL
Java_com_little_1star_detector_impl_tflite_LiteRtNativeDetector_nativeDestroyBlobSsbo(
    JNIEnv* env, jclass clazz) {

    LOGI("BLOB AHWB: 清理资源...");

    // 删除 compute shader 程序
    if (g_compute_program != 0) {
        glDeleteProgram(g_compute_program);
        g_compute_program = 0;
        LOGI("BLOB AHWB: compute program 已删除");
    }

    // 删除 SSBO
    if (g_blob_ssbo_id != 0) {
        glDeleteBuffers(1, &g_blob_ssbo_id);
        LOGI("BLOB AHWB: SSBO 已删除 (id=%u)", g_blob_ssbo_id);
        g_blob_ssbo_id = 0;
    }

    // 释放 Java HardwareBuffer 全局引用
    if (g_blob_ahwb_java) {
        env->DeleteGlobalRef(g_blob_ahwb_java);
        g_blob_ahwb_java = nullptr;
        LOGI("BLOB AHWB: Java HardwareBuffer 全局引用已释放");
    }

    // 释放原生 AHardwareBuffer
    if (g_blob_ahwb) {
        AHardwareBuffer_release(g_blob_ahwb);
        LOGI("BLOB AHWB: AHardwareBuffer 已释放");
        g_blob_ahwb = nullptr;
    }

    // 重置 uniform 位置
    g_compute_tex_loc = -1;
    g_compute_width_loc = -1;
    g_compute_height_loc = -1;

    LOGI("BLOB AHWB: 资源清理完成");
}

} // extern "C"
