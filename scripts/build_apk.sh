#!/bin/zsh
# Build ResQMesh debug APK on macOS. Run from the project root:  ./scripts/build_apk.sh
set -e
cd "$(dirname "$0")/.."
AS_JBR="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
[ -z "$JAVA_HOME" ] && [ -d "$AS_JBR" ] && export JAVA_HOME="$AS_JBR"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
[ -d "$SDK" ] || { echo "Android SDK not found at $SDK. Install Android Studio, open SDK Manager, install 'Android 14 (API 34)'."; exit 1; }
echo "sdk.dir=$SDK" > local.properties
chmod +x gradlew
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
APK=app/build/outputs/apk/debug/app-debug.apk
ls -l "$APK" && shasum -a 256 "$APK"
echo "Install:  adb install -r $APK"
