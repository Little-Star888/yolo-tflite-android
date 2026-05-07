#ifndef YOLO_IPOSTPROCESSOR_H
#define YOLO_IPOSTPROCESSOR_H

#include <vector>
#include <cstddef>
#include <memory>
#include "LetterboxUtils.h"

namespace yolo {

enum class TaskType : int {
    DETECTION     = 0,
    KEYPOINT      = 1,
    SEGMENTATION  = 2,
    CLASSIFICATION = 3,
    ORIENTED_BBOX = 4
};

enum class InferenceType : int {
    END2END     = 0,
    TRADITIONAL = 1
};

enum class PreprocessMode {
    LETTERBOX,
    CENTER_CROP
};

struct TensorOutput {
    const float* data;
    size_t size;  // float count
};

struct PostprocessConfig {
    int input_size;
    int num_classes;
    int num_keypoints;
    TaskType task_type;
    InferenceType inference_type;
    LetterboxParams letterbox;
};

class IPostprocessor {
public:
    virtual ~IPostprocessor() = default;

    virtual std::vector<float> postprocess(
        const TensorOutput* outputs, size_t num_outputs,
        int img_width, int img_height,
        float conf_threshold,
        const PostprocessConfig& config) = 0;

    virtual int getOutputBufferCount() const = 0;

    virtual PreprocessMode getPreprocessMode() const {
        return PreprocessMode::LETTERBOX;
    }
};

} // namespace yolo

#endif // YOLO_IPOSTPROCESSOR_H
