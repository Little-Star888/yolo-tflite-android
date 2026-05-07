import java.io.File
import java.io.FileReader
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 读取 targetSoc 配置：优先 local.properties，其次 gradle.properties，最后默认值
val targetSoc: String = {
    val lp = Properties()
    val lpFile = rootProject.file("local.properties")
    if (lpFile.exists()) {
        val reader = FileReader(lpFile)
        lp.load(reader)
        reader.close()
    }
    lp.getProperty("targetSoc") ?: ((findProperty("targetSoc") as? String) ?: "qualcomm").lowercase()
}()

android {
    // 指定 NDK 版本，消除 ndk.dir 废弃警告
    ndkVersion = "28.2.13676358"

    namespace = "com.little_star"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.little_star"
        minSdk = 31  // NPU 硬性要求 API 31+
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // NPU 只支持 arm64-v8a 架构
        ndk { abiFilters.add("arm64-v8a") }
        // Qualcomm NPU 运行时库需要 legacy packaging
        packaging { jniLibs { useLegacyPackaging = true } }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // 根据 targetSoc 属性选择性引入 NPU 运行时库
    // 精确指定型号只打包对应版本，qualcomm/mediatek 打包全部版本
    // 用法：./gradlew assembleDebug -PtargetSoc=sm8650
    // IDE：修改 gradle.properties 中的 targetSoc 值后 Sync
    // 本地配置：可在 local.properties 中添加 targetSoc=sm8650，优先于 gradle.properties
    when (targetSoc) {
        "mediatek" -> {
            dynamicFeatures.add(":litert_npu_runtime_libraries:mediatek_runtime")
        }
        // 精确指定 Qualcomm SoC 型号
        "sm8450" -> dynamicFeatures.add(":litert_npu_runtime_libraries:qualcomm_runtime_v69")
        "sm8550" -> dynamicFeatures.add(":litert_npu_runtime_libraries:qualcomm_runtime_v73")
        "sm8650" -> dynamicFeatures.add(":litert_npu_runtime_libraries:qualcomm_runtime_v75")
        "sm8750" -> dynamicFeatures.add(":litert_npu_runtime_libraries:qualcomm_runtime_v79")
        "sm8850" -> dynamicFeatures.add(":litert_npu_runtime_libraries:qualcomm_runtime_v81")
        // qualcomm（默认）：包含所有高通 Hexagon 版本
        else -> {
            dynamicFeatures.add(":litert_npu_runtime_libraries:qualcomm_runtime_v69")
            dynamicFeatures.add(":litert_npu_runtime_libraries:qualcomm_runtime_v73")
            dynamicFeatures.add(":litert_npu_runtime_libraries:qualcomm_runtime_v75")
            dynamicFeatures.add(":litert_npu_runtime_libraries:qualcomm_runtime_v79")
            dynamicFeatures.add(":litert_npu_runtime_libraries:qualcomm_runtime_v81")
        }
    }

    bundle {
        deviceTargetingConfig = file("device_targeting_configuration.xml")
        deviceGroup {
            enableSplit = true
            defaultGroup = "other"
        }
    }
}

