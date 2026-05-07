#ifndef LITERT_JNI_H
#define LITERT_JNI_H

#include <jni.h>
#include <android/bitmap.h>
#include <android/asset_manager.h>
#include <string>
#include <memory>
#include <vector>

// LiteRT C++ API
#include "litert/cc/litert_environment.h"
#include "litert/cc/litert_environment_options.h"
#include "litert/cc/litert_compiled_model.h"
#include "litert/cc/litert_tensor_buffer.h"
#include "litert/cc/litert_options.h"
#include "litert/cc/litert_common.h"
#include "litert/cc/options/litert_qualcomm_options.h"
#include "litert/cc/litert_buffer_ref.h"

#include "IPostprocessor.h"

struct NativeDetectorState {
    // Model asset (mmap lifetime — must outlive CompiledModel)
    // Declared first so it's destroyed LAST (C++ destroys members in reverse order)
    AAsset* model_asset = nullptr;

    std::unique_ptr<litert::CompiledModel> model;

    // Buffers
    std::unique_ptr<litert::TensorBuffer> input_buffer;
    std::unique_ptr<litert::TensorBuffer> output_buffer;
    std::unique_ptr<litert::TensorBuffer> output_buffer_2;  // SEG proto tensor

    // Postprocessor (created per model, dispatches by task_type + inference_type)
    std::unique_ptr<yolo::IPostprocessor> postprocessor;

    // Config
    int input_size = 640;
    int num_classes = 0;
    int num_keypoints = 0;
    int task_type = 0;      // 0=DET, 1=KPT, 2=SEG, 3=CLS, 4=OBB
    int inference_type = 0;  // 0=END2END, 1=TRADITIONAL
    int accelerator_mode = 0; // 0=CPU, 1=GPU, 2=NPU

    // Class names
    std::vector<std::string> class_names;

    // Letterbox params (written by preprocess, read by postprocess)
    float letterbox_scale = 1.0f;
    float letterbox_offset_x = 0.0f;
    float letterbox_offset_y = 0.0f;

    // Pre-allocated input buffer (~5MB)
    std::vector<float> input_data;

    // Persistent output buffers (avoid per-frame allocation)
    std::vector<float> output_data;
    std::vector<float> output_data_2;

    // Perf
    double last_inference_time_ms = 0.0;
    bool is_initialized = false;

    ~NativeDetectorState() {
        model.reset();
        if (model_asset) {
            AAsset_close(model_asset);
            model_asset = nullptr;
        }
    }
};

#endif // LITERT_JNI_H
