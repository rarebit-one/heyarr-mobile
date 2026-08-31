# CLAUDE.md — heyarr-mobile

Guidance for Claude Code working in **heyarr-mobile** (part of the `rarebit-one` org).
Read the workspace `~/Workspace/rarebit-one/CLAUDE.md` too — its Critical Rules
(worktree-only, signed commits, autonomous-merge on green CI, issue hygiene) apply here.

## What this is

The **first-party Android client for heyarr** (the self-hosted media platform). It is the
*product* client (plan `~/.claude-family/plans/voidbind-client-apps-and-push.md` §4); the
Subsonic/OPDS/DLNA compat adapters are *reach*, not the product. It signs in via **Voidbind
QR login**, browses heyarr's native library, and is built to hold **device-side personal
state** (decrypt-on-device) — the differentiator over a generic Subsonic app.

This is a **scaffold**: a buildable, tested foundation. Feature work lands as PRs on top.

## The two sibling repos (read before touching auth or the data client)

- **`rarebit-one/heyarr-core`** — the server (PUBLIC / AGPL). Serves the weblogin broker
  (`POST /login`, `GET /login/{id}`), the library/playback APIs (`/api/v1/works`,
  `/api/v1/playback`, `/api/v1/blobs/{hash}/content`), and the encrypted personal-state sync
  surface (`/api/v1/spaces/{id}/{keys,changes,snapshot}`). The **contract** this client builds
  against is `heyarr-core/docs/design/mobile-client.md` (+ ADR-0048 device auth, ADR-0049/0051
  personal state). Adding this client needs **no server change**.
- **`rarebit-one/voidbind-kmp`** — THE Voidbind authenticator + the shared `voidbind-client`
  (`WebLoginClient`, `LoginQr`, `WebLogin`, `LoginApproval`). This app is a *consumption
  client* that delegates login approval to that authenticator.

## Two credential shapes (mobile-client contract, ADR-0048)

- **Primary — `Authorization: Device <cert>~<proof>`** (`auth/Credential.Device`,
  `auth/DeviceCredential`): an enrolled device's user-signed cert + a fresh possession proof,
  joined by `~`, verified **offline**. The proof is signed **in-enclave** by a non-exportable
  Keystore/StrongBox key — that half is **phone-gated** (a documented `Prover` seam, no crypto
  here). Header *formatting* is real + tested.
- **Bootstrap — `Authorization: Bearer <token>`** (`auth/Credential.Session`): a short-lived
  session token from a **QR** web-login, how a fresh install reaches the library before it
  enrols as a device.

## Login is QR (heyarr's channel)

Per the plan's DECISIONS LOG: **heyarr login channel = QR**. The app is the RP/initiator
(`POST /login` → render the `voidbind:login?rp=&id=` tuple as a QR → poll `GET /login/{id}` →
Bearer token). QR *bitmap* rendering and the device-side scan+approve are follow-ups; the
initiator create+poll is real. (Contrast the sibling `allthing-android`, whose channel is
push-approve — do **not** copy FCM/ntfy wiring here; heyarr is QR.)

## voidbind-kmp seam (do not silently fork the wire contract)

voidbind-kmp doesn't publish a Maven artifact yet, so `login/` holds a **wire-compatible
scaffold** of voidbind-kmp's contract (the `voidbind:login?rp=&id=` tuple, `POST /login` +
poll). The invariant: **this seam stays byte-identical to voidbind-kmp / voidbind-go**, so an
extracted `voidbind-client` module drops in cleanly. When you touch `login/`, check it against
voidbind-kmp's `LoginQr`/`WebLoginClient`, and prefer swapping in the real artifact (uncomment
`includeBuild` in `settings.gradle.kts`) over re-deriving.

## Personal state is opaque; decrypt happens ONLY on-device

`personalstate/` fetches `/api/v1/spaces/{id}/{keys,changes,snapshot}` as **opaque
ciphertext** — the peer never decrypts (Invariant 6). Decryption happens on-device under a
space key **unwrapped in-enclave** via the `Unwrapper` seam (the exact interface heyarr-core's
`internal/personalstate/client` takes, ADR-0049). **This repo ships NO crypto**: `Unwrapper`
defaults to fail-closed (`Unavailable`) so nothing silently "decrypts" to plaintext. The real
unwrap + a local **Personal MCP** (#372/#387) are device-gated follow-ups.

## Layout

```
app/src/main/java/one/rarebit/heyarr/mobile/
  MainActivity.kt · AppViewModel.kt · HeyarrConfig.kt
  auth/         Credential (Device/Session) + DeviceCredential (~-join header, Prover seam)
  login/        Voidbind QR-login seam (tuple, mini-json, VoidbindLogin, QrLoginClient, screen)
  library/      LibraryClient (native /api/v1/works) + WorksJson + SubsonicClient stub + screen
  playback/     PlaybackClient (blob-stream target + /playback/plan) + Media3 player
                (HeyarrDataSource auth+Range data source, PlayerScreen, PlaybackTarget/Json)
  personalstate/ PersonalStateClient (opaque spaces sync) + Unwrapper (decrypt-on-device seam)
  net/          HttpTransport interface + OkHttp actual
app/src/test/…  pure-JVM unit tests (no Android runtime)
.github/workflows/android.yml   CI: testDebugUnitTest + assembleDebug on ubuntu-latest
```

## Build / test

```sh
./gradlew testDebugUnitTest      # unit tests — the acceptance bar, CI-run
./gradlew assembleDebug          # debug APK
```

Toolchain (matches `allthing-android` / `voidbind-kmp`, proven-green): **Gradle 8.9, AGP
8.7.3, Kotlin 2.3.20**, compileSdk 35, minSdk 24, JDK 17. `local.properties` (`sdk.dir=…`) is
git-ignored; CI provisions the SDK.

## What's phone-gated (deferred, can't be CI-proven)

On-device playback **acceptance** — the Media3/ExoPlayer player against `/blobs/.../content`
now ships (`playback/`), but a real codec decoding and a scrub's live 206 range reads only
prove out on a device; the `/playback` negotiation (`PlaybackClient.plan`) is wired but keyed
on an enrolled `device_id` — on-device personal-state **decrypt** (Keystore/StrongBox X25519 unwrap + AEAD) and the local
Personal MCP, **device-cert login** (in-enclave Ed25519 possession proof + enrolment), QR
**bitmap** rendering, and choosing whether to ship the Subsonic reach. See the README's
follow-ups. **Keep CI green** — unit tests + `assembleDebug` are the bar; anything needing a
real device stays a device-side follow-up, not a scaffold blocker.
