package one.rarebit.heyarr.mobile.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.rarebit.heyarr.mobile.home.RowState
import one.rarebit.heyarr.mobile.library.LibraryClient
import one.rarebit.heyarr.mobile.library.Work
import one.rarebit.heyarr.mobile.library.WorkAsset
import one.rarebit.heyarr.mobile.library.WorkDetailClient
import one.rarebit.heyarr.mobile.playback.MediaMime

class ArtistsViewModel(private val music: MusicClient, private val io: CoroutineDispatcher = Dispatchers.IO) : ViewModel() {
    private val _state = MutableStateFlow<RowState<Artist>>(RowState.Loading)
    val state: StateFlow<RowState<Artist>> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.value = RowState.Loading
        viewModelScope.launch {
            _state.value = withContext(io) {
                runCatching { RowState.Loaded(music.artists()) as RowState<Artist> }.getOrElse { RowState.Failed(it.message ?: "failed to load artists") }
            }
        }
    }
}

class ArtistViewModel(val artist: String, private val music: MusicClient, private val io: CoroutineDispatcher = Dispatchers.IO) : ViewModel() {
    private val _state = MutableStateFlow<RowState<Work>>(RowState.Loading)
    val state: StateFlow<RowState<Work>> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.value = RowState.Loading
        viewModelScope.launch {
            _state.value = withContext(io) {
                runCatching { RowState.Loaded(music.albums(artist)) as RowState<Work> }.getOrElse { RowState.Failed(it.message ?: "failed to load albums") }
            }
        }
    }
}

/** An album: the work and its playable tracks, in catalog order. */
data class AlbumUiState(val work: Work? = null, val tracks: List<WorkAsset> = emptyList(), val loading: Boolean = true, val error: String? = null)

class AlbumViewModel(
    private val workId: String,
    private val titleHint: String?,
    private val library: LibraryClient,
    private val detail: WorkDetailClient,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow(AlbumUiState(work = titleHint?.let { Work(id = workId, title = it, kind = "music") }))
    val state: StateFlow<AlbumUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val next = withContext(io) {
                runCatching {
                    val work = library.getWork(workId) ?: _state.value.work ?: Work(id = workId, title = workId, kind = "music")
                    val tracks = tracksOf(detail.assetsForWork(workId))
                    AlbumUiState(work = work, tracks = tracks, loading = false)
                }.getOrElse { AlbumUiState(work = _state.value.work, loading = false, error = it.message ?: "failed to load album") }
            }
            _state.value = next
        }
    }

    companion object {
        /** The playable audio files of an album, in filename order (track numbers lead most filenames). Pure. */
        fun tracksOf(assets: List<WorkAsset>): List<WorkAsset> =
            assets.filter { it.isPlayable && (it.role == null || it.role == "primary") && MediaMime.isAudio(it.mime, it.filename) }
                .sortedWith(compareBy({ it.filename?.lowercase() ?: "" }, { it.id }))
    }
}
