$ErrorActionPreference = "Stop"

$appId = "com.example.airis"

$sdkRoot = $env:ANDROID_HOME
if (-not $sdkRoot) { $sdkRoot = $env:ANDROID_SDK_ROOT }
if (-not $sdkRoot) { $sdkRoot = Join-Path $env:LOCALAPPDATA "Android\Sdk" }

$adb = Join-Path $sdkRoot "platform-tools\adb.exe"
if (-not (Test-Path $adb)) {
    throw "adb not found at $adb. Set ANDROID_HOME to your Android SDK directory."
}

$targetPid = & $adb shell pidof $appId
if (-not $targetPid) {
    throw "$appId is not running. Start it first (Ctrl+Shift+B)."
}
$targetPid = ($targetPid -split "\s+")[0].Trim()

Write-Host "Tailing logs for $appId (pid $targetPid). Ctrl+C to stop." -ForegroundColor Cyan
& $adb logcat --pid=$targetPid
