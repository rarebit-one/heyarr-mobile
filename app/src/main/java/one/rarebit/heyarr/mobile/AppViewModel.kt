package one.rarebit.heyarr.mobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import one.rarebit.heyarr.mobile.auth.Credential
import one.rarebit.heyarr.mobile.library.LibraryClient
import one.rarebit.heyarr.mobile.library.LibraryUiState
import one.rarebit.heyarr.mobile.library.Work
import one.rarebit.heyarr.mobile.login.LoginUiState
import one.rarebit.heyarr.mobile.login.QrLoginClient
import one.rarebit.heyarr.mobile.login.VoidbindLogin
import one.rarebit.heyarr.mobile.net.OkHttpTransport
import one.rarebit.heyarr.mobile.playback.PlaybackClient
import one.rarebit.heyarr.mobile.playback.PlaybackTarget
import one.rarebit.heyarr.mobile.search.SessionAuthority
import one.rarebit.heyarr.mobile.search.SessionClient
import one.rarebit.heyarr.mobile.settings.InMemorySettingsStore
import one.rarebit.heyarr.mobile.settings.SettingsStore

/** A resolved item the player is showing: its stream target and a display title. */
data class NowPlaying(val target: PlaybackTarget, val title: String)

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
) : ViewModel() {

    private val transport = OkHttpTransport()

    /** Shared OkHttp client for the Media3 blob-stream data source. */
    val httpClient: OkHttpClient = OkHttpClient()

    private val _config = MutableStateFlow(resolveConfig())
    val configState: StateFlow<HeyarrConfig> = _config.asStateFlow()

    /** The effective config right now (build default + saved overrides). */
    val config: HeyarrConfig get() = _config.value

    private val _loginState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val loginState: StateFlow<LoginUiState> = _loginState.asStateFlow()

    private val _libraryState = MutableStateFlow<LibraryUiState>(LibraryUiState.Loading)
    val libraryState: StateFlow<LibraryUiState> = _libraryState.asStateFlow()

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

    /** Close the player and release its target. */
    fun stopPlayback() {
        _nowPlaying.value = null
    }

    /** Clear the transient "cannot stream directly" notice once shown. */
    fun clearPlaybackNotice() {
        _playbackNotice.value = null
    }

    private fun loadLibrary() {
        val cred = credential ?: return
        _libraryState.value = LibraryUiState.Loading
        viewModelScope.launch {
            val state = withContext(Dispatchers.IO) {
                runCatching {
                    val works = LibraryClient(transport, config.baseUrl, cred).listWorks()
                    LibraryUiState.Loaded(works)
                }.getOrElse { LibraryUiState.Error(it.message ?: "failed to load library") }
            }
            _libraryState.value = state
        }
    }
}
