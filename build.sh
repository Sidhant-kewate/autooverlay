#!/usr/bin/env bash
# One-shot APK builder for Linux / macOS / WSL / Termux-proot.
# Downloads JDK 17, Android SDK and Gradle into ~/.autotap-build, then builds.
set -euo pipefail
cd "$(dirname "$0")"
T="$HOME/.autotap-build"; mkdir -p "$T"
OS=$(uname -s); ARCH=$(uname -m)
case "$OS" in Linux) P=linux; J=linux;; Darwin) P=mac; J=mac;; *) echo "Use build.ps1 on Windows"; exit 1;; esac
case "$ARCH" in x86_64|amd64) JA=x64;; arm64|aarch64) JA=aarch64;; *) echo "Unsupported CPU: $ARCH"; exit 1;; esac

need() { command -v "$1" >/dev/null || { echo "Please install '$1' first"; exit 1; }; }
need curl; need unzip; need tar

# --- JDK 17 ---
if [ -x "$T/jdk/bin/java" ] || [ -x "$T/jdk/Contents/Home/bin/java" ]; then :; else
  echo ">> Downloading JDK 17..."
  mkdir -p "$T/jdk"
  curl -L "https://api.adoptium.net/v3/binary/latest/17/ga/$J/$JA/jdk/hotspot/normal/eclipse" -o "$T/jdk.tgz"
  tar -xzf "$T/jdk.tgz" -C "$T/jdk" --strip-components=1
  rm "$T/jdk.tgz"
fi
export JAVA_HOME="$T/jdk"; [ -d "$T/jdk/Contents/Home" ] && export JAVA_HOME="$T/jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

# --- Android SDK ---
export ANDROID_HOME="$T/sdk"; export ANDROID_SDK_ROOT="$ANDROID_HOME"
if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo ">> Downloading Android command-line tools..."
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  curl -L "https://dl.google.com/android/repository/commandlinetools-${P}-11076708_latest.zip" -o "$T/cmd.zip"
  unzip -q "$T/cmd.zip" -d "$T/cmdtmp"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mv "$T/cmdtmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -rf "$T/cmdtmp" "$T/cmd.zip"
fi
SDKM="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
echo ">> Accepting licenses & installing platform/build-tools..."
yes | "$SDKM" --licenses >/dev/null || true
"$SDKM" "platforms;android-34" "build-tools;34.0.0" "platform-tools"
echo "sdk.dir=$ANDROID_HOME" > local.properties

# --- Gradle ---
if [ ! -x "$T/gradle-8.7/bin/gradle" ]; then
  echo ">> Downloading Gradle 8.7..."
  curl -L "https://services.gradle.org/distributions/gradle-8.7-bin.zip" -o "$T/gradle.zip"
  unzip -q "$T/gradle.zip" -d "$T"; rm "$T/gradle.zip"
fi

echo ">> Building APK..."
"$T/gradle-8.7/bin/gradle" assembleDebug --no-daemon
cp app/build/outputs/apk/debug/app-debug.apk ./AutoTapOverlay.apk
echo ""
echo "DONE -> $(pwd)/AutoTapOverlay.apk  (copy to your phone and install)"
