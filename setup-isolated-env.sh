#!/bin/bash
# ============================================================
# LTX Android 完全隔离环境安装脚本
# 所有依赖都下载到项目目录内的 .deps 子目录，方便统一管理
# ============================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# 版本配置
JDK_VERSION="21.0.11_10"
JDK_URL="https://mirrors.tuna.tsinghua.edu.cn/Adoptium/21/jdk/x64/linux/OpenJDK21U-jdk_x64_linux_hotspot_21.0.11_10.tar.gz"
ANDROID_CMDLINE_TOOLS_VERSION="11076708"
NDK_VERSION="28.2.13676358"

# 统一依赖目录
DEPS_DIR="$SCRIPT_DIR/.deps"
DOWNLOAD_DIR="$DEPS_DIR/.downloads"

# 创建目录
mkdir -p "$DEPS_DIR/jdk" "$DEPS_DIR/android-sdk/cmdline-tools" "$DOWNLOAD_DIR"

echo "============================================================"
echo "LTX Android 完全隔离环境安装"
echo "============================================================"
echo ""

# ============================================================
# 1. 下载 JDK (Adoptium Temurin - 清华镜像)
# ============================================================
download_jdk() {
    echo "[1/4] 下载 Adoptium JDK $JDK_VERSION (清华镜像)..."
    
    if ls "$DEPS_DIR/jdk/jdk-21"* 1>/dev/null 2>&1; then
        echo "JDK 已存在，跳过下载"
        return 0
    fi
    
    local JDK_ARCHIVE="$DOWNLOAD_DIR/jdk.tar.gz"
    
    echo "下载地址: $JDK_URL"
    echo "保存到: $JDK_ARCHIVE"
    if ! wget --show-progress -O "$JDK_ARCHIVE" "$JDK_URL"; then
        echo "错误: JDK 下载失败"
        rm -f "$JDK_ARCHIVE"
        return 1
    fi
    
    echo "解压 JDK..."
    if ! tar -xzf "$JDK_ARCHIVE" -C "$DEPS_DIR/jdk/"; then
        echo "错误: JDK 解压失败"
        rm -f "$JDK_ARCHIVE"
        return 1
    fi
    
    echo "删除下载文件..."
    rm -f "$JDK_ARCHIVE"
    
    echo "JDK 安装完成: $(ls "$DEPS_DIR/jdk/")"
    return 0
}

# ============================================================
# 2. 下载 Android SDK Command-line Tools
# ============================================================
download_android_sdk() {
    echo ""
    echo "[2/4] 下载 Android SDK Command-line Tools..."
    
    if [ -d "$DEPS_DIR/android-sdk/cmdline-tools/latest/bin" ]; then
        echo "Android SDK Command-line Tools 已存在，跳过下载"
        return 0
    fi
    
    local SDK_ARCHIVE="$DOWNLOAD_DIR/cmdline-tools.zip"
    local SDK_URL="https://mirrors.tuna.tsinghua.edu.cn/AndroidSDK/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS_VERSION}_latest.zip"
    
    echo "下载地址: $SDK_URL"
    echo "保存到: $SDK_ARCHIVE"
    if ! wget --show-progress -O "$SDK_ARCHIVE" "$SDK_URL"; then
        echo "清华镜像下载失败，尝试官方地址..."
        SDK_URL="https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS_VERSION}_latest.zip"
        if ! wget --show-progress -O "$SDK_ARCHIVE" "$SDK_URL"; then
            echo "错误: Android SDK 下载失败"
            rm -f "$SDK_ARCHIVE"
            return 1
        fi
    fi
    
    echo "解压 Android SDK..."
    mkdir -p "$DEPS_DIR/android-sdk/cmdline-tools/latest"
    if ! unzip -q "$SDK_ARCHIVE" -d "$DEPS_DIR/android-sdk/cmdline-tools/"; then
        echo "错误: Android SDK 解压失败"
        rm -f "$SDK_ARCHIVE"
        return 1
    fi
    
    # 移动文件到 latest 目录
    if [ -d "$DEPS_DIR/android-sdk/cmdline-tools/cmdline-tools" ]; then
        mv "$DEPS_DIR/android-sdk/cmdline-tools/cmdline-tools/"* "$DEPS_DIR/android-sdk/cmdline-tools/latest/" 2>/dev/null || true
        rm -rf "$DEPS_DIR/android-sdk/cmdline-tools/cmdline-tools"
    fi
    
    echo "删除下载文件..."
    rm -f "$SDK_ARCHIVE"
    
    echo "Android SDK Command-line Tools 安装完成"
    return 0
}

