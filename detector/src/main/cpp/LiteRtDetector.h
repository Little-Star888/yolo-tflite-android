#ifndef LITERT_DETECTOR_H
#define LITERT_DETECTOR_H

// 最近邻插值（保留作为备选）
void PreprocessLetterboxAndNormalize(
    const void* src_pixels,
    int src_width, int src_height,
    float* dst,
    int dst_size,
    float* out_scale,
    float* out_offset_x,
    float* out_offset_y);

void PreprocessCenterCropAndNormalize(
    const void* src_pixels,
    int src_width, int src_height,
    float* dst,
    int dst_size);

// 双线性插值（默认使用）
void PreprocessLetterboxAndNormalize_Bilinear(
    const void* src_pixels,
    int src_width, int src_height,
    float* dst,
    int dst_size,
    float* out_scale,
    float* out_offset_x,
    float* out_offset_y);

void PreprocessCenterCropAndNormalize_Bilinear(
    const void* src_pixels,
    int src_width, int src_height,
    float* dst,
    int dst_size);

#endif // LITERT_DETECTOR_H
