#Requires -Version 5.0
<#
.SYNOPSIS
    NarzoAI Assistant - Windows Setup Script
.DESCRIPTION
    Automates downloading pre-built native .so libraries, detecting Android SDK/NDK,
    downloading AI models, and building/installing the NarzoAI Assistant APK on Windows.

    Three modes:
      1. Quick build (default) - Download pre-built .so + detect SDK + build APK
      2. Full setup           - Download .so + AI models + build APK
      3. Build only           - Just build APK (assumes dependencies exist)
.PARAMETER Mode
    Build mode: "quick" (default), "full", or "build-only"
.PARAMETER AndroidSdkPath
    Path to Android SDK (auto-detected if not specified)
.PARAMETER NativeVersion
    Version of pre-built native libraries to download (default: "latest")
.PARAMETER SkipNativeDownload
    Skip downloading native .so files (use existing jniLibs)
.PARAMETER InstallApk
    Install the built APK to connected device via adb
.PARAMETER Help
    Show this help message
.EXAMPLE
    .\setup.ps1                                    # Quick mode
    .\setup.ps1 -Mode full                          # Download .so + AI models + build
    .\setup.ps1 -Mode build-only                    # Build only with existing deps
    .\setup.ps1 -AndroidSdkPath "C:\Android\sdk"    # Specify SDK path
    .\setup.ps1 -InstallApk                         # Build + install to device
    .\setup.ps1 -NativeVersion "v1.0.0"             # Specific version
#>

[CmdletBinding()]
param(
    [ValidateSet("quick", "full", "build-only")]
    [string]$Mode = "quick",
    [string]$AndroidSdkPath = "",
    [string]$NativeVersion = "latest",
    [switch]$SkipNativeDownload,
    [switch]$InstallApk,
    [switch]$Help
)

# ============================================================================
# CONFIGURATION
# ============================================================================

$Script:ScriptVersion = "1.0.0"
$Script:ProjectRoot    = Split-Path -Parent $MyInvocation.MyCommand.Path
$Script:JniLibsPath    = Join-Path $Script:ProjectRoot "app" "src" "main" "jniLibs"
$Script:ModelsPath     = Join-Path $Script:ProjectRoot "app" "src" "main" "assets" "models"
$Script:ApkRootDir     = Join-Path $Script:ProjectRoot "app" "build" "outputs" "apk"

# GitHub release config (match build.gradle)
$Script:NativeRepo = "Anilg1997/NarzoAI-Assistant"

# HuggingFace model URLs
$Script:GemmaModelUrl  = "https://huggingface.co/google/gemma-2b-GGUF/resolve/main/gemma-2b-it-q4_k_m.gguf"
$Script:WhisperModelUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin"

# Supported ABIs
$Script:Abis = @("arm64-v8a", "armeabi-v7a")

# Step tracking (script-level so all functions see it)
$Script:TotalSteps = 0

# ============================================================================
# COLOR / OUTPUT HELPERS
# ============================================================================

function Write-Color {
    param([string]$Text, [ConsoleColor]$Color = "White", [switch]$NoNewline)
    $original = $host.UI.RawUI.ForegroundColor
    $host.UI.RawUI.ForegroundColor = $Color
    if ($NoNewline) { Write-Host -NoNewline $Text } else { Write-Host $Text }
    $host.UI.RawUI.ForegroundColor = $original
}

function Write-Step  { param([int]$S, [int]$T, [string]$M)  Write-Color "[$S/$T] $M" -Color Cyan }
function Write-Ok    { param([string]$M)                     Write-Color "[`u{2713}] $M" -Color Green }
function Write-Warn  { param([string]$M)                     Write-Color "[!] $M" -Color Yellow }
function Write-Fail  { param([string]$M)                     Write-Color "[`u{2717}] $M" -Color Red }

function Write-Banner {
    Clear-Host
    Write-Color "============================================" -Color Cyan
    Write-Color "  NarzoAI Assistant - Windows Setup v$($Script:ScriptVersion)" -Color Cyan
    Write-Color "============================================" -Color Cyan
    Write-Host ""
    Write-Color "  Mode:         $Mode" -Color DarkGray
    Write-Color "  Project:      $($Script:ProjectRoot)" -Color DarkGray
    Write-Color "  NativeRepo:   $($Script:NativeRepo)@$NativeVersion" -Color DarkGray
    Write-Host ""
}

