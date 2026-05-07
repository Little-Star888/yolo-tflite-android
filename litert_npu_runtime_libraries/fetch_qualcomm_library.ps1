# fetch_qualcomm_library.ps1
# Download QAIRT SDK from Qualcomm and extract QNN runtime .so files
# Windows PowerShell version (equivalent to fetch_qualcomm_library.sh)
#
# Usage:
#   cd litert_npu_runtime_libraries
#   .\fetch_qualcomm_library.ps1

$ErrorActionPreference = "Stop"

# QAIRT SDK version (keep in sync with bash script)
$QairtUrl = "https://softwarecenter.qualcomm.com/api/download/software/sdks/Qualcomm_AI_Runtime_Community/All/2.44.0.260225/v2.44.0.260225.zip"
$QairtContentDir = "qairt/2.44.0.260225"

# QNN Hexagon versions
$QnnVersions = @(69, 73, 75, 79, 81)
$JniArm64Dir = "src/main/jni/arm64-v8a"

# Script directory
$DestDir = $PSScriptRoot
if (-not $DestDir) { $DestDir = Split-Path -Parent $MyInvocation.MyCommand.Definition }

# Create temp directory
$TmpDir = Join-Path $env:TEMP "qairt_sdk_$(Get-Random)"
New-Item -ItemType Directory -Path $TmpDir -Force | Out-Null

try {
    # Download QAIRT SDK
    $ZipPath = Join-Path $TmpDir "qairt_sdk.zip"
    $Aria2c = Join-Path $PSScriptRoot "..\.deps\tools\aria2\aria2c.exe"
    if (Test-Path $Aria2c) {
        Write-Host "Downloading QAIRT SDK via aria2c (multi-threaded)..."
        & $Aria2c -x 16 -s 16 -k 1M -d $TmpDir -o "qairt_sdk.zip" $QairtUrl
        if ($LASTEXITCODE -ne 0) { throw "aria2c download failed with exit code $LASTEXITCODE" }
    } else {
        Write-Host "Downloading QAIRT SDK via curl (aria2c not found)..."
        curl.exe -L -o $ZipPath $QairtUrl
        if ($LASTEXITCODE -ne 0) { throw "curl download failed with exit code $LASTEXITCODE" }
    }

    # Extract
    Write-Host "Extracting..."
    $ExtractDir = Join-Path $TmpDir "extracted"
    Expand-Archive -Path $ZipPath -DestinationPath $ExtractDir -Force
    Remove-Item $ZipPath -Force

    # SDK content root
    $SourceDir = Join-Path $ExtractDir $QairtContentDir

    foreach ($Version in $QnnVersions) {
        $TargetDir = Join-Path $DestDir "qualcomm_runtime_v$Version/$JniArm64Dir"
        Write-Host "Copying to $TargetDir"

        New-Item -ItemType Directory -Path $TargetDir -Force | Out-Null

        # Shared libraries
        Copy-Item -Path "$SourceDir/lib/aarch64-android/libQnnHtp.so" `
                  -Destination $TargetDir -Force
        Copy-Item -Path "$SourceDir/lib/aarch64-android/libQnnSystem.so" `
                  -Destination $TargetDir -Force
        Copy-Item -Path "$SourceDir/lib/aarch64-android/libQnnHtpPrepare.so" `
                  -Destination $TargetDir -Force

        # Version-specific Skel/Stub
        Copy-Item -Path "$SourceDir/lib/hexagon-v$Version/unsigned/libQnnHtpV${Version}Skel.so" `
                  -Destination $TargetDir -Force
        Copy-Item -Path "$SourceDir/lib/aarch64-android/libQnnHtpV${Version}Stub.so" `
                  -Destination $TargetDir -Force
    }

    Write-Host "Done! QNN SDK files copied to all module directories."
}
finally {
    Remove-Item -Path $TmpDir -Recurse -Force -ErrorAction SilentlyContinue
}