dependencies {
    // 识别引擎模块（包含 detector、model 数据类、C++ JNI）
    implementation(project(":detector"))
    // NPU 运行时库字符串资源
    implementation(project(":litert_npu_runtime_libraries:runtime_strings"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Navigation Compose
    implementation(libs.navigation.compose)

    implementation(libs.exifinterface)
    implementation(libs.documentfile)
    implementation(libs.okhttp)

    // CameraX
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// ── 本地一键安装任务 ──────────────────────────────────────────────────
// assembleDebug/Release + adb install-multiple（根据 targetSoc 自动选择 split APK）

// SoC 型号到 runtime 模块路径的映射
val socRuntimeMap = mapOf(
    "sm8450" to "qualcomm_runtime_v69",
    "sm8550" to "qualcomm_runtime_v73",
    "sm8650" to "qualcomm_runtime_v75",
    "sm8750" to "qualcomm_runtime_v79",
    "sm8850" to "qualcomm_runtime_v81",
)

// 根据 targetSoc 获取需要安装的 runtime 模块列表
val runtimeModules: List<String> = when {
    targetSoc == "mediatek" -> listOf("mediatek_runtime")
    targetSoc in socRuntimeMap -> listOf(socRuntimeMap[targetSoc]!!)
    else -> socRuntimeMap.values.toList() // qualcomm（默认）：所有版本
}

fun registerInstallTask(name: String, buildType: String, assembleTask: String) {
    // 配置阶段解析 adb 路径（从 local.properties 的 sdk.dir 推导）
    val lp = Properties()
    val lpFile = rootProject.file("local.properties")
    if (lpFile.exists()) lp.load(FileReader(lpFile))
    val sdkDir = lp.getProperty("sdk.dir")
        ?: throw GradleException("sdk.dir not found in local.properties")
    val adbExeName = if (System.getProperty("os.name").lowercase().contains("win")) "adb.exe" else "adb"
    val adbPath = File(sdkDir, "platform-tools/$adbExeName")

    tasks.register(name) {
        group = "install"
        description = "Build and install to device via adb install-multiple ($buildType, targetSoc=$targetSoc)"
        dependsOn(":app:$assembleTask")
        runtimeModules.forEach { mod ->
            dependsOn(":litert_npu_runtime_libraries:$mod:assemble${buildType.replaceFirstChar { it.uppercase() }}")
        }

        doLast {
            if (!adbPath.exists()) {
                throw GradleException("adb not found at $adbPath. Install Android SDK platform-tools.")
            }

            val baseApk = layout.projectDirectory.file(
                "build/outputs/apk/${buildType}/app-${buildType}.apk"
            ).asFile
            if (!baseApk.exists()) {
                throw GradleException("Base APK not found: ${baseApk.absolutePath}")
            }

            val apks = mutableListOf(baseApk.absolutePath)
            for (mod in runtimeModules) {
                val splitApk = rootProject.file(
                    "litert_npu_runtime_libraries/$mod/build/outputs/apk/${buildType}/${mod}-${buildType}.apk"
                )
                if (splitApk.exists()) {
                    apks.add(splitApk.absolutePath)
                    logger.lifecycle("  Include split: $mod")
                } else {
                    logger.warn("  Split APK not found: ${splitApk.absolutePath}")
                }
            }

            logger.lifecycle("Installing ${apks.size} APK(s) to device...")

            // 检测设备列表，多设备时自动选第一个
            val devicesOutput = ProcessBuilder(listOf(adbPath.absolutePath, "devices"))
                .redirectErrorStream(true).start().inputStream.bufferedReader().readText()
            val devices = devicesOutput.lines()
                .filter { it.contains("\tdevice") }
                .map { it.substringBefore("\t").trim() }
            if (devices.isEmpty()) {
                throw GradleException("No devices found. Connect a device and enable USB debugging.")
            }
            val targetDevice = devices.first()
            if (devices.size > 1) {
                logger.lifecycle("  Multiple devices detected, using: $targetDevice")
            }

            val result = ProcessBuilder(
                listOf(adbPath.absolutePath, "-s", targetDevice, "install-multiple") + apks
            ).inheritIO().start().waitFor()
            if (result != 0) throw GradleException("adb install-multiple failed (exit $result)")
            logger.lifecycle("Install complete ($buildType, targetSoc=$targetSoc)")

            // 安装完成后自动启动 App
            logger.lifecycle("Launching app...")
            val launchResult = ProcessBuilder(
                listOf(adbPath.absolutePath, "-s", targetDevice, "shell", "monkey", "-p", "com.little_star", "1")
            ).inheritIO().start().waitFor()
            if (launchResult != 0) {
                logger.warn("App launch may have failed (exit $launchResult)")
            } else {
                logger.lifecycle("App launched")
            }
        }
    }
}

registerInstallTask("installLocalDebug", "debug", "assembleDebug")