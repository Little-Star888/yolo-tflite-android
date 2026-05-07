# ============================================================
# LTX Android Isolated Environment Setup Script (aria2 Version)
# Uses aria2 for accelerated multi-threaded downloads
# All dependencies are stored in .deps subdirectory for unified management
# ============================================================

param()

$ErrorActionPreference = "Stop"
$SCRIPT_DIR = Split-Path -Parent $MyInvocation.MyCommand.Definition
Set-Location $SCRIPT_DIR

# Version Configuration
$JDK_VERSION = "21.0.11_10"
$JDK_URL = "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/21/jdk/x64/windows/OpenJDK21U-jdk_x64_windows_hotspot_21.0.11_10.zip"
$ANDROID_CMDLINE_TOOLS_VERSION = "11076708"
$NDK_VERSION = "28.2.13676358"

# Unified dependency directory
$DEPS_DIR = Join-Path $SCRIPT_DIR ".deps"

# aria2 Configuration
$ARIA2_VERSION = "1.37.0"
$ARIA2_URL = "https://github.com/aria2/aria2/releases/download/release-$ARIA2_VERSION/aria2-$ARIA2_VERSION-win-64bit-build1.zip"
$ARIA2_DIR = Join-Path $DEPS_DIR "tools\aria2"
$ARIA2_EXE = Join-Path $ARIA2_DIR "aria2c.exe"

# Download directory
$DOWNLOAD_DIR = Join-Path $DEPS_DIR ".downloads"

# Create directories
New-Item -ItemType Directory -Force -Path "$DEPS_DIR\jdk" | Out-Null
New-Item -ItemType Directory -Force -Path "$DEPS_DIR\android-sdk\cmdline-tools" | Out-Null
New-Item -ItemType Directory -Force -Path $DOWNLOAD_DIR | Out-Null

Write-Host "============================================================"
Write-Host "LTX Android Isolated Environment Setup (aria2 Version)"
Write-Host "============================================================"
Write-Host ""

# ============================================================
# Setup aria2
# ============================================================
function Setup-Aria2 {
    Write-Host "[0/5] Setting up aria2 download accelerator..."
    
    if (Test-Path $ARIA2_EXE) {
        Write-Host "  aria2 already exists, skipping setup"
        return $true
    }
    
    $aria2Archive = Join-Path $DOWNLOAD_DIR "aria2.zip"
    
    Write-Host "  Downloading aria2 $ARIA2_VERSION..."
    try {
        Invoke-WebRequest -Uri $ARIA2_URL -OutFile $aria2Archive -UseBasicParsing
    }
    catch {
        Write-Host "  ERROR: Failed to download aria2 - $_"
        return $false
    }
    
    Write-Host "  Extracting aria2..."
    try {
        Expand-Archive -Path $aria2Archive -DestinationPath "$DEPS_DIR\tools" -Force
        $extractedDir = Get-ChildItem -Path "$DEPS_DIR\tools" -Filter "aria2-*" -Directory | Select-Object -First 1
        if ($extractedDir) {
            Move-Item -Path $extractedDir.FullName -Destination $ARIA2_DIR -Force
        }
    }
    catch {
        Write-Host "  ERROR: Failed to extract aria2 - $_"
        Remove-Item -Force $aria2Archive -ErrorAction SilentlyContinue
        return $false
    }
    
    Remove-Item -Force $aria2Archive -ErrorAction SilentlyContinue
    
    if (Test-Path $ARIA2_EXE) {
        Write-Host "  aria2 installed successfully"
        return $true
    }
    else {
        Write-Host "  ERROR: aria2c.exe not found after extraction"
        return $false
    }
}

