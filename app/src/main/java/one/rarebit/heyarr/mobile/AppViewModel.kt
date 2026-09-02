package one.rarebit.heyarr.mobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import one.rarebit.heyarr.mobile.auth.Credential
import one.rarebit.heyarr.mobile.device.DeviceKeyInfo
import one.rarebit.heyarr.mobile.device.DeviceKeyring
import one.rarebit.heyarr.mobile.device.EnrolClient
import one.rarebit.heyarr.mobile.device.EnrolUiState
import one.rarebit.heyarr.mobile.device.InMemoryPendingPairingStore
import one.rarebit.heyarr.mobile.device.MembershipClient
import one.rarebit.heyarr.mobile.device.MembershipOps
import one.rarebit.heyarr.mobile.device.PairInvite
import one.rarebit.heyarr.mobile.device.PairingCoordinator
import one.rarebit.heyarr.mobile.device.PairingState
import one.rarebit.heyarr.mobile.device.PairingSteps
import one.rarebit.heyarr.mobile.library.LibraryClient
import one.rarebit.heyarr.mobile.library.LibraryUiState
import one.rarebit.heyarr.mobile.library.Work
import one.rarebit.heyarr.mobile.library.WorkAsset
import one.rarebit.heyarr.mobile.login.LoginUiState
import one.rarebit.heyarr.mobile.login.QrLoginClient
import one.rarebit.heyarr.mobile.login.VoidbindLogin
import one.rarebit.heyarr.mobile.net.DeviceAuthTransport
import one.rarebit.heyarr.mobile.net.HttpTransport
import one.rarebit.heyarr.mobile.net.OkHttpTransport
import one.rarebit.heyarr.mobile.net.OkHttpVoidbindTransport
import one.rarebit.heyarr.mobile.playback.ClientCapabilities
import one.rarebit.heyarr.mobile.playback.PlaybackClient
import one.rarebit.heyarr.mobile.playback.PlaybackDiagnostics
import one.rarebit.heyarr.mobile.playback.PlaybackTarget
import one.rarebit.heyarr.mobile.search.SessionAuthority
import one.rarebit.heyarr.mobile.search.SessionClient
import one.rarebit.heyarr.mobile.settings.InMemorySettingsStore
import one.rarebit.heyarr.mobile.settings.SettingsStore
import one.rarebit.voidbind.Membership
import one.rarebit.voidbind.MembershipOp
import one.rarebit.voidbind.auth.DeviceCredential
import one.rarebit.voidbind.auth.PossessionProof
import one.rarebit.voidbind.flow.PairingFailureKind
import one.rarebit.voidbind.flow.PairingOutcome

/**
 * A resolved item the player is showing: its stream target, a display title, the asset
 * it came from (so a decoder the phone lacks can be re-planned into a stream, once),
 * and a banner the app decided on after that re-plan.
 */
data class NowPlaying(
    val target: PlaybackTarget,
    val title: String,
    val assetId: String? = null,
    val blobHash: String? = null,
    val banner: String? = null,
    val replanned: Boolean = false,
)

/** The steps of a ViewModel built without the app's holder (tests): every pairing fails honestly. */
private object UnavailablePairingSteps : PairingSteps {
    private val failed = PairingOutcome.Failed(PairingFailureKind.PROTOCOL, "pairing is not available in this build", "")
    override suspend fun handshake(inviteQr: String, deadlineMillis: Long): PairingOutcome<String> = failed
    override suspend fun receive(deadlineMillis: Long): PairingOutcome<String> = failed
    override suspend fun register(op: String): EnrolClient.Outcome = EnrolClient.Outcome.Failed("pairing is not available in this build")
}

/**
 * Drives the QR login, holds the resulting Bearer session token as a [Credential],
 * then loads the library with it. Blocking transport calls run on [Dispatchers.IO];
 * the UI observes [loginState] and [libraryState].
 *
 * The effective [config] is resolved from the build default plus the runtime
 * overrides in [settings] ([HeyarrConfig.resolve]); [updateSettings] re-resolves it
 * and, when the base URL changed, signs out (a session token is only good for the
 * node that minted it).
 */
