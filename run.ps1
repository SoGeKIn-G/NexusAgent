<#
    Build, install, and launch NexusAgent on the connected phone.

    Usage (from the project root):
        .\run.ps1              build + install + launch
        .\run.ps1 -Logs        ...then stream the perception log
        .\run.ps1 -TestOnly    just run the JVM unit tests
        .\run.ps1 -Clean       wipe build outputs first

    Sets its own environment, so it works even in a terminal that was open before
    the toolchain was installed.
#>
param(
    [switch]$Logs,
    [switch]$TestOnly,
    [switch]$Clean
)

$ErrorActionPreference = "Stop"

$env:JAVA_HOME        = "C:\Program Files\Microsoft\jdk-21.0.12.8-hotspot"
$env:ANDROID_HOME     = "D:\dev\Android\Sdk"
$env:ANDROID_SDK_ROOT = "D:\dev\Android\Sdk"
$env:GRADLE_USER_HOME = "D:\dev\gradle-home"

$adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
$pkg = "com.nexusagent.debug"

Set-Location $PSScriptRoot

if ($TestOnly) {
    & ".\gradlew.bat" :core:model:test --console=plain
    exit $LASTEXITCODE
}

if ($Clean) {
    Write-Host "Cleaning..." -ForegroundColor Cyan
    & ".\gradlew.bat" clean --console=plain
}

# Fail early with a useful message rather than a confusing Gradle error.
$devices = & $adb devices | Select-String -Pattern "\sdevice$"
if (-not $devices) {
    Write-Host "No device connected." -ForegroundColor Red
    Write-Host "  - check the USB cable (many are charge-only)"
    Write-Host "  - set USB mode to File Transfer, not Charging only"
    Write-Host "  - accept the 'Allow USB debugging?' prompt on the phone"
    exit 1
}

Write-Host "Building and installing..." -ForegroundColor Cyan
& ".\gradlew.bat" installDebug --console=plain
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# Reinstalling unbinds the accessibility service, and Android does not reliably rebind it
# - the secure setting still lists the service while nothing is actually running, which
# looks exactly like a bug in the app. Toggling the entry off and on forces a rebind.
# Other enabled services are preserved.
Write-Host "Rebinding accessibility service..." -ForegroundColor Cyan
$svc = "$pkg/com.nexusagent.agent.perception.NexusAccessibilityService"
$current = (& $adb shell settings get secure enabled_accessibility_services).Trim()
if ($current -eq "null") { $current = "" }
$others = ($current -split ':' | Where-Object { $_ -and $_ -ne $svc }) -join ':'

& $adb shell settings put secure enabled_accessibility_services "'$others'" *>$null
Start-Sleep -Milliseconds 400
$restored = if ($others) { "${others}:${svc}" } else { $svc }
& $adb shell settings put secure enabled_accessibility_services "'$restored'" *>$null
& $adb shell settings put secure accessibility_enabled 1 *>$null
Start-Sleep -Milliseconds 600

Write-Host "Launching..." -ForegroundColor Cyan
# monkey launches through the normal launcher intent; `am start` can land the app in
# ColorOS's flexible mini-window instead of fullscreen.
#
# monkey writes its arg echo to stderr, which PowerShell 5.1 turns into NativeCommandError
# records that look like a crash. Capture both streams into $null and relax the error
# preference across the call so the noise never reaches the console.
$ErrorActionPreference = "Continue"
& $adb shell input keyevent KEYCODE_HOME *>$null
Start-Sleep -Milliseconds 800
& $adb shell monkey -p $pkg -c android.intent.category.LAUNCHER 1 *>$null
$ErrorActionPreference = "Stop"

Write-Host "Running." -ForegroundColor Green

if ($Logs) {
    Write-Host "Streaming perception log (Ctrl+C to stop)...`n" -ForegroundColor Cyan
    & $adb logcat -c
    & $adb logcat -s NexusPerception
}
