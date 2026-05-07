#include "YoloClsPostprocessor.h"
#include <limits>

namespace yolo {

std::vector<float> ClsPostprocessor::postprocess(
    const TensorOutput* outputs, size_t num_outputs,
    int img_width, int img_height,
    float conf_threshold,
    const PostprocessConfig& config) {

    if (!outputs[0].data || outputs[0].size == 0) {
        return {static_cast<float>(TaskType::CLASSIFICATION), 0.0f, 0.0f};
    }

    const float* data = outputs[0].data;
    size_t size = outputs[0].size;

    float max_score = -FLT_MAX;
    int max_class_id = 0;

    for (size_t i = 0; i < size; i++) {
        if (data[i] > max_score) {
            max_score = data[i];
            max_class_id = static_cast<int>(i);
        }
    }

    // Header: [task_type=3, num_items, extra=0]
    // Body (if num_items=1): [cls, conf]
    if (max_score < conf_threshold) {
        return {
            static_cast<float>(TaskType::CLASSIFICATION),
            0.0f,
            0.0f
        };
    }

    return {
        static_cast<float>(TaskType::CLASSIFICATION),
        1.0f,
        0.0f,
        static_cast<float>(max_class_id),
        max_score
    };
}

} // namespace yolo
