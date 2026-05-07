#include "YoloKptPostprocessor.h"
#include "NmsUtils.h"
#include "LetterboxUtils.h"

namespace yolo {

KptPostprocessor::KptPostprocessor(InferenceType inference_type)
    : inference_type_(inference_type) {}

std::vector<float> KptPostprocessor::postprocess(
    const TensorOutput* outputs, size_t num_outputs,
    int img_width, int img_height,
    float conf_threshold,
    const PostprocessConfig& config) {

    if (!outputs[0].data || outputs[0].size == 0) {
        return {static_cast<float>(TaskType::KEYPOINT), 0.0f, 0.0f};
    }

    int num_kpts = config.num_keypoints;
    if (num_kpts <= 0) {
        return {static_cast<float>(TaskType::KEYPOINT), 0.0f, 0.0f};
    }

    if (inference_type_ == InferenceType::TRADITIONAL) {
        return postprocessTraditional(outputs[0], img_width, img_height,
                                      conf_threshold, config);
    }
    return postprocessE2E(outputs[0], img_width, img_height,
                          conf_threshold, config);
}

std::vector<float> KptPostprocessor::postprocessE2E(
    const TensorOutput& output,
    int img_width, int img_height,
    float conf_threshold,
    const PostprocessConfig& config) {

    int num_kpts = config.num_keypoints;
    int stride = 6 + num_kpts * 3;
    int num_detections = output.size / stride;
    LetterboxParams lb = config.letterbox;

    std::vector<float> results;
    results.reserve(3 + num_detections * stride);

    results.push_back(static_cast<float>(TaskType::KEYPOINT));
    results.push_back(0.0f);  // num_items placeholder
    results.push_back(static_cast<float>(num_kpts));

    int kept = 0;
    for (int i = 0; i < num_detections; i++) {
        int base = i * stride;
        float x1 = output.data[base + 0];
        float y1 = output.data[base + 1];
        float x2 = output.data[base + 2];
        float y2 = output.data[base + 3];
        float conf = output.data[base + 4];
        float cls = output.data[base + 5];

        if (conf < conf_threshold || conf > 1.0f) continue;
        int cls_int = static_cast<int>(cls);
        if (cls_int < 0 || cls_int >= config.num_classes) continue;

        // Box: normalized → pixel space → inverse letterbox
        float bx1 = x1 * config.input_size;
        float by1 = y1 * config.input_size;
        float bx2 = x2 * config.input_size;
        float by2 = y2 * config.input_size;
        InverseTransformBox(&bx1, &by1, &bx2, &by2, lb, img_width, img_height);

        results.push_back(static_cast<float>(cls_int));
        results.push_back(conf);
        results.push_back(bx1);
        results.push_back(by1);
        results.push_back(bx2);
        results.push_back(by2);

        // Keypoints: normalized → pixel space → inverse transform
        for (int k = 0; k < num_kpts; k++) {
            float kx = output.data[base + 6 + k * 3] * config.input_size;
            float ky = output.data[base + 6 + k * 3 + 1] * config.input_size;
            float kv = output.data[base + 6 + k * 3 + 2];
            InverseTransformPoint(&kx, &ky, lb, img_width, img_height);
            results.push_back(kx);
            results.push_back(ky);
            results.push_back(kv);
        }
        kept++;
    }

    results[1] = static_cast<float>(kept);
    return results;
}

std::vector<float> KptPostprocessor::postprocessTraditional(
    const TensorOutput& output,
    int img_width, int img_height,
    float conf_threshold,
    const PostprocessConfig& config) {

    int num_kpts = config.num_keypoints;
    int kpt_offset = 4 + config.num_classes;
    int total_channels = 4 + config.num_classes + num_kpts * 3;
    int num_candidates = output.size / total_channels;
    LetterboxParams lb = config.letterbox;

    // Internal struct to carry keypoints through NMS
    struct KptCandidate {
        CandidateBox box;
        std::vector<float> kpts;  // [kx, ky, kv] per keypoint
    };

    std::vector<KptCandidate> candidates;
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
            KptCandidate cand;
            cand.box = {
                cx - w * 0.5f, cy - h * 0.5f,
                cx + w * 0.5f, cy + h * 0.5f,
                max_score, max_class
            };
            cand.kpts.reserve(num_kpts * 3);
            for (int k = 0; k < num_kpts; k++) {
                float kx = output.data[(kpt_offset + k * 3) * num_candidates + i] * config.input_size;
                float ky = output.data[(kpt_offset + k * 3 + 1) * num_candidates + i] * config.input_size;
                float kv = output.data[(kpt_offset + k * 3 + 2) * num_candidates + i];
                cand.kpts.push_back(kx);
                cand.kpts.push_back(ky);
                cand.kpts.push_back(kv);
            }
            candidates.push_back(std::move(cand));
        }
    }

    // NMS via indexed results
    std::vector<CandidateBox> boxes;
    boxes.reserve(candidates.size());
    for (const auto& c : candidates) boxes.push_back(c.box);

    auto kept_indices = ApplyNmsIndexed(boxes, kNmsIouThreshold);

    int stride = 6 + num_kpts * 3;
    std::vector<float> results;
    results.reserve(3 + kept_indices.size() * stride);
    results.push_back(static_cast<float>(TaskType::KEYPOINT));
    results.push_back(static_cast<float>(kept_indices.size()));
    results.push_back(static_cast<float>(num_kpts));

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

        for (int k = 0; k < num_kpts; k++) {
            float kx = cand.kpts[k * 3];
            float ky = cand.kpts[k * 3 + 1];
            float kv = cand.kpts[k * 3 + 2];
            InverseTransformPoint(&kx, &ky, lb, img_width, img_height);
            results.push_back(kx);
            results.push_back(ky);
            results.push_back(kv);
        }
    }

    return results;
}

} // namespace yolo
