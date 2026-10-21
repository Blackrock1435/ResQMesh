# Architecture

```
UI (Compose)  ──►  MeshViewModel  ──►  MeshManager ──► RelayEngine (:core, pure Kotlin)
                                           │                 │  uses interfaces
                                           │                 ├─ MeshStore  ◄── RoomMeshStore (Room/SQLite)
                                           │                 ├─ Transport  ◄── NearbyConnectionsTransport
                                           │                 └─ GroupKeys  ◄── MeshManager (Keystore-wrapped)
                                           └── foreground service, location, identity (Keystore)
```
* **`:core`** has no Android dependency; the whole protocol/relay is unit-tested with an in-memory store and a simulated radio (`SimNet`, tests only).
* **Threading:** every engine/store/`send()` call runs on one thread (`mesh-engine`). Nearby callbacks only post to it. Duplicate processing is additionally prevented by the atomic `insertIfNew` (Room `INSERT … IGNORE`).
* **Transport swap:** implement `Transport` (+ call `engine.onLinkUp/Down/onBytes`) for Bluetooth/Wi-Fi Direct later.

## Packet (v1, binary, strictly validated)
`magic, version, type, flags, id(16), sender(16), [dest(16)], [group], ttl, createdAt, maxHop, pubKey, payload, hop, signature`
Signature (ECDSA P-256/SHA-256) covers everything except `hop`; `sender = SHA-256(pubKey)[0..16]` (self-certifying). Limits: 16 KB packet, 8 KB payload, ≤10 hops, ≤7 d TTL.

## Relay rules
1. Decode → reject malformed/oversized → verify identity + signature → reject if `createdAt` is absurdly old/future (±12 h skew window).
2. `insertIfNew(id)` atomically; duplicates dropped. Expiry = **local receive time + ttl** (sender clock not trusted for lifetime).
3. Deliver (decrypt) if addressed to me / my group / broadcast / SOS; destination sends signed **RECEIPT(DELIVERED)** back (flooded to the sender).
4. Forward to every connected neighbour except the incoming link and the originator, with `hop+1`, only if `hop+1 ≤ maxHop`.
5. **Pull sync:** on link-up and every 30 s each side sends an `INVENTORY` (ids, SOS first); the peer `REQUEST`s only missing ids. Packets that existed before a link came up are *not* blindly pushed. New arrivals are pushed.
6. Retry: own packets back off 4 s → 60 s; after 30 failed attempts → FAILED (manual Retry). Relayed packets wait until expiry. Queue bounded to 500 (evicts lowest priority/oldest; never own active SOS). Per-link token-bucket rate limit.
7. Priority: SOS(0) > receipts(1) > chat(2) > broadcast(3) > hello(4).

## Acknowledgments
* `SENT` = handed to a neighbour's radio link (**not** delivery). `DELIVERED` = receipt from the destination (DIRECT: dest key; GROUP: HMAC proof of group key; SOS: per-device list). Relays never create DELIVERED.
* Link-level hop acks are the transport's own success callback; no separate ACCEPTED packet exists on the wire (documented simplification).

## Data (Room v1)
`packets` (message + relay queue state), `receipts`, `peers`, `groups` (Keystore-wrapped key), `diag`. Indices on type, status, expiry, sender, group, (relay,nextAttempt). History kept `retentionDays` (default 14 d, must exceed max TTL+skew so replays stay rejected); expired tombstones purged after 24 h.
