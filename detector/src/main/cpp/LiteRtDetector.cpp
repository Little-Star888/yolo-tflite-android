#include "LiteRtDetector.h"
#include <cmath>
#include <algorithm>
#include <cstring>
#include <vector>

#if defined(__ARM_NEON)
#include <arm_neon.h>
#endif

namespace {

constexpr float INV_128 = 1.0f / 128.0f;

} // anonymous namespace

void PreprocessLetterboxAndNormalize(
    const void* src_pixels,
    int src_width, int src_height,
    float* dst,
    int dst_size,
    float* out_scale,
    float* out_offset_x,
    float* out_offset_y) {

    float scale = std::min(static_cast<float>(dst_size) / src_width,
                           static_cast<float>(dst_size) / src_height);
    // 浮点缩放尺寸，用于计算精确的offset（与Kotlin端一致）
    float scaled_w = static_cast<float>(src_width) * scale;
    float scaled_h = static_cast<float>(src_height) * scale;
    // 预处理使用整数尺寸（像素对齐）
    int pad_w = static_cast<int>(scaled_w);
    int pad_h = static_cast<int>(scaled_h);
    int off_x = (dst_size - pad_w) / 2;
    int off_y = (dst_size - pad_h) / 2;
    float inv_scale = 1.0f / scale;

    *out_scale = scale;
    // 后处理使用浮点offset（与Kotlin端LetterboxTransform保持一致）
    *out_offset_x = (static_cast<float>(dst_size) - scaled_w) / 2.0f;
    *out_offset_y = (static_cast<float>(dst_size) - scaled_h) / 2.0f;

    const auto* src = reinterpret_cast<const uint8_t*>(src_pixels);
    constexpr float PAD = -1.0f;

    // x 轴 LUT（camera 分辨率不变时只算一次）
    static thread_local std::vector<int> x_lut;
    static thread_local int x_lut_src_width = 0;
    static thread_local float x_lut_inv_scale = 0.0f;
    if (static_cast<int>(x_lut.size()) != pad_w
        || x_lut_src_width != src_width
        || x_lut_inv_scale != inv_scale) {
        x_lut.resize(pad_w);
        for (int dx = 0; dx < pad_w; dx++) {
            x_lut[dx] = std::min(static_cast<int>(dx * inv_scale), src_width - 1);
        }
        x_lut_src_width = src_width;
        x_lut_inv_scale = inv_scale;
    }

    // 行缓冲区（~2KB，常驻 L1 cache）
    static thread_local std::vector<uint8_t> row_rgb;
    if (static_cast<int>(row_rgb.size()) < pad_w * 3) {
        row_rgb.resize(pad_w * 3);
    }

    // 填充上边 padding
    std::fill(dst, dst + static_cast<size_t>(off_y) * static_cast<size_t>(dst_size) * 3, PAD);

    for (int dy = 0; dy < pad_h; dy++) {
        int src_y = std::min(static_cast<int>(dy * inv_scale), src_height - 1);
        const uint8_t* src_row = src + static_cast<size_t>(src_y) * static_cast<size_t>(src_width) * 4;
        float* dst_row = dst + static_cast<size_t>(off_y + dy) * static_cast<size_t>(dst_size) * 3;

        // 填充左边 padding
        std::fill(dst_row, dst_row + off_x * 3, PAD);

        // Step 1: gather 到连续 RGB 缓冲区
        uint8_t* rb = row_rgb.data();
        for (int dx = 0; dx < pad_w; dx++) {
            const uint8_t* sp = src_row + x_lut[dx] * 4;
            rb[dx * 3 + 0] = sp[0];
            rb[dx * 3 + 1] = sp[1];
            rb[dx * 3 + 2] = sp[2];
        }

        // Step 2: NEON 归一化（连续内存读写）
        float* dv = dst_row + off_x * 3;

#if defined(__ARM_NEON)
        const float32x4_t vInv = vdupq_n_f32(INV_128);
        const float32x4_t vNeg = vdupq_n_f32(-1.0f);
        int dx = 0;
        for (; dx <= pad_w - 8; dx += 8) {
            // vld3_u8: 一次加载 8 个 RGB 像素并自动解交织为 R/G/B 三通道
            uint8x8x3_t rgb = vld3_u8(rb + dx * 3);

            // R: u8 → f32, normalize
            uint16x8_t r16 = vmovl_u8(rgb.val[0]);
            float32x4_t r_lo = vmlaq_f32(vNeg,
                vcvtq_f32_u32(vmovl_u16(vget_low_u16(r16))), vInv);
            float32x4_t r_hi = vmlaq_f32(vNeg,
                vcvtq_f32_u32(vmovl_u16(vget_high_u16(r16))), vInv);

            // G
            uint16x8_t g16 = vmovl_u8(rgb.val[1]);
            float32x4_t g_lo = vmlaq_f32(vNeg,
                vcvtq_f32_u32(vmovl_u16(vget_low_u16(g16))), vInv);
            float32x4_t g_hi = vmlaq_f32(vNeg,
                vcvtq_f32_u32(vmovl_u16(vget_high_u16(g16))), vInv);

            // B
            uint16x8_t b16 = vmovl_u8(rgb.val[2]);
            float32x4_t b_lo = vmlaq_f32(vNeg,
                vcvtq_f32_u32(vmovl_u16(vget_low_u16(b16))), vInv);
            float32x4_t b_hi = vmlaq_f32(vNeg,
                vcvtq_f32_u32(vmovl_u16(vget_high_u16(b16))), vInv);

            // 交错写入: R G B R G B ...
            float32x4x3_t lo;
            lo.val[0] = r_lo; lo.val[1] = g_lo; lo.val[2] = b_lo;
            vst3q_f32(dv + dx * 3, lo);

            float32x4x3_t hi;
            hi.val[0] = r_hi; hi.val[1] = g_hi; hi.val[2] = b_hi;
            vst3q_f32(dv + (dx + 4) * 3, hi);
        }

        // 剩余像素标量处理
        for (; dx < pad_w; dx++) {
            dv[dx * 3 + 0] = (rb[dx * 3 + 0] - 128.0f) * INV_128;
            dv[dx * 3 + 1] = (rb[dx * 3 + 1] - 128.0f) * INV_128;
            dv[dx * 3 + 2] = (rb[dx * 3 + 2] - 128.0f) * INV_128;
        }
#else
        for (int dx = 0; dx < pad_w; dx++) {
            dv[dx * 3 + 0] = (rb[dx * 3 + 0] - 128.0f) * INV_128;
            dv[dx * 3 + 1] = (rb[dx * 3 + 1] - 128.0f) * INV_128;
            dv[dx * 3 + 2] = (rb[dx * 3 + 2] - 128.0f) * INV_128;
        }
#endif

        // 填充右边 padding
        std::fill(dv + pad_w * 3,
                  dv + pad_w * 3 + (dst_size - off_x - pad_w) * 3, PAD);
    }

    // 填充下边 padding
    float* bot = dst + static_cast<size_t>(off_y + pad_h) * static_cast<size_t>(dst_size) * 3;
    std::fill(bot, bot + static_cast<size_t>(dst_size - off_y - pad_h) * static_cast<size_t>(dst_size) * 3, PAD);
}