function Read-YesNo {
    param([string]$Prompt)
    while ($true) {
        $r = Read-Host "$Prompt (y/n)"
        if ($r -eq 'y' -or $r -eq 'Y') { return $true }
        if ($r -eq 'n' -or $r -eq 'N') { return $false }
        Write-Warn "Please answer 'y' or 'n'."
    }
}

function File-SizeMB {
    param([string]$Path)
    if (Test-Path $Path) { return [math]::Round((Get-Item $Path).Length / 1MB, 1) }
    return 0
}

# ============================================================================
# PREREQUISITES
# ============================================================================

function Test-Prerequisites {
    Write-Step 1 $Script:TotalSteps "Checking prerequisites..."

    $issues   = @()
    $warnings = @()

    # PowerShell version already guaranteed by #Requires at top

    # Execution policy
    if ((Get-ExecutionPolicy) -eq "Restricted") {
        $warnings += "Execution policy is Restricted. Run: Set-ExecutionPolicy RemoteSigned -Scope CurrentUser"
    }

    # Git
    if (Get-Command "git" -ErrorAction SilentlyContinue) {
        Write-Ok "Git: $(git --version)"
    } else {
        $warnings += "Git not found. Install from https://git-scm.com/download/win"
    }

    # Java (check both PATH and JAVA_HOME)
    $javaPath = $null
    if (Test-Path "$env:JAVA_HOME\bin\java.exe") {
        $javaPath = "$env:JAVA_HOME\bin\java.exe"
    } elseif (Get-Command "java" -ErrorAction SilentlyContinue) {
        $javaPath = (Get-Command "java").Source
    }
    if ($javaPath) {
        $raw = & $javaPath -version 2>&1
        if ($raw -match '"(\d+)') {
            $v = [int]$Matches[1]
            if ($v -ge 17) { Write-Ok "Java $v (JDK 17+) at $javaPath" }
            elseif ($v -ge 11) { Write-Warn "Java $v detected. JDK 17+ recommended." }
            else { $warnings += "Java $v is too old. Install JDK 17+ from https://adoptium.net/" }
        }
    } else {
        $warnings += "Java not found. Install JDK 17+ from https://adoptium.net/"
    }

    # Gradle wrapper
    $gradlewBat = Join-Path $Script:ProjectRoot "gradlew.bat"
    if (Test-Path $gradlewBat) {
        Write-Ok "Gradle wrapper found: gradlew.bat"
    } else {
        $issues += "gradlew.bat missing. Re-clone the repository."
    }

    # Android SDK
    if (Find-AndroidSdk) {
        Write-Ok "Android SDK: $Script:DetectedSdkPath"
    } else {
        $warnings += "Android SDK not found. Use -AndroidSdkPath or set ANDROID_HOME."
    }

    Write-Host ""
    if ($issues.Count -gt 0) {
        foreach ($i in $issues) { Write-Fail $i }
        return $false
    }
    if ($warnings.Count -gt 0 -and -not (Read-YesNo "Continue despite warnings?")) {
        return $false
    }
    return $true
}

# ============================================================================
# ANDROID SDK DETECTION
# ============================================================================

function Find-AndroidSdk {
    $Script:DetectedSdkPath = ""

    # 1. Command-line parameter
    if ($AndroidSdkPath -and (Test-Path $AndroidSdkPath)) {
        $Script:DetectedSdkPath = $AndroidSdkPath; return $true
    }

    # 2. Environment variables
    foreach ($var in @("ANDROID_HOME", "ANDROID_SDK_ROOT")) {
        foreach ($scope in @("User", "Machine")) {
            $val = [Environment]::GetEnvironmentVariable($var, $scope)
            if ($val -and (Test-Path $val)) {
                $Script:DetectedSdkPath = $val; return $true
            }
        }
    }

    # 3. Common paths
    $common = @(
        "$env:LOCALAPPDATA\Android\Sdk",
        "$env:USERPROFILE\AppData\Local\Android\Sdk",
        "C:\Android\Sdk",
        "C:\Program Files\Android\Sdk"
    )
    foreach ($p in $common) { if (Test-Path $p) { $Script:DetectedSdkPath = $p; return $true } }

    # 4. local.properties
    $props = Join-Path $Script:ProjectRoot "local.properties"
    if (Test-Path $props) {
        foreach ($line in (Get-Content $props)) {
            if ($line -match '^sdk\.dir=(.+)$') {
                $dir = $Matches[1].Trim()
                if (Test-Path $dir) { $Script:DetectedSdkPath = $dir; return $true }
            }
        }
    }

    return $false
}

