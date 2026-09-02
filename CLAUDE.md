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
- **`rarebit-one/voidbind-kmp`** — THE Voidbind authenticator app (**Cruciform**, `one.rarebit.cruciform`) + the shared `voidbind-client`
  (`WebLoginClient`, `LoginQr`, `WebLogin`, `LoginApproval`). This app is a *consumption
  client* that delegates login approval to that authenticator.

## Two credential shapes (mobile-client contract, ADR-0048)

- **Primary — `Authorization: Device <cert>~<proof>`** (`auth/Credential.Device`,
  `auth/DeviceCredential`, `auth/PossessionProof`, `auth/DeviceSession`): an enrolled
  device's user-signed cert + a fresh **possession proof**, joined by `~`. The proof is
  a byte-exact port of voidbind-go v0.5.0 `enrolment.SignPossession` (what heyarr-core
  vendors): `base64url({"v":2,"crt":b64url(sha256(cert)),"iat","exp"}) + "." +
  base64url(ed25519 sig)`, no padding, no domain label — pinned by two Go-minted
  golden vectors in `PossessionProofTest`. The signature comes from the phone's
  **hardware-sealed** Ed25519 key (voidbind-client `DeviceKeyStore`, voidbind-kmp
  ADR-0001: software seed sealed by a non-extractable, user-presence-gated AES key —
  StrongBox where present, **TEE on the Nothing Phone**; `device/DeviceKeyring` reports
  the honest tier). `net/DeviceAuthTransport` keeps the proof fresh and re-mints +
  retries **once** on a 401 (heyarr's Device refusals are all an undifferentiated 401).
- **Bootstrap — `Authorization: Bearer <token>`** (`auth/Credential.Session`): a
  short-lived session token from a **QR** web-login, how a fresh install reaches the
  library before it enrols as a device.

## Login is QR (heyarr's channel) — plus the same-phone hand-off

Per the plan's DECISIONS LOG: **heyarr login channel = QR**. The app is the RP/initiator
(`POST /login` → render the `voidbind:login?rp=&id=` tuple as a QR → poll `GET /login/{id}`
→ Bearer token), driven through voidbind-client's `WebLoginClient` (`login/QrLoginClient`
is the app's state machine over it). When Cruciform (the Voidbind authenticator app) is installed on the
**same phone**, an "Approve on this phone" button fires the identical tuple as an
`ACTION_VIEW` intent (`login/VoidbindHandoff`, with a `callback=heyarr-mobile://login`
so it can foreground us) — no second-phone QR dance; the RP is still polled. (Contrast
`allthing-android`, whose channel is push-approve — do **not** copy FCM/ntfy wiring.)

## voidbind-kmp is consumed as the published `voidbind-client` artifact

`one.rarebit.voidbind:voidbind-client:0.2.1` from GitHub Packages (private; needs a
`read:packages` token — `settings.gradle.kts` reads `gpr.user`/`gpr.token` gradle
properties or `GITHUB_ACTOR`/`GITHUB_TOKEN`; CI passes its own token). The library's
minSdk is 33, so ours is too. **Do not re-derive any Voidbind wire format here** —
`LoginQr`, `Cert`, `Invite`, `DevicePairing`, `RelayClient`, `MiniJson`, `Base64Url` are
the library's. The one format the library does not mint is the possession proof
(`auth/PossessionProof`), ported from voidbind-go with golden vectors. For a local
composite build against an unpublished voidbind-kmp change, see the commented
`includeBuild` in `settings.gradle.kts`.

## Enrolment (device/) — this phone is the pairing RESPONDER

`device/DeviceKeyring` owns the keys: the sealed Ed25519 signer (`DeviceKeyStore`, alias
`heyarr-device`, biometric-gated via `device/BiometricGate` — hence `MainActivity` is a
`FragmentActivity`), the X25519 enc key sealed at rest by `device/SealedSecretStore`, and
the stored cert. `device/EnrolScreen` + `AppViewModel` run voidbind-client's
`DevicePairing` (the **new** device / relay responder): this phone opens a relay session on
`HeyarrConfig.effectiveRelayBase` (default `<baseUrl>/pair` — voidbind-client's `RelayClient`
appends the voidbind-go relay wire itself, `POST {base}/v1/sessions`, `PUT|GET
{base}/v1/sessions/{id}/{role}/{type}`, landing on heyarr-core's `/pair/v1/...` mount, #421/ADR-0066), shows the
`voidbind:pair?…` invite as a QR **and** an "Open in Cruciform" hand-off, waits for the
authorising side (the Cruciform app on the same phone, or `voidbind pair-initiate` on a
machine holding the user identity), shows the 7-digit SAS, and on "codes match" receives
the sealed cert. It can also **join** an invite the other side created — the Mac's
`voidbind pair-initiate` QR **scanned with the camera** (`device/QrScanner`: CameraX +
ML Kit, the same stack as voidbind-kmp's androidApp; CAMERA runtime permission) or
pasted — gated by `device/PairInvite` (the library's `Invite.decode`, never a re-derived
parser; non-invites are refused with a reason and the scanner keeps looking). Then
`device/EnrolClient` tries `POST /enrol {cert, proof, name}` (planned self-enrol) and,
if absent, `POST /api/v1/identities/devices` (admin) — surfacing the cert for an operator
when neither works, never pretending. **Known server gaps:** heyarr-core's legacy
`/pair/sessions/{s}/slots/{slot}` relay speaks heyarr's OLD pairflow (not usable by
`DevicePairing`); a device is read-scoped on current `main` until heyarr-core #417 lands
and an admin grants its key (`POST /api/v1/session/management-grants {device_key}`);
`POST /api/v1/devices` is a playback profile, not identity.

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
  MainActivity.kt · AppViewModel.kt · HeyarrConfig.kt (BuildConfig default → Settings override)
  settings/     SettingsStore (SharedPreferences; in-memory for tests) + SettingsScreen
  auth/         Credential (Device/Session) · DeviceCredential (~-join, Prover) · PossessionProof
                (Go-exact proof) · DeviceSession (live proof, re-mint)
  device/       DeviceKeyring (sealed keys + cert) · BiometricGate · SealedSecretStore ·
                EnrolScreen + EnrolClient (pairing responder, registration) · HandoffLauncher
  login/        QR login over voidbind-client (LoginTuple façade, QrLoginClient, VoidbindHandoff, screen)
  library/      LibraryClient (native /api/v1/works) + WorksJson + SubsonicClient stub + screen
  playback/     PlaybackClient (blob-stream target + /playback/plan) + Media3 player
                (HeyarrDataSource auth+Range data source, PlayerScreen, PlaybackTarget/Json)
  personalstate/ PersonalStateClient (opaque spaces sync) + Unwrapper (decrypt-on-device seam)
  net/          HttpTransport + OkHttp actual · DeviceAuthTransport (Device re-mint/retry) ·
                OkHttpVoidbindTransport (voidbind-client's seam) · JsonEscapes
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
