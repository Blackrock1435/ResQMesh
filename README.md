# ResQMesh — Offline Emergency Mesh Chat (prototype)

Nearby Android phones exchange messages, GPS coordinates and SOS alerts **with no internet, SIM, cell tower or server**.
Phones relay for each other (store-and-forward, controlled flooding), so A → B → C → D → E works even when A cannot reach E directly.

> **Prototype, not a certified life-safety system.** It never contacts emergency services and never guarantees delivery, range or GPS accuracy.

## Honest status (read this first)

| Area | Status |
|---|---|
| Protocol, crypto, relay engine (`:core`) | Implemented. **33 automated tests passed** (run in the authoring sandbox, JUnit-compatible harness; see `TEST_RESULTS.txt`). Run `./gradlew :core:test` on your Mac to confirm with real JUnit. |
| 5-phone chain A→B→C→D→E | **Verified in simulation only** (logical devices, simulated transport). |
| Android app (`:app`: Compose UI, Room, Nearby Connections, GPS, foreground service) | **Written but NOT compiled or run** in the authoring environment (no Android SDK / Google Maven access there). Expect to fix a few compile errors on first build. |
| Real radios, 2/3/5 physical phones, GPS offline, SOS over real links | **Not tested.** Procedure in `TEST_PLAN.md`; results table is blank on purpose. |
| Debug APK | **Not produced yet** (authoring environment had no Android SDK or access to Google/Maven servers). Build it with `BUILD_APK.md` (GitHub Actions, Android Studio, or `./scripts/build_apk.sh`). |
| Offline map | Not implemented (coordinates only, copy + external map button). |

## What works without internet
Everything after installation: pairing, chat, group chat, relay, SOS, GPS fix (GNSS chip). Internet is only needed on the dev machine to download Gradle/SDK/Google Play services once.
Required radios: Bluetooth + Wi-Fi ON (Nearby Connections picks Bluetooth/BLE/Wi-Fi LAN/Wi-Fi Direct itself), Location ON for GPS. Mobile data may be OFF; no SIM needed.

## Requirements
Android 8.0+ (minSdk 26), **Google Play services** (Nearby Connections is a Play services API — phones without it, e.g. some Huawei/AOSP devices, are unsupported and the app says so). Dev: JDK 17, Android Studio (Koala+) or SDK command-line tools, Android SDK 34.

## Build & install — see BUILD_APK.md (cloud build option included)

### Terminal on macOS (zsh)
```zsh
cd ResQMesh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # JDK 17 bundled with Android Studio
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
./gradlew :core:test                 # relay-engine tests (real JUnit)
./gradlew :app:assembleDebug         # APK -> app/build/outputs/apk/debug/app-debug.apk
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk   # repeat per phone (-s <serial>)
```
See `TERMINAL_COMMANDS.md` for adb/logcat/permission commands and `TROUBLESHOOTING.md`.

## Using it
1. Open the app, choose a display name.
2. **Start Network** on every phone (consent dialog + permissions). Optionally enable *Auto-join mesh* for emergency mode; otherwise compare the code and tap Accept.
3. Chats: *Everyone* (broadcast, not private), *Groups* (same name + passphrase on each phone), *People* (end-to-end encrypted; the recipient's identity must have reached you first).
4. Location: Get my location → copy / share. SOS: press and hold 1.5 s (or tap + confirm).
5. Status wording you will see: "Saved. Waiting…", "Passed to a nearby device. Final delivery is not confirmed.", "Delivered to the recipient." (only after the destination's signed receipt).

## Testing 2 / 3 / 5 phones
Follow `TEST_PLAN.md`. For a true 5-hop chain, phones must be physically arranged so only neighbours are in range (e.g. separated by distance/walls); otherwise Nearby may connect all of them directly and the test does not prove relaying. Check `adb logcat` / the Diagnostics screen (relay count, hop in received packets).

## Docs
`ARCHITECTURE.md` · `SECURITY.md` · `TEST_PLAN.md` · `TERMINAL_COMMANDS.md` · `TROUBLESHOOTING.md` · `KNOWN_LIMITATIONS.md`