# ============================================================
# Download function using aria2
# ============================================================
function Download-File {
    param(
        [string]$URL,
        [string]$FilePath,
        [string]$Description = "file"
    )
    
    Write-Host "Downloading $Description..."
    Write-Host "  URL: $URL"
    Write-Host "  Destination: $FilePath"
    
    if (Test-Path $FilePath) {
        Remove-Item -Force $FilePath -ErrorAction SilentlyContinue
    }
    
    if (-not (Test-Path $ARIA2_EXE)) {
        Write-Host "  ERROR: aria2c.exe not found"
        return $false
    }
    
    # 清除可能导致格式错误的代理环境变量
    $oldAllProxy = $env:all_proxy
    $oldHttpProxy = $env:http_proxy
    $oldHttpsProxy = $env:https_proxy
    $env:all_proxy = ""
    $env:http_proxy = ""
    $env:https_proxy = ""

    $arguments = @(
        "-x", "16",
        "-s", "16",
        "-k", "1M",
        "--file-allocation=none",
        "--check-certificate=false",
        "-c",
        "-d", (Split-Path $FilePath -Parent),
        "-o", (Split-Path $FilePath -Leaf),
        $URL
    )

    try {
        $process = Start-Process -FilePath $ARIA2_EXE -ArgumentList $arguments -NoNewWindow -Wait -PassThru
        # 恢复代理环境变量
        $env:all_proxy = $oldAllProxy
        $env:http_proxy = $oldHttpProxy
        $env:https_proxy = $oldHttpsProxy
        if ($process.ExitCode -eq 0 -and (Test-Path $FilePath)) {
            Write-Host "  Download completed successfully"
            return $true
        }
        else {
            Write-Host "  ERROR: Download failed with exit code $($process.ExitCode)"
            return $false
        }
    }
    catch {
        Write-Host "  ERROR: Download failed - $_"
        Remove-Item -Force $FilePath -ErrorAction SilentlyContinue
        return $false
    }
}

# ============================================================
# 1. Download JDK
# ============================================================
function Download-JDK {
    Write-Host ""
    Write-Host "[1/5] Setting up Adoptium JDK $JDK_VERSION..."
    
    $existingJdk = Get-ChildItem -Path "$DEPS_DIR\jdk" -Filter "jdk-21*" -Directory -ErrorAction SilentlyContinue
    if ($existingJdk) {
        Write-Host "  JDK already exists, skipping download"
        return $true
    }
    
    $JDK_ARCHIVE = Join-Path $DOWNLOAD_DIR "jdk.zip"
    
    if (-not (Download-File -URL $JDK_URL -FilePath $JDK_ARCHIVE -Description "JDK")) {
        return $false
    }
    
    Write-Host "  Extracting JDK..."
    try {
        Expand-Archive -Path $JDK_ARCHIVE -DestinationPath "$DEPS_DIR\jdk" -Force
        Write-Host "  Extraction completed"
    }
    catch {
        Write-Host "  ERROR: JDK extraction failed - $_"
        Remove-Item -Force $JDK_ARCHIVE -ErrorAction SilentlyContinue
        return $false
    }
    
    Remove-Item -Force $JDK_ARCHIVE -ErrorAction SilentlyContinue
    
    $jdkDir = Get-ChildItem -Path "$DEPS_DIR\jdk" -Filter "jdk-21*" -Directory | Select-Object -First 1
    Write-Host "  JDK installed: $($jdkDir.Name)"
    return $true
}

# ============================================================
# 2. Download Android SDK Command-line Tools
# ============================================================
function Download-AndroidSDK {
    Write-Host ""
    Write-Host "[2/5] Setting up Android SDK Command-line Tools..."
    
    $sdkManagerPath = Join-Path $DEPS_DIR "android-sdk\cmdline-tools\latest\bin\sdkmanager.bat"
    if (Test-Path $sdkManagerPath) {
        Write-Host "  Android SDK Command-line Tools already exists, skipping download"
        return $true
    }
    
    $SDK_ARCHIVE = Join-Path $DOWNLOAD_DIR "cmdline-tools.zip"
    $SDK_URL = "https://dl.google.com/android/repository/commandlinetools-win-${ANDROID_CMDLINE_TOOLS_VERSION}_latest.zip"
    
    if (-not (Download-File -URL $SDK_URL -FilePath $SDK_ARCHIVE -Description "Android SDK Command-line Tools")) {
        return $false
    }
    
    Write-Host "  Extracting Android SDK..."
    $latestDir = Join-Path $DEPS_DIR "android-sdk\cmdline-tools\latest"
    New-Item -ItemType Directory -Force -Path $latestDir | Out-Null
    
    try {
        Expand-Archive -Path $SDK_ARCHIVE -DestinationPath "$DEPS_DIR\android-sdk\cmdline-tools" -Force
        Write-Host "  Extraction completed"
    }
    catch {
        Write-Host "  ERROR: Android SDK extraction failed - $_"
        Remove-Item -Force $SDK_ARCHIVE -ErrorAction SilentlyContinue
        return $false
    }
    
    $cmdlineToolsDir = Join-Path $DEPS_DIR "android-sdk\cmdline-tools\cmdline-tools"
    if (Test-Path $cmdlineToolsDir) {
        Write-Host "  Organizing directory structure..."
        Get-ChildItem -Path $cmdlineToolsDir | Move-Item -Destination $latestDir -Force
        Remove-Item -Force -Recurse $cmdlineToolsDir
    }
    
    Remove-Item -Force $SDK_ARCHIVE -ErrorAction SilentlyContinue
    
    if (Test-Path $sdkManagerPath) {
        Write-Host "  Android SDK Command-line Tools installed successfully"
        return $true
    }
    else {
        Write-Host "  ERROR: sdkmanager.bat not found after extraction"
        return $false
    }
}

