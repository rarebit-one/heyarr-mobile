package one.rarebit.heyarr.mobile.acquisition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.rarebit.heyarr.mobile.library.LibraryClient
import one.rarebit.heyarr.mobile.library.Want
import one.rarebit.heyarr.mobile.library.WorkDetailClient

/** The wants dashboard: every want, with the title of the work it is for. */
data class WantsUiState(
    val wants: List<Want> = emptyList(),
    val titles: Map<String, String> = emptyMap(),
    val loading: Boolean = true,
    val error: String? = null,
) {
    fun titleOf(want: Want): String = want.workId?.let { titles[it] } ?: want.workId ?: want.id
}

class WantsViewModel(
    private val wants: WantsClient,
    private val library: LibraryClient,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow(WantsUiState())
    val state: StateFlow<WantsUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val next = withContext(io) {
                runCatching {
                    val all = wants.listAll()
                    // Titles for the works the wants name — a bounded fan-out, failures dropped per work.
                    val titles = all.mapNotNull { it.workId }.distinct().take(TITLE_LOOKUPS).mapNotNull { id ->
                        runCatching { library.getWork(id) }.getOrNull()?.let { id to it.title }
                    }.toMap()
                    WantsUiState(wants = all, titles = titles, loading = false)
                }.getOrElse { WantsUiState(loading = false, error = it.message ?: "failed to load wants") }
            }
            _state.value = next
        }
    }

    companion object { const val TITLE_LOOKUPS = 40 }
}

/** One want: its status, its candidates, and the actions on it. */
data class WantDetailUiState(
    val want: Want? = null,
    val title: String? = null,
    val candidates: CandidateSet = CandidateSet(candidates = emptyList()),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
    val gone: Boolean = false,
)

class WantDetailViewModel(
    private val wantId: String,
    private val wants: WantsClient,
    private val detail: WorkDetailClient,
    private val library: LibraryClient,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow(WantDetailUiState())
    val state: StateFlow<WantDetailUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val next = withContext(io) {
                runCatching {
                    val want = wants.listAll().firstOrNull { it.id == wantId }
                    val title = want?.workId?.let { runCatching { library.getWork(it) }.getOrNull()?.title }
                    val cands = runCatching { wants.candidates(wantId) }.getOrDefault(CandidateSet(candidates = emptyList()))
                    _state.value.copy(want = want, title = title, candidates = cands, loading = false, gone = want == null)
                }.getOrElse { _state.value.copy(loading = false, error = it.message ?: "failed to load the want") }
            }
            _state.value = next
        }
    }

    fun select(candidate: Candidate) = act { wants.select(wantId, candidate.id).let { if (it is WantsClient.SelectOutcome.Refused) it.message else "Chose “${candidate.title}”" } }
    fun searchAgain() = act { detail.searchAgain(wantId).message("Search queued") }
    fun retry() = act { detail.reconcile(wantId).message("Reconciliation queued") }
    fun setMonitor(monitor: Boolean) = act { detail.setMonitor(wantId, monitor).message(if (monitor) "Monitoring" else "Paused") }
    fun cancel() = act { detail.cancelWant(wantId).message("Cancelled") }

    private fun act(call: () -> String) {
        _state.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            val notice = withContext(io) { runCatching(call).getOrElse { it.message ?: "action failed" } }
            _state.update { it.copy(busy = false, notice = notice) }
            load()
        }
    }

    private fun WorkDetailClient.Outcome.message(ok: String): String = when (this) {
        is WorkDetailClient.Outcome.Done -> ok
        is WorkDetailClient.Outcome.ReadOnly -> this.message
        is WorkDetailClient.Outcome.Refused -> this.message
        is WorkDetailClient.Outcome.Failed -> this.message
    }
}
