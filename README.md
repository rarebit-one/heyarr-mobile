# heyarr-mobile

The first-party **Android client for [heyarr](https://github.com/rarebit-one/heyarr-core)** —
the self-hosted media platform. Voidbind **QR** sign-in, a native **library browse**, and the
seams for **device-side personal state** (decrypt-on-device) that make this the *product*
client rather than a generic Subsonic app.

This is a **scaffold** — a buildable, tested foundation, not a finished app.

## What's here

| Area | State | Where |
|------|-------|-------|
| **Auth** | Two credential shapes — primary `Authorization: Device <cert>~<proof>` and bootstrap `Bearer` (QR session). Header formatting real + tested; the in-enclave proof is phone-gated. | `auth/` |
| **Login** | Voidbind **QR** seam (heyarr's channel). Real initiator `POST /login` + poll `GET /login/{id}` → Bearer token. QR bitmap render + device-side approve are follow-ups. | `login/` |
| **Library** | `LibraryClient` against the native `GET /api/v1/works`, auth header + tolerant parse, wired to a browse list. Subsonic reach is a documented stub. | `library/` |
| **Playback** | A **Media3/ExoPlayer** player streaming `/api/v1/blobs/{hash}/content` over an OkHttp-backed data source with the auth header + native **Range/206** seek (the M10 win); transport controls for audio **and** video; wired to tap-an-item-to-play. The URL/plan/target logic is unit-tested; a real codec decoding on a device is the phone-gated half. | `playback/` |
| **Personal state** | `/api/v1/spaces/{id}/{keys,changes,snapshot}` fetched as **opaque ciphertext**; a fail-closed `Unwrapper` decrypt-on-device seam. **No crypto in this repo.** | `personalstate/` |

## Relationship to the other repos

- **[`rarebit-one/heyarr-core`](https://github.com/rarebit-one/heyarr-core)** (PUBLIC / AGPL) —
  the server this talks to. The **contract** this client builds against is
  `heyarr-core/docs/design/mobile-client.md` (device auth ADR-0048, personal state
  ADR-0049/0051). **No server change is needed** to add this client.
- **[`rarebit-one/voidbind-kmp`](https://github.com/rarebit-one/voidbind-kmp)** — THE Voidbind
  authenticator + the shared `voidbind-client` (`WebLoginClient`, `LoginQr`, `WebLogin`,
  `LoginApproval`). This app delegates login approval to that authenticator.

### voidbind-kmp consumption — packaging follow-up

voidbind-kmp does **not** publish a consumable Maven artifact yet (`0.1.0-SNAPSHOT`, no
`maven-publish`), so this app **cannot** depend on it over the wire and CI can't fetch it.
Until a `voidbind-client` module is extracted + published (plan §5/§6), this repo ships a
thin, **wire-compatible** `login/` seam that mirrors voidbind-kmp's `WebLoginClient` create/poll
and `LoginQr` tuple byte-for-byte. `settings.gradle.kts` documents a commented
`includeBuild("../voidbind-kmp")` for local composite builds, and `login/VoidbindLogin.kt` +
`auth/DeviceCredential.kt` carry the `TODO`s to swap in the real artifact once it exists.

## Build / test

```sh
./gradlew testDebugUnitTest      # pure-JVM unit tests (login state machine, works parse, URLs, credential)
./gradlew assembleDebug          # debug APK
```

Requires JDK 17+ and an Android SDK (API 35). Point `local.properties` at your SDK
(`sdk.dir=…`) — CI provisions it via `android-actions/setup-android`.

## Phone-gated follow-ups (deferred by design)

These need real hardware / a live server and can't be proven in CI:

- **On-device playback acceptance** — the Media3/ExoPlayer pipeline over
  `/api/v1/blobs/{hash}/content` (Range + `Authorization`) now **ships** (`playback/`), but a
  real codec decoding the stream and a scrub issuing live 206 range reads can only be proven on
  a device/emulator. The `POST /api/v1/playback` transcode/remux negotiation is wired
  (`PlaybackClient.plan`) but keyed on an enrolled `device_id`, so it goes live with device auth.
- **Device-cert login** — the in-enclave Ed25519 **possession proof** (Keystore/StrongBox,
  non-exportable key) + the enrolment handshake that turns a QR-bootstrapped session into an
  enrolled `Device` credential; re-mint-on-wake for a slept, clock-drifted device (ADR-0048).
- **On-device personal-state decrypt** — the real `Unwrapper` (X25519 ECDH in-enclave → HKDF →
  the space key) + AEAD decryption of opaque changes, and the local **Personal MCP** (#372/#387)
  that reads the decrypted state. **No crypto ships in this repo.**
- **QR bitmap rendering** — drawing the `voidbind:login?…` tuple as a scannable QR (a
  QR-encoder dependency); today the tuple is shown as text.
- **Subsonic/OPDS/DLNA reach** — deciding whether to ship the compat adapters at all vs. leaning
  on the native API; `library/SubsonicClient` marks the fork as a decision, not an omission.

## License

[GNU AGPL v3](./LICENSE) — matching `rarebit-one/heyarr-core`.
