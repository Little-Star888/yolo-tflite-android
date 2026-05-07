package com.little_star.navigation

/**
 * 类型安全路由定义
 * 定义应用中所有页面的路由路径
 *
 * 每个任务类型（目标检测、关键点检测等）有独立的首页和检测模式页面
 * 首页包含模型配置和模式选择，点击模式卡片直接进入对应的检测页面
 */
sealed class Route(val route: String) {
    /** 主首页（功能选择） */
    data object MainHome : Route("main_home")

    /** 设置页面 */
    data object Settings : Route("settings")

    // ── 目标检测 ──
    /** 目标检测首页（模型配置 + 模式选择） */
    data object DetectionHome : Route("detection_home")
    /** 目标检测 - 相机识别（实时检测 + 拍照检测） */
    data object DetectionCamera : Route("detection_camera")
    /** 目标检测 - 图片识别 */
    data object DetectionImage : Route("detection_image")
    /** 目标检测 - 图片目录识别 */
    data object DetectionDirectory : Route("detection_directory")
    /** 目标检测 - 视频识别 */
    data object DetectionVideo : Route("detection_video")

    // ── 关键点检测 ──
    /** 关键点检测首页 */
    data object KeypointHome : Route("keypoint_home")
    /** 关键点检测 - 相机识别（实时检测 + 拍照检测） */
    data object KeypointCamera : Route("keypoint_camera")
    /** 关键点检测 - 图片识别 */
    data object KeypointImage : Route("keypoint_image")
    /** 关键点检测 - 图片目录识别 */
    data object KeypointDirectory : Route("keypoint_directory")
    /** 关键点检测 - 视频识别 */
    data object KeypointVideo : Route("keypoint_video")

    // ── 实例分割 ──
    /** 实例分割首页 */
    data object SegmentationHome : Route("segmentation_home")
    /** 实例分割 - 相机识别（实时检测 + 拍照检测） */
    data object SegmentationCamera : Route("segmentation_camera")
    /** 实例分割 - 图片识别 */
    data object SegmentationImage : Route("segmentation_image")
    /** 实例分割 - 图片目录识别 */
    data object SegmentationDirectory : Route("segmentation_directory")
    /** 实例分割 - 视频识别 */
    data object SegmentationVideo : Route("segmentation_video")

    // ── 图像分类 ──
    /** 图像分类首页 */
    data object ClassificationHome : Route("classification_home")
    /** 图像分类 - 相机识别（实时检测 + 拍照检测） */
    data object ClassificationCamera : Route("classification_camera")
    /** 图像分类 - 图片识别 */
    data object ClassificationImage : Route("classification_image")
    /** 图像分类 - 图片目录识别 */
    data object ClassificationDirectory : Route("classification_directory")
    /** 图像分类 - 视频识别 */
    data object ClassificationVideo : Route("classification_video")

    // ── 旋转框检测 ──
    /** 旋转框检测首页 */
    data object OrientedBboxHome : Route("oriented_bbox_home")
    /** 旋转框检测 - 相机识别（实时检测 + 拍照检测） */
    data object OrientedBboxCamera : Route("oriented_bbox_camera")
    /** 旋转框检测 - 图片识别 */
    data object OrientedBboxImage : Route("oriented_bbox_image")
    /** 旋转框检测 - 图片目录识别 */
    data object OrientedBboxDirectory : Route("oriented_bbox_directory")
    /** 旋转框检测 - 视频识别 */
    data object OrientedBboxVideo : Route("oriented_bbox_video")
}
