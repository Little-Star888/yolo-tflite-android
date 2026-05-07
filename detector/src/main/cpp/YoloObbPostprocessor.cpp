#include "YoloObbPostprocessor.h"
#include "NmsUtils.h"
#include "LetterboxUtils.h"

namespace yolo {

ObbPostprocessor::ObbPostprocessor(InferenceType inference_type)
    : inference_type_(inference_type) {}

std::vector<float> ObbPostprocessor::postprocess(
    const TensorOutput* outputs, size_t num_outputs,
    int img_width, int img_height,
    float conf_threshold,
    const PostprocessConfig& config) {

    if (!outputs[0].data || outputs[0].size == 0) {
        return {static_cast<float>(TaskType::ORIENTED_BBOX), 0.0f, 0.0f};
    }

    if (inference_type_ == InferenceType::TRADITIONAL) {
        return postprocessTraditional(outputs[0], img_width, img_height,
                                      conf_threshold, config);
    }
    return postprocessE2E(outputs[0], img_width, img_height,
                          conf_threshold, config);
}

std::vector<float> ObbPostprocessor::postprocessE2E(
    const TensorOutput& output,
    int img_width, int img_height,
    float conf_threshold,
    const PostprocessConfig& config) {

    int num_detections = output.size / 7;
    LetterboxParams lb = config.letterbox;

    std::vector<float> results;
    results.reserve(3 + num_detections * 7);

    results.push_back(static_cast<float>(TaskType::ORIENTED_BBOX));
    results.push_back(0.0f);  // placeholder
    results.push_back(0.0f);

    int kept = 0;
    for (int i = 0; i < num_detections; i++) {
        int base = i * 7;
        float cx = output.data[base + 0] * config.input_size;
        float cy = output.data[base + 1] * config.input_size;
        float w  = output.data[base + 2] * config.input_size;
        float h  = output.data[base + 3] * config.input_size;
        float conf = output.data[base + 4];
        float cls = output.data[base + 5];
        float angle_rad = output.data[base + 6];

        if (conf < conf_threshold || conf > 1.0f) continue;
        int cls_int = static_cast<int>(cls);
        if (cls_int < 0 || cls_int >= config.num_classes) continue;

        float x1 = cx - w * 0.5f;
        float y1 = cy - h * 0.5f;
        float x2 = cx + w * 0.5f;
        float y2 = cy + h * 0.5f;
        InverseTransformBox(&x1, &y1, &x2, &y2, lb, img_width, img_height);

        float angle_deg = angle_rad * kRadToDeg;

        results.push_back(static_cast<float>(cls_int));
        results.push_back(conf);
        results.push_back(x1);
        results.push_back(y1);
        results.push_back(x2);
        results.push_back(y2);
        results.push_back(angle_deg);
        kept++;
    }

    results[1] = static_cast<float>(kept);
    return results;
}

std::vector<float> ObbPostprocessor::postprocessTraditional(
    const TensorOutput& output,
    int img_width, int img_height,
    float conf_threshold,
    const PostprocessConfig& config) {

    int num_candidates = output.size / (4 + config.num_classes + 1);
    LetterboxParams lb = config.letterbox;

    struct ObbCandidate {
        CandidateBox box;
        float angle;
    };

    std::vector<ObbCandidate> candidates;
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

        float angle = output.data[(4 + config.num_classes) * num_candidates + i];

        if (max_score >= conf_threshold) {
            candidates.push_back({
                CandidateBox{
                    cx - w * 0.5f, cy - h * 0.5f,
                    cx + w * 0.5f, cy + h * 0.5f,
                    max_score, max_class
                },
                angle
            });
        }
    }

    std::vector<CandidateBox> boxes;
    boxes.reserve(candidates.size());
    for (const auto& c : candidates) boxes.push_back(c.box);

    auto kept_indices = ApplyNmsIndexed(boxes, kNmsIouThreshold);

    std::vector<float> results;
    results.reserve(3 + kept_indices.size() * 7);
    results.push_back(static_cast<float>(TaskType::ORIENTED_BBOX));
    results.push_back(static_cast<float>(kept_indices.size()));
    results.push_back(0.0f);

    for (int idx : kept_indices) {
        const auto& cand = candidates[idx];
        float x1 = cand.box.x1, y1 = cand.box.y1, x2 = cand.box.x2, y2 = cand.box.y2;
        InverseTransformBox(&x1, &y1, &x2, &y2, lb, img_width, img_height);

        results.push_back(static_cast<float>(cand.box.class_id));
        results.push_back(cand.box.confidence);
        results.push_back(x1);
        results.push_back(y1);
        results.push_back(x2);
        results.push_back(y2);
        results.push_back(cand.angle * kRadToDeg);
    }

    return results;
}

} // namespace yolo
