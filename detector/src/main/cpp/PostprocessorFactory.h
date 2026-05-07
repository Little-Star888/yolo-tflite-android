#ifndef YOLO_POSTPROCESSOR_FACTORY_H
#define YOLO_POSTPROCESSOR_FACTORY_H

#include "IPostprocessor.h"

namespace yolo {

std::unique_ptr<IPostprocessor> CreatePostprocessor(
    TaskType task_type, InferenceType inference_type);

} // namespace yolo

#endif // YOLO_POSTPROCESSOR_FACTORY_H
