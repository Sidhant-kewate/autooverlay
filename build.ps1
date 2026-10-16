# One-shot APK builder for Windows (PowerShell).
# Run:  powershell -ExecutionPolicy Bypass -File build.ps1
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
Set-Location $PSScriptRoot
$T = Join-Path $env:USERPROFILE ".autotap-build"
New-Item -ItemType Directory -Force $T | Out-Null

# --- JDK 17 ---
$jdk = Join-Path $T "jdk"
if (-not (Test-Path "$jdk\bin\java.exe")) {
  Write-Host ">> Downloading JDK 17..."
  Invoke-WebRequest "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse" -OutFile "$T\jdk.zip"
  Expand-Archive "$T\jdk.zip" "$T\jdktmp" -Force
  Move-Item (Get-ChildItem "$T\jdktmp" | Select-Object -First 1).FullName $jdk
  Remove-Item "$T\jdktmp","$T\jdk.zip" -Recurse -Force
}
$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;$env:Path"

# --- Android SDK ---
$sdk = Join-Path $T "sdk"
$env:ANDROID_HOME = $sdk; $env:ANDROID_SDK_ROOT = $sdk
$sdkm = "$sdk\cmdline-tools\latest\bin\sdkmanager.bat"
if (-not (Test-Path $sdkm)) {
  Write-Host ">> Downloading Android command-line tools..."
  Invoke-WebRequest "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" -OutFile "$T\cmd.zip"
  Expand-Archive "$T\cmd.zip" "$T\cmdtmp" -Force
  New-Item -ItemType Directory -Force "$sdk\cmdline-tools" | Out-Null
  Move-Item "$T\cmdtmp\cmdline-tools" "$sdk\cmdline-tools\latest"
  Remove-Item "$T\cmdtmp","$T\cmd.zip" -Recurse -Force
}
Write-Host ">> Accepting licenses & installing platform/build-tools..."
(1..30 | ForEach-Object { "y" }) | & $sdkm --licenses | Out-Null
& $sdkm "platforms;android-34" "build-tools;34.0.0" "platform-tools"
"sdk.dir=" + $sdk.Replace("\","/") | Set-Content local.properties

# --- Gradle ---
$gr = Join-Path $T "gradle-8.7\bin\gradle.bat"
if (-not (Test-Path $gr)) {
  Write-Host ">> Downloading Gradle 8.7..."
  Invoke-WebRequest "https://services.gradle.org/distributions/gradle-8.7-bin.zip" -OutFile "$T\gradle.zip"
  Expand-Archive "$T\gradle.zip" $T -Force
  Remove-Item "$T\gradle.zip"
}

Write-Host ">> Building APK..."
& $gr assembleDebug --no-daemon
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }
Copy-Item "app\build\outputs\apk\debug\app-debug.apk" ".\AutoTapOverlay.apk" -Force
Write-Host ""
Write-Host "DONE -> $PWD\AutoTapOverlay.apk  (copy to your phone and install)"
