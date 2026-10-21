# Security & privacy

| Property | Implementation |
|---|---|
| Identity | ECDSA/ECDH P-256 key pair per device; deviceId = hash of public key (no registry, cannot be spoofed without the key). Private key stored wrapped by an Android Keystore AES-256-GCM key. |
| Authenticity | Every packet signed; invalid signature or sender≠hash(key) ⇒ dropped. Receipts signed; DIRECT receipts only accepted from the true destination; GROUP receipts need an HMAC proof of the group key. |
| Private messages (DIRECT) | **End-to-end**: ephemeral ECDH → HKDF-SHA256 → AES-256-GCM, AAD binds id/sender/dest. Relays cannot read them. |
| Groups | AES-256-GCM with key = PBKDF2-HMAC-SHA256(passphrase, groupId). Membership = knowing the passphrase. No per-member revocation; weak passphrases are brute-forceable by anyone who captured traffic. |
| Broadcast & SOS | Signed but **plaintext by design** (any relay must display them). Location in SOS/broadcast is visible to all relays. |
| Transport encryption | Nearby Connections encrypts each hop; it does **not** protect content on intermediate phones — hence the E2E layer above. Nearby auth code is shown for manual approval. |
| Replay / duplicates | Unique 128-bit ids + atomic DB insert; stale/future `createdAt` rejected; tombstones retained ≥ TTL+skew. |
| DoS | Size limits, bounded queue (500), per-link rate limit, bounded retries, bounded inventory/request sizes. |
| Logging | Diagnostics contain event kinds and counters only; no message text, no coordinates. Export is opt-in. |
| Permissions | No INTERNET, contacts, SMS, call-log, microphone. Location requested only when used. |

**Known gaps:** messages are stored unencrypted at rest (app sandbox only; consider SQLCipher); no forward secrecy for groups; no key-change warnings/verification UI (a peer is trusted on first HELLO — compare IDs in person); a malicious relay can alter the unsigned `hop` field (only affects range) and can drop traffic; SOS receipts are claims by device keys, not by authorities; cryptography has not been independently audited.
