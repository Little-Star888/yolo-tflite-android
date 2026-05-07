#include "PostprocessorFactory.h"
#include "YoloDetPostprocessor.h"
#include "YoloClsPostprocessor.h"
#include "YoloKptPostprocessor.h"
#include "YoloSegPostprocessor.h"
#include "YoloObbPostprocessor.h"

namespace yolo {

std::unique_ptr<IPostprocessor> CreatePostprocessor(
    TaskType task_type, InferenceType inference_type) {
    switch (task_type) {
        case TaskType::DETECTION:
            return std::make_unique<DetPostprocessor>(inference_type);
        case TaskType::CLASSIFICATION:
            return std::make_unique<ClsPostprocessor>();
        case TaskType::KEYPOINT:
            return std::make_unique<KptPostprocessor>(inference_type);
        case TaskType::SEGMENTATION:
            return std::make_unique<SegPostprocessor>(inference_type);
        case TaskType::ORIENTED_BBOX:
            return std::make_unique<ObbPostprocessor>(inference_type);
    }
    return nullptr;
}

} // namespace yolo
