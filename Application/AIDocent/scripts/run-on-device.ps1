$ErrorActionPreference = "Stop"

$appId = "com.example.airis"
$activity = "$appId/.MainActivity"
$projectRoot = Split-Path -Parent $PSScriptRoot

# Resolve the SDK: prefer the environment, but fall back to the standard install
# location so the script works in shells started before ANDROID_HOME was set.
$sdkRoot = $env:ANDROID_HOME
if (-not $sdkRoot) { $sdkRoot = $env:ANDROID_SDK_ROOT }
if (-not $sdkRoot) { $sdkRoot = Join-Path $env:LOCALAPPDATA "Android\Sdk" }

$adb = Join-Path $sdkRoot "platform-tools\adb.exe"
if (-not (Test-Path $adb)) {
    throw "adb not found at $adb. Set ANDROID_HOME to your Android SDK directory."
}

if (-not $env:JAVA_HOME) {
    $jbr = "C:\Program Files\Android\Android Studio\jbr"
    if (Test-Path $jbr) { $env:JAVA_HOME = $jbr }
}

$device = & $adb devices | Select-String "\tdevice$"
if (-not $device) {
    throw "No authorized device found. Connect via USB, enable USB debugging, and accept the RSA prompt on the device, then check 'adb devices'."
}

Write-Host "Building and installing..." -ForegroundColor Cyan
Push-Location $projectRoot
try {
    & "$projectRoot\gradlew.bat" installDebug
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed (exit code $LASTEXITCODE)" }
} finally {
    Pop-Location
}

& $adb shell am force-stop $appId
& $adb shell am start -n $activity | Out-Null

Write-Host "App is running on the device." -ForegroundColor Green
