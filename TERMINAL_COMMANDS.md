# Terminal commands (macOS, zsh). Run from the project root unless noted.

**Tab 1 – build**
```zsh
java -version                                   # need 17
ls ~/Library/Android/sdk/platform-tools/adb     # if missing: install "Android SDK Platform-Tools" in Android Studio SDK Manager
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
./gradlew :core:test
./gradlew :app:assembleDebug
```
**Tab 2 – devices** (enable Developer options → USB debugging first)
```zsh
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
adb devices
adb -s <SERIAL> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <SERIAL> shell am start -n com.resqmesh.app/.MainActivity
adb -s <SERIAL> shell pm grant com.resqmesh.app android.permission.ACCESS_FINE_LOCATION   # optional shortcut
```
**Tab 3 – logs**
```zsh
adb -s <SERIAL> logcat -c
adb -s <SERIAL> logcat | grep -iE "resqmesh|nearby|AndroidRuntime"
adb -s <SERIAL> logcat -d > crash_$(date +%H%M).txt           # capture a crash report
adb -s <SERIAL> shell dumpsys package com.resqmesh.app | grep -i permission
```
Nothing here deletes files or kills processes. `adb uninstall com.resqmesh.app` removes the app and its data (only run if you want that).