# ============================================================
# 3. Accept Android SDK Licenses
# ============================================================
function Accept-Licenses {
    param($SDK_MANAGER)
    
    Write-Host "  Accepting Android SDK licenses (Auto-Yes)..."
    $yesString = "y`n" * 20
    $yesString | & $SDK_MANAGER --licenses 2>&1 | Out-Null
    
    Write-Host "  All licenses accepted successfully"
    return $true
}

# ============================================================
# 4. Install SDK Components
# ============================================================
function Install-SDKComponents {
    Write-Host ""
    Write-Host "[3/5] Installing SDK components..."
    
    $env:ANDROID_HOME = Join-Path $DEPS_DIR "android-sdk"
    $env:ANDROID_SDK_ROOT = Join-Path $DEPS_DIR "android-sdk"
    $env:ANDROID_SDK_HOME = $DEPS_DIR
    
    $PROJECT_JDK = Get-ChildItem -Path "$DEPS_DIR\jdk" -Filter "jdk-21*" -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($PROJECT_JDK) {
        $env:JAVA_HOME = $PROJECT_JDK.FullName
        Write-Host "  Using project JDK: $($PROJECT_JDK.FullName)"
    }
    else {
        Write-Host "  WARNING: Project JDK not found"
    }
    
    $SDK_MANAGER = Join-Path $env:ANDROID_HOME "cmdline-tools\latest\bin\sdkmanager.bat"
    
    if (-not (Test-Path $SDK_MANAGER)) {
        Write-Host "  ERROR: sdkmanager not found"
        return $false
    }
    
    if (-not (Accept-Licenses -SDK_MANAGER $SDK_MANAGER)) {
        return $false
    }
    
    Write-Host "  Installing SDK components..."
    $output = & $SDK_MANAGER --install "platform-tools" "platforms;android-35" "build-tools;35.0.0" 2>&1
    Write-Host $output
    
    $platformToolsPath = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
    $platformsPath = Join-Path $env:ANDROID_HOME "platforms\android-35"
    $buildToolsPath = Join-Path $env:ANDROID_HOME "build-tools\35.0.0"
    
    $success = $true
    if (-not (Test-Path $platformToolsPath)) { $success = $false }
    if (-not (Test-Path $platformsPath)) { $success = $false }
    if (-not (Test-Path $buildToolsPath)) { $success = $false }
    
    if ($success) {
        Write-Host "  SDK components installed successfully"
    }
    return $success
}

# ============================================================
# 5. Download NDK (Optional)
# ============================================================
function Download-NDK {
    Write-Host ""
    Write-Host "[4/5] Setting up NDK $NDK_VERSION..."
    
    $ndkPath = Join-Path $DEPS_DIR "android-sdk\ndk\$NDK_VERSION"
    if (Test-Path $ndkPath) {
        Write-Host "  NDK already exists, skipping download"
        return $true
    }
    
    $env:ANDROID_HOME = Join-Path $DEPS_DIR "android-sdk"
    $env:ANDROID_SDK_HOME = $DEPS_DIR
    
    $PROJECT_JDK = Get-ChildItem -Path "$DEPS_DIR\jdk" -Filter "jdk-21*" -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($PROJECT_JDK) {
        $env:JAVA_HOME = $PROJECT_JDK.FullName
    }
    
    $SDK_MANAGER = Join-Path $env:ANDROID_HOME "cmdline-tools\latest\bin\sdkmanager.bat"
    
    Write-Host "  Installing NDK..."
    $output = & $SDK_MANAGER --install "ndk;$NDK_VERSION" 2>&1
    Write-Host $output
    
    $ndkBuildPath = Join-Path $ndkPath "ndk-build.cmd"
    if (Test-Path $ndkBuildPath) {
        Write-Host "  NDK installed successfully"
        return $true
    }
    else {
        Write-Host "  ERROR: NDK installation failed"
        return $false
    }
}

