# Incomplete features & known limitations

**Not done / not verified**
* Android module never compiled in the authoring environment; no APK; no physical-device test executed.
* No offline map tiles; no Room/instrumented/UI tests; no per-group member roster (so "unconfirmed members" cannot be listed — only members who confirmed).
* No read receipts; no hop-level ACCEPTED packet on the wire (transport callback only).
* Peer trust is trust-on-first-use with no fingerprint-verification screen; no key rotation/revocation; no forward secrecy; groups use a shared passphrase.
* Messages unencrypted at rest. A late-arriving SOS_RESOLVE (before its SOS) is ignored.
* Clock skew window ±12 h: phones with badly wrong clocks reject/are rejected.
* Controlled flooding (not routing): fine for ~5–30 phones, not city scale. Nearby Connections limits concurrent links per phone (device dependent). Battery use is significant while the network is on.
* Phones without Google Play services unsupported; background restrictions on some OEMs may stop the foreground service.
