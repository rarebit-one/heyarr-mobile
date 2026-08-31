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

/** A resolved item the player is showing: its stream target and a display title. */
data class NowPlaying(val target: PlaybackTarget, val title: String)

/**
 * Drives the QR login, holds the resulting Bearer session token as a [Credential],
 * then loads the library with it. Blocking transport calls run on [Dispatchers.IO];
 * the UI observes [loginState] and [libraryState].
 */
class AppViewModel(
    val config: HeyarrConfig = HeyarrConfig(),
    private val login: VoidbindLogin = QrLoginClient(
        http = OkHttpTransport(),
        rpBase = config.baseUrl,
    ),
) : ViewModel() {

    private val transport = OkHttpTransport()

    /** Shared OkHttp client for the Media3 blob-stream data source. */
    val httpClient: OkHttpClient = OkHttpClient()

    private val _loginState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val loginState: StateFlow<LoginUiState> = _loginState.asStateFlow()

    private val _libraryState = MutableStateFlow<LibraryUiState>(LibraryUiState.Loading)
    val libraryState: StateFlow<LibraryUiState> = _libraryState.asStateFlow()

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    /** A transient notice when an item cannot be streamed directly (negotiation-gated). */
    private val _playbackNotice = MutableStateFlow<String?>(null)
    val playbackNotice: StateFlow<String?> = _playbackNotice.asStateFlow()

    private var credential: Credential? = null

    /**
     * The credential established by QR login (a Bearer session, later a device cert),
     * or null before sign-in. Exposed so the search/acquire/following features can be
     * driven with the same authenticated identity that browses the library.
     */
    fun credentialOrNull(): Credential? = credential

    fun signIn() {
        if (_loginState.value is LoginUiState.AwaitingScan) return
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
                    loadLibrary()
                }
                is VoidbindLogin.Result.Denied -> _loginState.value = LoginUiState.Error(result.reason)
                is VoidbindLogin.Result.Failed -> _loginState.value = LoginUiState.Error(result.error)
            }
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
