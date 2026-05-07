package com.little_star.pipeline

import android.content.Context
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import com.little_star.detector.model.DetectionResult
import java.util.concurrent.Executor

/**
 * 产线回调接口 — 产线通过此接口通知检测结果和状态变化
 */
interface PipelineCallback {
    /**
     * 检测结果就绪
     * @param detections 检测结果列表
     * @param overlayWidth overlay 映射用的宽度
     * @param overlayHeight overlay 映射用的高度
     * @param cameraFacing overlay 用的摄像头朝向（GL 产线传 0 禁用镜像）
     * @param inferenceTimeMs 推理耗时（毫秒）
     * @param fps 每秒帧数
     */
    fun onDetectionResult(
        detections: List<DetectionResult>,
        overlayWidth: Float,
        overlayHeight: Float,
        cameraFacing: Int,
        inferenceTimeMs: Long,
        fps: Long
    )

    /** 传感器方向变化 */
    fun onSensorOrientationChanged(degrees: Int)
}

/**
 * 摄像头产线接口
 * 抽象了 GL 产线和 CPU 产线的公共行为
 * 产线负责：视图创建、相机绑定、帧预处理、检测触发、结果回调
 */
interface CameraPipeline {

    /**
     * 创建视图并添加到 frameLayout
     * @param ctx Android 上下文
     * @param frameLayout 父布局
     * @param overlayView 检测结果叠加层
     */
    fun setupViews(ctx: Context, frameLayout: ViewGroup, overlayView: android.view.View)

    /**
     * 绑定 CameraX 到指定生命周期
     * @param ctx Android 上下文
     * @param lifecycleOwner 生命周期所有者
     * @param cameraFacing 摄像头朝向（0=后置, 1=前置）
     * @param executor 相机线程池
     * @param callback 结果回调
     * @param imageCapture 可选的 ImageCapture 用例，用于拍照功能
     */
    fun bindCamera(
        ctx: Context,
        lifecycleOwner: LifecycleOwner,
        cameraFacing: Int,
        executor: Executor,
        callback: PipelineCallback,
        imageCapture: androidx.camera.core.ImageCapture? = null
    )

    /** 释放资源 */
    fun release()

    /**
     * 仅解绑相机，不释放视图和管线资源
     * 用于策略切换时先安全解绑，防止 GL 上下文销毁导致 CameraX 崩溃
     */
    fun unbindCamera() {}

    /** 设置相机缩放比例 */
    fun setZoomRatio(ratio: Float) {}

    /** 获取最小缩放比例 */
    fun getMinZoomRatio(): Float = 1f

    /** 获取最大缩放比例 */
    fun getMaxZoomRatio(): Float = 1f

    /** 开关手电筒（持续补光） */
    fun enableTorch(enabled: Boolean) {}
}
