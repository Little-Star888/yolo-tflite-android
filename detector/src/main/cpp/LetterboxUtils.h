#ifndef YOLO_LETTERBOX_UTILS_H
#define YOLO_LETTERBOX_UTILS_H

#include <cmath>
#include <algorithm>

namespace yolo {

struct LetterboxParams {
    float scale;
    float offset_x;
    float offset_y;
};

inline void InverseTransformBox(
    float* x1, float* y1, float* x2, float* y2,
    const LetterboxParams& lb,
    int img_width, int img_height) {

    float left   = (*x1 - lb.offset_x) / lb.scale;
    float top    = (*y1 - lb.offset_y) / lb.scale;
    float right  = (*x2 - lb.offset_x) / lb.scale;
    float bottom = (*y2 - lb.offset_y) / lb.scale;

    *x1 = std::max(0.0f, std::min(left,   static_cast<float>(img_width)));
    *y1 = std::max(0.0f, std::min(top,    static_cast<float>(img_height)));
    *x2 = std::max(0.0f, std::min(right,  static_cast<float>(img_width)));
    *y2 = std::max(0.0f, std::min(bottom, static_cast<float>(img_height)));
}

inline void InverseTransformPoint(
    float* x, float* y,
    const LetterboxParams& lb,
    int img_width, int img_height) {

    *x = std::max(0.0f, std::min((*x - lb.offset_x) / lb.scale,
                                  static_cast<float>(img_width)));
    *y = std::max(0.0f, std::min((*y - lb.offset_y) / lb.scale,
                                  static_cast<float>(img_height)));
}

} // namespace yolo

#endif // YOLO_LETTERBOX_UTILS_H
