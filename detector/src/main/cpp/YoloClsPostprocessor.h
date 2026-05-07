#ifndef YOLO_CLS_POSTPROCESSOR_H
#define YOLO_CLS_POSTPROCESSOR_H

#include "IPostprocessor.h"

namespace yolo {

class ClsPostprocessor : public IPostprocessor {
public:
    std::vector<float> postprocess(
        const TensorOutput* outputs, size_t num_outputs,
        int img_width, int img_height,
        float conf_threshold,
        const PostprocessConfig& config) override;

    int getOutputBufferCount() const override { return 1; }

    PreprocessMode getPreprocessMode() const override {
        return PreprocessMode::CENTER_CROP;
    }
};

} // namespace yolo

#endif // YOLO_CLS_POSTPROCESSOR_H
