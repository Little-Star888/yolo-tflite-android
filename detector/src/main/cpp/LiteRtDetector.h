#ifndef LITERT_DETECTOR_H
#define LITERT_DETECTOR_H

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

#endif // LITERT_DETECTOR_H
