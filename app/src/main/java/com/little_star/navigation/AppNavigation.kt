package com.little_star.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.little_star.detector.model.TaskType
import com.little_star.ui.camera.UnifiedCameraScreen
import com.little_star.ui.directory.DirectoryScreen
import com.little_star.ui.home.HomeScreen
import com.little_star.ui.image.ImageScreen
import com.little_star.ui.main.MainHomeScreen
import com.little_star.ui.settings.SettingsScreen
import com.little_star.ui.video.VideoScreen
import com.little_star.viewmodel.SharedDetectorViewModel

/**
 * 应用导航主机
 * 管理应用内所有页面的导航逻辑
 *
 * 每个任务类型有独立的导航图，使用 SharedDetectorViewModel.Factory 创建带 taskType 参数的 ViewModel
 *
 * @param navController 导航控制器
 */
@Composable
fun AppNavigation(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Route.MainHome.route,
        enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(200)) },
        exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(200)) },
        popEnterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(200)) },
        popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(200)) }
    ) {
        // 主首页：功能选择
        composable(Route.MainHome.route) {
            MainHomeScreen(
                onNavigateToDetection = { navController.navigate(Route.DetectionHome.route) },
                onNavigateToKeypoint = { navController.navigate(Route.KeypointHome.route) },
                onNavigateToSegmentation = { navController.navigate(Route.SegmentationHome.route) },
                onNavigateToClassification = { navController.navigate(Route.ClassificationHome.route) },
                onNavigateToOrientedBbox = { navController.navigate(Route.OrientedBboxHome.route) },
                onNavigateToSettings = { navController.navigate(Route.Settings.route) }
            )
        }

        // 设置页面
        composable(Route.Settings.route) {
            SettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }

        // 目标检测流程
        taskNavigationGraph(
            navController = navController,
            taskType = TaskType.DETECTION,
            homeRoute = Route.DetectionHome.route,
            cameraRoute = Route.DetectionCamera.route,
            imageRoute = Route.DetectionImage.route,
            directoryRoute = Route.DetectionDirectory.route,
            videoRoute = Route.DetectionVideo.route
        )

        // 关键点检测流程
        taskNavigationGraph(
            navController = navController,
            taskType = TaskType.KEYPOINT,
            homeRoute = Route.KeypointHome.route,
            cameraRoute = Route.KeypointCamera.route,
            imageRoute = Route.KeypointImage.route,
            directoryRoute = Route.KeypointDirectory.route,
            videoRoute = Route.KeypointVideo.route
        )

        // 实例分割流程
        taskNavigationGraph(
            navController = navController,
            taskType = TaskType.SEGMENTATION,
            homeRoute = Route.SegmentationHome.route,
            cameraRoute = Route.SegmentationCamera.route,
            imageRoute = Route.SegmentationImage.route,
            directoryRoute = Route.SegmentationDirectory.route,
            videoRoute = Route.SegmentationVideo.route
        )

        // 图像分类流程
        taskNavigationGraph(
            navController = navController,
            taskType = TaskType.CLASSIFICATION,
            homeRoute = Route.ClassificationHome.route,
            cameraRoute = Route.ClassificationCamera.route,
            imageRoute = Route.ClassificationImage.route,
            directoryRoute = Route.ClassificationDirectory.route,
            videoRoute = Route.ClassificationVideo.route
        )

        // 旋转框检测流程
        taskNavigationGraph(
            navController = navController,
            taskType = TaskType.ORIENTED_BBOX,
            homeRoute = Route.OrientedBboxHome.route,
            cameraRoute = Route.OrientedBboxCamera.route,
            imageRoute = Route.OrientedBboxImage.route,
            directoryRoute = Route.OrientedBboxDirectory.route,
            videoRoute = Route.OrientedBboxVideo.route
        )
    }
}

/**
 * 任务类型导航图（可复用）
 *
 * 为指定任务类型创建完整的导航图，包括首页和各检测模式页面
 *
 * @param navController 导航控制器
 * @param taskType 任务类型
 * @param homeRoute 首页路由
 * @param cameraRoute 相机页面路由（统一相机模式：实时检测 + 拍照检测）
 * @param imageRoute 图片页面路由
 * @param directoryRoute 目录页面路由
 * @param videoRoute 视频页面路由
 */
private fun NavGraphBuilder.taskNavigationGraph(
    navController: NavHostController,
    taskType: TaskType,
    homeRoute: String,
    cameraRoute: String,
    imageRoute: String,
    directoryRoute: String,
    videoRoute: String
) {
    // 首页：模型配置 + 模式选择
    composable(homeRoute) {
        val sharedViewModel: SharedDetectorViewModel = viewModel(
            factory = SharedDetectorViewModel.Factory(
                application = LocalContext.current.applicationContext as android.app.Application,
                taskType = taskType
            )
        )

        HomeScreen(
            sharedViewModel = sharedViewModel,
            taskType = taskType,
            onBack = {
                navController.popBackStack(Route.MainHome.route, inclusive = false)
            },
            onNavigateToCamera = {
                navController.navigate(cameraRoute)
            },
            onNavigateToImage = {
                navController.navigate(imageRoute)
            },
            onNavigateToDirectory = {
                navController.navigate(directoryRoute)
            },
            onNavigateToVideo = {
                navController.navigate(videoRoute)
            }
        )
    }

    // 统一相机识别页面（实时检测 + 拍照检测）
    composable(cameraRoute) {
        @Suppress("UnrememberedGetBackStackEntry")
        val parentEntry = navController.getBackStackEntry(homeRoute)
        val sharedViewModel: SharedDetectorViewModel = viewModel(parentEntry)

        UnifiedCameraScreen(
            sharedViewModel = sharedViewModel,
            onBack = { navController.popBackStack() }
        )
    }

    // 单张图片识别页面
    composable(imageRoute) {
        @Suppress("UnrememberedGetBackStackEntry")
        val parentEntry = navController.getBackStackEntry(homeRoute)
        val sharedViewModel: SharedDetectorViewModel = viewModel(parentEntry)

        ImageScreen(
            sharedViewModel = sharedViewModel,
            onBack = { navController.popBackStack() }
        )
    }

    // 图片目录识别页面
    composable(directoryRoute) {
        @Suppress("UnrememberedGetBackStackEntry")
        val parentEntry = navController.getBackStackEntry(homeRoute)
        val sharedViewModel: SharedDetectorViewModel = viewModel(parentEntry)

        DirectoryScreen(
            sharedViewModel = sharedViewModel,
            onBack = { navController.popBackStack() }
        )
    }

    // 视频识别页面
    composable(videoRoute) {
        @Suppress("UnrememberedGetBackStackEntry")
        val parentEntry = navController.getBackStackEntry(homeRoute)
        val sharedViewModel: SharedDetectorViewModel = viewModel(parentEntry)

        VideoScreen(
            sharedViewModel = sharedViewModel,
            onBack = { navController.popBackStack() }
        )
    }
}
