# Getting the APK (three ways)

> **Status:** the Android module has NOT been compiled by the author's tooling (no Android SDK / Google Maven access in that environment). The first real build happens in one of the ways below. Expect possible small compile errors; send the first error lines and they can be fixed quickly.

## A. No Mac needed: build in the cloud with GitHub Actions (free)
1. Create a free GitHub account → **New repository** (private is fine).
2. Upload the unzipped project contents (drag-and-drop in the web UI, or `git push`). Keep the `.github/workflows/build-apk.yml` file.
3. Open the repo → **Actions** tab → *Build ResQMesh APK* → wait ~5-10 min (you can also press **Run workflow**).
4. Open the finished run → **Artifacts** → download **ResQMesh-debug-apk** (contains `app-debug.apk` and its SHA-256). If the run is red, download **test-and-build-reports** or copy the failing step's log and send it to me.

## B. Android Studio (Mac/Windows/Linux)
1. Install Android Studio; open the project folder; let Gradle sync (needs internet once).
2. Menu **Build → Build Bundle(s)/APK(s) → Build APK(s)**. APK: `app/build/outputs/apk/debug/app-debug.apk`.

## C. Terminal on macOS
```zsh
./scripts/build_apk.sh
```

## Install on your phone
1. Copy `app-debug.apk` to the phone (USB, Google Drive, WhatsApp-to-self, e-mail).
2. Open it from the Files app. If asked, allow **Install unknown apps** for that app. Tap **Install** → **Open**.
3. Optional with USB debugging: `adb install -r app-debug.apk`.
4. First run: choose a display name → Home → **Start Network** → allow Bluetooth/Nearby (and Notifications). Use the location tab to grant location when you share GPS.
5. Install the same APK on a second phone and follow `TEST_PLAN.md`.

## Requirements
Android 8.0+ (API 26), Google Play services installed and up to date (update once with internet), Bluetooth + Wi-Fi radios ON (no internet or SIM needed), Location ON for GPS. Some phone makers (Xiaomi, Oppo, Vivo, Huawei, Samsung battery saver) kill background apps: allow "no restrictions"/auto-start for ResQMesh.
