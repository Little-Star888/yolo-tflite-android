#ifndef YOLO_SEG_POSTPROCESSOR_H
#define YOLO_SEG_POSTPROCESSOR_H

#include "IPostprocessor.h"

namespace yolo {

class SegPostprocessor : public IPostprocessor {
public:
    explicit SegPostprocessor(InferenceType inference_type);

    std::vector<float> postprocess(
        const TensorOutput* outputs, size_t num_outputs,
        int img_width, int img_height,
        float conf_threshold,
        const PostprocessConfig& config) override;

    int getOutputBufferCount() const override { return 2; }

private:
    InferenceType inference_type_;
    static constexpr int kNumMaskProtos = 32;
    static constexpr int kDefaultMaskH = 160;
    static constexpr int kDefaultMaskW = 160;

    std::vector<float> postprocessE2E(
        const TensorOutput* outputs, size_t num_outputs,
        int img_width, int img_height,
        float conf_threshold,
        const PostprocessConfig& config);

    std::vector<float> postprocessTraditional(
        const TensorOutput* outputs, size_t num_outputs,
        int img_width, int img_height,
        float conf_threshold,
        const PostprocessConfig& config);
};

} // namespace yolo

#endif // YOLO_SEG_POSTPROCESSOR_H