# ============================================================
# 6. Generate local.properties
# ============================================================
function Generate-LocalProperties {
    Write-Host ""
    Write-Host "[5/5] Generating local.properties..."
    
    $PROJECT_JDK = Get-ChildItem -Path "$DEPS_DIR\jdk" -Filter "jdk-21*" -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    
    if (-not $PROJECT_JDK) {
        Write-Host "  ERROR: JDK directory not found"
        return $false
    }
    
    $sdkFullPath = Join-Path $DEPS_DIR "android-sdk"
    $jdkFullPath = $PROJECT_JDK.FullName
    
    $sdkPath = $sdkFullPath -replace '\\', '\\' -replace ':', '\:'
    $jdkPath = $jdkFullPath -replace '\\', '\\' -replace ':', '\:'
    
    $content = @"
# Auto-generated - $(Get-Date)
# This file should not be committed to version control

# Android SDK
sdk.dir=$sdkPath

# JDK (Adoptium Temurin)
org.gradle.java.home=$jdkPath

# NDK 版本已在 build.gradle.kts 中通过 ndkVersion 指定
# AGP 9.x 会自动从 {sdk.dir}/ndk/{ndkVersion}/ 查找
"@

    Set-Content -Path "local.properties" -Value $content -Encoding UTF8
    
    Write-Host "  local.properties generated successfully"
    return $true
}

# ============================================================
# Cleanup
# ============================================================
function Cleanup {
    Write-Host ""
    Write-Host "Cleaning up temporary files..."
    
    if (Test-Path $DOWNLOAD_DIR) {
        Remove-Item -Force -Recurse $DOWNLOAD_DIR -ErrorAction SilentlyContinue
        Write-Host "  Removed .downloads directory"
    }
    
    $cacheDir = Join-Path $DEPS_DIR "cache"
    if (Test-Path $cacheDir) {
        Remove-Item -Force -Recurse $cacheDir -ErrorAction SilentlyContinue
    }
    
    # Note: Do NOT delete .deps\.android directory - AGP 9 requires it to exist
    
    Write-Host "Cleanup complete"
}

# ============================================================
# Main
# ============================================================
function Main {
    Write-Host "This script uses aria2 for accelerated downloads:"
    Write-Host ""
    Write-Host "  aria2 Features:"
    Write-Host "    - 16 concurrent connections per server"
    Write-Host "    - Automatic resume on interruption"
    Write-Host "    - Support for HTTP/HTTPS/FTP/BitTorrent"
    Write-Host ""
    Write-Host "  Required components:"
    Write-Host "    - Adoptium JDK $JDK_VERSION (~200MB)"
    Write-Host "    - Android SDK Command-line Tools (~150MB)"
    Write-Host "    - SDK components (~150MB)"
    Write-Host ""
    Write-Host "  Optional components:"
    Write-Host "    - NDK $NDK_VERSION (~1GB)"
    Write-Host ""
    Write-Host "  All files will be stored in: $DEPS_DIR"
    Write-Host ""
    
    $reply = Read-Host "Install required components? [y/N]"
    Write-Host ""
    
    if ($reply -notmatch "^[Yy]$") {
        Write-Host "Installation cancelled"
        exit 0
    }
    
    if (-not (Setup-Aria2)) { 
        Write-Host "FATAL: aria2 setup failed"
        exit 1 
    }
    
    if (-not (Download-JDK)) { 
        Write-Host "FATAL: JDK installation failed"
        exit 1 
    }
    
    if (-not (Download-AndroidSDK)) { 
        Write-Host "FATAL: Android SDK installation failed"
        exit 1 
    }
    
    if (-not (Install-SDKComponents)) { 
        Write-Host "FATAL: SDK components installation failed"
        exit 1 
    }
    
    Write-Host ""
    $reply = Read-Host "Install NDK (for native development)? [y/N]"
    Write-Host ""
    
    if ($reply -match "^[Yy]$") {
        Download-NDK
    }
    
    if (-not (Generate-LocalProperties)) {
        Write-Host "FATAL: Failed to generate local.properties"
        exit 1
    }
    
    Cleanup
    
    Write-Host ""
    Write-Host "============================================================"
    Write-Host "Installation Complete!"
    Write-Host "============================================================"
    Write-Host ""
    Write-Host "Directory structure (.deps\):"
    $jdkName = Get-ChildItem -Path "$DEPS_DIR\jdk" -Filter "jdk-21*" -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    Write-Host "  .deps\"
    Write-Host "    +-- jdk\                - JDK $($jdkName.Name)"
    Write-Host "    +-- android-sdk\        - Android SDK"
    Write-Host "    +-- tools\aria2\        - aria2 download accelerator"
    Write-Host "    +-- .android\           - Android SDK cache"
    Write-Host "    +-- gradle-home\        - Gradle cache (created on first build)"
    Write-Host ""
    Write-Host "  local.properties          - SDK/NDK paths"
    Write-Host ""
    Write-Host "Next steps:"
    Write-Host "  Run '.\gradlew.bat build' to build the project"
    Write-Host ""
}

Main