void PreprocessCenterCropAndNormalize(
    const void* src_pixels,
    int src_width, int src_height,
    float* dst,
    int dst_size) {

    // 1. 缩放最短边到 dst_size
    float scale = static_cast<float>(dst_size) / std::min(src_width, src_height);
    int scaled_w = static_cast<int>(src_width * scale);
    int scaled_h = static_cast<int>(src_height * scale);

    // 2. 中心裁剪起点
    int start_x = (scaled_w - dst_size) / 2;
    int start_y = (scaled_h - dst_size) / 2;

    float inv_scale = 1.0f / scale;
    constexpr float INV_255 = 1.0f / 255.0f;

    const auto* src = reinterpret_cast<const uint8_t*>(src_pixels);

    // x 轴 LUT
    static thread_local std::vector<int> x_lut;
    static thread_local int x_lut_src_width = 0;
    static thread_local float x_lut_inv_scale = 0.0f;
    static thread_local int x_lut_start_x = -1;
    if (static_cast<int>(x_lut.size()) != dst_size
        || x_lut_src_width != src_width
        || x_lut_inv_scale != inv_scale
        || x_lut_start_x != start_x) {
        x_lut.resize(dst_size);
        for (int dx = 0; dx < dst_size; dx++) {
            x_lut[dx] = std::min(static_cast<int>((start_x + dx) * inv_scale), src_width - 1);
        }
        x_lut_src_width = src_width;
        x_lut_inv_scale = inv_scale;
        x_lut_start_x = start_x;
    }

    // y 轴 LUT
    static thread_local std::vector<int> y_lut;
    static thread_local int y_lut_src_height = 0;
    static thread_local float y_lut_inv_scale = 0.0f;
    static thread_local int y_lut_start_y = -1;
    if (static_cast<int>(y_lut.size()) != dst_size
        || y_lut_src_height != src_height
        || y_lut_inv_scale != inv_scale
        || y_lut_start_y != start_y) {
        y_lut.resize(dst_size);
        for (int dy = 0; dy < dst_size; dy++) {
            y_lut[dy] = std::min(static_cast<int>((start_y + dy) * inv_scale), src_height - 1);
        }
        y_lut_src_height = src_height;
        y_lut_inv_scale = inv_scale;
        y_lut_start_y = start_y;
    }

    // 行缓冲区（~0.7KB for 224，常驻 L1 cache）
    static thread_local std::vector<uint8_t> row_rgb;
    if (static_cast<int>(row_rgb.size()) < dst_size * 3) {
        row_rgb.resize(dst_size * 3);
    }

    for (int dy = 0; dy < dst_size; dy++) {
        const uint8_t* src_row = src + static_cast<size_t>(y_lut[dy]) * static_cast<size_t>(src_width) * 4;
        float* dst_row = dst + static_cast<size_t>(dy) * static_cast<size_t>(dst_size) * 3;

        // Step 1: gather 到连续 RGB 缓冲区
        uint8_t* rb = row_rgb.data();
        for (int dx = 0; dx < dst_size; dx++) {
            const uint8_t* sp = src_row + x_lut[dx] * 4;
            rb[dx * 3 + 0] = sp[0];
            rb[dx * 3 + 1] = sp[1];
            rb[dx * 3 + 2] = sp[2];
        }

        // Step 2: NEON 归一化（连续内存读写）
#if defined(__ARM_NEON)
        const float32x4_t vScale = vdupq_n_f32(INV_255);
        int dx = 0;
        for (; dx <= dst_size - 8; dx += 8) {
            uint8x8x3_t rgb = vld3_u8(rb + dx * 3);

            uint16x8_t r16 = vmovl_u8(rgb.val[0]);
            float32x4_t r_lo = vmulq_f32(vcvtq_f32_u32(vmovl_u16(vget_low_u16(r16))), vScale);
            float32x4_t r_hi = vmulq_f32(vcvtq_f32_u32(vmovl_u16(vget_high_u16(r16))), vScale);

            uint16x8_t g16 = vmovl_u8(rgb.val[1]);
            float32x4_t g_lo = vmulq_f32(vcvtq_f32_u32(vmovl_u16(vget_low_u16(g16))), vScale);
            float32x4_t g_hi = vmulq_f32(vcvtq_f32_u32(vmovl_u16(vget_high_u16(g16))), vScale);

            uint16x8_t b16 = vmovl_u8(rgb.val[2]);
            float32x4_t b_lo = vmulq_f32(vcvtq_f32_u32(vmovl_u16(vget_low_u16(b16))), vScale);
            float32x4_t b_hi = vmulq_f32(vcvtq_f32_u32(vmovl_u16(vget_high_u16(b16))), vScale);

            float32x4x3_t lo;
            lo.val[0] = r_lo; lo.val[1] = g_lo; lo.val[2] = b_lo;
            vst3q_f32(dst_row + dx * 3, lo);

            float32x4x3_t hi;
            hi.val[0] = r_hi; hi.val[1] = g_hi; hi.val[2] = b_hi;
            vst3q_f32(dst_row + (dx + 4) * 3, hi);
        }

        for (; dx < dst_size; dx++) {
            dst_row[dx * 3 + 0] = rb[dx * 3 + 0] * INV_255;
            dst_row[dx * 3 + 1] = rb[dx * 3 + 1] * INV_255;
            dst_row[dx * 3 + 2] = rb[dx * 3 + 2] * INV_255;
        }
#else
        for (int dx = 0; dx < dst_size; dx++) {
            dst_row[dx * 3 + 0] = rb[dx * 3 + 0] * INV_255;
            dst_row[dx * 3 + 1] = rb[dx * 3 + 1] * INV_255;
            dst_row[dx * 3 + 2] = rb[dx * 3 + 2] * INV_255;
        }
#endif
    }
}