class AppViewModel(
    private val settings: SettingsStore = InMemorySettingsStore(),
    private val loginFactory: (baseUrl: String) -> VoidbindLogin = { base ->
        QrLoginClient(http = OkHttpTransport(), rpBase = base)
    },
    /**
     * The app-scoped pairing holder ([one.rarebit.heyarr.mobile.HeyarrApp.pairing] on
     * the phone): the join → SAS → admission → `/enrol` pipeline outlives this
     * ViewModel; it only observes and drives it. The default cannot pair (tests).
     */
    private val pairing: PairingCoordinator = PairingCoordinator(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        store = InMemoryPendingPairingStore(),
        steps = { UnavailablePairingSteps },
    ),
) : ViewModel() {

    /**
     * Once this phone is enrolled, its live `Device` credential (cert + the possession
     * proof in force). Null while signed in with a QR session, or before enrolment.
     */
    @Volatile
    private var deviceCredential: DeviceCredential? = null

    /** The raw transport — what the public, unauthenticated membership read uses. */
    private val rawTransport: HttpTransport = OkHttpTransport()

    /**
     * The app's transport: every `/api/v1` request an enrolled device makes goes through
     * [DeviceAuthTransport], which keeps the `Device` credential fresh, presents the
     * membership ops this device knows (`Voidbind-Membership`, ADR-0005) and re-mints +
     * retries once on a 401 (mobile-client constraint 2) — after [refreshMembership]
     * has had its say: a device that learns it was removed does not retry.
     */
    val transport: HttpTransport = DeviceAuthTransport(
        rawTransport,
        credential = { deviceCredential },
        membership = { keyring?.let { MembershipOps.headerValue(it.knownOps(), it.certToken()) } },
        onUnauthorized = ::refreshMembership,
    )

    /** Shared OkHttp client for the Media3 blob-stream data source. */
    val httpClient: OkHttpClient = OkHttpClient()

    /**
     * What this phone can decode, for `POST /playback/plan` (heyarr-core #432). Set by
     * the Activity from `MediaCodecList`; null (tests, or before attach) means "don't
     * plan — stream the blob directly, exactly as before the contract".
     */
    var capabilities: ClientCapabilities? = null

    private val _config = MutableStateFlow(resolveConfig())
    val configState: StateFlow<HeyarrConfig> = _config.asStateFlow()

    /** The effective config right now (build default + saved overrides). */
    val config: HeyarrConfig get() = _config.value

    private val _loginState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val loginState: StateFlow<LoginUiState> = _loginState.asStateFlow()

    private val _libraryState = MutableStateFlow<LibraryUiState>(LibraryUiState.Loading)
    val libraryState: StateFlow<LibraryUiState> = _libraryState.asStateFlow()

    /** True while a pull-to-refresh reload of the library is in flight (the list stays shown). */
    private val _libraryRefreshing = MutableStateFlow(false)
    val libraryRefreshing: StateFlow<Boolean> = _libraryRefreshing.asStateFlow()

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    /** A transient notice when an item cannot be streamed directly (negotiation-gated). */
    private val _playbackNotice = MutableStateFlow<String?>(null)
    val playbackNotice: StateFlow<String?> = _playbackNotice.asStateFlow()

    /**
     * This session's authority as `GET /api/v1/session` reports it (who we are signed
     * in as, and whether the session is read-only). Null until loaded or if the read
     * failed — the UI treats unknown as read-only, the safe floor.
     */
    private val _sessionAuthority = MutableStateFlow<SessionAuthority?>(null)
    val sessionAuthority: StateFlow<SessionAuthority?> = _sessionAuthority.asStateFlow()

    private var credential: Credential? = null

    /**
     * The credential established by QR login (a Bearer session, later a device cert),
     * or null before sign-in. Exposed so the search/acquire/following features can be
     * driven with the same authenticated identity that browses the library.
     */
    fun credentialOrNull(): Credential? = credential

    private fun resolveConfig(): HeyarrConfig =
        HeyarrConfig.resolve(settings.baseUrlOverride, settings.qualityProfileOverride)

    /**
     * Persist new overrides (a value equal to the build default is stored as "no
     * override") and re-resolve [config]. A changed base URL signs out.
     */
    fun updateSettings(baseUrl: String, qualityProfile: String) {
        val normalized = HeyarrConfig.normalizeBaseUrl(baseUrl)
        settings.baseUrlOverride = normalized?.takeIf { it != HeyarrConfig.DEFAULT_BASE_URL }
        settings.qualityProfileOverride =
            qualityProfile.trim().takeIf { it.isNotEmpty() && it != HeyarrConfig.DEFAULT_QUALITY_PROFILE }
        applyConfig(resolveConfig())
    }

    /** Clear all overrides back to the build defaults. */
    fun resetSettings() {
        settings.baseUrlOverride = null
        settings.qualityProfileOverride = null
        applyConfig(resolveConfig())
    }

    private fun applyConfig(next: HeyarrConfig) {
        val baseChanged = next.baseUrl != _config.value.baseUrl
        _config.value = next
        if (baseChanged) signOut()
    }

    /** Drop the session and return to the login screen. */
    fun signOut() {
        credential = null
        deviceCredential = null
        _sessionAuthority.value = null
        _nowPlaying.value = null
        _libraryState.value = LibraryUiState.Loading
        _loginState.value = LoginUiState.Idle
    }

    fun signIn() {
        if (_loginState.value is LoginUiState.AwaitingScan) return
        val login = loginFactory(config.baseUrl)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val pending = login.begin()
                    _loginState.value = LoginUiState.AwaitingScan(pending.qrTuple)
                    login.awaitApproval(pending)
                }.getOrElse { VoidbindLogin.Result.Failed(it.message ?: "login error") }
            }
            when (result) {
                is VoidbindLogin.Result.Approved -> {
                    // The QR bootstrap yields a Bearer session token (auth/Credential.Session).
                    credential = Credential.Session(result.sessionToken)
                    _loginState.value = LoginUiState.Approved(result.user)
                    loadSessionAuthority()
                    loadLibrary()
                }
                is VoidbindLogin.Result.Denied -> _loginState.value = LoginUiState.Error(result.reason)
                is VoidbindLogin.Result.Failed -> _loginState.value = LoginUiState.Error(result.error)
            }
        }
    }

    /** Introspect the session (`GET /api/v1/session`) for the signed-in / read-only banner. */
    fun loadSessionAuthority() {
        val cred = credential ?: return
        viewModelScope.launch {
            val next = withContext(Dispatchers.IO) {
                runCatching { SessionClient(transport, config.baseUrl, cred).authority() }.getOrNull()
            }
            _sessionAuthority.value = next
        }
    }

    // ── Device enrolment (voidbind-client DeviceKeyStore + DevicePairing) ─────────

    private var keyring: DeviceKeyring? = null

    private val _enrolState = MutableStateFlow<EnrolUiState>(EnrolUiState.Loading)
    val enrolState: StateFlow<EnrolUiState> = _enrolState.asStateFlow()

    /** This phone's device keys (key, honest tier, cert), once read. */
    private val _deviceInfo = MutableStateFlow<DeviceKeyInfo?>(null)
    val deviceInfo: StateFlow<DeviceKeyInfo?> = _deviceInfo.asStateFlow()

    /** True while [enrolState] is a projection of the coordinator's (non-idle) state. */
    private var showingPairing = false

    init {
        // The Enrol screen's state is a projection of the app-scoped pairing wherever
        // one is in flight or just ended; the resting states (keys / no keys / adopted)
        // are this ViewModel's own.
        viewModelScope.launch { pairing.state.collect { reflectPairing(it) } }
    }

    /** What the Enrol screen shows when no pairing is in flight. */
    private fun restingState(info: DeviceKeyInfo? = _deviceInfo.value): EnrolUiState = when {
        info == null -> EnrolUiState.Unprovisioned
        info.certToken != null -> EnrolUiState.Enrolled(info, "This device holds an admission.", needsAdmin = false)
        else -> EnrolUiState.Ready(info)
    }

    private suspend fun reflectPairing(ps: PairingState) {
        val info = _deviceInfo.value
        when (ps) {
            PairingState.Idle -> if (showingPairing) {
                showingPairing = false
                _enrolState.value = restingState()
            }
            is PairingState.Joining -> {
                info ?: return
                showingPairing = true
                _enrolState.value = EnrolUiState.Joining(info, ps.inviteQr, ps.sameDevice, ps.deadlineMillis)
            }
            is PairingState.CompareSas -> {
                info ?: return
                showingPairing = true
                _enrolState.value = EnrolUiState.CompareSas(info, ps.sas, ps.sameDevice, ps.deadlineMillis, ps.awaitingAdmission)
            }
            is PairingState.Registering -> {
                info ?: return
                showingPairing = true
                _enrolState.value = EnrolUiState.Registering(info)
            }
            is PairingState.Enrolled -> {
                showingPairing = true
                val ring = keyring
                val fresh = if (ring != null) withContext(Dispatchers.IO) { runCatching { ring.info() }.getOrNull() } else null
                val shown = fresh ?: info ?: return
                _deviceInfo.value = shown
                _enrolState.value = EnrolUiState.Enrolled(shown, ps.registration, ps.needsAdmin, retriable = ps.retriable)
            }
            is PairingState.Failed -> {
                showingPairing = true
                _enrolState.value = EnrolUiState.Error(info, ps.message, ps.kind)
            }
        }
    }

    /**
     * An invite that arrived by deep link from Cruciform on this phone
     * (`heyarr-mobile://pair?invite=…`, voidbind-kmp ADR-0006) while this phone could
     * not join it yet — no device key (the user must create one, which prompts for a
     * fingerprint) or the keys still being read. Joined automatically as soon as the
     * phone is [EnrolUiState.Ready]; shown on the Enrol screen meanwhile so the user
     * knows why they are being asked for a key. Cleared on join, forget, or a fresh link.
     */
    private val _parkedInvite = MutableStateFlow<String?>(null)
    val parkedInvite: StateFlow<String?> = _parkedInvite.asStateFlow()

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    /**
     * Attach the phone's [DeviceKeyring] (needs an Activity for the biometric prompt).
     * Reads the device keys — provisioning them on first run, which shows the prompt —
     * and, if a cert is already stored, adopts the Device credential straight away so
     * an enrolled phone never falls back to the QR session.
     */
    fun attachDevice(ring: DeviceKeyring) {
        keyring = ring
        if (deviceCredential != null) return
        viewModelScope.launch {
            // peek(): never provisions, so a fresh install does not open with a biometric prompt.
            val result = withContext(Dispatchers.IO) { runCatching { ring.peek() } }
            result.onSuccess { info ->
                _deviceInfo.value = info
                when {
                    info == null -> _enrolState.value = EnrolUiState.Unprovisioned
                    info.certToken != null -> adoptDevice(ring, info.certToken)
                    else -> {
                        _enrolState.value = EnrolUiState.Ready(info)
                        // A pairing already in flight / just ended in the app-scoped holder
                        // (a recreation, or a restart reporting an interrupted one) wins.
                        if (pairing.state.value !is PairingState.Idle) reflectPairing(pairing.state.value)
                        continueParkedInvite()
                    }
                }
            }.onFailure {
                _enrolState.value = EnrolUiState.Error(null, "device key unavailable: ${it.message}")
            }
        }
    }

    /**
     * An invite handed to us by Cruciform on this phone (the `heyarr-mobile://pair` deep
     * link). Already validated by [one.rarebit.heyarr.mobile.device.PairDeepLink] through
     * the library's parser; re-checked in [joinPairing] regardless. Joins straight away
     * when this phone has an unenrolled device key; otherwise **parks** it — a fresh
     * install first needs its key created (a fingerprint prompt the user must answer,
     * so it is never auto-triggered by a link), and a phone still reading its keys
     * continues when the read lands ([attachDevice]). An already-enrolled phone refuses:
     * it holds an admission, and only the user can choose to forget it.
     */
    fun receiveInviteLink(inviteQr: String) {
        val invite = when (val checked = PairInvite.check(inviteQr)) {
            is PairInvite.Valid -> checked.inviteQr
            is PairInvite.Invalid -> {
                _enrolState.value = EnrolUiState.Error(_deviceInfo.value, checked.message)
                return
            }
        }
        // A new link supersedes any in-flight join of an older one; the SAME link (Android
        // re-delivers the launching intent on a recreation) is a no-op in the coordinator.
        when (val state = _enrolState.value) {
            is EnrolUiState.Ready -> {
                _parkedInvite.value = null
                joinPairing(invite, sameDevice = true)
            }
            is EnrolUiState.Enrolled -> {
                _parkedInvite.value = null
                _enrolState.value = EnrolUiState.Error(
                    state.info,
                    "This phone is already enrolled as a device. Forget the enrolment first if you want " +
                        "to join a new invite.",
                )
            }
            else -> {
                // Unprovisioned / Loading / Joining / CompareSas / Removed / Error: park it and
                // put the screen where the user can act (create the key, or retry).
                _parkedInvite.value = invite
                when (state) {
                    is EnrolUiState.Joining, is EnrolUiState.CompareSas -> {
                        val info = _deviceInfo.value
                        if (info != null) joinPairing(invite, sameDevice = true) else _enrolState.value = EnrolUiState.Unprovisioned
                    }
                    is EnrolUiState.Error -> _enrolState.value =
                        if (state.info == null) EnrolUiState.Unprovisioned else EnrolUiState.Ready(state.info).also { continueParkedInvite() }
                    else -> Unit
                }
            }
        }
    }

    /** Join the parked invite, if any, now that the phone is [EnrolUiState.Ready]. */
    private fun continueParkedInvite() {
        val invite = _parkedInvite.value ?: return
        if (_enrolState.value !is EnrolUiState.Ready) return
        _parkedInvite.value = null
        joinPairing(invite, sameDevice = true)
    }

    /** A `heyarr-mobile://pair` link that was ours but unusable: say so on the Enrol screen. */
    fun rejectInviteLink(message: String) {
        _enrolState.value = EnrolUiState.Error(_deviceInfo.value, message)
    }

    /** The user dismissed a parked invite without joining it. */
    fun discardParkedInvite() {
        _parkedInvite.value = null
    }

    /** First run: generate + seal the device keys (shows the user-presence prompt). */
    fun provisionDevice() {
        val ring = keyring ?: return
        viewModelScope.launch {
            _enrolState.value = EnrolUiState.Loading
            val result = withContext(Dispatchers.IO) { runCatching { ring.info() } }
            result.onSuccess { info ->
                _deviceInfo.value = info
                _enrolState.value = EnrolUiState.Ready(info)
                if (pairing.state.value !is PairingState.Idle) reflectPairing(pairing.state.value)
                continueParkedInvite()
            }.onFailure {
                _enrolState.value = EnrolUiState.Error(null, "could not create the device key: ${it.message}")
            }
        }
    }

    /** Switch the app to the Device credential for [certToken] (the admitting op): mint a proof, load the library. */
    private suspend fun adoptDevice(ring: DeviceKeyring, certToken: String) {
        val adopted = withContext(Dispatchers.IO) {
            runCatching {
                val identity = ring.identity()
                val live = DeviceCredential(
                    certToken = certToken,
                    signer = identity.asSigner(),
                    clock = { System.currentTimeMillis() / 1000 },
                    ttlSeconds = DEVICE_PROOF_TTL_SECONDS,
                    reuseForSeconds = DEVICE_PROOF_REUSE_SECONDS,
                )
                val first = live.current() // mints the first proof — may prompt
                deviceCredential = live
                Credential.Device(first.cert, first.proof)
            }
        }
        adopted.onSuccess { cred ->
            credential = cred
            _loginState.value = LoginUiState.Approved(user = null)
            _enrolState.value = EnrolUiState.Enrolled(
                info = _deviceInfo.value ?: withContext(Dispatchers.IO) { ring.info() },
                registration = "Signed in with this device's admission.",
                needsAdmin = false,
            )
            loadSessionAuthority()
            loadLibrary()
        }.onFailure {
            _enrolState.value = EnrolUiState.Error(_deviceInfo.value, "could not sign with the device key: ${it.message}")
        }
    }

    /**
     * Join a pairing a member device started — the v3 `voidbind:pair?…` invite Cruciform
     * or the Mac's `voidbind pair-initiate` rendered, scanned with the camera or pasted.
     * (Under ADR-0005 only a member can mint an invite — it names the identity — so
     * this phone, the NEW device, never opens the session itself.) Re-checked here
     * through the library's parser ([PairInvite]) even though the screen already did,
     * so a caller can never push a non-invite into the handshake.
     */
    fun joinPairing(inviteQr: String) = joinPairing(inviteQr, sameDevice = false)

    /**
     * [sameDevice] marks an invite that came from Cruciform on THIS phone (the deep
     * link), so the SAS screen tells the user to switch back to Cruciform to compare
     * and confirm there, rather than to look at "the other device".
     */
    private fun joinPairing(inviteQr: String, sameDevice: Boolean) {
        if (keyring == null) return
        val info = _deviceInfo.value ?: return
        val invite = when (val checked = PairInvite.check(inviteQr)) {
            is PairInvite.Valid -> checked.inviteQr
            is PairInvite.Invalid -> {
                _enrolState.value = EnrolUiState.Error(info, checked.message)
                return
            }
        }
        // The pipeline runs in the app-scoped holder, keyed by the invite's session id;
        // this ViewModel's enrolState follows it (reflectPairing).
        pairing.start(invite, sameDevice)
    }

    /**
     * The human saw the SAME code on both screens. The holder then waits — up to the
     * relay session's TTL, SAS still on screen — for the admission Cruciform seals to
     * this device after the human confirms THERE, stores both halves (the op is the
     * credential token, the ops the replica) and registers at the node presenting
     * those ops (`POST /enrol`).
     */
    fun confirmSas() = pairing.confirmMatch()

    /** The codes differ — abort; nothing was signed or received. */
    fun rejectSas() = pairing.rejectMatch()

    /** Give up on the pairing in flight (the relay wait) and go back to the resting screen. */
    fun cancelPairing() = pairing.cancel()

    /** `POST /enrol` again for a stored admission the node has not accepted (e.g. the proof could not be signed in the background). */
    fun registerDevice() = pairing.retryRegister()

    fun retryEnrol() {
        pairing.dismiss()
        showingPairing = false
        _enrolState.value = restingState()
    }

    /**
     * After a `401` on a Device request, before the single re-mint + retry
     * ([DeviceAuthTransport.onUnauthorized]): re-read the identity's membership from
     * the node (`GET /membership/{usr}`, public; a node without it — 404 — teaches
     * nothing and the retry goes ahead), merge it into this device's replica, and
     * evaluate. A device the ops no longer find a member — another member removed
     * it, or its add lapsed — drops its Device credential, moves to the honest
     * [EnrolUiState.Removed] and returns `false`: the 401 stands and nothing loops.
     * Runs on the transport's (IO) thread.
     */
    private fun refreshMembership(): Boolean {
        val ring = keyring ?: return true
        val own = ring.certToken() ?: return true
        val usr = ring.userId() ?: return true
        val remote = runCatching { MembershipClient(rawTransport, config.baseUrl).fetch(usr) }.getOrNull() ?: return true
        val merged = Membership.merge(ring.knownOps(), remote)
        runCatching { ring.saveOps(merged) }
        val view = runCatching { Membership.evaluate(usr, merged, nowSeconds()) }.getOrNull() ?: return true
        val self = runCatching { MembershipOp.verify(own).device }.getOrNull() ?: return true
        if (view.isMember(self)) return true

        val why = when {
            self in view.removed -> "Another member of your identity removed this device."
            view.rejected[MembershipOp.hash(own)]?.contains("expired") == true ||
                view.ineffective[MembershipOp.hash(own)]?.contains("expired") == true ->
                "This device's admission has expired."
            else -> "This device is no longer a member of the identity " +
                "(${view.rejected[MembershipOp.hash(own)] ?: view.ineffective[MembershipOp.hash(own)] ?: "not admitted"})."
        }
        deviceCredential = null
        credential = null
        _deviceInfo.value = runCatching { ring.info() }.getOrNull() ?: _deviceInfo.value
        _enrolState.value = EnrolUiState.Removed(_deviceInfo.value ?: return false, why)
        _loginState.value = LoginUiState.Error("This device was removed from your Voidbind identity. $why")
        return false
    }

    /** After enrolment: start using the Device credential now. */
    fun useDeviceCredential() {
        val ring = keyring ?: return
        val cert = _deviceInfo.value?.certToken ?: return
        pairing.dismiss()
        showingPairing = false
        viewModelScope.launch { adoptDevice(ring, cert) }
    }

    /** Drop the stored admission (keys stay) and fall back to QR login. */
    fun forgetDevice() {
        val ring = keyring ?: return
        pairing.cancel()
        showingPairing = false
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { ring.clearCert() } }
            deviceCredential = null
            _parkedInvite.value = null
            val info = withContext(Dispatchers.IO) { runCatching { ring.info() }.getOrNull() }
            _deviceInfo.value = info
            _enrolState.value = if (info != null) EnrolUiState.Ready(info) else EnrolUiState.Unprovisioned
            signOut()
        }
    }

    /** A human-readable name for the node's device registry. */
    var deviceName: String = "heyarr-mobile"

    private companion object {
        /**
         * How long one possession proof is reused before the app re-signs. Each signature
         * unseals the device key behind a 30 s user-presence window, so the Go default
         * (2 min) would mean a biometric prompt every couple of minutes of use; the server
         * checks `exp` strictly but does not cap it. Re-minted early on a 401 regardless.
         */
        const val DEVICE_PROOF_TTL_SECONDS = 60L * 60

        /**
         * How long the live proof is REUSED before a proactive re-mint: until it is within
         * the server's skew window of expiry (the library default, spelled out here so the
         * biometric cadence is a deliberate app choice, not an inherited one).
         */
        const val DEVICE_PROOF_REUSE_SECONDS = DEVICE_PROOF_TTL_SECONDS - PossessionProof.SKEW_SECONDS
    }

    /**
     * Open the player for a tapped [work]. A row that carries a content hash streams
     * directly over the authenticated, range-capable blob endpoint (the M10 path); one
     * without a hash needs the enrolment-gated plan negotiation, so we surface a notice
     * rather than opening an empty player.
     */
    fun play(work: Work) {
        val cred = credential ?: return
        val hash = work.blobHash
        if (hash.isNullOrBlank()) {
            _playbackNotice.value =
                "“${work.title}” has no directly-streamable asset yet — playback " +
                    "negotiation is enrolment-gated (device auth)."
            return
        }
        val isVideo = PlaybackTarget.looksLikeVideo(work.mime, work.kind)
        val target = PlaybackClient(transport, config.baseUrl, cred).blobTarget(hash, isVideo, work.mime)
        _nowPlaying.value = NowPlaying(target = target, title = work.title)
    }

    /**
     * Play one of a work's files from the detail screen: the asset's own blob hash and
     * MIME drive the stream target (the M10 direct path over `/blobs/{hash}/content`).
     */
    fun playAsset(work: Work, asset: WorkAsset) {
        val cred = credential ?: return
        val hash = asset.blobHash
        if (hash.isNullOrBlank()) {
            _playbackNotice.value = "“${asset.filename ?: work.title}” has no blob to stream (a linked asset has none)."
            return
        }
        val mime = asset.mime ?: work.mime
        val isVideo = PlaybackTarget.looksLikeVideo(mime, work.kind)
        val title = asset.filename?.let { "${work.title} — $it" } ?: work.title
        val client = PlaybackClient(transport, config.baseUrl, cred)
        val caps = capabilities
        if (caps == null) {
            _nowPlaying.value = NowPlaying(target = client.blobTarget(hash, isVideo, mime), title = title, assetId = asset.id, blobHash = hash)
            return
        }
        // Plan first (heyarr-core #432): the node may repackage for this phone. A node
        // that predates the contract answers 400 and `resolve` falls back to the blob.
        viewModelScope.launch {
            val target = withContext(Dispatchers.IO) {
                runCatching { client.resolve(asset.id, hash, isVideo, mime, caps) }
                    .getOrElse { client.blobTarget(hash, isVideo, mime).copy(reason = it.message) }
            }
            _nowPlaying.value = NowPlaying(target = target, title = title, assetId = asset.id, blobHash = hash)
        }
    }

    /**
     * The player found a renderer type Media3 can't decode (e.g. AC-3 5.1). If the node
     * spoke the plan contract and judged the blob direct, ask ONCE more with that codec
     * struck off — a `stream` answer swaps the target; anything else leaves an honest
     * banner. A node that never spoke the contract has nothing to offer: the player's
     * own "not available yet" banner already says so.
     */
    fun onPlaybackIssue(issue: PlaybackDiagnostics.Issue) {
        val playing = _nowPlaying.value ?: return
        val cred = credential ?: return
        val caps = capabilities ?: return
        if (playing.target.origin != PlaybackTarget.Origin.DIRECT_PLANNED || playing.replanned) return
        val assetId = playing.assetId ?: return
        val hash = playing.blobHash ?: return
        val codec = issue.codec ?: return
        val client = PlaybackClient(transport, config.baseUrl, cred)
        viewModelScope.launch {
            val replanned = withContext(Dispatchers.IO) {
                runCatching { client.resolve(assetId, hash, playing.target.isVideo, playing.target.mimeType, caps.without(codec)) }.getOrNull()
            }
            if (_nowPlaying.value !== playing) return@launch // the user moved on
            if (replanned != null && replanned.origin == PlaybackTarget.Origin.STREAM) {
                _nowPlaying.value = playing.copy(target = replanned, replanned = true)
            } else {
                _nowPlaying.value = playing.copy(banner = PlaybackDiagnostics.afterReplanFailed(issue), replanned = true)
            }
        }
    }

    /** Close the player and release its target. */
    fun stopPlayback() {
        _nowPlaying.value = null
    }

    /** Clear the transient "cannot stream directly" notice once shown. */
    fun clearPlaybackNotice() {
        _playbackNotice.value = null
    }

    /** Pull-to-refresh: reload the library, keeping the current list on screen meanwhile. */
    fun refreshLibrary() = loadLibrary(keepShowing = true)

    private fun loadLibrary(keepShowing: Boolean = false) {
        val cred = credential ?: return
        if (!keepShowing || _libraryState.value !is LibraryUiState.Loaded) _libraryState.value = LibraryUiState.Loading
        _libraryRefreshing.value = true
        viewModelScope.launch {
            val state = withContext(Dispatchers.IO) {
                runCatching {
                    val works = LibraryClient(transport, config.baseUrl, cred).listWorks()
                    LibraryUiState.Loaded(works)
                }.getOrElse { LibraryUiState.Error(it.message ?: "failed to load library") }
            }
            _libraryState.value = state
            _libraryRefreshing.value = false
        }
    }
}
