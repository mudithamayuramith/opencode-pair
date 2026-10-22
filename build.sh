#!/usr/bin/env bash
# One-shot builder (Linux/macOS/WSL). Needs JDK 17+. Installs the Android SDK if missing.
set -e
cd "$(dirname "$0")"

SDK_VER="36"
BUILD_TOOLS="36.0.0"

if [ -z "$ANDROID_HOME" ] && [ -z "$ANDROID_SDK_ROOT" ] && [ ! -f local.properties ]; then
  export ANDROID_HOME="$HOME/android-sdk"
  if [ ! -d "$ANDROID_HOME/cmdline-tools/latest" ]; then
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    OS=linux; [ "$(uname)" = "Darwin" ] && OS=mac
    curl -L -o /tmp/cmdtools.zip "https://dl.google.com/android/repository/commandlinetools-${OS}-11076708_latest.zip"
    unzip -q -o /tmp/cmdtools.zip -d "$ANDROID_HOME/cmdline-tools" && mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  fi
  yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null || true
  "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "platforms;android-${SDK_VER}" "build-tools;${BUILD_TOOLS}" "platform-tools"
  echo "sdk.dir=$ANDROID_HOME" > local.properties
fi

chmod +x gradlew
./gradlew --no-daemon assembleDebug assembleRelease

echo
echo "Debug APK:   $(pwd)/app/build/outputs/apk/debug/app-debug.apk"
echo "Release APK: $(pwd)/app/build/outputs/apk/release/app-release.apk"
