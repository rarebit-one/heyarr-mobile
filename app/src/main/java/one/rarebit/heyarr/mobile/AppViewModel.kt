package one.rarebit.heyarr.mobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.rarebit.heyarr.mobile.auth.Credential
import one.rarebit.heyarr.mobile.library.LibraryClient
import one.rarebit.heyarr.mobile.library.LibraryUiState
import one.rarebit.heyarr.mobile.login.LoginUiState
import one.rarebit.heyarr.mobile.login.QrLoginClient
import one.rarebit.heyarr.mobile.login.VoidbindLogin
import one.rarebit.heyarr.mobile.net.OkHttpTransport

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

    private val _loginState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val loginState: StateFlow<LoginUiState> = _loginState.asStateFlow()

    private val _libraryState = MutableStateFlow<LibraryUiState>(LibraryUiState.Loading)
    val libraryState: StateFlow<LibraryUiState> = _libraryState.asStateFlow()

    private var credential: Credential? = null

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