# ============================================================================
# NATIVE LIBRARY DOWNLOAD
# ============================================================================

function Install-NativeLibraries {
    param([string]$Version)
    Write-Step 2 $Script:TotalSteps "Downloading pre-built native libraries..."

    Initialize-Dir $Script:JniLibsPath

    $dlUrl = if ($Version -eq "latest") {
        "https://github.com/$($Script:NativeRepo)/releases/latest/download/narzoai-native-latest.zip"
    } else {
        "https://github.com/$($Script:NativeRepo)/releases/download/$Version/narzoai-native-$Version.zip"
    }

    Write-Host "  Download URL: $dlUrl"
    $tempDir = Join-Path $env:TEMP "narzoai-native-download"
    if (Test-Path $tempDir) { Remove-Item -Recurse -Force $tempDir }
    New-Item -ItemType Directory -Path $tempDir -Force | Out-Null
    $zipFile = Join-Path $tempDir "narzoai-native.zip"

    # Download
    try {
        Write-Host "  Downloading..." -NoNewline
        $wc = New-Object System.Net.WebClient
        $job = $wc.DownloadFileTaskAsync($dlUrl, $zipFile)
        while (-not $job.IsCompleted) { Start-Sleep -Milliseconds 500; Write-Host "." -NoNewline }
        Write-Host ""
        if (-not (Test-Path $zipFile) -or (Get-Item $zipFile).Length -eq 0) {
            throw "Download failed - file empty or missing"
        }
        $kb = [math]::Round((Get-Item $zipFile).Length / 1KB)
        Write-Ok "Downloaded: ${kb} KB"
    } catch {
        Write-Fail "Download error: $_"
        Write-Warn "  Ensure GitHub release exists or run with -SkipNativeDownload"
        if (-not (Read-YesNo "Continue (build will likely fail without native libs)?")) { return $false }
        return $false
    }

    # Extract zip
    Write-Host "  Extracting..." -NoNewline
    try {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        [System.IO.Compression.ZipFile]::ExtractToDirectory($zipFile, $tempDir)
        Write-Host " done"
    } catch {
        Write-Host " (using Expand-Archive)"
        Expand-Archive -Path $zipFile -DestinationPath $tempDir -Force
    }

    # Handle nested root directory
    $extracted = $tempDir
    $subs = Get-ChildItem -Path $tempDir -Directory
    if ($subs.Count -eq 1) { $extracted = $subs[0].FullName }

    # Copy to jniLibs
    $copied = 0
    foreach ($abi in $Script:Abis) {
        $src  = Join-Path $extracted $abi "libnarzoai_jni.so"
        $dest = Join-Path $Script:JniLibsPath $abi
        if (Test-Path $src) {
            Initialize-Dir $dest
            Get-ChildItem $dest -Filter "*.so" | Remove-Item -Force
            Copy-Item -Path $src -Destination (Join-Path $dest "libnarzoai_jni.so") -Force
            $kb = [math]::Round((Get-Item $src).Length / 1KB)
            Write-Ok "  $abi : libnarzoai_jni.so (${kb} KB)"
            $copied++
        } else {
            Write-Warn "  $abi : NOT FOUND (expected $src)"
        }
    }

    Remove-Item -Recurse -Force $tempDir -ErrorAction SilentlyContinue

    if ($copied -eq 0) {
        Write-Fail "No .so files extracted."
        return $false
    }
    Write-Ok "Native libraries installed ($copied/$($Script:Abis.Count) ABIs)"
    return $true
}

# ============================================================================
# AI MODEL DOWNLOAD
# ============================================================================

function Install-AiModels {
    Write-Host ""
    Write-Color "--- AI Model Download ---" -Color Yellow
    Initialize-Dir $Script:ModelsPath

    $models = @(
        @{ Name = "Whisper Tiny"; Url = $Script:WhisperModelUrl; Out = Join-Path $Script:ModelsPath "ggml-tiny.bin"; MinMB = 70 },
        @{ Name = "Gemma 2B GGUF"; Url = $Script:GemmaModelUrl; Out = Join-Path $Script:ModelsPath "gemma-2b-it-q4_k_m.gguf"; MinMB = 1000 }
    )

    $ok = 0
    foreach ($m in $models) {
        Write-Host "  Downloading $($m.Name) ..."
        if ((Test-Path $m.Out) -and (File-SizeMB $m.Out) -gt $m.MinMB) {
            Write-Ok "  $($m.Name) already exists ($(File-SizeMB $m.Out) MB)"
            $ok++
            continue
        }
        try {
            # PowerShell 5.1 compatible: suppress progress via preference
            $oldPref = $ProgressPreference
            $ProgressPreference = 'SilentlyContinue'
            Invoke-WebRequest -Uri $m.Url -OutFile $m.Out -UseBasicParsing
            $ProgressPreference = $oldPref
            $size = File-SizeMB $m.Out
            if ($size -gt $m.MinMB) { Write-Ok "  $($m.Name) : ${size}MB"; $ok++ }
            else { Write-Fail "  $($m.Name) downloaded but only ${size}MB (expected >$($m.MinMB)MB)" }
        } catch {
            Write-Fail "  Failed to download $($m.Name) : $_"
        }
    }

    Write-Host ""
    Write-Ok "Models downloaded: $ok/$($models.Count)"
}

