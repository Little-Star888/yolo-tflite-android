#include "YoloSegPostprocessor.h"
#include "NmsUtils.h"
#include "LetterboxUtils.h"

namespace yolo {

SegPostprocessor::SegPostprocessor(InferenceType inference_type)
    : inference_type_(inference_type) {}

std::vector<float> SegPostprocessor::postprocess(
    const TensorOutput* outputs, size_t num_outputs,
    int img_width, int img_height,
    float conf_threshold,
    const PostprocessConfig& config) {

    if (!outputs[0].data || outputs[0].size == 0) {
        return {static_cast<float>(TaskType::SEGMENTATION), 0.0f, 0.0f};
    }

    if (inference_type_ == InferenceType::TRADITIONAL) {
        return postprocessTraditional(outputs, num_outputs, img_width, img_height,
                                      conf_threshold, config);
    }
    return postprocessE2E(outputs, num_outputs, img_width, img_height,
                          conf_threshold, config);
}

namespace {

// mask裁剪区域
struct CropRegion {
    int x1, y1, x2, y2;
};

void ComputeMask(
    const float* mask_coeffs, int num_coeffs,
    const float* mask_protos, size_t proto_size,
    float box_x1, float box_y1, float box_x2, float box_y2,
    int input_size,
    int mask_w, int mask_h,
    std::vector<float>& mask_out,
    CropRegion& crop) {

    mask_out.assign(mask_h * mask_w, 0.0f);

    if (!mask_protos || proto_size == 0) {
        crop = {0, 0, mask_w, mask_h};
        return;
    }
    if (proto_size < static_cast<size_t>(mask_h * mask_w * num_coeffs)) {
        crop = {0, 0, mask_w, mask_h};
        return;
    }

    float scale = static_cast<float>(mask_w) / input_size;
    int bx1 = std::max(0, std::min(static_cast<int>(box_x1 * scale), mask_w - 1));
    int by1 = std::max(0, std::min(static_cast<int>(box_y1 * scale), mask_h - 1));
    int bx2 = std::max(0, std::min(static_cast<int>(box_x2 * scale), mask_w));
    int by2 = std::max(0, std::min(static_cast<int>(box_y2 * scale), mask_h));

    // 记录裁剪区域，供Kotlin侧渲染时使用
    crop = {bx1, by1, bx2, by2};

    for (int y = by1; y < by2; y++) {
        for (int x = bx1; x < bx2; x++) {
            float sum = 0.0f;
            for (int k = 0; k < num_coeffs; k++) {
                sum += mask_coeffs[k] * mask_protos[y * mask_w * num_coeffs + x * num_coeffs + k];
            }
            mask_out[y * mask_w + x] = (sum > 0.0f) ? 1.0f : 0.0f;
        }
    }
}

} // anonymous namespace

std::vector<float> SegPostprocessor::postprocessE2E(
    const TensorOutput* outputs, size_t num_outputs,
    int img_width, int img_height,
    float conf_threshold,
    const PostprocessConfig& config) {

    const float* det_data = outputs[0].data;
    size_t det_size = outputs[0].size;
    const float* proto_data = (num_outputs > 1) ? outputs[1].data : nullptr;
    size_t proto_size = (num_outputs > 1) ? outputs[1].size : 0;

    int stride = 6 + kNumMaskProtos;
    int num_detections = det_size / stride;
    LetterboxParams lb = config.letterbox;

    // First pass: count valid detections
    struct SegDet {
        int cls;
        float conf, x1, y1, x2, y2;
        const float* mask_coeffs;
    };

    std::vector<SegDet> valid;
    for (int i = 0; i < num_detections; i++) {
        int base = i * stride;
        float x1 = det_data[base + 0];
        float y1 = det_data[base + 1];
        float x2 = det_data[base + 2];
        float y2 = det_data[base + 3];
        float conf = det_data[base + 4];
        float cls = det_data[base + 5];

        if (conf < conf_threshold || conf > 1.0f) continue;
        int cls_int = static_cast<int>(cls);
        if (cls_int < 0 || cls_int >= config.num_classes) continue;

        valid.push_back({cls_int, conf, x1, y1, x2, y2, det_data + base + 6});
    }

    int mask_size = kDefaultMaskH * kDefaultMaskW;
    // 每项: [cls, conf, x1, y1, x2, y2, cropX1, cropY1, cropX2, cropY2, mask...]
    int body_stride = 10 + mask_size;

    // Header: [task_type=2, num_items, extra=mask_size]
    std::vector<float> results;
    results.reserve(3 + valid.size() * body_stride);
    results.push_back(static_cast<float>(TaskType::SEGMENTATION));
    results.push_back(static_cast<float>(valid.size()));
    results.push_back(static_cast<float>(mask_size));

    std::vector<float> mask_buf;
    CropRegion crop;
    for (const auto& det : valid) {
        float bx1 = det.x1 * config.input_size;
        float by1 = det.y1 * config.input_size;
        float bx2 = det.x2 * config.input_size;
        float by2 = det.y2 * config.input_size;
        InverseTransformBox(&bx1, &by1, &bx2, &by2, lb, img_width, img_height);

        ComputeMask(det.mask_coeffs, kNumMaskProtos,
                    proto_data, proto_size,
                    det.x1 * config.input_size, det.y1 * config.input_size,
                    det.x2 * config.input_size, det.y2 * config.input_size,
                    config.input_size, kDefaultMaskW, kDefaultMaskH, mask_buf, crop);

        results.push_back(static_cast<float>(det.cls));
        results.push_back(det.conf);
        results.push_back(bx1);
        results.push_back(by1);
        results.push_back(bx2);
        results.push_back(by2);
        // 裁剪区域坐标，供渲染时正确缩放mask
        results.push_back(static_cast<float>(crop.x1));
        results.push_back(static_cast<float>(crop.y1));
        results.push_back(static_cast<float>(crop.x2));
        results.push_back(static_cast<float>(crop.y2));
        results.insert(results.end(), mask_buf.begin(), mask_buf.end());
    }

    return results;
}

std::vector<float> SegPostprocessor::postprocessTraditional(
    const TensorOutput* outputs, size_t num_outputs,
    int img_width, int img_height,
    float conf_threshold,
    const PostprocessConfig& config) {

    const float* det_data = outputs[0].data;
    size_t det_size = outputs[0].size;
    const float* proto_data = (num_outputs > 1) ? outputs[1].data : nullptr;
    size_t proto_size = (num_outputs > 1) ? outputs[1].size : 0;

    int num_candidates = det_size / (4 + config.num_classes + kNumMaskProtos);
    LetterboxParams lb = config.letterbox;

    struct SegCandidate {
        CandidateBox box;
        float mask_coeffs[kNumMaskProtos];
    };

    std::vector<SegCandidate> candidates;
    candidates.reserve(num_candidates);

    for (int i = 0; i < num_candidates; i++) {
        float cx = det_data[0 * num_candidates + i] * config.input_size;
        float cy = det_data[1 * num_candidates + i] * config.input_size;
        float w  = det_data[2 * num_candidates + i] * config.input_size;
        float h  = det_data[3 * num_candidates + i] * config.input_size;

        float max_score = 0.0f;
        int max_class = 0;
        for (int c = 0; c < config.num_classes; c++) {
            float score = det_data[(4 + c) * num_candidates + i];
            if (score > max_score) {
                max_score = score;
                max_class = c;
            }
        }

        if (max_score >= conf_threshold) {
            SegCandidate cand;
            cand.box = {
                cx - w * 0.5f, cy - h * 0.5f,
                cx + w * 0.5f, cy + h * 0.5f,
                max_score, max_class
            };
            for (int m = 0; m < kNumMaskProtos; m++) {
                cand.mask_coeffs[m] = det_data[(4 + config.num_classes + m) * num_candidates + i];
            }
            candidates.push_back(cand);
        }
    }

    std::vector<CandidateBox> boxes;
    boxes.reserve(candidates.size());
    for (const auto& c : candidates) boxes.push_back(c.box);

    auto kept_indices = ApplyNmsIndexed(boxes, kNmsIouThreshold);

    int mask_size = kDefaultMaskH * kDefaultMaskW;
    // 每项: [cls, conf, x1, y1, x2, y2, cropX1, cropY1, cropX2, cropY2, mask...]
    int body_stride = 10 + mask_size;

    std::vector<float> results;
    results.reserve(3 + kept_indices.size() * body_stride);
    results.push_back(static_cast<float>(TaskType::SEGMENTATION));
    results.push_back(static_cast<float>(kept_indices.size()));
    results.push_back(static_cast<float>(mask_size));

    std::vector<float> mask_buf;
    CropRegion crop;
    for (int idx : kept_indices) {
        const auto& cand = candidates[idx];
        float x1 = cand.box.x1, y1 = cand.box.y1, x2 = cand.box.x2, y2 = cand.box.y2;
        InverseTransformBox(&x1, &y1, &x2, &y2, lb, img_width, img_height);

        ComputeMask(cand.mask_coeffs, kNumMaskProtos,
                    proto_data, proto_size,
                    cand.box.x1, cand.box.y1, cand.box.x2, cand.box.y2,
                    config.input_size, kDefaultMaskW, kDefaultMaskH, mask_buf, crop);

        results.push_back(static_cast<float>(cand.box.class_id));
        results.push_back(cand.box.confidence);
        results.push_back(x1);
        results.push_back(y1);
        results.push_back(x2);
        results.push_back(y2);
        // 裁剪区域坐标，供渲染时正确缩放mask
        results.push_back(static_cast<float>(crop.x1));
        results.push_back(static_cast<float>(crop.y1));
        results.push_back(static_cast<float>(crop.x2));
        results.push_back(static_cast<float>(crop.y2));
        results.insert(results.end(), mask_buf.begin(), mask_buf.end());
    }

    return results;
}

} // namespace yolo
