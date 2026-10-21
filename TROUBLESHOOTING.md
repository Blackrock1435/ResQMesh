# Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| "Google Play services is missing…" | Device lacks/outdated Play services. Update it once (needs internet) or use another phone. |
| "Could not search/advertise (code …)" | Turn ON Bluetooth, Wi-Fi and Location; grant Nearby/Bluetooth permissions; toggle Start Network. Code 8034/8038-range = permissions or radios off. |
| No devices found | Network must be started on **both** phones; stay within ~10 m for first pairing; keep screens on; disable battery saver. |
| Stuck "Waiting for your approval" | Tap Accept on both sides (or enable Auto-join). Times out after 30 s → Connect again. |
| "Recipient's key not known yet" | You have not yet received that person's HELLO. Stay connected a few seconds (HELLOs also arrive via other phones). |
| Message stays "Passed to a nearby device…" | Correct: no receipt from the destination yet. Destination may be out of range; it will resolve or expire (24 h). |
| Message FAILED | Retry budget (~30 attempts, ≈25 min) exhausted with no neighbour. Tap Retry. |
| Location "No GPS fix" | Go outdoors/near a window, wait up to a minute, retry. Indoors GPS often fails. Messaging is unaffected. |
| Build: `SDK location not found` | Create `local.properties` with `sdk.dir=…`. |
| Build: JDK errors | Use JDK 17 (`JAVA_HOME` = Android Studio JBR). |
| `./gradlew: Permission denied` | `chmod +x gradlew`. |
| A phone connects directly to all others in the 5-phone test | Increase distance/use walls; otherwise relay is not being exercised. |
