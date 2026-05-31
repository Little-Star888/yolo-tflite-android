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

// ── Qualcomm NPU 运行时下载（仅当 libQnn*.so 不存在时触发）────────────
// 用于手动更新：删除 src/main/assets/npu/vendor/qualcomm/ 下的 libQnn*.so 后构建即可重新下载
val qairtVersion = "2.44.0.260225"
val qairtUrl = "https://softwarecenter.qualcomm.com/api/download/software/sdks/Qualcomm_AI_Runtime_Community/All/${qairtVersion}/v${qairtVersion}.zip"
val qairtContentDir = "qairt/${qairtVersion}"
val qnnVersions = listOf(69, 73, 75, 79, 81)
val qualcommAssetsDir = file("src/main/assets/npu/vendor/qualcomm")
val skelStubDir = "skel_stub"

tasks.register("downloadQualcommRuntime") {
    doLast {
        // 检查通用库 + 各版本 Skel/Stub 是否完整
        val commonLibsExist = file("${qualcommAssetsDir}/libQnnHtp.so").exists()
                && file("${qualcommAssetsDir}/libQnnSystem.so").exists()
                && file("${qualcommAssetsDir}/libQnnHtpPrepare.so").exists()
        val allSkelStubExist = qnnVersions.all { ver ->
            file("${qualcommAssetsDir}/${skelStubDir}/v${ver}/libQnnHtpV${ver}Skel.so").exists()
            && file("${qualcommAssetsDir}/${skelStubDir}/v${ver}/libQnnHtpV${ver}Stub.so").exists()
        }
        if (commonLibsExist && allSkelStubExist) {
            logger.lifecycle("Qualcomm NPU runtime already exists and complete, skipping download.")
            return@doLast
        }
        if (commonLibsExist && !allSkelStubExist) {
            val missingVersions = qnnVersions.filter { ver ->
                !file("${qualcommAssetsDir}/${skelStubDir}/v${ver}/libQnnHtpV${ver}Skel.so").exists()
            }
            logger.lifecycle("Common libs exist but Skel/Stub missing for versions: ${missingVersions}. Re-downloading...")
        }
        qualcommAssetsDir.mkdirs()
        val archiveFile = file("${layout.buildDirectory.get()}/qairt/qairt_sdk.zip")
        archiveFile.parentFile.mkdirs()
        logger.lifecycle("Downloading Qualcomm AI Runtime ${qairtVersion}...")
        // 使用 curl 下载：带浏览器 User-Agent（高通服务器拒绝无 UA 的请求）、跟随重定向、支持断点续传
        val proc = ProcessBuilder(
            "curl", "-L", "-C", "-", "-A", "Mozilla/5.0",
            "--retry", "3", "--retry-delay", "5",
            "-o", archiveFile.absolutePath, qairtUrl
        ).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        val exitCode = proc.waitFor()
        if (exitCode != 0) {
            throw GradleException("Failed to download Qualcomm AI Runtime (exit $exitCode):\n$output")
        }
        val extractDir = file("${layout.buildDirectory.get()}/qairt/extracted")
        extractDir.deleteRecursively()
        ant.withGroovyBuilder {
            "unzip"("src" to archiveFile, "dest" to extractDir)
        }
        val sourceDir = file("${extractDir}/${qairtContentDir}")
        // 复制通用 QNN 库
        listOf("libQnnHtp.so", "libQnnSystem.so", "libQnnHtpPrepare.so").forEach { lib ->
            ant.withGroovyBuilder {
                "copy"("file" to "${sourceDir}/lib/aarch64-android/${lib}",
                       "todir" to qualcommAssetsDir)
            }
        }
        // 复制各版本特有的 Skel/Stub（按版本号分子目录）
        qnnVersions.forEach { ver ->
            val verDir = file("${qualcommAssetsDir}/${skelStubDir}/v${ver}")
            verDir.mkdirs()
            ant.withGroovyBuilder {
                "copy"("file" to "${sourceDir}/lib/hexagon-v${ver}/unsigned/libQnnHtpV${ver}Skel.so",
                       "todir" to verDir)
            }
            ant.withGroovyBuilder {
                "copy"("file" to "${sourceDir}/lib/aarch64-android/libQnnHtpV${ver}Stub.so",
                       "todir" to verDir)
            }
        }
        // 清理临时文件
        archiveFile.delete()
        extractDir.deleteRecursively()
        logger.lifecycle("Qualcomm NPU runtime downloaded to ${qualcommAssetsDir}")
    }
}

// CMake 构建时自动触发 QNN SDK 下载
tasks.configureEach {
    if (name.contains("externalNativeBuild") || name.contains("CMake")) {
        dependsOn("downloadQualcommRuntime")
    }
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