# ============================================================================
# GRADLE BUILD
# ============================================================================

function Invoke-GradleBuild {
    Write-Step 3 $Script:TotalSteps "Building APK with Gradle..."

    $gradlew = Join-Path $Script:ProjectRoot "gradlew.bat"
    if (-not (Test-Path $gradlew)) { Write-Fail "gradlew.bat not found"; return $false }

    # Ensure local.properties exists for SDK path
    $props = Join-Path $Script:ProjectRoot "local.properties"
    if (-not (Test-Path $props) -and $Script:DetectedSdkPath) {
        "sdk.dir=$($Script:DetectedSdkPath -replace '\\', '/')" | Out-File $props -Encoding ASCII
        Write-Ok "local.properties created"
    }

    Write-Host "  Running: $gradlew assembleDebug -PusePrebuiltNative --no-daemon"
    Write-Host "  (this may take several minutes)"
    Write-Host ""

    # Direct invocation with $LASTEXITCODE for reliable exit code capture
    $process = Start-Process -FilePath $gradlew -ArgumentList @(
        "assembleDebug", "-PusePrebuiltNative", "--no-daemon"
    ) -NoNewWindow -Wait -PassThru

    if ($process.ExitCode -eq 0) {
        Write-Ok "Build successful!"
        return $true
    } else {
        Write-Fail "Build failed (exit code $($process.ExitCode))."
        Write-Warn "Retry manually: $gradlew assembleDebug -PusePrebuiltNative"
        return $false
    }
}

# ============================================================================
# APK INSTALLATION VIA ADB
# ============================================================================

function Install-ApkToDevice {
    Write-Step 4 $Script:TotalSteps "Installing APK to device..."

    # Locate adb
    $adb = if ($Script:DetectedSdkPath) {
        Join-Path $Script:DetectedSdkPath "platform-tools" "adb.exe"
    } else { "adb" }
    if (-not (Get-Command $adb -ErrorAction SilentlyContinue)) {
        Write-Fail "adb not found. Install Android Platform Tools."
        return $false
    }

    # List devices — capture as string array, handle Windows \r\n line endings
    $rawDevices = & $adb devices
    $deviceList = @()
    foreach ($line in $rawDevices) {
        $line = $line.Trim()
        if ($line -match '^(\S+)\s+device$') {
            $deviceList += $Matches[1]
        }
    }

    if ($deviceList.Count -eq 0) {
        Write-Fail "No Android devices connected. Enable USB Debugging on your phone."
        return $false
    }

    $deviceId = $deviceList[0]
    Write-Ok "Device: $deviceId"

    # Find APK
    $apks = Get-ChildItem -Path $Script:ApkRootDir -Recurse -Filter "*.apk" | Where-Object {
        $_.Name -notmatch 'unaligned|unsigned'
    } | Sort-Object LastWriteTime -Descending

    if ($apks.Count -eq 0) {
        Write-Fail "No APK found under $Script:ApkRootDir"
        return $false
    }

    $apk = $apks[0]
    Write-Host "  APK: $($apk.FullName) ($(File-SizeMB $apk.FullName) MB)"

    Write-Host "  Installing..."
    $result = & $adb -s $deviceId install -r $apk.FullName 2>&1

    if ($LASTEXITCODE -eq 0 -and ($result -match '^Success')) {
        Write-Ok "APK installed! Launch 'NarzoAI Assistant' on your device."
        return $true
    } else {
        Write-Fail "Install failed: $result"
        return $false
    }
}

# ============================================================================
# SUMMARY
# ============================================================================

