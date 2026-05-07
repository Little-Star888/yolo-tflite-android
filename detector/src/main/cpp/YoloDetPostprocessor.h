#ifndef YOLO_DET_POSTPROCESSOR_H
#define YOLO_DET_POSTPROCESSOR_H

#include "IPostprocessor.h"

namespace yolo {

class DetPostprocessor : public IPostprocessor {
public:
    explicit DetPostprocessor(InferenceType inference_type);

    std::vector<float> postprocess(
        const TensorOutput* outputs, size_t num_outputs,
        int img_width, int img_height,
        float conf_threshold,
        const PostprocessConfig& config) override;

    int getOutputBufferCount() const override { return 1; }

private:
    InferenceType inference_type_;

    std::vector<float> postprocessE2E(
        const TensorOutput& output,
        int img_width, int img_height,
        float conf_threshold,
        const PostprocessConfig& config);

    std::vector<float> postprocessTraditional(
        const TensorOutput& output,
        int img_width, int img_height,
        float conf_threshold,
        const PostprocessConfig& config);
};

} // namespace yolo

#endif // YOLO_DET_POSTPROCESSOR_H
