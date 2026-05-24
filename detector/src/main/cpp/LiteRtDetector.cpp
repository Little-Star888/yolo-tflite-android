#include "LiteRtDetector.h"
#include <cmath>
#include <algorithm>
#include <cstring>
#include <vector>

#if defined(__ARM_NEON)
#include <arm_neon.h>
#endif

namespace {

constexpr float INV_255 = 1.0f / 255.0f;

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
    constexpr float PAD = 114.0f / 255.0f;

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
        // 归一化 [0,1]: pixel * INV_255
        const float32x4_t vScale = vdupq_n_f32(INV_255);
        int dx = 0;
        for (; dx <= pad_w - 8; dx += 8) {
            // vld3_u8: 一次加载 8 个 RGB 像素并自动解交织为 R/G/B 三通道
            uint8x8x3_t rgb = vld3_u8(rb + dx * 3);

            // R: u8 → f32, normalize [0,1]
            uint16x8_t r16 = vmovl_u8(rgb.val[0]);
            float32x4_t r_lo = vmulq_f32(
                vcvtq_f32_u32(vmovl_u16(vget_low_u16(r16))), vScale);
            float32x4_t r_hi = vmulq_f32(
                vcvtq_f32_u32(vmovl_u16(vget_high_u16(r16))), vScale);

            // G
            uint16x8_t g16 = vmovl_u8(rgb.val[1]);
            float32x4_t g_lo = vmulq_f32(
                vcvtq_f32_u32(vmovl_u16(vget_low_u16(g16))), vScale);
            float32x4_t g_hi = vmulq_f32(
                vcvtq_f32_u32(vmovl_u16(vget_high_u16(g16))), vScale);

            // B
            uint16x8_t b16 = vmovl_u8(rgb.val[2]);
            float32x4_t b_lo = vmulq_f32(
                vcvtq_f32_u32(vmovl_u16(vget_low_u16(b16))), vScale);
            float32x4_t b_hi = vmulq_f32(
                vcvtq_f32_u32(vmovl_u16(vget_high_u16(b16))), vScale);

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
            dv[dx * 3 + 0] = rb[dx * 3 + 0] * INV_255;
            dv[dx * 3 + 1] = rb[dx * 3 + 1] * INV_255;
            dv[dx * 3 + 2] = rb[dx * 3 + 2] * INV_255;
        }
#else
        for (int dx = 0; dx < pad_w; dx++) {
            dv[dx * 3 + 0] = rb[dx * 3 + 0] * INV_255;
            dv[dx * 3 + 1] = rb[dx * 3 + 1] * INV_255;
            dv[dx * 3 + 2] = rb[dx * 3 + 2] * INV_255;
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

// ============================================================
// CenterCrop + 双线性插值 + normalize [0,1]（分类器用）
// 与 cv::resize(bilinear) 效果一致
// ============================================================

void PreprocessCenterCropAndNormalize_Bilinear(
        const void* src_pixels,
        int src_width, int src_height,
        float* dst,
        int dst_size) {

    // 缩放最短边到 dst_size
    float scale = static_cast<float>(dst_size) / std::min(src_width, src_height);
    int scaled_w = static_cast<int>(src_width * scale);
    int scaled_h = static_cast<int>(src_height * scale);

    // 中心裁剪起点
    int start_x = (scaled_w - dst_size) / 2;
    int start_y = (scaled_h - dst_size) / 2;

    const auto* src = reinterpret_cast<const uint8_t*>(src_pixels);
    int src_stride = src_width * 4;
    constexpr float INV_255 = 1.0f / 255.0f;

    // x 轴双线性 LUT: {x0, x1, fx}
    struct XLut { int x0, x1; float fx; };
    static thread_local std::vector<XLut> x_lut;
    static thread_local int x_lut_srcW = 0;
    static thread_local float x_lut_scale = 0.0f;
    static thread_local int x_lut_startX = -1;
    if (static_cast<int>(x_lut.size()) != dst_size
        || x_lut_srcW != src_width
        || x_lut_scale != scale
        || x_lut_startX != start_x) {
        x_lut.resize(dst_size);
        for (int dx = 0; dx < dst_size; dx++) {
            float sx = (start_x + dx + 0.5f) / scale - 0.5f;
            int x0 = std::max(0, std::min(static_cast<int>(std::floor(sx)), src_width - 1));
            int x1 = std::max(0, std::min(x0 + 1, src_width - 1));
            x_lut[dx] = {x0, x1, sx - std::floor(sx)};
        }
        x_lut_srcW = src_width;
        x_lut_scale = scale;
        x_lut_startX = start_x;
    }

#if defined(__ARM_NEON)
    const float32x4_t vScale = vdupq_n_f32(INV_255);
    const float32x4_t vOne = vdupq_n_f32(1.0f);
#endif

    for (int dy = 0; dy < dst_size; dy++) {
        // y 轴双线性权重
        float sy = (start_y + dy + 0.5f) / scale - 0.5f;
        int y0 = std::max(0, std::min(static_cast<int>(std::floor(sy)), src_height - 1));
        int y1 = std::max(0, std::min(y0 + 1, src_height - 1));
        float fy = sy - std::floor(sy);
        float wfy0 = 1.0f - fy, wfy1 = fy;

        const uint8_t *row0 = src + static_cast<size_t>(y0) * src_stride;
        const uint8_t *row1 = src + static_cast<size_t>(y1) * src_stride;
        float *dst_row = dst + static_cast<size_t>(dy) * dst_size * 3;

#if defined(__ARM_NEON)
        const float32x4_t vWfy0 = vdupq_n_f32(wfy0);
        const float32x4_t vWfy1 = vdupq_n_f32(wfy1);
        alignas(16) float fx_buf[8];

        int dx = 0;
        for (; dx <= dst_size - 8; dx += 8) {
            // 标量 gather: 8 像素 × 4 角 × RGB
            alignas(16) uint8_t c00_rgb[24], c01_rgb[24], c10_rgb[24], c11_rgb[24];
            for (int i = 0; i < 8; i++) {
                auto &xl = x_lut[dx + i];
                const uint8_t *p;
                p = row0 + xl.x0 * 4;
                c00_rgb[i*3+0]=p[0]; c00_rgb[i*3+1]=p[1]; c00_rgb[i*3+2]=p[2];
                p = row0 + xl.x1 * 4;
                c01_rgb[i*3+0]=p[0]; c01_rgb[i*3+1]=p[1]; c01_rgb[i*3+2]=p[2];
                p = row1 + xl.x0 * 4;
                c10_rgb[i*3+0]=p[0]; c10_rgb[i*3+1]=p[1]; c10_rgb[i*3+2]=p[2];
                p = row1 + xl.x1 * 4;
                c11_rgb[i*3+0]=p[0]; c11_rgb[i*3+1]=p[1]; c11_rgb[i*3+2]=p[2];
                fx_buf[i] = xl.fx;
            }

            // vld3_u8 解交织 RGB
            uint8x8x3_t nc00 = vld3_u8(c00_rgb);
            uint8x8x3_t nc01 = vld3_u8(c01_rgb);
            uint8x8x3_t nc10 = vld3_u8(c10_rgb);
            uint8x8x3_t nc11 = vld3_u8(c11_rgb);

            float32x4_t fx_lo = vld1q_f32(fx_buf);
            float32x4_t fx_hi = vld1q_f32(fx_buf + 4);
            float32x4_t wfx_lo = vsubq_f32(vOne, fx_lo);
            float32x4_t wfx_hi = vsubq_f32(vOne, fx_hi);

            // 对 3 个通道分别做双线性插值 + 归一化 [0,1]
            float32x4x3_t out_lo, out_hi;
            for (int ch = 0; ch < 3; ch++) {
                uint8x8_t u8_00 = nc00.val[ch], u8_01 = nc01.val[ch];
                uint8x8_t u8_10 = nc10.val[ch], u8_11 = nc11.val[ch];

                uint16x8_t u16_00 = vmovl_u8(u8_00);
                float32x4_t f00_lo = vcvtq_f32_u32(vmovl_u16(vget_low_u16(u16_00)));
                float32x4_t f00_hi = vcvtq_f32_u32(vmovl_u16(vget_high_u16(u16_00)));

                uint16x8_t u16_01 = vmovl_u8(u8_01);
                float32x4_t f01_lo = vcvtq_f32_u32(vmovl_u16(vget_low_u16(u16_01)));
                float32x4_t f01_hi = vcvtq_f32_u32(vmovl_u16(vget_high_u16(u16_01)));

                uint16x8_t u16_10 = vmovl_u8(u8_10);
                float32x4_t f10_lo = vcvtq_f32_u32(vmovl_u16(vget_low_u16(u16_10)));
                float32x4_t f10_hi = vcvtq_f32_u32(vmovl_u16(vget_high_u16(u16_10)));

                uint16x8_t u16_11 = vmovl_u8(u8_11);
                float32x4_t f11_lo = vcvtq_f32_u32(vmovl_u16(vget_low_u16(u16_11)));
                float32x4_t f11_hi = vcvtq_f32_u32(vmovl_u16(vget_high_u16(u16_11)));

                // 水平插值
                float32x4_t htop_lo = vaddq_f32(vmulq_f32(f00_lo, wfx_lo),
                                                  vmulq_f32(f01_lo, fx_lo));
                float32x4_t htop_hi = vaddq_f32(vmulq_f32(f00_hi, wfx_hi),
                                                  vmulq_f32(f01_hi, fx_hi));
                float32x4_t hbot_lo = vaddq_f32(vmulq_f32(f10_lo, wfx_lo),
                                                  vmulq_f32(f11_lo, fx_lo));
                float32x4_t hbot_hi = vaddq_f32(vmulq_f32(f10_hi, wfx_hi),
                                                  vmulq_f32(f11_hi, fx_hi));

                // 垂直插值
                float32x4_t res_lo = vaddq_f32(vmulq_f32(htop_lo, vWfy0),
                                                 vmulq_f32(hbot_lo, vWfy1));
                float32x4_t res_hi = vaddq_f32(vmulq_f32(htop_hi, vWfy0),
                                                 vmulq_f32(hbot_hi, vWfy1));

                // 归一化 [0,1]: result * INV_255
                out_lo.val[ch] = vmulq_f32(res_lo, vScale);
                out_hi.val[ch] = vmulq_f32(res_hi, vScale);
            }

            // NHWC 交织写入
            vst3q_f32(dst_row + dx * 3, out_lo);
            vst3q_f32(dst_row + (dx + 4) * 3, out_hi);
        }

        // 剩余像素标量处理
        for (; dx < dst_size; dx++) {
            auto &xl = x_lut[dx];
            float wfx0 = 1.0f - xl.fx, wfx1 = xl.fx;
            float w00 = wfx0 * wfy0, w01 = wfx1 * wfy0;
            float w10 = wfx0 * wfy1, w11 = wfx1 * wfy1;

            const uint8_t *p00 = row0 + xl.x0 * 4;
            const uint8_t *p01 = row0 + xl.x1 * 4;
            const uint8_t *p10 = row1 + xl.x0 * 4;
            const uint8_t *p11 = row1 + xl.x1 * 4;

            dst_row[dx * 3 + 0] = (p00[0]*w00 + p01[0]*w01 + p10[0]*w10 + p11[0]*w11) * INV_255;
            dst_row[dx * 3 + 1] = (p00[1]*w00 + p01[1]*w01 + p10[1]*w10 + p11[1]*w11) * INV_255;
            dst_row[dx * 3 + 2] = (p00[2]*w00 + p01[2]*w01 + p10[2]*w10 + p11[2]*w11) * INV_255;
        }
#else
        for (int dx = 0; dx < dst_size; dx++) {
            auto &xl = x_lut[dx];
            float wfx0 = 1.0f - xl.fx, wfx1 = xl.fx;
            float w00 = wfx0 * wfy0, w01 = wfx1 * wfy0;
            float w10 = wfx0 * wfy1, w11 = wfx1 * wfy1;

            const uint8_t *p00 = row0 + xl.x0 * 4;
            const uint8_t *p01 = row0 + xl.x1 * 4;
            const uint8_t *p10 = row1 + xl.x0 * 4;
            const uint8_t *p11 = row1 + xl.x1 * 4;

            dst_row[dx * 3 + 0] = (p00[0]*w00 + p01[0]*w01 + p10[0]*w10 + p11[0]*w11) * INV_255;
            dst_row[dx * 3 + 1] = (p00[1]*w00 + p01[1]*w01 + p10[1]*w10 + p11[1]*w11) * INV_255;
            dst_row[dx * 3 + 2] = (p00[2]*w00 + p01[2]*w01 + p10[2]*w10 + p11[2]*w11) * INV_255;
        }
#endif
    }
}

// ============================================================
// Letterbox + 双线性插值 + normalize [0,1]（检测器用）
// 与 cv::resize(bilinear) 效果一致
// ============================================================

void PreprocessLetterboxAndNormalize_Bilinear(
        const void* src_pixels,
        int src_width, int src_height,
        float* dst,
        int dst_size,
        float* out_scale,
        float* out_offset_x,
        float* out_offset_y) {

    float scale = std::min(static_cast<float>(dst_size) / src_width,
                           static_cast<float>(dst_size) / src_height);
    float scaled_w = static_cast<float>(src_width) * scale;
    float scaled_h = static_cast<float>(src_height) * scale;
    int pad_w = static_cast<int>(scaled_w);
    int pad_h = static_cast<int>(scaled_h);
    int off_x = (dst_size - pad_w) / 2;
    int off_y = (dst_size - pad_h) / 2;

    *out_scale = scale;
    // 后处理使用浮点offset（与Kotlin端LetterboxTransform保持一致）
    *out_offset_x = (static_cast<float>(dst_size) - scaled_w) / 2.0f;
    *out_offset_y = (static_cast<float>(dst_size) - scaled_h) / 2.0f;

    const auto* src = reinterpret_cast<const uint8_t*>(src_pixels);
    int src_stride = src_width * 4;
    constexpr float PAD = 114.0f / 255.0f;
    constexpr float INV_255 = 1.0f / 255.0f;

    // x 轴双线性 LUT: {x0, x1, fx}
    struct XLut { int x0, x1; float fx; };
    static thread_local std::vector<XLut> x_lut;
    static thread_local int x_lut_srcW = 0;
    static thread_local float x_lut_scale = 0.0f;
    static thread_local int x_lut_padW = 0;
    if (static_cast<int>(x_lut.size()) != pad_w
        || x_lut_srcW != src_width
        || x_lut_scale != scale
        || x_lut_padW != pad_w) {
        x_lut.resize(pad_w);
        for (int dx = 0; dx < pad_w; dx++) {
            float sx = (dx + 0.5f) / scale - 0.5f;
            int x0 = std::max(0, std::min(static_cast<int>(std::floor(sx)), src_width - 1));
            int x1 = std::max(0, std::min(x0 + 1, src_width - 1));
            x_lut[dx] = {x0, x1, sx - std::floor(sx)};
        }
        x_lut_srcW = src_width;
        x_lut_scale = scale;
        x_lut_padW = pad_w;
    }

#if defined(__ARM_NEON)
    // 归一化 [0,1]: result * INV_255
    const float32x4_t vScale = vdupq_n_f32(INV_255);
    const float32x4_t vOne = vdupq_n_f32(1.0f);
#endif

    // 填充上边 padding
    std::fill(dst, dst + static_cast<size_t>(off_y) * static_cast<size_t>(dst_size) * 3, PAD);

    for (int dy = 0; dy < pad_h; dy++) {
        // y 轴双线性权重
        float sy = (dy + 0.5f) / scale - 0.5f;
        int y0 = std::max(0, std::min(static_cast<int>(std::floor(sy)), src_height - 1));
        int y1 = std::max(0, std::min(y0 + 1, src_height - 1));
        float fy = sy - std::floor(sy);
        float wfy0 = 1.0f - fy, wfy1 = fy;

        const uint8_t *row0 = src + static_cast<size_t>(y0) * src_stride;
        const uint8_t *row1 = src + static_cast<size_t>(y1) * src_stride;
        float *dst_row = dst + static_cast<size_t>(off_y + dy) * static_cast<size_t>(dst_size) * 3;

        // 填充左边 padding
        std::fill(dst_row, dst_row + off_x * 3, PAD);

        float *dv = dst_row + off_x * 3;

#if defined(__ARM_NEON)
        const float32x4_t vWfy0 = vdupq_n_f32(wfy0);
        const float32x4_t vWfy1 = vdupq_n_f32(wfy1);
        alignas(16) float fx_buf[8];

        int dx = 0;
        for (; dx <= pad_w - 8; dx += 8) {
            // 标量 gather: 8 像素 × 4 角 × RGB
            alignas(16) uint8_t c00_rgb[24], c01_rgb[24], c10_rgb[24], c11_rgb[24];
            for (int i = 0; i < 8; i++) {
                auto &xl = x_lut[dx + i];
                const uint8_t *p;
                p = row0 + xl.x0 * 4;
                c00_rgb[i*3+0]=p[0]; c00_rgb[i*3+1]=p[1]; c00_rgb[i*3+2]=p[2];
                p = row0 + xl.x1 * 4;
                c01_rgb[i*3+0]=p[0]; c01_rgb[i*3+1]=p[1]; c01_rgb[i*3+2]=p[2];
                p = row1 + xl.x0 * 4;
                c10_rgb[i*3+0]=p[0]; c10_rgb[i*3+1]=p[1]; c10_rgb[i*3+2]=p[2];
                p = row1 + xl.x1 * 4;
                c11_rgb[i*3+0]=p[0]; c11_rgb[i*3+1]=p[1]; c11_rgb[i*3+2]=p[2];
                fx_buf[i] = xl.fx;
            }

            // vld3_u8 解交织 RGB
            uint8x8x3_t nc00 = vld3_u8(c00_rgb);
            uint8x8x3_t nc01 = vld3_u8(c01_rgb);
            uint8x8x3_t nc10 = vld3_u8(c10_rgb);
            uint8x8x3_t nc11 = vld3_u8(c11_rgb);

            float32x4_t fx_lo = vld1q_f32(fx_buf);
            float32x4_t fx_hi = vld1q_f32(fx_buf + 4);
            float32x4_t wfx_lo = vsubq_f32(vOne, fx_lo);
            float32x4_t wfx_hi = vsubq_f32(vOne, fx_hi);

            // 对 3 个通道分别做双线性插值 + 归一化 [0,1]
            float32x4x3_t out_lo, out_hi;
            for (int ch = 0; ch < 3; ch++) {
                uint8x8_t u8_00 = nc00.val[ch], u8_01 = nc01.val[ch];
                uint8x8_t u8_10 = nc10.val[ch], u8_11 = nc11.val[ch];

                uint16x8_t u16_00 = vmovl_u8(u8_00);
                float32x4_t f00_lo = vcvtq_f32_u32(vmovl_u16(vget_low_u16(u16_00)));
                float32x4_t f00_hi = vcvtq_f32_u32(vmovl_u16(vget_high_u16(u16_00)));

                uint16x8_t u16_01 = vmovl_u8(u8_01);
                float32x4_t f01_lo = vcvtq_f32_u32(vmovl_u16(vget_low_u16(u16_01)));
                float32x4_t f01_hi = vcvtq_f32_u32(vmovl_u16(vget_high_u16(u16_01)));

                uint16x8_t u16_10 = vmovl_u8(u8_10);
                float32x4_t f10_lo = vcvtq_f32_u32(vmovl_u16(vget_low_u16(u16_10)));
                float32x4_t f10_hi = vcvtq_f32_u32(vmovl_u16(vget_high_u16(u16_10)));

                uint16x8_t u16_11 = vmovl_u8(u8_11);
                float32x4_t f11_lo = vcvtq_f32_u32(vmovl_u16(vget_low_u16(u16_11)));
                float32x4_t f11_hi = vcvtq_f32_u32(vmovl_u16(vget_high_u16(u16_11)));

                // 水平插值
                float32x4_t htop_lo = vaddq_f32(vmulq_f32(f00_lo, wfx_lo),
                                                  vmulq_f32(f01_lo, fx_lo));
                float32x4_t htop_hi = vaddq_f32(vmulq_f32(f00_hi, wfx_hi),
                                                  vmulq_f32(f01_hi, fx_hi));
                float32x4_t hbot_lo = vaddq_f32(vmulq_f32(f10_lo, wfx_lo),
                                                  vmulq_f32(f11_lo, fx_lo));
                float32x4_t hbot_hi = vaddq_f32(vmulq_f32(f10_hi, wfx_hi),
                                                  vmulq_f32(f11_hi, fx_hi));

                // 垂直插值
                float32x4_t res_lo = vaddq_f32(vmulq_f32(htop_lo, vWfy0),
                                                 vmulq_f32(hbot_lo, vWfy1));
                float32x4_t res_hi = vaddq_f32(vmulq_f32(htop_hi, vWfy0),
                                                 vmulq_f32(hbot_hi, vWfy1));

                // 归一化 [0,1]: result * INV_255
                out_lo.val[ch] = vmulq_f32(res_lo, vScale);
                out_hi.val[ch] = vmulq_f32(res_hi, vScale);
            }

            // NHWC 交织写入
            vst3q_f32(dv + dx * 3, out_lo);
            vst3q_f32(dv + (dx + 4) * 3, out_hi);
        }

        // 剩余像素标量处理
        for (; dx < pad_w; dx++) {
            auto &xl = x_lut[dx];
            float wfx0 = 1.0f - xl.fx, wfx1 = xl.fx;
            float w00 = wfx0 * wfy0, w01 = wfx1 * wfy0;
            float w10 = wfx0 * wfy1, w11 = wfx1 * wfy1;

            const uint8_t *p00 = row0 + xl.x0 * 4;
            const uint8_t *p01 = row0 + xl.x1 * 4;
            const uint8_t *p10 = row1 + xl.x0 * 4;
            const uint8_t *p11 = row1 + xl.x1 * 4;

            dv[dx * 3 + 0] = (p00[0]*w00 + p01[0]*w01 + p10[0]*w10 + p11[0]*w11) * INV_255;
            dv[dx * 3 + 1] = (p00[1]*w00 + p01[1]*w01 + p10[1]*w10 + p11[1]*w11) * INV_255;
            dv[dx * 3 + 2] = (p00[2]*w00 + p01[2]*w01 + p10[2]*w10 + p11[2]*w11) * INV_255;
        }
#else
        for (int dx = 0; dx < pad_w; dx++) {
            auto &xl = x_lut[dx];
            float wfx0 = 1.0f - xl.fx, wfx1 = xl.fx;
            float w00 = wfx0 * wfy0, w01 = wfx1 * wfy0;
            float w10 = wfx0 * wfy1, w11 = wfx1 * wfy1;

            const uint8_t *p00 = row0 + xl.x0 * 4;
            const uint8_t *p01 = row0 + xl.x1 * 4;
            const uint8_t *p10 = row1 + xl.x0 * 4;
            const uint8_t *p11 = row1 + xl.x1 * 4;

            dv[dx * 3 + 0] = (p00[0]*w00 + p01[0]*w01 + p10[0]*w10 + p11[0]*w11) * INV_255;
            dv[dx * 3 + 1] = (p00[1]*w00 + p01[1]*w01 + p10[1]*w10 + p11[1]*w11) * INV_255;
            dv[dx * 3 + 2] = (p00[2]*w00 + p01[2]*w01 + p10[2]*w10 + p11[2]*w11) * INV_255;
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
