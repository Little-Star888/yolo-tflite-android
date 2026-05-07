# ============================================================
# LTX Android Isolated Environment Setup Script (Surge Version)
# Uses Surge for fastest multi-threaded downloads with TUI
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

# Surge Configuration
$SURGE_VERSION = "0.7.8"
$SURGE_URL = "https://github.com/SurgeDM/Surge/releases/download/v${SURGE_VERSION}/Surge_${SURGE_VERSION}_windows_amd64.zip"
# $SURGE_URL = "https://ghproxy.cn/https://github.com/SurgeDM/Surge/releases/download/v${SURGE_VERSION}/Surge_${SURGE_VERSION}_windows_amd64.zip"
$SURGE_DIR = Join-Path $DEPS_DIR "tools\surge"
$SURGE_EXE = Join-Path $SURGE_DIR "surge.exe"

# Download directory
$DOWNLOAD_DIR = Join-Path $DEPS_DIR ".downloads"

# Create directories
New-Item -ItemType Directory -Force -Path "$DEPS_DIR\jdk" | Out-Null
New-Item -ItemType Directory -Force -Path "$DEPS_DIR\android-sdk\cmdline-tools" | Out-Null
New-Item -ItemType Directory -Force -Path $DOWNLOAD_DIR | Out-Null

Write-Host "============================================================"
Write-Host "LTX Android Isolated Environment Setup (Surge Version)"
Write-Host "============================================================"
Write-Host ""

# ============================================================
# Setup Surge
# ============================================================
function Setup-Surge {
    Write-Host "[0/5] Setting up Surge download accelerator..."
    
    if (Test-Path $SURGE_EXE) {
        Write-Host "  Surge already exists, skipping setup"
        return $true
    }
    
    New-Item -ItemType Directory -Force -Path $SURGE_DIR | Out-Null
    
    $surgeArchive = Join-Path $DOWNLOAD_DIR "surge.zip"
    
    Write-Host "  Downloading Surge $SURGE_VERSION..."
    try {
        Invoke-WebRequest -Uri $SURGE_URL -OutFile $surgeArchive -UseBasicParsing
    }
    catch {
        Write-Host "  ERROR: Failed to download Surge - $_"
        return $false
    }
    
    Write-Host "  Extracting Surge..."
    try {
        Expand-Archive -Path $surgeArchive -DestinationPath $SURGE_DIR -Force
    }
    catch {
        Write-Host "  ERROR: Failed to extract Surge - $_"
        Remove-Item -Force $surgeArchive -ErrorAction SilentlyContinue
        return $false
    }

    Remove-Item -Force $surgeArchive -ErrorAction SilentlyContinue
    
    if (Test-Path $SURGE_EXE) {
        Write-Host "  Surge installed successfully"
        return $true
    }
    else {
        Write-Host "  ERROR: surge.exe not found after extraction"
        return $false
    }
}

# ============================================================
# Download function using Surge (Fixed Version)
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
    
    if (-not (Test-Path $SURGE_EXE)) {
        Write-Host "  ERROR: surge.exe not found"
        return $false
    }
    
    # Create a unique temp directory for this download
    $tempDir = Join-Path (Split-Path $FilePath -Parent) ([guid]::NewGuid().ToString())
    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
    
    # Surge parameters:
    # URL: pass URL directly to trigger download
    # -o: output DIRECTORY (in Surge, -o means directory, not filename)
    # --exit-when-done: auto-exit after download completes (avoid blocking script)
    $arguments = @(
        $URL,
        "-o", $tempDir,
        "--exit-when-done"
    )
    
    try {
        $process = Start-Process -FilePath $SURGE_EXE -ArgumentList $arguments -NoNewWindow -Wait -PassThru
        
        # Get the file Surge just downloaded (with original name from URL)
        $downloadedFile = Get-ChildItem -Path $tempDir -File | Select-Object -First 1
        
        if ($process.ExitCode -eq 0 -and $downloadedFile) {
            # Move and rename the file to the expected location (e.g., jdk.zip)
            Move-Item -Path $downloadedFile.FullName -Destination $FilePath -Force
            Remove-Item -Force -Recurse $tempDir -ErrorAction SilentlyContinue
            
            Write-Host "  Download completed successfully"
            return $true
        }
        else {
            Write-Host "  ERROR: Download failed with exit code $($process.ExitCode)"
            Remove-Item -Force -Recurse $tempDir -ErrorAction SilentlyContinue
            return $false
        }
    }
    catch {
        Write-Host "  ERROR: Download failed - $_"
        Remove-Item -Force -Recurse $tempDir -ErrorAction SilentlyContinue
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
    Write-Host "This script uses Surge for fastest accelerated downloads:"
    Write-Host ""
    Write-Host "  Surge Features:"
    Write-Host "    - 32 concurrent connections (fastest)"
    Write-Host "    - Beautiful TUI interface"
    Write-Host "    - Multi-mirror support"
    Write-Host "    - Browser extension integration"
    Write-Host "    - Headless server mode"
    Write-Host ""
    Write-Host "  Benchmark (1GB file):"
    Write-Host "    wget: 61s | curl: 57s | aria2: 40s | Surge: 28s"
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
    
    if (-not (Setup-Surge)) { 
        Write-Host "FATAL: Surge setup failed"
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
    Write-Host "    +-- tools\surge\        - Surge download accelerator"
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
