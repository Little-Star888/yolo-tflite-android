plugins {
    alias(libs.plugins.android.library)
}

// ── LiteRT C++ SDK 自动下载（Gradle 配置阶段）────────────────────────
// 在 Configure project 阶段执行，日志直接出现在主输出流
// CMake sync 时 SDK 已就绪，无需 CMake 侧下载
val litertVersion = "2.1.4"
val litertCcSdkDir = file("src/main/cpp/litert_cc_sdk")

if (!File(litertCcSdkDir, "CMakeLists.txt").exists()) {
    logger.lifecycle("LiteRT C++ SDK not found, downloading v${litertVersion}...")
    val tempDir = file("${layout.buildDirectory.get()}/tmp/litert_sdk")
    tempDir.deleteRecursively()
    tempDir.mkdirs()

    // 下载并解压 C++ SDK
    val ccSdkZip = File(tempDir, "litert_cc_sdk.zip")
    ant.withGroovyBuilder {
        "get"(
            "src" to "https://github.com/google-ai-edge/LiteRT/releases/download/v${litertVersion}/litert_cc_sdk.zip",
            "dest" to ccSdkZip
        )
    }
    ant.withGroovyBuilder {
        "unzip"("src" to ccSdkZip, "dest" to tempDir)
    }
    ccSdkZip.delete()

    // 处理嵌套目录：zip 内可能包含 litert_cc_sdk/ 顶层目录
    val nestedDir = File(tempDir, "litert_cc_sdk")
    val sourceDir = if (File(nestedDir, "CMakeLists.txt").exists()) nestedDir else tempDir
    litertCcSdkDir.deleteRecursively()
    sourceDir.renameTo(litertCcSdkDir)
    tempDir.deleteRecursively()
    logger.lifecycle("LiteRT C++ SDK ready: ${litertCcSdkDir}")
}

// 提取 libLiteRt.so（从 Maven AAR）
if (!File(litertCcSdkDir, "libLiteRt.so").exists()) {
    logger.lifecycle("Extracting libLiteRt.so from AAR...")
    val tempDir = file("${layout.buildDirectory.get()}/tmp/litert_aar")
    tempDir.deleteRecursively()
    tempDir.mkdirs()

    val aarFile = File(tempDir, "litert-${litertVersion}.aar")
    ant.withGroovyBuilder {
        "get"(
            "src" to "https://dl.google.com/dl/android/maven2/com/google/ai/edge/litert/litert/${litertVersion}/litert-${litertVersion}.aar",
            "dest" to aarFile
        )
    }
    val aarExtractDir = File(tempDir, "aar_extracted")
    aarExtractDir.mkdirs()
    ant.withGroovyBuilder {
        "unzip"("src" to aarFile, "dest" to aarExtractDir)
    }
    aarFile.delete()

    // 查找 arm64-v8a 的 libLiteRt.so
    val soFile = aarExtractDir.walk()
        .firstOrNull { it.name == "libLiteRt.so" && it.absolutePath.contains("arm64-v8a") }
    if (soFile != null) {
        soFile.copyTo(File(litertCcSdkDir, "libLiteRt.so"), overwrite = true)
        logger.lifecycle("libLiteRt.so ready")
    } else {
        logger.warn("libLiteRt.so not found in AAR (arm64-v8a)")
    }
    tempDir.deleteRecursively()
}

android {
    namespace = "com.little_star.detector"
    compileSdk = 36

    defaultConfig {
        minSdk = 31
        // NPU 只支持 arm64-v8a 架构
        ndk { abiFilters.add("arm64-v8a") }
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

    // 指定 NDK 版本，消除 ndk.dir 废弃警告
    ndkVersion = "28.2.13676358"

    // CMake 编译原生库 liblitert_jni.so
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    // LiteRT Java SDK（通过 api 传递给消费者模块）
    api(libs.litert)

    // Bitmap.createBitmap() 等 Kotlin 扩展
    implementation(libs.androidx.core.ktx)
}
