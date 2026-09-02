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
  authenticator app (**Cruciform**, `one.rarebit.cruciform`) + the shared `voidbind-client` (`WebLoginClient`, `LoginQr`, `WebLogin`,
  `LoginApproval`). This app delegates login approval to that authenticator.

### voidbind-kmp consumption

This app depends on the **published** shared client,
`one.rarebit.voidbind:voidbind-client:0.2.1` (GitHub Packages, private — a token with
`read:packages` is required even for a same-org read). `settings.gradle.kts` reads
`gpr.user` / `gpr.token` from `~/.gradle/gradle.properties`, or `GITHUB_ACTOR` /
`GITHUB_TOKEN` from the environment; CI passes its own workflow token. Locally:

```sh
GITHUB_ACTOR=<your login> GITHUB_TOKEN=$(gh auth token) ./gradlew testDebugUnitTest assembleDebug
```

`login/` is now a thin façade over the library's `WebLoginClient` / `LoginQr`, and the
device credential is real: `auth/PossessionProof` mints heyarr's possession proof
byte-for-byte (pinned by Go-minted golden vectors) and signs it with the phone's
hardware-sealed key (`device/DeviceKeyring` → voidbind-client `DeviceKeyStore`).

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
- **Device-cert login** — now **ships**: the sealed-key possession proof, the pairing
  handshake (`device/`, this phone = relay responder) and re-mint-on-401. What is still
  gated on the *server*: a voidbind-go relay mounted under the node (`<baseUrl>/pair/v1/…`, heyarr-core #421 — the client's relay base is `<baseUrl>/pair`),
  a self-enrol route (`POST /enrol`) or an admin registering the cert
  (`POST /api/v1/identities/devices`), and write scope for a device (heyarr-core #417 +
  `POST /api/v1/session/management-grants`). Completing a pairing needs the owner's
  identity on the other side, so it is proven on-device, not in CI. Honest key tier on
  the Nothing Phone (2a): **TEE**, not StrongBox.
- **On-device personal-state decrypt** — the real `Unwrapper` (X25519 ECDH in-enclave → HKDF →
  the space key) + AEAD decryption of opaque changes, and the local **Personal MCP** (#372/#387)
  that reads the decrypted state. **No crypto ships in this repo.**
- **Subsonic/OPDS/DLNA reach** — deciding whether to ship the compat adapters at all vs. leaning
  on the native API; `library/SubsonicClient` marks the fork as a decision, not an omission.

## License

[GNU AGPL v3](./LICENSE) — matching `rarebit-one/heyarr-core`.
