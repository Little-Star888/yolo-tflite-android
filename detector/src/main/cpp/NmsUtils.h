#ifndef YOLO_NMS_UTILS_H
#define YOLO_NMS_UTILS_H

#include <vector>
#include <algorithm>
#include <cmath>

namespace yolo {

constexpr float kNmsIouThreshold = 0.45f;

struct CandidateBox {
    float x1, y1, x2, y2;
    float confidence;
    int class_id;
};

inline float ComputeIou(const CandidateBox& a, const CandidateBox& b) {
    float inter_x1 = std::max(a.x1, b.x1);
    float inter_y1 = std::max(a.y1, b.y1);
    float inter_x2 = std::min(a.x2, b.x2);
    float inter_y2 = std::min(a.y2, b.y2);

    float inter_w = std::max(0.0f, inter_x2 - inter_x1);
    float inter_h = std::max(0.0f, inter_y2 - inter_y1);
    float inter_area = inter_w * inter_h;

    float a_area = (a.x2 - a.x1) * (a.y2 - a.y1);
    float b_area = (b.x2 - b.x1) * (b.y2 - b.y1);
    float union_area = a_area + b_area - inter_area;

    return (union_area > 0) ? inter_area / union_area : 0.0f;
}

inline std::vector<CandidateBox> ApplyNms(
    std::vector<CandidateBox> boxes, float iou_threshold) {
    if (boxes.empty()) return {};

    std::sort(boxes.begin(), boxes.end(),
              [](const CandidateBox& a, const CandidateBox& b) {
                  return a.confidence > b.confidence;
              });

    std::vector<bool> suppressed(boxes.size(), false);
    std::vector<CandidateBox> result;

    for (size_t i = 0; i < boxes.size(); i++) {
        if (suppressed[i]) continue;
        result.push_back(boxes[i]);

        for (size_t j = i + 1; j < boxes.size(); j++) {
            if (suppressed[j]) continue;
            if (boxes[j].class_id != boxes[i].class_id) continue;
            if (ComputeIou(boxes[i], boxes[j]) >= iou_threshold) {
                suppressed[j] = true;
            }
        }
    }

    return result;
}

// Returns indices of surviving candidates (into the original boxes array, pre-sort)
inline std::vector<int> ApplyNmsIndexed(
    const std::vector<CandidateBox>& boxes, float iou_threshold) {
    if (boxes.empty()) return {};

    // Track original indices through sort
    struct Indexed { CandidateBox box; int orig_idx; };
    std::vector<Indexed> indexed(boxes.size());
    for (size_t i = 0; i < boxes.size(); i++) {
        indexed[i] = {boxes[i], static_cast<int>(i)};
    }

    std::sort(indexed.begin(), indexed.end(),
              [](const Indexed& a, const Indexed& b) {
                  return a.box.confidence > b.box.confidence;
              });

    std::vector<bool> suppressed(indexed.size(), false);
    std::vector<int> result;

    for (size_t i = 0; i < indexed.size(); i++) {
        if (suppressed[i]) continue;
        result.push_back(indexed[i].orig_idx);

        for (size_t j = i + 1; j < indexed.size(); j++) {
            if (suppressed[j]) continue;
            if (indexed[j].box.class_id != indexed[i].box.class_id) continue;
            if (ComputeIou(indexed[i].box, indexed[j].box) >= iou_threshold) {
                suppressed[j] = true;
            }
        }
    }

    return result;
}

} // namespace yolo

#endif // YOLO_NMS_UTILS_H