function Show-Summary {
    param([bool]$NativeOk, [bool]$BuildOk, [bool]$InstallOk)
    Write-Host ""
    Write-Color "============================================" -Color Cyan
    Write-Color "  Summary" -Color Cyan
    Write-Color "============================================" -Color Cyan
    Write-Host ""
    if ($NativeOk -or $SkipNativeDownload) { Write-Color "  Native Libs:  Ready" -Color Green }
    else { Write-Color "  Native Libs:  Not installed" -Color Red }
    if ($BuildOk) { Write-Color "  Build:        Successful" -Color Green }
    else { Write-Color "  Build:        Failed" -Color Red }
    if ($InstallOk) { Write-Color "  Install:      Installed" -Color Green }
    elseif ($InstallApk) { Write-Color "  Install:      Failed" -Color Red }
    else { Write-Color "  Install:      Skipped" -Color DarkGray }
    Write-Host ""

    if ($BuildOk) {
        $latest = Get-ChildItem $Script:ApkRootDir -Recurse -Filter "*.apk" |
            Where-Object { $_.Name -notmatch 'unaligned|unsigned' } |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($latest) {
            Write-Color "  APK: $($latest.FullName)" -Color Cyan
            Write-Color "  Size: $(File-SizeMB $latest.FullName) MB" -Color DarkGray
        }
        Write-Host ""
        Write-Host "  Install on device: .\setup.ps1 -InstallApk"
    }
    Write-Host ""
}

# ============================================================================
# MAIN
# ============================================================================

function Main {
    if ($Help) {
        # Display help from comment-based help
        Get-Help $MyInvocation.MyCommand.Path -Detailed
        exit 0
    }

    Write-Banner

    # Verify we're in the project root
    if (-not (Test-Path (Join-Path $Script:ProjectRoot "gradlew.bat"))) {
        Write-Fail "Run this script from the project root (where gradlew.bat is)."
        exit 1
    }

    # Determine total steps based on mode
    $Script:TotalSteps = 3
    if ($Mode -eq "full") { $Script:TotalSteps = 4 }
    if ($InstallApk) { $Script:TotalSteps++ }

    # --- Step 0: Prerequisites ---
    if (-not (Test-Prerequisites)) { exit 1 }

    $nativeOk = $false
    $buildOk  = $false
    $installOk = $false

    # --- Step 1: Native libraries ---
    if ($Mode -eq "build-only") {
        Write-Step 2 $Script:TotalSteps "Build-only mode — verifying jniLibs..."
        # Verify .so files exist
        $missing = @()
        foreach ($abi in $Script:Abis) {
            $f = Join-Path $Script:JniLibsPath $abi "libnarzoai_jni.so"
            if (-not (Test-Path $f)) { $missing += $abi }
        }
        if ($missing.Count -gt 0) {
            Write-Warn "Missing .so files for: $($missing -join ', ')"
            Write-Warn "Run without -Mode build-only or: .\setup.ps1"
        } else {
            Write-Ok "Native libraries found in jniLibs"
        }
        $nativeOk = $true
    } elseif (-not $SkipNativeDownload) {
        $nativeOk = Install-NativeLibraries -Version $NativeVersion
    } else {
        Write-Step 2 $Script:TotalSteps "Skipping native library download (-SkipNativeDownload)"
        $nativeOk = $true
    }

    # --- Step 2: AI models (full mode only) ---
    if ($Mode -eq "full") {
        Install-AiModels
    } elseif ($Mode -ne "build-only") {
        Write-Host ""
        Write-Color "--- AI Model Download (skipped) ---" -Color Yellow
        Write-Host "  Use '-Mode full' to also download AI models."
        Write-Host ""
    }

    # --- Step 3: Build ---
    $buildOk = Invoke-GradleBuild

    # --- Step 4: Install ---
    if ($buildOk -and $InstallApk) {
        $installOk = Install-ApkToDevice
    } elseif ($InstallApk -and -not $buildOk) {
        Write-Fail "Cannot install because build failed."
    }

    Show-Summary -NativeOk $nativeOk -BuildOk $buildOk -InstallOk $installOk

    if ($buildOk) { exit 0 } else { exit 1 }
}

# ============================================================================
# ENTRY POINT
# ============================================================================

function Initialize-Dir {
    param([string]$Path)
    if (-not (Test-Path $Path)) { New-Item -ItemType Directory -Path $Path -Force | Out-Null }
}

# Run
try { Main } catch {
    Write-Host ""
    Write-Fail "Unexpected error: $($_.Exception.Message)"
    Write-Warn "Please report this issue with the full error details."
    exit 1
}
