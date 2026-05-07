pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
    }
}

rootProject.name = "yolo-tflite-android"
include(":app")
include(":detector")

// NPU 运行时库：根据 targetSoc 属性选择性引入动态特性模块
// 精确指定型号只打包对应版本，qualcomm/mediatek 打包全部版本
// 用法示例：
//   ./gradlew assembleDebug                        # 默认：所有高通版本
//   ./gradlew assembleDebug -PtargetSoc=sm8650     # 只打包 v75（骁龙8 Gen 3）
//   ./gradlew assembleDebug -PtargetSoc=mediatek   # 所有 MTK 版本
// 本地配置：可在 local.properties 中添加 targetSoc=sm8650，优先于 gradle.properties
include(":litert_npu_runtime_libraries:runtime_strings")
val localProps = java.util.Properties()
file("local.properties").takeIf { it.exists() }?.inputStream()?.use { localProps.load(it) }
val targetSoc = localProps.getProperty("targetSoc")
    ?: settings.providers.gradleProperty("targetSoc").getOrElse("qualcomm").lowercase()
when (targetSoc) {
    "mediatek" -> {
        include(":litert_npu_runtime_libraries:mediatek_runtime")
    }
    // 精确指定 Qualcomm SoC 型号，只打包对应 Hexagon 版本
    "sm8450" -> include(":litert_npu_runtime_libraries:qualcomm_runtime_v69")
    "sm8550" -> include(":litert_npu_runtime_libraries:qualcomm_runtime_v73")
    "sm8650" -> include(":litert_npu_runtime_libraries:qualcomm_runtime_v75")
    "sm8750" -> include(":litert_npu_runtime_libraries:qualcomm_runtime_v79")
    "sm8850" -> include(":litert_npu_runtime_libraries:qualcomm_runtime_v81")
    // qualcomm（默认）：包含所有高通 Hexagon 版本
    else -> {
        include(":litert_npu_runtime_libraries:qualcomm_runtime_v69")
        include(":litert_npu_runtime_libraries:qualcomm_runtime_v73")
        include(":litert_npu_runtime_libraries:qualcomm_runtime_v75")
        include(":litert_npu_runtime_libraries:qualcomm_runtime_v79")
        include(":litert_npu_runtime_libraries:qualcomm_runtime_v81")
    }
}