# ============================================================
# 3. 安装必要的 SDK 组件
# ============================================================
install_sdk_components() {
    echo ""
    echo "[3/4] 安装 SDK 组件..."
    
    export ANDROID_HOME="$DEPS_DIR/android-sdk"
    export ANDROID_SDK_ROOT="$DEPS_DIR/android-sdk"
    export ANDROID_SDK_HOME="$DEPS_DIR"
    
    local PROJECT_JDK=$(ls -d "$DEPS_DIR/jdk/jdk-21"* 2>/dev/null | head -1)
    if [ -n "$PROJECT_JDK" ]; then
        export JAVA_HOME="$PROJECT_JDK"
        echo "使用项目内 JDK: $JAVA_HOME"
    else
        echo "警告: 未找到项目内 JDK，使用系统默认 Java"
    fi
    
    local SDK_MANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
    
    if [ ! -f "$SDK_MANAGER" ]; then
        echo "错误: sdkmanager 未找到，请检查 Android SDK 是否正确安装"
        return 1
    fi
    
    chmod +x "$SDK_MANAGER"
    
    echo "接受许可证..."
    yes | "$SDK_MANAGER" --licenses > /dev/null 2>&1 || true
    
    echo "安装 SDK 组件 (platform-tools, platforms, build-tools)..."
    if ! "$SDK_MANAGER" --install \
        "platform-tools" \
        "platforms;android-35" \
        "build-tools;35.0.0"; then
        echo "警告: 部分 SDK 组件安装可能失败"
    fi
    
    echo "SDK 组件安装完成"
    return 0
}

# ============================================================
# 4. 下载 NDK (可选)
# ============================================================
download_ndk() {
    echo ""
    echo "[4/4] 下载 NDK $NDK_VERSION (可选，用于原生开发)..."
    
    if [ -d "$DEPS_DIR/android-sdk/ndk/$NDK_VERSION" ]; then
        echo "NDK 已存在，跳过下载"
        return 0
    fi
    
    export ANDROID_HOME="$DEPS_DIR/android-sdk"
    export ANDROID_SDK_HOME="$DEPS_DIR"
    
    local PROJECT_JDK=$(ls -d "$DEPS_DIR/jdk/jdk-21"* 2>/dev/null | head -1)
    if [ -n "$PROJECT_JDK" ]; then
        export JAVA_HOME="$PROJECT_JDK"
    fi
    
    local SDK_MANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
    
    echo "安装 NDK..."
    if ! "$SDK_MANAGER" --install "ndk;$NDK_VERSION"; then
        echo "警告: NDK 安装可能失败"
    fi
    
    echo "NDK 安装完成"
    return 0
}

# ============================================================
# 5. 生成 local.properties
# ============================================================
generate_local_properties() {
    echo ""
    echo "生成 local.properties..."
    
    local JDK_DIR=$(ls -d "$DEPS_DIR/jdk/jdk-21"* 2>/dev/null | head -1)
    
    if [ -z "$JDK_DIR" ]; then
        echo "错误: 未找到 JDK 目录"
        return 1
    fi
    
    cat > local.properties << EOF
# 自动生成 - $(date)
# 此文件不应提交到版本控制

# Android SDK
sdk.dir=$DEPS_DIR/android-sdk

# JDK (Adoptium Temurin)
org.gradle.java.home=$JDK_DIR

# NDK 版本已在 build.gradle.kts 中通过 ndkVersion 指定
# AGP 9.x 会自动从 {sdk.dir}/ndk/{ndkVersion}/ 查找
EOF
    
    echo "local.properties 已生成"
    return 0
}

# ============================================================
# 清理临时文件
# ============================================================
cleanup() {
    echo ""
    echo "清理临时文件..."
    rm -rf "$DOWNLOAD_DIR"
    rm -rf "$DEPS_DIR/cache"
    # Note: Do NOT delete .deps/.android directory - AGP 9 requires it to exist
    echo "清理完成"
}

# ============================================================
# 主流程
# ============================================================
main() {
    echo "此脚本将下载以下内容到 .deps 目录："
    echo ""
    echo "  必需组件："
    echo "    - Adoptium JDK $JDK_VERSION (清华镜像, 约 200MB)"
    echo "    - Android SDK Command-line Tools (约 150MB)"
    echo "    - SDK 组件 (约 150MB)"
    echo ""
    echo "  可选组件："
    echo "    - NDK $NDK_VERSION (约 1GB) - 仅用于原生开发"
    echo ""
    echo "  总计必需: 约 500MB"
    echo "  总计全部: 约 1.5GB"
    echo ""
    echo "  所有文件将下载到: $DOWNLOAD_DIR"
    echo "  解压完成后自动删除下载文件"
    echo ""
    read -p "是否安装必需组件? [y/N] " -r
    echo ""
    
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo "已取消"
        exit 0
    fi
    
    download_jdk || exit 1
    download_android_sdk || exit 1
    install_sdk_components || exit 1
    
    echo ""
    read -p "是否安装 NDK (用于原生开发)? [y/N] " -r
    echo ""
    
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        download_ndk
    fi
    
    generate_local_properties
    cleanup
    
    echo ""
    echo "============================================================"
    echo "安装完成！"
    echo "============================================================"
    echo ""
    echo "目录结构 (.deps/)："
    echo "  .deps/"
    echo "    ├── jdk/                - JDK $(ls "$DEPS_DIR/jdk/" 2>/dev/null || echo '未安装')"
    echo "    ├── android-sdk/        - Android SDK"
    echo "    │   ├── cmdline-tools/  - 命令行工具"
    echo "    │   ├── platform-tools/ - 平台工具"
    echo "    │   ├── platforms/      - Android 平台"
    echo "    │   ├── build-tools/    - 构建工具"
    echo "    │   └── ndk/            - NDK (可选)"
    echo "    ├── .android/           - Android SDK 缓存"
    echo "    └── gradle-home/        - Gradle 缓存"
    echo ""
    echo "现在可以运行: ./gradlew build"
}

main "$@"
