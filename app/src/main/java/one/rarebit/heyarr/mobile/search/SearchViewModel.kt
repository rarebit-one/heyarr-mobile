package one.rarebit.heyarr.mobile.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.rarebit.heyarr.mobile.HeyarrConfig
import one.rarebit.heyarr.mobile.auth.Credential
import one.rarebit.heyarr.mobile.net.HttpTransport
import one.rarebit.heyarr.mobile.net.OkHttpTransport

/**
 * Drives the Search + Subscribe/One-off + Following screens against heyarr's **live**
 * M12 routes: search on `POST /api/v1/search` ([SearchClient]); **Get once** on
 * `POST /api/v1/desired` (`monitor:false`) and **Follow** on
 * `POST /api/v1/followed-sources` ([AcquireClient]); the Following list on
 * `GET /api/v1/followed-sources` and unfollow on `DELETE /api/v1/followed-sources/{id}`
 * ([FollowingClient]).
 *
 * Blocking transport calls run on [Dispatchers.IO]; the UI observes the flows. The
 * arithmetic-free, network-free state transitions live in [SearchUiState]/[AcquireState]
 * so they are the parts under unit test.
 *
 * **Scope reality:** a QR/web-login **session** is minted *read-scoped*, so Search and
 * the Following list work, while Get-once / Follow / Unfollow (write routes) `403`
 * until the device enrols a write-scoped cert. Those 403s are surfaced as honest
 * "enrol this device" states by the clients, not swallowed or faked.
 */
class SearchViewModel(
    private val config: HeyarrConfig,
    private val credential: Credential,
    /**
     * The app's transport — injected so an enrolled device's requests go through the
     * same `Device`-credential refresh path as the library browse (AppViewModel).
     */
    private val transport: HttpTransport = OkHttpTransport(),
) : ViewModel() {

    private val search by lazy { SearchClient(transport, config.baseUrl, credential) }
    private val acquire by lazy {
        AcquireClient(transport, config.baseUrl, credential, config.defaultQualityProfile)
    }
    private val following by lazy { FollowingClient(transport, config.baseUrl, credential) }
    private val session by lazy { SessionClient(transport, config.baseUrl, credential) }

    private val _searchState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val searchState: StateFlow<SearchUiState> = _searchState.asStateFlow()

    /** Per-result acquire status, keyed by [SearchResult.workId]. */
    private val _acquireStates = MutableStateFlow<Map<String, AcquireState>>(emptyMap())
    val acquireStates: StateFlow<Map<String, AcquireState>> = _acquireStates.asStateFlow()

    private val _followingState = MutableStateFlow<FollowingUiState>(FollowingUiState.Idle)
    val followingState: StateFlow<FollowingUiState> = _followingState.asStateFlow()

    /** Per-source unfollow error message (a Phase-1 refusal, or a transport failure), keyed by id. */
    private val _unfollowErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val unfollowErrors: StateFlow<Map<String, String>> = _unfollowErrors.asStateFlow()

    /**
     * This caller's authority (`GET /api/v1/session`, ADR-0061). Null until loaded or
     * when it cannot be read — treated as read-only, the safe floor. The Follow UI reads
     * [SessionAuthority.canWrite] to decide whether Follow/Unfollow are live, and
     * [SessionAuthority.deviceKey] to show which device an operator must authorise.
     */
    private val _authority = MutableStateFlow<SessionAuthority?>(null)
    val authority: StateFlow<SessionAuthority?> = _authority.asStateFlow()

    /** Fetch (or re-check) this session's authority — the "I authorised it, re-check" action. */
    fun loadAuthority() {
        viewModelScope.launch {
            val next = withContext(Dispatchers.IO) { runCatching { session.authority() }.getOrNull() }
            _authority.value = next
        }
    }

    fun onSearch(query: String) {
        if (query.isBlank()) {
            _searchState.value = SearchUiState.Idle
            return
        }
        _searchState.value = SearchUiState.Searching(query)
        _acquireStates.value = emptyMap()
        viewModelScope.launch {
            val next = withContext(Dispatchers.IO) {
                runCatching { SearchUiState.forResults(query, search.search(query)) }
                    .getOrElse { SearchUiState.Error(it.message ?: "search failed") }
            }
            _searchState.value = next
        }
    }

    fun onGetOnce(result: SearchResult) = act(result) { acquire.getOnce(result) }

    // A search hit now carries the feed identity a follow needs (SearchResult.tvdbId,
    // from WorkSummary.tvdb_id), so follow-from-search is one tap: pass it through to
    // FollowSourceRequest.tvdb_id. A hit with no stored id sends none and the server
    // refuses loudly, exactly as before.
    fun onFollow(result: SearchResult) = act(result) { acquire.follow(result, tvdbId = result.tvdbId) }

    private fun act(result: SearchResult, call: () -> AcquireClient.Result) {
        setAcquire(result.workId, AcquireState.InFlight)
        viewModelScope.launch {
            val state = withContext(Dispatchers.IO) {
                runCatching { AcquireState.of(call()) }
                    .getOrElse { AcquireState.Failed(it.message ?: "action failed") }
            }
            setAcquire(result.workId, state)
        }
    }

    private fun setAcquire(workId: String, state: AcquireState) {
        _acquireStates.update { it + (workId to state) }
    }

    fun loadFollowing() {
        _followingState.value = FollowingUiState.Loading
        _unfollowErrors.value = emptyMap()
        viewModelScope.launch {
            val next = withContext(Dispatchers.IO) {
                runCatching { FollowingUiState.Loaded(following.list()) }
                    .getOrElse { FollowingUiState.Error(it.message ?: "failed to load following") }
            }
            _followingState.value = next
        }
    }

    /**
     * Unfollow [source] (keeping its archive — Phase-1 default). On success the list
     * reloads; a refusal or failure lands as a per-row message in [unfollowErrors].
     */
    fun onUnfollow(source: FollowedSource) {
        _unfollowErrors.update { it - source.id }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { following.unfollow(source.id) }
                    .getOrElse { FollowingClient.UnfollowResult.Failed(0, it.message ?: "unfollow failed") }
            }
            when (result) {
                is FollowingClient.UnfollowResult.Removed -> loadFollowing()
                is FollowingClient.UnfollowResult.Refused ->
                    _unfollowErrors.update { it + (source.id to result.message) }
                is FollowingClient.UnfollowResult.Failed ->
                    _unfollowErrors.update { it + (source.id to result.message) }
            }
        }
    }
}

/** UI state for the Following list screen. */
sealed interface FollowingUiState {
    data object Idle : FollowingUiState
    data object Loading : FollowingUiState
    data class Loaded(val sources: List<FollowedSource>) : FollowingUiState
    data class Error(val message: String) : FollowingUiState
}
