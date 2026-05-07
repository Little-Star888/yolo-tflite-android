#include "YoloDetPostprocessor.h"
#include "NmsUtils.h"
#include "LetterboxUtils.h"

namespace yolo {

DetPostprocessor::DetPostprocessor(InferenceType inference_type)
    : inference_type_(inference_type) {}

std::vector<float> DetPostprocessor::postprocess(
    const TensorOutput* outputs, size_t num_outputs,
    int img_width, int img_height,
    float conf_threshold,
    const PostprocessConfig& config) {

    if (!outputs[0].data || outputs[0].size == 0) {
        return {static_cast<float>(TaskType::DETECTION), 0.0f, 0.0f};
    }

    if (inference_type_ == InferenceType::TRADITIONAL) {
        return postprocessTraditional(outputs[0], img_width, img_height,
                                      conf_threshold, config);
    }
    return postprocessE2E(outputs[0], img_width, img_height,
                          conf_threshold, config);
}

std::vector<float> DetPostprocessor::postprocessE2E(
    const TensorOutput& output,
    int img_width, int img_height,
    float conf_threshold,
    const PostprocessConfig& config) {

    int num_detections = output.size / 6;

    // Header: [task_type=0, num_items, extra=0]
    std::vector<float> results;
    results.reserve(3 + num_detections * 6);

    int kept = 0;
    // Placeholder for count — filled after loop
    results.push_back(static_cast<float>(TaskType::DETECTION));
    results.push_back(0.0f);  // num_items placeholder
    results.push_back(0.0f);  // extra

    LetterboxParams lb = config.letterbox;

    for (int i = 0; i < num_detections; i++) {
        int base = i * 6;
        float x1 = output.data[base + 0];
        float y1 = output.data[base + 1];
        float x2 = output.data[base + 2];
        float y2 = output.data[base + 3];
        float conf = output.data[base + 4];
        float cls = output.data[base + 5];

        if (conf < conf_threshold || conf > 1.0f) continue;
        int cls_int = static_cast<int>(cls);
        if (cls_int < 0 || cls_int >= config.num_classes) continue;

        x1 *= config.input_size;
        y1 *= config.input_size;
        x2 *= config.input_size;
        y2 *= config.input_size;

        InverseTransformBox(&x1, &y1, &x2, &y2, lb, img_width, img_height);

        results.push_back(static_cast<float>(cls_int));
        results.push_back(conf);
        results.push_back(x1);
        results.push_back(y1);
        results.push_back(x2);
        results.push_back(y2);
        kept++;
    }

    results[1] = static_cast<float>(kept);
    return results;
}

std::vector<float> DetPostprocessor::postprocessTraditional(
    const TensorOutput& output,
    int img_width, int img_height,
    float conf_threshold,
    const PostprocessConfig& config) {

    int num_candidates = output.size / (4 + config.num_classes);
    LetterboxParams lb = config.letterbox;

    std::vector<CandidateBox> candidates;
    candidates.reserve(num_candidates);

    for (int i = 0; i < num_candidates; i++) {
        float cx = output.data[0 * num_candidates + i] * config.input_size;
        float cy = output.data[1 * num_candidates + i] * config.input_size;
        float w  = output.data[2 * num_candidates + i] * config.input_size;
        float h  = output.data[3 * num_candidates + i] * config.input_size;

        float max_score = 0.0f;
        int max_class = 0;
        for (int c = 0; c < config.num_classes; c++) {
            float score = output.data[(4 + c) * num_candidates + i];
            if (score > max_score) {
                max_score = score;
                max_class = c;
            }
        }

        if (max_score >= conf_threshold) {
            candidates.push_back({
                cx - w * 0.5f, cy - h * 0.5f,
                cx + w * 0.5f, cy + h * 0.5f,
                max_score, max_class
            });
        }
    }

    auto nms_boxes = ApplyNms(std::move(candidates), kNmsIouThreshold);

    std::vector<float> results;
    results.reserve(3 + nms_boxes.size() * 6);
    results.push_back(static_cast<float>(TaskType::DETECTION));
    results.push_back(static_cast<float>(nms_boxes.size()));
    results.push_back(0.0f);

    for (const auto& box : nms_boxes) {
        float x1 = box.x1, y1 = box.y1, x2 = box.x2, y2 = box.y2;
        InverseTransformBox(&x1, &y1, &x2, &y2, lb, img_width, img_height);

        results.push_back(static_cast<float>(box.class_id));
        results.push_back(box.confidence);
        results.push_back(x1);
        results.push_back(y1);
        results.push_back(x2);
        results.push_back(y2);
    }

    return results;
}

} // namespace yolo
