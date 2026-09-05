package one.rarebit.heyarr.mobile.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.rarebit.heyarr.mobile.library.LibraryClient
import one.rarebit.heyarr.mobile.library.Work
import one.rarebit.heyarr.mobile.personalstate.PersonalStateCoordinator

/**
 * The playlists list. Folds every openable non-role space as a playlist (the same
 * convention heyarr-core's device gateway uses) and mints new ones — all decrypted
 * on this device. `notEnrolled` is the honest state when there is no device key to
 * unwrap a space key: playlists are device-side encrypted state, not a server list.
 */
internal class PlaylistsViewModel(
    private val personalState: PersonalStateCoordinator?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = false,
        val notEnrolled: Boolean = false,
        val playlists: List<PersonalStateCoordinator.PlaylistView> = emptyList(),
        val error: String? = null,
        val busy: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState(loading = personalState != null, notEnrolled = personalState == null))
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        if (personalState != null) refresh()
    }

    fun refresh() {
        val ps = personalState ?: return
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val result = withContext(io) { runCatching { ps.playlists() } }
            _state.value = result.fold(
                { UiState(playlists = it) },
                { UiState(error = it.message ?: "couldn't load playlists") },
            )
        }
    }

    /** Create a playlist and hand its space id back (to navigate into it). */
    fun create(name: String?, onCreated: (String) -> Unit) {
        val ps = personalState ?: return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            val id = withContext(io) { runCatching { ps.createPlaylist(name?.trim()?.takeIf { it.isNotEmpty() }) } }
            _state.value = _state.value.copy(busy = false)
            id.getOrNull()?.let { refresh(); onCreated(it) }
        }
    }
}

/**
 * One playlist's items, resolved from their content ids to browsable [Work]s (an id
 * this device cannot resolve on the node is dropped, not shown as a broken row). Play
 * is delegated to the app's playback; remove writes to the CRDT and re-folds.
 */
internal class PlaylistViewModel(
    private val spaceId: String,
    titleHint: String?,
    private val personalState: PersonalStateCoordinator,
    private val library: LibraryClient,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val name: String = "",
        val works: List<Work> = emptyList(),
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState(name = titleHint ?: ""))
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val result = withContext(io) {
                runCatching {
                    val view = personalState.playlist(spaceId)
                    val works = view?.itemIds?.mapNotNull { runCatching { library.getWork(it) }.getOrNull() } ?: emptyList()
                    (view?.name ?: "") to works
                }
            }
            _state.value = result.fold(
                { (name, works) -> UiState(loading = false, name = name, works = works) },
                { _state.value.copy(loading = false, error = it.message ?: "couldn't open playlist") },
            )
        }
    }

    fun remove(itemId: String) {
        viewModelScope.launch {
            withContext(io) { runCatching { personalState.removeFromPlaylist(spaceId, itemId) } }
            load()
        }
    }

    fun rename(name: String) {
        val trimmed = name.trim().ifEmpty { return }
        viewModelScope.launch {
            withContext(io) { personalState.renamePlaylist(spaceId, trimmed) }
            _state.value = _state.value.copy(name = trimmed)
        }
    }
}
