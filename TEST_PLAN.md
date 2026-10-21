# Test plan

## A. Automated (`./gradlew :core:test`) — status: 33/33 passed in sandbox harness
Codec round-trip, tamper/forgery, malformed/oversized/truncated, unique ids, expiry, hop limit, duplicate suppression, **concurrent** duplicate delivery (8 threads), five-node chain A→B→C→D→E (no A↔E link, DELIVERED receipt returns, intermediates cannot read), relay≠delivery, recipient never acks, unavailable destination → EXPIRED, disconnect during transfer, store-and-forward on later connection, restart persistence, retry limit + manual retry, bounded queue (own SOS kept), SOS priority + per-device acks + resolve, group encryption + forged receipt rejected, spoofed receipt rejected, inventory sync without re-sending, rate limit, location validation, crypto (E2E, AAD binding, group).
Not automated yet: Room DAO tests (need instrumented `androidTest`), Compose UI tests.

## B. Physical-device tests — **ALL PENDING (not executed)**
Prep: install same build on all phones; Bluetooth+Wi-Fi ON; mobile data OFF; (optionally airplane mode then re-enable Bluetooth+Wi-Fi); grant permissions.

| # | Test | Steps | Expected | Actual | Pass? |
|---|---|---|---|---|---|
| 1 | Two-phone direct | Start network on both, connect/accept, A sends text | B shows it; A shows "Delivered" only after B's receipt | | |
| 2 | Three-phone relay | A–B–C in a line, A and C out of direct range | C receives A's DM; A shows Delivered; B logs relay; Diagnostics "relayed" > 0 | | |
| 3 | Five-phone chain | A–B–C–D–E each only in range of neighbours | E receives; received hop = 3; A Delivered | | |
| 4 | Internet off | Disable mobile data & Wi-Fi internet (keep Wi-Fi radio) | Tests 1–3 still pass | | |
| 5 | No SIM | Phone without SIM | Same | | |
| 6 | Location offline | Outdoors, data off, Get my location | Real coordinates, accuracy, age; stale label for last-known | | |
| 7 | SOS relay w/ location | A sends SOS; E far away | E shows SOS + coordinates; A lists acks per device; no "emergency services notified" text | | |
| 8 | Background/foreground | Send app to background 5 min with network on | Foreground notification persists; messages still arrive | | |
| 9 | Phone restart | Queue message with no peer, reboot, restart app + network | Message still queued, then sent on connect | | |
| 10 | Disconnect/reconnect | Walk B out of range and back | State shows Disconnected → reconnects (auto-join) or via Connect; pending message delivered | | |

Record device models and Android versions; test ≥2 models.
